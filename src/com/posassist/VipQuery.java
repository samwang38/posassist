package com.posassist;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

/**
 * 在一條**專屬**資料庫連線上跑查詢，不碰 EPB 的共用連線。
 *
 * 為什麼要有這支：EPB 全行程只有一條 {@code Engine.sharedConnection}
 * （private static java.sql.Connection）。EpbApplicationUtility.getResultList 走的就是它，
 * 而取得連線之後的 prepareStatement / executeQuery / 讀 ResultSet 完全沒有鎖。
 * java.sql.Connection 不是 thread-safe，所以「面板在背景查會員（實機要 1–9 秒）」
 * 碰上「店員同時在 EPB 做別的事，POSN 在 EDT 上用同一條連線查東西」時，
 * 就是兩條執行緒交錯操作同一條連線 —— driver 層協定錯亂，EPB 那側直接彈錯誤視窗。
 *
 * {@code Engine.getAdHocConnection()} 每次給一條新連線，正好解掉這個衝突：
 * 面板自己的查詢從此與 EPB 各走各的，店員在查詢中想做什麼都可以。
 *
 * 不自己組連線字串、不讀 persistence.properties —— 帳密與連線設定仍然全由 EPB 管。
 */
final class VipQuery {

    private static final String ENGINE = "com.ipt.epbdtm.engine.Engine";

    /**
     * 這一次查詢有沒有真的走獨立連線。
     *
     * 要把「拿不到獨立連線」與「拿到了但 SQL 本身失敗」分開：前者要退回共用連線，
     * 後者不該退 —— 同一句 SQL 在共用連線上也會失敗，退過去只是多戳共用連線一次。
     */
    static final class Result {
        /** true 代表已經在獨立連線上跑完，呼叫端不要再走共用連線。 */
        final boolean handled;
        /** handled 且查詢成功才非 null；查詢失敗是 null，語意與舊的 query() 一致。 */
        final List<Vector> rows;

        private Result(boolean handled, List<Vector> rows) {
            this.handled = handled;
            this.rows = rows;
        }

        static final Result UNAVAILABLE = new Result(false, null);

        static Result done(List<Vector> rows) {
            return new Result(true, rows);
        }
    }

    /**
     * EPB 這一版有沒有 Engine.getAdHocConnection。null 代表還沒檢查過。
     *
     * 刻意只把「結構性缺失」（類別或方法不存在）記成永久結論 —— 那是不會變的。
     * 反之「這次要不到連線」可能只是一時的，不能因此就整個 session 都退回共用連線，
     * 否則一次網路抖動就把這個修正關掉了。
     */
    private static volatile Boolean engineAvailable;
    /** 要不到連線只記一次 log，不然每查一次會員就洗一行。 */
    private static volatile boolean warnedNoConnection;

    private VipQuery() {
    }

    /** 給 SelfTest 看這台走不走得通獨立連線。 */
    static boolean adHocUsable() {
        return engineAvailable();
    }

    private static boolean engineAvailable() {
        Boolean cached = engineAvailable;
        if (cached == null) {
            boolean ok = false;
            Class<?> type = Safe.type(ENGINE);
            if (type != null) {
                try {
                    type.getMethod("getAdHocConnection");
                    ok = true;
                } catch (Throwable missing) {
                    ok = false;
                }
            }
            if (!ok) {
                PosLog.warn("這版 EPB 沒有 Engine.getAdHocConnection，"
                    + "會員查詢改用共用連線");
            }
            cached = Boolean.valueOf(ok);
            engineAvailable = cached;
        }
        return cached.booleanValue();
    }

    /** maxRows <= 0 代表不限筆數，跟 EPB 那邊傳 -1 的語意一樣。 */
    static Result run(String sql, List<Object> params, int maxRows) {
        if (!engineAvailable()) {
            return Result.UNAVAILABLE;
        }
        Object handle = Safe.staticCall(ENGINE, "getAdHocConnection",
            new Class<?>[0], new Object[0]);
        if (!(handle instanceof Connection)) {
            if (!warnedNoConnection) {
                warnedNoConnection = true;
                PosLog.warn("這次要不到 EPB 的獨立連線，本次查詢改用共用連線"
                    + "（之後仍會再試，這行只記一次）");
            }
            return Result.UNAVAILABLE;
        }
        Connection connection = (Connection) handle;
        try {
            return Result.done(rows(connection, sql, params, maxRows));
        } catch (Throwable t) {
            // 拿到連線了就算已處理：SQL 本身的問題（例如這台沒有備註欄位）
            // 由呼叫端照既有邏輯處理，不要退回共用連線再失敗一次
            PosLog.warn("獨立連線查詢失敗", t);
            return Result.done(null);
        } finally {
            close(connection);
        }
    }

    /**
     * 照 EPB 的 DatabaseUtility.getResult 逐列組 Vector，回傳型別完全一致，
     * 所以 VipLookup 的結果組裝（toOutcome / cell）一行都不用改。
     *
     * 參數只做 setObject 就夠：EPB 的 getConvertedParameter 只轉
     * Character、java.util.Date、BigInteger 三種，而面板一律只傳 String。
     */
    @SuppressWarnings("unchecked")
    static List<Vector> rows(Connection connection, String sql,
        List<Object> params, int maxRows) throws Exception {
        List<Vector> result = new ArrayList<Vector>();
        PreparedStatement statement = null;
        ResultSet rs = null;
        try {
            statement = connection.prepareStatement(sql,
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            if (maxRows > 0) {
                statement.setMaxRows(maxRows);
            }
            if (params != null) {
                for (int i = 0; i < params.size(); i++) {
                    statement.setObject(i + 1, params.get(i));
                }
            }
            rs = statement.executeQuery();
            ResultSetMetaData meta = rs.getMetaData();
            int columns = meta.getColumnCount();
            while (rs.next()) {
                Vector row = new Vector();
                for (int column = 1; column <= columns; column++) {
                    row.add(rs.getObject(column));
                }
                result.add(row);
                // setMaxRows 只是給 driver 的提示，不是每個 driver 都吃，所以自己也擋一次
                if (maxRows > 0 && result.size() >= maxRows) {
                    break;
                }
            }
            return result;
        } finally {
            close(rs);
            close(statement);
        }
    }

    /**
     * 關閉。刻意不用 Engine.release —— 它失敗時會呼叫 EpbExceptionMessenger
     * 彈出 EPB 的錯誤視窗，而這裡跑在背景執行緒上，清理動作不該冒出對話框。
     */
    private static void close(Object closeable) {
        if (closeable == null) {
            return;
        }
        try {
            if (closeable instanceof ResultSet) {
                ((ResultSet) closeable).close();
            } else if (closeable instanceof PreparedStatement) {
                ((PreparedStatement) closeable).close();
            } else if (closeable instanceof Connection) {
                ((Connection) closeable).close();
            }
        } catch (Throwable t) {
            // 關不掉只能記一筆：連線本來就可能已經斷了
            PosLog.warn("關閉獨立連線資源失敗", t);
        }
    }
}
