package com.posassist;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 重開單據的資料：找一張已結帳的舊單，讀出它的明細，給 ReorderPane 預覽與 ReorderRunner 帶入。
 *
 * 只讀不寫。查詢走 VipLookup.query（先用獨立連線，不跟 POSN 搶共用連線），
 * 在自己的背景執行緒上跑，結果轉回 EDT 才交給呼叫端。
 *
 * 欄位來源（2026-10-16 用 posn.jar 反組譯核對）：
 * - POSMAS.TRANS_TYPE：銷售 A（POSN.doSales）、退貨 E、退款 J。只列 A。
 * - POSLINE.SRN_ID 是 F10 視窗的序號欄（serialNoTextField），REF1–REF8 對應 lineRef1–8
 *   （預約單號在士林是 REF7）。
 * - POSLINE.LINE_TYPE：S 是一般商品；M／Y／C／X 是折扣、會員折扣、服務費、點數折抵這類
 *   POSN 自己產生的列，重新帶商品時 POSN 會再算一次，所以預設不勾。
 */
final class Reorder {

    /** 一張可以重開的舊單。 */
    static final class Doc {
        final String docId;
        final java.util.Date date;
        final String posNo;
        final String vipId;
        final String invoice;
        final BigDecimal total;
        final BigDecimal qty;

        Doc(String docId, java.util.Date date, String posNo, String vipId,
            String invoice, BigDecimal total, BigDecimal qty) {
            this.docId = docId;
            this.date = date;
            this.posNo = posNo;
            this.vipId = vipId;
            this.invoice = invoice;
            this.total = total;
            this.qty = qty;
        }
    }

    /** 舊單的一列明細。code／qty／include 由店員在預覽畫面改。 */
    static final class Line {
        static final int REF_COUNT = 8;

        final String lineNo;
        final String lineType;
        final String stkId;
        final String name;
        final String serial;
        /** REF1–REF8，索引 0 是 REF1。沒有值是空字串。 */
        final String[] refs;
        String code;
        int qty;
        boolean include;
        /** 讀進來時的代碼。店員自己加的列是 null。 */
        private String original;

        Line(String lineNo, String lineType, String code, String stkId, String name,
            int qty, String serial, String[] refs) {
            this.lineNo = lineNo;
            this.lineType = lineType;
            this.code = code;
            this.stkId = stkId;
            this.name = name;
            this.qty = qty;
            this.serial = serial;
            this.refs = refs;
            this.include = isItem(lineType) && code.length() != 0;
        }

        /** 店員自己加的一列。 */
        static Line blank() {
            Line line = new Line("", "S", "", "", "", 1, "", new String[REF_COUNT]);
            for (int i = 0; i < REF_COUNT; i++) {
                line.refs[i] = "";
            }
            line.include = true;
            return line;
        }

        /** F10 視窗要補填的東西（序號或任一個 REF）有沒有值。 */
        boolean hasF10Values() {
            if (serial.length() != 0) {
                return true;
            }
            for (String ref : refs) {
                if (ref.length() != 0) {
                    return true;
                }
            }
            return false;
        }

        /** 代碼被店員改過，原單的序號就不是這個商品的了，不能再填。 */
        boolean codeChanged() {
            return original != null && !original.equals(code);
        }
    }

    /** 帶入的一步：某一列的第幾件。 */
    static final class Step {
        final Line line;
        final int unit;

        Step(Line line, int unit) {
            this.line = line;
            this.unit = unit;
        }
    }

    interface Callback<T> {
        /** 跑在 EDT 上。查詢失敗時 result 是 null（跟查無資料的空 list 分開）。 */
        void done(T result);
    }

    static final int RECENT_DAYS = 30;
    static final int MAX_DOCS = 30;
    static final int MAX_LINES = 200;
    /** 單列數量上限。超過多半是資料怪，不要真的帶幾百次。 */
    static final int MAX_QTY = 50;

