package com.posassist;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 重開單據的資料：找一張已結帳的舊單，讀出它的明細，給 ReorderPane 預覽與 ReorderRunner 帶入。
 *
 * 只讀不寫。本機查詢走 VipLookup.query（先用獨立連線，不跟 POSN 搶共用連線）；
 * 本機找不到的單（別台開的、前幾天已上傳的）改走 POSN 自己複製單據用的同一條路
 * EPBRemoteFunctionCall.pullEntities，向總部 AP 伺服器查。全部在自己的背景執行緒上跑，
 * 結果轉回 EDT 才交給呼叫端。
 *
 * 欄位來源（2026-10-16 用 posn.jar 反組譯與實機核對）：
 * - POSMAS.TRANS_TYPE：銷售 A（POSN.doSales）、退貨 E、退款 J。只列 A。
 * - POSLINE.SRN_ID 是主機序號。POSN 加主機時就在「Key in unicode」視窗（PosKeyinDialog）
 *   要序號，品項要序號通過才會加進明細 —— 不是 F10。
 * - POSLINE.REF1–REF8 對應 F10 視窗的 lineRef1–8（預約單號在士林是 REF7）。
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
        /** 會員姓名（POSMAS.NAME）。 */
        final String vipName;
        /** 其中一個商品的品名，清單上讓店員認單用。 */
        final String itemName;
        /** 本機沒有、從總部伺服器查到的單。明細也要去伺服器讀。 */
        final boolean remote;

        Doc(String docId, java.util.Date date, String posNo, String vipId,
            String invoice, BigDecimal total, BigDecimal qty,
            String vipName, String itemName, boolean remote) {
            this.docId = docId;
            this.date = date;
            this.posNo = posNo;
            this.vipId = vipId;
            this.invoice = invoice;
            this.total = total;
            this.qty = qty;
            this.vipName = vipName;
            this.itemName = itemName;
            this.remote = remote;
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

        /** 帶入時要自動填進「Key in unicode」的序號。代碼改過就不是原來那台，不填。 */
        String serialToFill() {
            return codeChanged() ? "" : serial;
        }

        /** F10 視窗要補填的 REF（預約單號等）有沒有值。序號不算，它在加入時就填了。 */
        boolean hasF10Values() {
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

    /**
     * 品名用相關子查詢取一個（MIN），Postgres 與 Oracle 都能跑。
     * 不取「第一列」：那要用到兩邊寫法不同的分頁語法。
     */
    private static final String DOC_COLUMNS =
        "SELECT m.DOC_ID, m.DOC_DATE, m.POS_NO, m.VIP_ID, m.TAX_INV_NO, m.GRANT_TOTAL, "
        + "m.TOTAL_STK_QTY, m.NAME, "
        + "(SELECT MIN(l.NAME) FROM POSLINE l WHERE l.DOC_ID = m.DOC_ID AND l.LINE_TYPE = 'S') "
        + "FROM POSMAS m ";

    /** 日期由程式帶綁定參數（不用 CURRENT_DATE／SYSDATE），Postgres 與 Oracle 都能跑。 */
    static final String SQL_BY_VIP = DOC_COLUMNS
        + "WHERE m.VIP_ID = ? AND m.TRANS_TYPE = 'A' AND m.DOC_DATE >= ? "
        + "ORDER BY m.DOC_DATE DESC, m.DOC_ID DESC";

    static final String SQL_BY_POS = DOC_COLUMNS
        + "WHERE m.POS_NO = ? AND m.TRANS_TYPE = 'A' AND m.DOC_DATE >= ? "
        + "ORDER BY m.DOC_DATE DESC, m.DOC_ID DESC";

    static final String SQL_BY_NUMBER = DOC_COLUMNS
        + "WHERE (m.DOC_ID = ? OR m.TAX_INV_NO = ?) AND m.TRANS_TYPE = 'A' "
        + "ORDER BY m.DOC_DATE DESC, m.DOC_ID DESC";

    static final String LINE_SQL =
        "SELECT LINE_NO, LINE_TYPE, PLU_ID, STK_ID, NAME, STK_QTY, SRN_ID, "
        + "REF1, REF2, REF3, REF4, REF5, REF6, REF7, REF8 "
        + "FROM POSLINE WHERE DOC_ID = ? ORDER BY LINE_NO";

    /** 向總部伺服器查。POSN 複製單據用的就是這兩句的同型寫法（SELECT * 對應實體類別）。 */
    static final String REMOTE_DOC_SQL =
        "SELECT * FROM POSMAS WHERE (DOC_ID = ? OR TAX_INV_NO = ?) AND TRANS_TYPE = 'A'";
    static final String REMOTE_LINE_SQL =
        "SELECT * FROM POSLINE WHERE DOC_ID = ? ORDER BY LINE_NO";

    private static final String REMOTE = "com.epb.rfc.EPBRemoteFunctionCall";
    private static final String POSMAS_ENTITY = "com.epb.pst.entity.Posmas";
    private static final String POSLINE_ENTITY = "com.epb.pst.entity.Posline";

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "PosAssist-Reorder");
        thread.setDaemon(true);
        return thread;
    });

    /** 診斷過的號碼，同一次開機只診斷一次。 */
    private static final Set<String> DIAGNOSED =
        Collections.synchronizedSet(new java.util.HashSet<String>());

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

    /** 用單號或發票號找。本機沒有就寫一次診斷，再向總部伺服器查。 */
    static void searchAsync(final String number, final Callback<List<Doc>> callback) {
        WORKER.execute(() -> Safe.guard("查單號", () -> {
            String key = number == null ? "" : number.trim().toUpperCase();
            List<Object> params = new ArrayList<Object>();
            params.add(key);
            params.add(key);
            List<Doc> local = docs(VipLookup.query(SQL_BY_NUMBER, params, MAX_DOCS));
            if (local != null && !local.isEmpty()) {
                deliver(callback, local);
                return;
            }
            diagnose(key);
            List<Doc> remote = remoteDocs(key);
            PosLog.info("重開單據 單號 " + key + " 本機 "
                + (local == null ? "查詢失敗" : "0 筆") + "，伺服器 "
                + (remote == null ? "查詢失敗" : remote.size() + " 筆"));
            // 伺服器查詢失敗時，本機的結果（就算是 0 筆）仍然是有效答案
            deliver(callback, remote != null ? remote : local);
        }));
    }

    static void linesAsync(final Doc doc, final Callback<List<Line>> callback) {
        WORKER.execute(() -> Safe.guard("讀單據明細", () -> {
            List<Line> lines;
            if (doc.remote) {
                lines = remoteLines(doc.docId);
            } else {
                List<Object> params = new ArrayList<Object>();
                params.add(doc.docId);
                List<Vector> rows = VipLookup.query(LINE_SQL, params, MAX_LINES);
                lines = rows == null ? null : lines(rows);
            }
            if (lines != null) {
                PosLog.info("重開單據 讀明細 " + doc.docId + (doc.remote ? "（伺服器）" : "")
                    + " → " + lines.size() + " 列，類型 " + typeCounts(lines));
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

    // -- 找不到時的診斷（只讀，寫進 log）------------------------------------

    /**
     * 本機找不到這個號碼時，看是本機根本沒這張單，還是條件對不上（發票號格式、交易類型）。
     * 單號、發票號、機號不是個資，可以寫進 log。
     */
    static void diagnose(String key) {
        if (!DIAGNOSED.add(key)) {
            return;
        }
        String digits = key.replaceAll("[^0-9]", "");
        if (digits.length() >= 6) {
            List<Object> like = new ArrayList<Object>();
            like.add("%" + digits + "%");
            like.add("%" + digits + "%");
            List<Vector> rows = VipLookup.query(
                "SELECT DOC_ID, TAX_INV_NO, TRANS_TYPE, POS_NO, DOC_DATE FROM POSMAS "
                + "WHERE TAX_INV_NO LIKE ? OR DOC_ID LIKE ?", like, 5);
            PosLog.info("診斷重開單據 " + key + "：本機放寬條件 "
                + (rows == null ? "查詢失敗" : rows.size() + " 筆 " + rows));
        }
        List<Vector> range = VipLookup.query(
            "SELECT COUNT(*), MIN(DOC_DATE), MAX(DOC_DATE) FROM POSMAS",
            new ArrayList<Object>(), 1);
        PosLog.info("診斷重開單據：本機 POSMAS 筆數／最早／最晚 " + range);
        List<Vector> machines = VipLookup.query(
            "SELECT POS_NO, COUNT(*) FROM POSMAS GROUP BY POS_NO ORDER BY POS_NO",
            new ArrayList<Object>(), 20);
        PosLog.info("診斷重開單據：本機各機號單數 " + machines);
    }

    // -- 總部伺服器 --------------------------------------------------------

    /** 讀不到（沒有這個 API、連線失敗）回 null。 */
    private static List<?> pull(String sql, Object[] params, String entity) {
        Class<?> type = Safe.type(entity);
        if (type == null) {
            return null;
        }
        Object result = Safe.staticCall(REMOTE, "pullEntities",
            new Class<?>[] { String.class, Object[].class, Class.class },
            new Object[] { sql, params, type });
        return result instanceof List ? (List<?>) result : null;
    }

    static List<Doc> remoteDocs(String key) {
        List<?> rows = pull(REMOTE_DOC_SQL, new Object[] { key, key }, POSMAS_ENTITY);
        if (rows == null) {
            return null;
        }
        List<Doc> docs = new ArrayList<Doc>();
        for (Object row : rows) {
            Object date = Safe.call(row, "getDocDate");
            docs.add(new Doc(text(Safe.call(row, "getDocId")),
                date instanceof java.util.Date ? (java.util.Date) date : null,
                text(Safe.call(row, "getPosNo")), text(Safe.call(row, "getVipId")),
                text(Safe.call(row, "getTaxInvNo")), number(Safe.call(row, "getGrantTotal")),
                number(Safe.call(row, "getTotalStkQty")), text(Safe.call(row, "getName")),
                "", true));
        }
        return docs;
    }

    static List<Line> remoteLines(String docId) {
        List<?> rows = pull(REMOTE_LINE_SQL, new Object[] { docId }, POSLINE_ENTITY);
        if (rows == null) {
            return null;
        }
        List<Vector> vectors = new ArrayList<Vector>();
        for (Object row : rows) {
            Vector v = new Vector();
            v.add(Safe.call(row, "getLineNo"));
            v.add(Safe.call(row, "getLineType"));
            v.add(Safe.call(row, "getPluId"));
            v.add(Safe.call(row, "getStkId"));
            v.add(Safe.call(row, "getName"));
            v.add(Safe.call(row, "getStkQty"));
            v.add(Safe.call(row, "getSrnId"));
            for (int i = 1; i <= Line.REF_COUNT; i++) {
                v.add(Safe.call(row, "getRef" + i));
            }
            vectors.add(v);
        }
        return lines(vectors);
    }

    // -- 結果組裝（純邏輯，離線可測）----------------------------------------

    /** 欄位順序同 DOC_COLUMNS。 */
    static List<Doc> docs(List<Vector> rows) {
        if (rows == null) {
            return null;
        }
        List<Doc> docs = new ArrayList<Doc>();
        for (Vector row : rows) {
            Object date = at(row, 1);
            docs.add(new Doc(cell(row, 0),
                date instanceof java.util.Date ? (java.util.Date) date : null,
                cell(row, 2), cell(row, 3), cell(row, 4), number(at(row, 5)), number(at(row, 6)),
                cell(row, 7), cell(row, 8), false));
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

    /** 清單上的單據名稱：有發票號用發票號，沒有就用單號最後一段（前面都是門市與機號）。 */
    static String label(Doc doc) {
        if (doc.invoice.length() != 0) {
            return doc.invoice;
        }
        int dash = doc.docId.lastIndexOf('-');
        String tail = dash >= 0 && dash < doc.docId.length() - 1 ? doc.docId.substring(dash + 1) : doc.docId;
        return "未開發票 " + tail;
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

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static Object at(Vector row, int index) {
        return row != null && index < row.size() ? row.get(index) : null;
    }

    private static String cell(Vector row, int index) {
        return text(at(row, index));
    }
}
