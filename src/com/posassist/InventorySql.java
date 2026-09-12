package com.posassist;

import java.util.*;

/** Oracle SQL for the REMOTE source only. Never used with the client's local DB. */
final class InventorySql {
    static final int SEARCH_LIMIT = 100, STOCK_LIMIT = 30000, STORE_LIMIT = 200, CATALOG_LIMIT = 1000;
    private InventorySql() {}

    static String literal(String text) {
        if (text == null || text.length() > 200 || text.indexOf('|') >= 0)
            throw new IllegalArgumentException("查詢內容過長或含不支援字元");
        for (int i = 0; i < text.length(); i++) if (Character.isISOControl(text.charAt(i)))
            throw new IllegalArgumentException("查詢內容含控制字元");
        return "'" + text.replace("'", "''") + "'";
    }
    static String like(String text) {
        return literal("%" + text.toUpperCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
    }
    static String roster(String storeAccess) {
        return "SELECT m.STORE_ID, MIN(p.SHOP_NAME) STORE_NAME, MIN(p.SHOPTYPE_ID) SHOP_TYPE "
            + "FROM STOREMAS m JOIN POS_SHOP_MAS p ON p.STORE_ID=m.STORE_ID "
            + "WHERE m.ORG_ID='01' AND m.STATUS_FLG='A' AND p.STATUS_FLG='A' "
            + "AND REGEXP_LIKE(m.STORE_ID,'^SA[0-9]{3}$') AND m.STORE_ID<>'SA999' "
            + "AND REGEXP_LIKE(p.SHOP_ID,'^[0-9]{3}$') AND p.SHOPTYPE_ID IN ('1_APR','2_AAR') "
            + "AND (" + storeAccess + ") GROUP BY m.STORE_ID "
            + "HAVING COUNT(DISTINCT p.SHOPTYPE_ID)=1 ORDER BY m.STORE_ID";
    }
    static String ids(Collection<String> ids) {
        if (ids.isEmpty()) throw new IllegalArgumentException("查詢範圍為空");
        StringBuilder s = new StringBuilder();
        for (String id : ids) { if (s.length() > 0) s.append(','); s.append(literal(id)); }
        return s.toString();
    }
    static String base(List<Inventory.Store> stores, String access) {
        List<String> ids = new ArrayList<String>(); for (Inventory.Store s : stores) ids.add(s.id);
        return " FROM STORESUM WHERE STORESUM.ORG_ID='01' AND STORESUM.STORE_ID IN (" + ids(ids) + ") "
            + "AND STORESUM.STATUS_FLG='A' AND STORESUM.STORE_STATUS_FLG='A' "
            + "AND STORESUM.CAT3_ID IN ('3001','3002','3003') AND STORESUM.CAT1_ID='1001' "
            + "AND STORESUM.STK_ID NOT LIKE '999%' "
            + "AND STORESUM.NAME NOT LIKE '@%' "
            + "AND NOT REGEXP_LIKE(UPPER(STORESUM.NAME),'展示|展機|樣品|福利|整新|專案|驗收|非賣|贈品|DEMO|NFR') "
            + "AND (" + access + ") ";
    }
    static final String PRODUCT_COLUMNS = "STORESUM.STK_ID, MIN(STORESUM.NAME) PRODUCT_NAME, "
        + "MIN(STORESUM.MODEL) MODEL, MIN(STORESUM.UOM_ID) UOM_ID, "
        + "MIN(STORESUM.CAT3_ID) CAT3_ID, MIN(STORESUM.CAT4_ID) CAT4_ID";
    // Candidate IDs only, never displayed before the STORESUM access/sellability check.
    // Pushing literal STK_IDs into STORESUM avoids evaluating its inventory functions for all history.
    static String catalog(String query) {
        String sql="SELECT DISTINCT k.STK_ID FROM STKMAS k LEFT JOIN SKUMAS s ON s.STK_ID=k.STK_ID "
            + "WHERE k.LINE_TYPE='S' AND k.STATUS_FLG='A' "
            + "AND ((k.CAT1_ID='1001' AND k.CAT3_ID IN ('3001','3002','3003')) OR "
            + "(NVL(s.CAT1_ID,k.CAT1_ID)='1001' AND NVL(s.CAT3_ID,k.CAT3_ID) IN ('3001','3002','3003'))) AND k.STK_ID NOT LIKE '999%' "
            + "AND k.NAME NOT LIKE '@%' AND NOT REGEXP_LIKE(UPPER(k.NAME),'展示|展機|樣品|福利|整新|專案|驗收|非賣|贈品|DEMO|NFR') ";
        sql+=searchTerms(query,"k",true);
        return sql+" ORDER BY k.STK_ID";
    }
    static String restrict(String sql, List<String> candidates) {
        int group=sql.indexOf(" GROUP BY STORESUM.");
        if(group<0)throw new IllegalArgumentException("不支援的庫存查詢模板");
        return sql.substring(0,group)+" AND STORESUM.STK_ID IN ("+ids(candidates)+") "+sql.substring(group);
    }
    static String search(List<Inventory.Store> stores, String access, String query) {
        String condition=searchTerms(query,"STORESUM",false);
        return "SELECT " + PRODUCT_COLUMNS + base(stores, access) + condition
            + " GROUP BY STORESUM.STK_ID HAVING COUNT(DISTINCT STORESUM.UOM_ID)=1 ORDER BY STORESUM.STK_ID";
    }
    static String products(List<Inventory.Store> stores,String access,List<String> productIds) {
        return "SELECT " + PRODUCT_COLUMNS + ", COUNT(DISTINCT STORESUM.UOM_ID) UNIT_COUNT"
            + base(stores,access) + " AND STORESUM.STK_ID IN (" + ids(NativeInventorySelection.ids(productIds)) + ")"
            + " GROUP BY STORESUM.STK_ID ORDER BY STORESUM.STK_ID";
    }
    static List<String> terms(String query) {
        query=java.text.Normalizer.normalize(Inventory.clean(query),java.text.Normalizer.Form.NFKC).trim();
        if(query.length()<2||query.length()>80)throw new IllegalArgumentException("請輸入 2–80 個字元");
        java.util.LinkedHashSet<String> words=new java.util.LinkedHashSet<String>(Arrays.asList(query.toUpperCase(Locale.ROOT).split("\\s+")));
        if(words.size()>12)throw new IllegalArgumentException("關鍵字最多 12 組，請縮小條件");
        return new ArrayList<String>(words);
    }
    private static String searchTerms(String query,String table,boolean skuBarcodes) {
        StringBuilder sql=new StringBuilder();
        for(String term:terms(query)){
            String pattern=like(term),exact=literal(term);
            sql.append(" AND (UPPER(").append(table).append(".STK_ID) LIKE ").append(pattern).append(" ESCAPE '!' ")
               .append("OR UPPER(").append(table).append(".MODEL) LIKE ").append(pattern).append(" ESCAPE '!' ")
               .append("OR UPPER(").append(table).append(".NAME) LIKE ").append(pattern).append(" ESCAPE '!' ")
               .append("OR UPPER(").append(table).append(".BAR_CODE1)=").append(exact)
               .append(" OR UPPER(").append(table).append(".BAR_CODE2)=").append(exact);
            if(skuBarcodes)sql.append(" OR UPPER(s.BAR_CODE1)=").append(exact).append(" OR UPPER(s.BAR_CODE2)=").append(exact);
            sql.append(") ");
        }
        return sql.toString();
    }
    static String stock(List<Inventory.Store> stores, String access, List<Inventory.Line> lines) {
        if (lines.isEmpty() || lines.size() > Inventory.MAX_ITEMS) throw new IllegalArgumentException("請先加入商品");
        List<String> ids = new ArrayList<String>(); for (Inventory.Line l : lines) ids.add(l.product.id);
        return rows(stores, access, " AND STORESUM.STK_ID IN (" + ids(ids) + ") ");
    }
    static String category(List<Inventory.Store> stores, String access, Inventory.Category category) {
        return rows(stores, access, " AND STORESUM.CAT3_ID='3001' AND STORESUM.CAT4_ID IN (" + ids(category.codes) + ") ");
    }
    static String rows(List<Inventory.Store> stores, String access, String condition) {
        return "SELECT STORESUM.STORE_ID, " + PRODUCT_COLUMNS + ", SUM(NVL(STORESUM.STK_QTY,0)) STOCK_QTY, "
            + "COUNT(DISTINCT STORESUM.UOM_ID) UNIT_COUNT" + base(stores, access) + condition
            + " GROUP BY STORESUM.STORE_ID, STORESUM.STK_ID ORDER BY STORESUM.STORE_ID, STORESUM.STK_ID";
    }
    static String bounded(String sql, int limit) {
        // Reject statements/comments outside literals, including unexpected vendor ACL syntax.
        String structural = sql.replaceAll("'([^']|'')*'", "''").toUpperCase(Locale.ROOT);
        if (!structural.startsWith("SELECT ") || structural.contains(";") || structural.contains("--")
                || structural.contains("/*") || structural.matches("(?s).*\\b(INSERT|UPDATE|DELETE|MERGE|DROP|ALTER|CREATE|CALL|EXECUTE)\\b.*"))
            throw new IllegalArgumentException("庫存查詢必須是單一唯讀 SELECT");
        return "SELECT PA_ROWS.*, COUNT(*) OVER () PA_RESULT_ROWS FROM (" + sql + ") PA_ROWS WHERE ROWNUM <= " + (limit + 1);
    }
}