    private static final String DOC_COLUMNS =
        "SELECT DOC_ID, DOC_DATE, POS_NO, VIP_ID, TAX_INV_NO, GRANT_TOTAL, TOTAL_STK_QTY "
        + "FROM POSMAS ";

    /** 日期由程式帶綁定參數（不用 CURRENT_DATE／SYSDATE），Postgres 與 Oracle 都能跑。 */
    static final String SQL_BY_VIP = DOC_COLUMNS
        + "WHERE VIP_ID = ? AND TRANS_TYPE = 'A' AND DOC_DATE >= ? "
        + "ORDER BY DOC_DATE DESC, DOC_ID DESC";

    static final String SQL_BY_POS = DOC_COLUMNS
        + "WHERE POS_NO = ? AND TRANS_TYPE = 'A' AND DOC_DATE >= ? "
        + "ORDER BY DOC_DATE DESC, DOC_ID DESC";

    static final String SQL_BY_NUMBER = DOC_COLUMNS
        + "WHERE (DOC_ID = ? OR TAX_INV_NO = ?) AND TRANS_TYPE = 'A' "
        + "ORDER BY DOC_DATE DESC, DOC_ID DESC";

    static final String LINE_SQL =
        "SELECT LINE_NO, LINE_TYPE, PLU_ID, STK_ID, NAME, STK_QTY, SRN_ID, "
        + "REF1, REF2, REF3, REF4, REF5, REF6, REF7, REF8 "
        + "FROM POSLINE WHERE DOC_ID = ? ORDER BY LINE_NO";

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "PosAssist-Reorder");
        thread.setDaemon(true);
        return thread;
    });

    private Reorder() {
    }

    // -- 查詢 --------------------------------------------------------------

    /**
     * 按下「重開單據」時的預設清單：有會員列他近 30 天的單，沒有會員列本機今天的單。
     */
    static void recentAsync(final String vipId, final String posNo, final Callback<List<Doc>> callback) {
        WORKER.execute(() -> Safe.guard("查近期單據", () -> {
            List<Object> params = new ArrayList<Object>();
            String sql;
            if (vipId != null && vipId.trim().length() != 0) {
                sql = SQL_BY_VIP;
                params.add(vipId.trim());
                params.add(daysAgo(RECENT_DAYS));
            } else {
                sql = SQL_BY_POS;
                params.add(posNo == null ? "" : posNo.trim());
                params.add(daysAgo(0));
            }
            deliver(callback, docs(VipLookup.query(sql, params, MAX_DOCS)));
        }));
    }

    /** 用單號或發票號找。 */
    static void searchAsync(final String number, final Callback<List<Doc>> callback) {
        WORKER.execute(() -> Safe.guard("查單號", () -> {
            String key = number == null ? "" : number.trim().toUpperCase();
            List<Object> params = new ArrayList<Object>();
            params.add(key);
            params.add(key);
            deliver(callback, docs(VipLookup.query(SQL_BY_NUMBER, params, MAX_DOCS)));
        }));
    }

    static void linesAsync(final String docId, final Callback<List<Line>> callback) {
        WORKER.execute(() -> Safe.guard("讀單據明細", () -> {
            List<Object> params = new ArrayList<Object>();
            params.add(docId);
            List<Vector> rows = VipLookup.query(LINE_SQL, params, MAX_LINES);
            List<Line> lines = rows == null ? null : lines(rows);
            if (lines != null) {
                PosLog.info("重開單據 讀明細 " + docId + " → " + lines.size() + " 列，類型 "
                    + typeCounts(lines));
            }
            deliver(callback, lines);
        }));
    }

    private static <T> void deliver(final Callback<T> callback, final T result) {
        FloatingPanel.onEdt(() -> Safe.guard("重開單據回呼", () -> callback.done(result)));
    }

    /** 今天零點往前推 days 天。 */
    static java.sql.Timestamp daysAgo(int days) {
        Calendar day = Calendar.getInstance();
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        day.add(Calendar.DAY_OF_MONTH, -days);
        return new java.sql.Timestamp(day.getTimeInMillis());
    }

    // -- 結果組裝（純邏輯，離線可測）----------------------------------------

    static List<Doc> docs(List<Vector> rows) {
        if (rows == null) {
            return null;
        }
        List<Doc> docs = new ArrayList<Doc>();
        for (Vector row : rows) {
            Object date = at(row, 1);
            docs.add(new Doc(cell(row, 0),
                date instanceof java.util.Date ? (java.util.Date) date : null,
                cell(row, 2), cell(row, 3), cell(row, 4), number(at(row, 5)), number(at(row, 6))));
        }
        return docs;
    }

    static List<Line> lines(List<Vector> rows) {
        List<Line> lines = new ArrayList<Line>();
        for (Vector row : rows) {
            String code = cell(row, 2);
            String stkId = cell(row, 3);
            if (code.length() == 0) {
                code = stkId;          // 沒有 PLU 的列退回存貨代碼，POSN 的 PLU 欄兩種都吃
            }
            if (code.length() == 0) {
                continue;              // 沒有代碼就沒辦法帶，連顯示都省了
            }
            String[] refs = new String[Line.REF_COUNT];
            for (int i = 0; i < Line.REF_COUNT; i++) {
                refs[i] = cell(row, 7 + i);
            }
            Line line = new Line(lineNo(at(row, 0)), cell(row, 1).toUpperCase(), code, stkId,
                cell(row, 4), quantity(at(row, 5)), cell(row, 6), refs);
            line.original = code;
            lines.add(line);
        }
        return lines;
    }

    /** 勾選的列展開成一步一件。代碼空白的列略過。 */
    static List<Step> plan(List<Line> lines) {
        List<Step> steps = new ArrayList<Step>();
        for (Line line : lines) {
            if (!line.include || line.code == null || line.code.trim().length() == 0) {
                continue;
            }
            int qty = Math.max(1, Math.min(MAX_QTY, line.qty));
            for (int unit = 1; unit <= qty; unit++) {
                steps.add(new Step(line, unit));
            }
        }
        return steps;
    }

    /** 一般商品才預設勾選；其他類型是 POSN 自己產生的列。 */
    static boolean isItem(String lineType) {
        return lineType == null || lineType.length() == 0 || "S".equalsIgnoreCase(lineType);
    }

    /** 給店員看的類型說明。 */
    static String typeLabel(String lineType) {
        if (isItem(lineType)) {
            return "";
        }
        if ("M".equals(lineType) || "Y".equals(lineType)) {
            return "折扣列";
        }
        if ("C".equals(lineType)) {
            return "服務費列";
        }
        if ("X".equals(lineType)) {
            return "點數列";
        }
        return "類型 " + lineType;
    }

    /**
     * POS 明細的 ID 欄（LINE_NO）與 F10 視窗的行號欄可能一邊是 1、一邊是 1.0，
     * 一律正規化成不帶小數的寫法再比。
     */
    static String lineNo(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim();
        try {
            return new BigDecimal(text).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException notNumber) {
            return text;
        }
    }

    static int quantity(Object value) {
        BigDecimal qty = number(value);
        if (qty == null) {
            return 1;
        }
        // 退貨單是負數；這裡只列銷售單，但保險起見取絕對值。小數（秤重品）無法照件數帶，當一件
        int units = qty.abs().setScale(0, BigDecimal.ROUND_HALF_UP).intValue();
        return Math.max(1, units);
    }

    private static Map<String, Integer> typeCounts(List<Line> lines) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (Line line : lines) {
            String key = line.lineType.length() == 0 ? "-" : line.lineType;
            Integer seen = counts.get(key);
            counts.put(key, seen == null ? 1 : seen + 1);
        }
        return counts;
    }

    private static BigDecimal number(Object value) {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return new BigDecimal(String.valueOf(value));
        }
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException notNumber) {
            return null;
        }
    }

    private static Object at(Vector row, int index) {
        return row != null && index < row.size() ? row.get(index) : null;
    }

    private static String cell(Vector row, int index) {
        Object value = at(row, index);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
