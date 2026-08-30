package com.posassist;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Vector;

/**
 * 會員查詢。查詢規則沿用已驗證的 member-lookup（POS_VIP_MAS + POS_VIP_CLASS、
 * 電話正規化、最多 5 筆），但改走 EPB 自己的連線並使用綁定參數，不拼字串 SQL。
 *
 * 跟 member-lookup 的兩點差異：
 * 1. 多回姓名與 Email —— 店員在 POS 前本來就看得到會員姓名，這是櫃檯用的面板。
 * 2. 等級接 POS_VIP_CLASS 時，ORG_ID 是 NULL 的會員改用登入中的 ORG_ID 去接。
 *    POS_VIP_MAS 絕大多數資料的 ORG_ID 是 NULL，直接接會全部接不到，
 *    只能顯示代碼而不是等級名稱。
 *
 * SQL 一律只用兩邊都有的 ANSI 寫法。EpbApplicationUtility 走的是**本機 client 端
 * 資料庫**（不是 AP WebService 後面那台 Oracle）；這台 DB_TYPE.xml = 0 是
 * PostgreSQL，別台可能是 Oracle，所以不能用 NVL、ROWNUM、SYSDATE、TRUNC
 * 這類單邊專有的東西。SelfTest 會直接掃這裡產生的 SQL 把關。
 */
public final class VipLookup {

    public static final int MAX_RESULTS = 5;

    private static final String UTILITY = "com.ipt.epbtls.EpbApplicationUtility";
    private static final String SHARED = "com.ipt.epbfrw.EpbSharedObjects";
    private static final String DEFAULT_ORG_ID = "01";

    /**
     * REMARK4 就是會員畫面上的「備註4」，門市拿它記 LINE 綁定狀態。
     * 欄位名不是猜的：EPB 自己的 com.epb.beans.Posvipmas 有 remark1〜remark4。
     * 萬一哪一台的 schema 沒有這欄，query() 會退回不帶它的 SQL，查詢本身不受影響。
     */
    static final String REMARK_COLUMN = "m.REMARK4";

    private static final String BASE_SELECT =
        "SELECT m.VIP_ID, m.NAME, m.VIP_PHONE1, m.VIP_PHONE2, m.EMAIL_ADDR, "
        + "       m.CLASS_ID, c.CLASS_NAME REMARK_SLOT "
        + "FROM POS_VIP_MAS m "
        + "LEFT JOIN (SELECT ORG_ID, CLASS_ID, MAX(CLASS_NAME) AS CLASS_NAME "
        + "           FROM POS_VIP_CLASS GROUP BY ORG_ID, CLASS_ID) c "
        + "  ON c.CLASS_ID = m.CLASS_ID "
        // 直接拿 ? 跟 c.ORG_ID 比，型別由欄位決定，Postgres 與 Oracle 都不必轉型
        + " AND (c.ORG_ID = m.ORG_ID OR (m.ORG_ID IS NULL AND c.ORG_ID = ?)) ";

    /**
     * 這台的 POS_VIP_MAS 有沒有 REMARK4。查詢失敗過一次就關掉，
     * 之後整個 session 都不再帶它 —— 寧可少一個欄位，也不要讓會員查詢一直失敗。
     */
    private static volatile boolean remarkAvailable = true;

    private VipLookup() {
    }

    /** 把 SELECT 裡的備註欄位插進去或拿掉。 */
    private static String base(boolean withRemark) {
        return BASE_SELECT.replace(" REMARK_SLOT",
            withRemark ? ", " + REMARK_COLUMN : "");
    }

    // -- 結果 --------------------------------------------------------------

    public static final class Vip {
        public final String memberCode;
        public final String name;
        public final String phone;
        public final String email;
        public final String level;
        /** 備註4 的原文；欄位讀不到或空白時是空字串。 */
        public final String remark;

        Vip(String memberCode, String name, String phone, String email, String level,
            String remark) {
            this.memberCode = memberCode;
            this.name = name;
            this.phone = phone;
            this.email = email;
            this.level = level;
            this.remark = remark;
        }
    }

    public static final class Outcome {
        public final List<Vip> results;
        /** 非 null 代表要顯示的訊息（查無、太多筆、查詢失敗），此時 results 為空。 */
        public final String message;

        private Outcome(List<Vip> results, String message) {
            this.results = results;
            this.message = message;
        }

        static Outcome of(List<Vip> results) {
            return new Outcome(results, null);
        }

