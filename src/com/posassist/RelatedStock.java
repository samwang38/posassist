package com.posassist;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主機的關聯存貨（實務上就是對應的 AppleCare+）。
 *
 * 資料在 EPB 的 STKMAS_RET：STK_ID 是主機、STK_ID_RET 是關聯存貨。POSN 自己在加品項時
 * 也是用 LocalPersistence 讀這張表（EpbPosCheckUtility），所以本機資料庫一定有它。
 * 同一台主機可能掛著好幾筆舊的、已停用的 AppleCare，只取商品主檔 STATUS_FLG='A' 的；
 * 2026-09-30 用正式資料核對過：台灣正常品主機每台恰好一筆有效關聯。
 *
 * 只讀不寫。查詢走 VipLookup.query（先用獨立連線，不跟 POSN 搶共用連線），
 * 在自己的背景執行緒上跑，結果轉回 EDT 才交給呼叫端。
 */
final class RelatedStock {

    static final class Item {
        final String code;
        final String name;
        /**
         * 公司端沒設、由同型號推出來的才有值，內容是給人看的依據（型號與參照的主機）。
         * 推測的關聯點下去要先確認，不能跟公司設定的一樣直接帶入。
         */
        final String inferredFrom;

        Item(String code, String name) {
            this(code, name, null);
        }

        Item(String code, String name, String inferredFrom) {
            this.code = code;
            this.name = name;
            this.inferredFrom = inferredFrom;
        }

        boolean inferred() {
            return inferredFrom != null;
        }
    }

    interface Callback {
        /** 跑在 EDT 上。查詢失敗時不會呼叫。 */
        void found(List<Item> items);
    }

    /**
     * 兩種資料庫都要能跑（本機 PostgreSQL，別台可能是 Oracle），所以只用 ANSI 寫法；
     * 日期由程式帶綁定參數（明天零點、今天零點），不用 SYSDATE／CURRENT_DATE。
     * 開始日用「早於明天零點」而不是「不晚於今天零點」：開始日帶時間時，當天就該生效。
     */
    static final String SQL =
        "SELECT r.STK_ID_RET, m.NAME FROM STKMAS_RET r "
        + "JOIN STKMAS m ON m.STK_ID = r.STK_ID_RET "
        + "WHERE r.STK_ID = ? AND m.STATUS_FLG = 'A' "
        + "AND (r.START_DATE IS NULL OR r.START_DATE < ?) "
        + "AND (r.END_DATE IS NULL OR r.END_DATE >= ?) "
        + "ORDER BY r.STK_ID_RET";

    /**
     * 公司端沒設關聯時的推論：同一個 Apple 型號（STKMAS.MODEL，例如 MFYM4ZP/A）的其他主機
     * 設了哪個 AppleCare，就推測是那個。同型號就是同一台機器，AppleCare 一定相同 ——
     * 常見的是同一台機器有 0731 與 905 兩組存貨代碼，只有一組設了關聯。
     *
     * 排除 265S 系列（名稱跟 2650 一模一樣，但 2026-08～09 全台一筆都沒賣過）與「維修專用」版。
     * 排除後只剩唯一一個才採用（由呼叫端判斷），有兩個以上就不猜。
     * 2026-10-01 用近 60 天正式銷售核對：公司沒設關聯、實際有賣 AppleCare 的 168 筆，
     * 可推 158 筆，其中 152 筆與實際賣出的一致；不一致的看過有店員選錯的。
     */
    static final String INFER_SQL =
        "SELECT h.MODEL, r.STK_ID_RET, c.NAME, MIN(s.STK_ID) FROM STKMAS h "
        + "JOIN STKMAS s ON s.MODEL = h.MODEL AND s.STK_ID <> h.STK_ID "
        + "JOIN STKMAS_RET r ON r.STK_ID = s.STK_ID "
        + "JOIN STKMAS c ON c.STK_ID = r.STK_ID_RET "
        + "WHERE h.STK_ID = ? AND h.MODEL IS NOT NULL AND c.STATUS_FLG = 'A' "
        + "AND r.STK_ID_RET NOT LIKE ? AND c.NAME NOT LIKE ? "
        + "AND (r.START_DATE IS NULL OR r.START_DATE < ?) "
        + "AND (r.END_DATE IS NULL OR r.END_DATE >= ?) "
        + "GROUP BY h.MODEL, r.STK_ID_RET, c.NAME ORDER BY r.STK_ID_RET";

    private static final int MAX_ROWS = 10;
    private static final int CACHE_SIZE = 300;

