package com.posassist;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Vector;

/** 重開單據的離線邏輯：明細轉換、預設勾選、展開件數、行號正規化。不需要 EPB。 */
public final class ReorderTest {
    private static int checks;

    static void check(boolean ok, String name) {
        checks++;
        if (!ok) throw new AssertionError(name);
    }

    /** 欄位順序同 Reorder.LINE_SQL：LINE_NO, LINE_TYPE, PLU_ID, STK_ID, NAME, STK_QTY, SRN_ID, REF1..REF8 */
    static Vector row(Object lineNo, String type, String plu, String stk, String name,
        Object qty, String serial, String ref7) {
        Vector row = new Vector(Arrays.asList(lineNo, type, plu, stk, name, qty, serial,
            null, null, null, null, null, null, ref7, null));
        return row;
    }

    public static void main(String[] args) {
        lines();
        plan();
        lineNumbers();
        docs();
        sql();
        System.out.println("ReorderTest: " + checks + " checks passed");
    }

    static void lines() {
        List<Vector> rows = new ArrayList<Vector>();
        rows.add(row(new BigDecimal("1"), "S", "07313590", "07313590", "iPad Air", new BigDecimal("1.000"), "F9X3K2L", "R2026101"));
        rows.add(row(new BigDecimal("2"), "S", "", "99901687", "保護貼", new BigDecimal("2"), null, null));
        rows.add(row(new BigDecimal("3"), "Y", "VIPDISC", "", "會員折扣", new BigDecimal("-1"), null, null));
        rows.add(row(new BigDecimal("4"), "S", null, null, "沒有代碼", BigDecimal.ONE, null, null));
        List<Reorder.Line> lines = Reorder.lines(rows);

        check(lines.size() == 3, "沒有代碼的列不顯示");
        Reorder.Line ipad = lines.get(0);
        check(ipad.include, "一般商品預設勾選");
        check(ipad.qty == 1, "1.000 是一件");
        check("F9X3K2L".equals(ipad.serial), "序號讀進來");
        check("R2026101".equals(ipad.refs[6]), "REF7 在索引 6");
        check("".equals(ipad.refs[0]), "沒值的 REF 是空字串");
        check(ipad.hasF10Values(), "有序號就要補 F10");
        check(!ipad.codeChanged(), "沒改代碼");
        ipad.code = "07313591";
        check(ipad.codeChanged(), "改了代碼就不填原序號");

        Reorder.Line film = lines.get(1);
        check("99901687".equals(film.code), "沒有 PLU 退回存貨代碼");
        check(film.qty == 2, "數量 2");
        check(!film.hasF10Values(), "沒有序號");

        Reorder.Line disc = lines.get(2);
        check(!disc.include, "折扣列預設不勾");
        check("折扣列".equals(Reorder.typeLabel(disc.lineType)), "折扣列標示");
        check(disc.qty == 1, "負數量取絕對值");

        Reorder.Line blank = Reorder.Line.blank();
        check(blank.include && blank.code.length() == 0 && !blank.hasF10Values(), "空白列");
        check(!blank.codeChanged(), "自己加的列沒有原代碼");
    }

    static void plan() {
        List<Vector> rows = new ArrayList<Vector>();
        rows.add(row(1, "S", "A", "A", "a", 1, null, null));
        rows.add(row(2, "S", "B", "B", "b", 3, null, null));
        rows.add(row(3, "M", "C", "C", "c", 1, null, null));
        List<Reorder.Line> lines = Reorder.lines(rows);
        List<Reorder.Step> steps = Reorder.plan(lines);
        check(steps.size() == 4, "1 + 3 件，折扣列不算");
        check(steps.get(1).line.code.equals("B") && steps.get(1).unit == 1, "第二步是 B 第一件");
        check(steps.get(3).unit == 3, "B 第三件");

        lines.get(1).include = false;
        lines.get(2).include = true;
        check(Reorder.plan(lines).size() == 2, "取消勾選與手動勾選");

        lines.get(0).code = "  ";
        check(Reorder.plan(lines).size() == 1, "代碼清空的列略過");

        lines.get(2).qty = 999;
        check(Reorder.plan(lines).size() == Reorder.MAX_QTY, "數量有上限");
    }

    static void lineNumbers() {
        check("1".equals(Reorder.lineNo(new BigDecimal("1.0"))), "1.0 → 1");
        check("1".equals(Reorder.lineNo("1")), "字串 1");
        check("12".equals(Reorder.lineNo(Integer.valueOf(12))), "整數");
        check("".equals(Reorder.lineNo(null)), "null");
        check("A1".equals(Reorder.lineNo(" A1 ")), "非數字原樣");
        check(Reorder.quantity(new BigDecimal("0.4")) == 1, "小數至少一件");
        check(Reorder.quantity(null) == 1, "沒數量當一件");
    }

    static void docs() {
        List<Vector> rows = new ArrayList<Vector>();
        rows.add(new Vector(Arrays.asList("D001", new java.sql.Timestamp(0), "P01", "V001",
            "AB12345678", new BigDecimal("35900"), new BigDecimal("2"))));
        rows.add(new Vector(Arrays.asList("D002", null, null, null, null, null, null)));
        List<Reorder.Doc> docs = Reorder.docs(rows);
        check(docs.size() == 2, "兩張單");
        check("AB12345678".equals(docs.get(0).invoice) && docs.get(0).date != null, "發票與日期");
        check("".equals(docs.get(1).vipId) && docs.get(1).total == null, "空值不炸");
        check(Reorder.docs(null) == null, "查詢失敗維持 null");
    }

    static void sql() {
        String[] all = { Reorder.SQL_BY_VIP, Reorder.SQL_BY_POS, Reorder.SQL_BY_NUMBER, Reorder.LINE_SQL };
        for (String sql : all) {
            String upper = sql.toUpperCase();
            check(upper.startsWith("SELECT "), "只有 SELECT");
            check(upper.indexOf("CURRENT_DATE") < 0 && upper.indexOf("SYSDATE") < 0, "日期用參數");
        }
        check(Reorder.SQL_BY_VIP.indexOf("TRANS_TYPE = 'A'") > 0, "只列銷售單");
        check(Reorder.daysAgo(0).getTime() <= System.currentTimeMillis(), "今天零點不在未來");
    }
}