        static Outcome message(String message) {
            return new Outcome(new ArrayList<Vip>(), message);
        }
    }

    // -- 輸入正規化（照搬 member-lookup 規則）------------------------------

    /** 去掉非數字，886 開頭換 0，長度需 8-15；不合格回 null。 */
    public static String normalizePhone(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.length() < 8 || text.length() > 40) {
            return null;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean allowed = (c >= '0' && c <= '9')
                || c == '+' || c == '(' || c == ')' || c == '-' || c == ' ';
            if (!allowed) {
                return null;
            }
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        String result = digits.toString();
        if (result.startsWith("886") && result.length() >= 11 && result.length() <= 12) {
            result = "0" + result.substring(3);
        }
        if (result.length() < 8 || result.length() > 15) {
            return null;
        }
        return result;
    }

    private static boolean looksLikeMemberCode(String text) {
        if (text.length() < 3 || text.length() > 32) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean allowed = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    // -- 查詢 --------------------------------------------------------------

    public static Outcome lookup(String input) {
        String text = input == null ? "" : input.trim();
        if (text.length() < 3 || text.length() > 40
            || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return Outcome.message("請輸入會員電話或會員代碼");
        }

        Set<String> codes = new LinkedHashSet<String>();
        if (looksLikeMemberCode(text)) {
            codes.add(text);
            codes.add(text.toUpperCase());
        }

        String canonical = normalizePhone(text);
        Set<String> phones = new LinkedHashSet<String>();
        if (canonical != null) {
            phones.add(canonical);
            phones.add(text.replace(" ", ""));
        }

        if (codes.isEmpty() && phones.isEmpty()) {
            return Outcome.message("請輸入會員電話或會員代碼");
        }

        List<Vector> rows = runExact(codes, phones);
        if (rows == null) {
            return Outcome.message("查詢無法完成，請稍後再試");
        }
        if (rows.isEmpty() && canonical != null) {
            // 精確查無，才跑資料庫端正規化備援
            rows = runFallback(canonical);
            if (rows == null) {
                return Outcome.message("查詢無法完成，請稍後再試");
            }
        }
        return toOutcome(rows, canonical);
    }

    /** 產生精確查詢的 SQL。SelfTest 也用這支做語法把關。 */
    static String buildExactSql(int codeCount, int phoneCount) {
        return buildExactSql(codeCount, phoneCount, true);
    }

    static String buildExactSql(int codeCount, int phoneCount, boolean withRemark) {
        StringBuilder where = new StringBuilder();
        if (codeCount > 0) {
            where.append("m.VIP_ID IN (").append(placeholders(codeCount)).append(")");
        }
        if (phoneCount > 0) {
            String marks = placeholders(phoneCount);
            if (where.length() != 0) {
                where.append(" OR ");
            }
            where.append("m.VIP_PHONE1 IN (").append(marks).append(")")
                 .append(" OR m.VIP_PHONE2 IN (").append(marks).append(")");
        }
        return base(withRemark) + "WHERE " + where + " ORDER BY m.VIP_ID";
    }

    /** 產生電話正規化備援的 SQL。SelfTest 也用這支做語法把關。 */
    static String buildFallbackSql() {
        return buildFallbackSql(true);
    }

    static String buildFallbackSql(boolean withRemark) {
        return base(withRemark)
            + "WHERE " + normalizedPhoneExpr("m.VIP_PHONE1") + " = ? "
            + "   OR " + normalizedPhoneExpr("m.VIP_PHONE2") + " = ? "
            + "ORDER BY m.VIP_ID";
    }

    private static List<Vector> runExact(Set<String> codes, Set<String> phones) {
        List<Object> params = new ArrayList<Object>();
        params.add(sessionOrgId());          // JOIN 條件裡的 ? 排在最前面
        params.addAll(codes);
        if (!phones.isEmpty()) {
            params.addAll(phones);           // VIP_PHONE1
            params.addAll(phones);           // VIP_PHONE2
        }
        List<Vector> rows = query(
            buildExactSql(codes.size(), phones.size(), remarkAvailable), params);
        if (rows == null && remarkAvailable) {
            rows = withoutRemark(query(buildExactSql(codes.size(), phones.size(), false),
                params));
        }
        return rows;
    }

    /**
     * 帶備註欄位查失敗、不帶就成功 —— 代表這台沒有那個欄位。關掉它，
     * 後面的查詢都走不帶備註的版本。回傳值原樣傳回去，只是順便記一筆。
     */
    private static List<Vector> withoutRemark(List<Vector> rows) {
        if (rows != null) {
            remarkAvailable = false;
            PosLog.warn("查不到 " + REMARK_COLUMN + " 欄位，面板不顯示 LINE 會員");
        }
        return rows;
    }

    private static List<Vector> runFallback(String canonicalPhone) {
        List<Object> params = new ArrayList<Object>();
        params.add(sessionOrgId());
        params.add(canonicalPhone);
        params.add(canonicalPhone);
        List<Vector> rows = query(buildFallbackSql(remarkAvailable), params);
        if (rows == null && remarkAvailable) {
            rows = withoutRemark(query(buildFallbackSql(false), params));
        }
        return rows;
    }

    /** 登入中的 ORG_ID；取不到就退回 01。 */
    private static String sessionOrgId() {
        Object orgId = Safe.staticCall(SHARED, "getOrgId", new Class<?>[0], new Object[0]);
        if (orgId == null) {
            return DEFAULT_ORG_ID;
        }
        String text = String.valueOf(orgId).trim();
        return text.length() == 0 ? DEFAULT_ORG_ID : text;
    }

    /** 在資料庫端把電話正規化成比對用字串。只用 Postgres 與 Oracle 都有的函式。 */
    private static String normalizedPhoneExpr(String column) {
        String compact = "REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(TRIM(" + column
            + "), ' ', ''), '-', ''), '(', ''), ')', ''), '+', '')";
        return "CASE WHEN " + compact + " LIKE '886%' "
            + "THEN '0' || SUBSTR(" + compact + ", 4) ELSE " + compact + " END";
    }

    private static String placeholders(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("?");
        }
        return sb.toString();
    }