    /** 同一台主機一個交易裡可能被掃好幾次，關聯資料一天內不會變，記在記憶體裡就好。 */
    private static final Map<String, List<Item>> CACHE =
        Collections.synchronizedMap(new LinkedHashMap<String, List<Item>>(64, 0.75f, true) {
            protected boolean removeEldestEntry(Map.Entry<String, List<Item>> eldest) {
                return size() > CACHE_SIZE;
            }
        });

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "PosAssist-RelatedStock");
        thread.setDaemon(true);
        return thread;
    });

    private RelatedStock() {
    }

    /** 背景查詢，結果在 EDT 上交給 callback。 */
    static void lookupAsync(final String stkId, final Callback callback) {
        if (stkId == null || stkId.trim().length() == 0) {
            return;
        }
        WORKER.execute(() -> Safe.guard("查關聯存貨", () -> {
            final List<Item> items = lookup(stkId.trim());
            if (items != null) {
                FloatingPanel.onEdt(() -> callback.found(items));
            }
        }));
    }

    /** 診斷過的主機，同一次開機只查一次。 */
    private static final java.util.Set<String> DIAGNOSED =
        Collections.synchronizedSet(new java.util.HashSet<String>());

    /**
     * 查到 0 筆時的診斷：不加任何篩選再查一次，看是本機資料庫沒有這筆關聯，
     * 還是被「有效狀態」或起訖日濾掉。只讀，結果寫進 log。
     */
    static void diagnoseAsync(final String stkId) {
        if (!DIAGNOSED.add(stkId)) {
            return;
        }
        WORKER.execute(() -> Safe.guard("診斷關聯存貨", () -> {
            List<Object> params = new ArrayList<Object>();
            params.add(stkId);
            List<Vector> rows = VipLookup.query(
                "SELECT r.STK_ID_RET, m.STATUS_FLG, r.START_DATE, r.END_DATE "
                + "FROM STKMAS_RET r LEFT JOIN STKMAS m ON m.STK_ID = r.STK_ID_RET "
                + "WHERE r.STK_ID = ?", params);
            if (rows == null) {
                PosLog.warn("診斷關聯存貨 " + stkId + "：查詢失敗（本機可能沒有 STKMAS_RET）");
                return;
            }
            StringBuilder detail = new StringBuilder();
            for (int i = 0; i < rows.size(); i++) {
                Vector row = rows.get(i);
                detail.append(i == 0 ? "" : "；").append(cell(row, 0)).append(" 狀態=")
                    .append(cell(row, 1)).append(" 起=").append(cell(row, 2))
                    .append(" 迄=").append(cell(row, 3));
            }
            PosLog.info("診斷關聯存貨 " + stkId + "：不篩選共 " + rows.size() + " 筆"
                + (rows.isEmpty() ? "（本機資料庫沒有這台主機的關聯）" : "：" + detail));
        }));
    }

    /** 查詢失敗回 null（不快取，下次再試），查無關聯回空 list。 */
    static List<Item> lookup(String stkId) {
        List<Item> cached = CACHE.get(stkId);
        if (cached != null) {
            return cached;
        }
        List<Item> configured = configured(stkId);
        if (configured == null) {
            return null;
        }
        List<Item> items = configured.isEmpty() ? infer(stkId) : configured;
        if (items == null) {
            return null;
        }
        List<Item> frozen = Collections.unmodifiableList(items);
        CACHE.put(stkId, frozen);
        return frozen;
    }

    /** 公司端在 STKMAS_RET 設好的關聯。查詢失敗回 null。 */
    private static List<Item> configured(String stkId) {
        java.sql.Timestamp[] days = todayAndTomorrow();
        List<Object> params = new ArrayList<Object>();
        params.add(stkId);
        params.add(days[1]);
        params.add(days[0]);
        List<Vector> rows = VipLookup.query(SQL, params);
        if (rows == null) {
            PosLog.warn("查關聯存貨失敗：" + stkId);
            return null;
        }
        List<Item> items = new ArrayList<Item>();
        for (int i = 0; i < rows.size() && i < MAX_ROWS; i++) {
            Vector row = rows.get(i);
            String code = cell(row, 0);
            if (code.length() != 0) {
                items.add(new Item(code, cell(row, 1)));
            }
        }
        return items;
    }

    /** 依同型號推論（見 INFER_SQL）。不只一個候選就不猜，回空 list；查詢失敗回 null。 */
    private static List<Item> infer(String stkId) {
        java.sql.Timestamp[] days = todayAndTomorrow();
        List<Object> params = new ArrayList<Object>();
        params.add(stkId);
        params.add("265S%");
        params.add("%維修專用%");
        params.add(days[1]);
        params.add(days[0]);
        List<Vector> rows = VipLookup.query(INFER_SQL, params);
        if (rows == null) {
            PosLog.warn("依同型號推論關聯存貨失敗：" + stkId);
            return null;
        }
        List<Item> items = new ArrayList<Item>();
        if (rows.size() == 1) {
            Vector row = rows.get(0);
            String code = cell(row, 1);
            if (code.length() != 0) {
                items.add(new Item(code, cell(row, 2),
                    "同型號 " + cell(row, 0) + "（參照 " + cell(row, 3) + " 的設定）"));
            }
        } else if (rows.size() > 1) {
            PosLog.info("主機 " + stkId + " 依同型號有 " + rows.size() + " 個候選，不推論");
        }
        return items;
    }

    private static java.sql.Timestamp[] todayAndTomorrow() {
        java.util.Calendar day = java.util.Calendar.getInstance();
        day.set(java.util.Calendar.HOUR_OF_DAY, 0);
        day.set(java.util.Calendar.MINUTE, 0);
        day.set(java.util.Calendar.SECOND, 0);
        day.set(java.util.Calendar.MILLISECOND, 0);
        java.sql.Timestamp today = new java.sql.Timestamp(day.getTimeInMillis());
        day.add(java.util.Calendar.DAY_OF_MONTH, 1);
        return new java.sql.Timestamp[] { today, new java.sql.Timestamp(day.getTimeInMillis()) };
    }

    private static String cell(Vector row, int index) {
        if (row == null || index >= row.size() || row.get(index) == null) {
            return "";
        }
        return String.valueOf(row.get(index)).trim();
    }
}
