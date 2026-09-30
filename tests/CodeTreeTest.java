package com.posassist;

import java.util.Arrays;
import java.util.List;

/** 代碼編輯器的樹狀資料：攤平順序、改名合併、搬移、驗證。不需要 EPB。 */
public final class CodeTreeTest {
    private static int checks;

    static void check(boolean ok, String name) {
        checks++;
        if (!ok) throw new AssertionError(name);
    }

    static CodeItem item(String category, String name, String code) {
        return new CodeItem(category, name, code);
    }

    static String lines(List<CodeItem> items) {
        StringBuilder text = new StringBuilder();
        for (CodeItem item : items) text.append(item.toLine()).append(item.pinned ? "*" : "").append('\n');
        return text.toString();
    }

    static CodeTree sample() {
        return CodeTree.of(Arrays.asList(
            item("常用", "環保紙袋", "07310011"),
            item("配件/袋類", "紙提袋", "07310013"),
            item("常用", "大提袋", "07310012").withPinned(true),
            item("配件", "吊繩", "07320001"),
            item("配件/保護", "保護貼", "07320145"),
            item("服務", "全機包膜", "07320200")));
    }

    public static void main(String[] args) {
        roundTrip();
        categories();
        items();
        validation();
        System.out.println("CodeTreeTest: " + checks + " checks passed");
    }

    static void roundTrip() {
        CodeTree tree = sample();
        check(tree.categories().equals(Arrays.asList("常用", "配件", "服務")), "categories by first appearance, pinned included");
        check(tree.subs("配件").equals(Arrays.asList("袋類", "保護")), "subs by first appearance, loose excluded");
        check(lines(tree.flatten()).equals(
            "常用|環保紙袋|07310011\n常用|大提袋|07310012*\n"
            + "配件|吊繩|07320001\n配件/袋類|紙提袋|07310013\n配件/保護|保護貼|07320145\n"
            + "服務|全機包膜|07320200\n"), "flatten groups by tree order, loose first, keeps pins");
        check(lines(CodeTree.of(tree.flatten()).flatten()).equals(lines(tree.flatten())), "flatten is stable");
        check(tree.count("配件", "") == 1 && tree.total("配件") == 3, "counts");
    }

    static void categories() {
        CodeTree tree = sample();
        check(tree.addCategory("新品") == null && tree.hasCategory("新品"), "add category");
        check(tree.addCategory("新品") != null, "duplicate category rejected");
        check(tree.addCategory("a/b") != null && tree.addCategory("a|b") != null && tree.addCategory("  ") != null, "bad names rejected");
        check(tree.emptyCategories().equals(Arrays.asList("新品")), "empty category reported");
        check(!lines(tree.flatten()).contains("新品"), "empty category dropped on flatten");
        check(tree.addSub("新品", "耳機") == null && tree.hasGroup("新品", "耳機"), "add sub");
        check(tree.addSub("新品", "耳機") != null, "duplicate sub rejected");

        check(tree.renameCategory("服務", "售後") == null && tree.categories().contains("售後") && !tree.hasCategory("服務"), "rename category");
        check(lines(tree.flatten()).contains("售後|全機包膜|07320200"), "rename rewrites items");
        check(tree.renameCategory("售後", "常用") == null && !tree.hasCategory("售後"), "rename onto existing merges");
        check(tree.items("常用", "").size() == 3 && tree.items("常用", "").get(2).code.equals("07320200"), "merged items appended");

        check(tree.renameSub("配件", "保護", "袋類") == null && tree.subs("配件").equals(Arrays.asList("袋類")), "sub rename onto existing merges");
        check(tree.items("配件", "袋類").size() == 2, "merged sub items");
        check(tree.renameSub("配件", "袋類", "包材") == null && tree.subs("配件").equals(Arrays.asList("包材")), "rename sub");

        check(tree.moveCategory("新品", -1) && tree.categories().indexOf("新品") == 1, "move category up");
        check(!tree.moveCategory("常用", -1), "cannot move first up");
        tree.addSub("配件", "線材");
        check(tree.moveSub("配件", "線材", -1) && tree.subs("配件").equals(Arrays.asList("線材", "包材")), "move sub");
        check(!tree.moveSub("配件", "線材", -1), "sub cannot pass loose group");

        tree.removeSub("配件", "包材");
        check(!tree.hasGroup("配件", "包材") && tree.hasGroup("配件", ""), "remove sub, loose stays");
        tree.removeCategory("新品");
        check(!tree.hasCategory("新品"), "remove category");
    }

    static void items() {
        CodeTree tree = sample();
        int at = tree.addItem("配件", "保護", item("x", "鏡頭貼", "07320146"));
        check(at == 1 && tree.items("配件", "保護").get(1).name.equals("鏡頭貼"), "add item at end of group");
        check(tree.addItem("沒有", "", item("x", "a", "b")) == -1, "add to missing group");
        check(tree.moveItem("配件", "保護", 1, 0) == 0 && tree.items("配件", "保護").get(0).name.equals("鏡頭貼"), "move item up");
        check(tree.moveItem("配件", "保護", 0, 99) == 1, "move clamps to bottom");
        tree.setItem("配件", "保護", 0, item("x", "玻璃貼", "07320145").withPinned(true));
        check(tree.items("配件", "保護").get(0).pinned, "set item keeps pin");
        check(lines(tree.flatten()).contains("配件/保護|玻璃貼|07320145*"), "category comes from tree, not item");
        check(tree.moveItemTo("配件", "保護", 1, "常用", ""), "move item to other group");
        check(tree.items("常用", "").get(2).name.equals("鏡頭貼") && tree.count("配件", "保護") == 1, "moved item appended");
        check(!tree.moveItemTo("常用", "", 0, "常用", ""), "same group move rejected");
        tree.removeItem("常用", "", 0);
        check(tree.items("常用", "").get(0).name.equals("大提袋"), "remove item");
        List<CodeTree.Hit> hits = tree.search("0732014");
        check(hits.size() == 2 && hits.get(0).category.equals("常用") && hits.get(1).sub.equals("保護"), "search by code in tree order");
        check(tree.search("紙袋").size() == 0 && tree.search("提袋").size() == 2 && tree.search(" ").isEmpty(), "search by name");
    }

    static void validation() {
        CodeTree tree = sample();
        check(tree.firstInvalid() == null, "sample valid");
        tree.addItem("配件", "袋類", item("x", "", ""));
        CodeTree.Hit bad = tree.firstInvalid();
        check(bad != null && bad.category.equals("配件") && bad.sub.equals("袋類") && bad.index == 1, "first invalid located");
        tree.setItem("配件", "袋類", 1, item("x", "名|稱", "1"));
        check(tree.firstInvalid() != null, "separator in name invalid");
    }
}