    /** 走 EPB 已登入的連線。查詢失敗回 null，跟「查無資料」的空 list 區分開。 */
    @SuppressWarnings("unchecked")
    private static List<Vector> query(String sql, List<Object> params) {
        Object result = Safe.staticCall(
            UTILITY, "getResultList",
            new Class<?>[] { String.class, List.class, int.class },
            new Object[] { sql, params, Integer.valueOf(MAX_RESULTS + 1) });
        if (result == null) {
            return null;
        }
        try {
            return (List<Vector>) result;
        } catch (Throwable t) {
            PosLog.warn("查詢回傳型別非預期", t);
            return null;
        }
    }

    // -- 結果組裝 ----------------------------------------------------------

    private static Outcome toOutcome(List<Vector> rows, String canonicalPhone) {
        if (rows.size() > MAX_RESULTS) {
            return Outcome.message("符合的會員太多，請輸入更完整的資料");
        }

        List<Vip> results = new ArrayList<Vip>();
        Set<String> seen = new LinkedHashSet<String>();
        for (Vector row : rows) {
            String memberCode = cell(row, 0);
            String name = cell(row, 1);
            String phone1 = cell(row, 2);
            String phone2 = cell(row, 3);
            String email = cell(row, 4);
            String classId = cell(row, 5);
            String className = cell(row, 6);
            String remark = cell(row, 7);

            String phone = displayPhone(phone1, phone2, canonicalPhone);
            if (memberCode.length() == 0) {
                continue;
            }
            if (!seen.add(memberCode)) {
                continue;
            }
            results.add(new Vip(memberCode, name, phone, email,
                level(classId, className), remark));
        }

        if (results.isEmpty()) {
            return Outcome.message("查無此會員");
        }
        return Outcome.of(results);
    }

    private static String level(String classId, String className) {
        if (className.length() != 0 && classId.length() != 0 && !className.equals(classId)) {
            return className + " (" + classId + ")";
        }
        if (className.length() != 0) {
            return className;
        }
        return classId.length() != 0 ? classId : "未設定";
    }

    private static String displayPhone(String phone1, String phone2, String canonicalPhone) {
        if (canonicalPhone != null) {
            if (phone1.length() != 0 && canonicalPhone.equals(normalizePhone(phone1))) {
                return phone1;
            }
            if (phone2.length() != 0 && canonicalPhone.equals(normalizePhone(phone2))) {
                return phone2;
            }
        }
        return phone1.length() != 0 ? phone1 : phone2;
    }

    private static String cell(Vector row, int index) {
        if (row == null || index >= row.size()) {
            return "";
        }
        Object value = row.get(index);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
