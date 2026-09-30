package com.posassist;

import java.util.ArrayList;
import java.util.List;

/**
 * 代碼編輯器背後的資料：主分類 → 群組（未分段＋子分類）→ 代碼，全部有順序。
 *
 * 編輯器改成「左邊選分類、右邊列代碼」之後，分類不再手打，而是由這棵樹的位置決定；
 * 存檔時 flatten() 依樹的順序攤平回 codes.txt 的一行一筆。攤平的順序刻意符合面板的顯示規則
 * （分類依首次出現、未分段排在子分類前面），所以編輯器裡看到的順序就是面板上的順序。
 *
 * 釘選的代碼一樣留在自己的分類裡 —— 面板把它們拉到最上面顯示，但檔案裡它們仍屬於某個分類，
 * 取消釘選就回到原位。所以這裡不能用 CodeStore.categories()（那個會略過釘選）。
 *
 * 純資料、不碰 Swing，讓規則可以直接測。
 */
final class CodeTree {

    static final String LOOSE = "";

    private static final class Group {
        String sub;
        final List<CodeItem> items = new ArrayList<CodeItem>();

        Group(String sub) {
            this.sub = sub;
        }
    }

    private static final class Category {
        String name;
        /** 第 0 個永遠是未分段（sub 為空字串），其後是子分類。 */
        final List<Group> groups = new ArrayList<Group>();

        Category(String name) {
            this.name = name;
            groups.add(new Group(LOOSE));
        }

        Group group(String sub) {
            for (int i = 0; i < groups.size(); i++) {
                if (groups.get(i).sub.equals(sub)) {
                    return groups.get(i);
                }
            }
            return null;
        }

        int total() {
            int count = 0;
            for (int i = 0; i < groups.size(); i++) {
                count += groups.get(i).items.size();
            }
            return count;
        }
    }

    /** 搜尋結果的一筆：在哪個群組的第幾個。 */
    static final class Hit {
        final String category;
        final String sub;
        final int index;
        final CodeItem item;

        Hit(String category, String sub, int index, CodeItem item) {
            this.category = category;
            this.sub = sub;
            this.index = index;
            this.item = item;
        }
    }

    private final List<Category> categories = new ArrayList<Category>();

    static CodeTree of(List<CodeItem> items) {
        CodeTree tree = new CodeTree();
        for (int i = 0; i < items.size(); i++) {
            CodeItem item = items.get(i);
            tree.ensureGroup(item.category, item.sub).items.add(item);
        }
        return tree;
    }

    // -- 讀取 --------------------------------------------------------------

    List<String> categories() {
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < categories.size(); i++) {
            names.add(categories.get(i).name);
        }
        return names;
    }

    /** 某主分類底下的子分類（不含未分段）。 */
    List<String> subs(String category) {
        List<String> names = new ArrayList<String>();
        Category found = find(category);
        if (found != null) {
            for (int i = 1; i < found.groups.size(); i++) {
                names.add(found.groups.get(i).sub);
            }
        }
        return names;
    }

    List<CodeItem> items(String category, String sub) {
        Group group = group(category, sub);
        return group == null ? new ArrayList<CodeItem>() : new ArrayList<CodeItem>(group.items);
    }

    int count(String category, String sub) {
        Group group = group(category, sub);
        return group == null ? 0 : group.items.size();
    }

    int total(String category) {
        Category found = find(category);
        return found == null ? 0 : found.total();
    }

    boolean hasCategory(String category) {
        return find(category) != null;
    }

    boolean hasGroup(String category, String sub) {
        return group(category, sub) != null;
    }

    /** 沒有任何代碼的主分類。存檔時它們不會留下來（檔案格式只記代碼）。 */
    List<String> emptyCategories() {
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < categories.size(); i++) {
            if (categories.get(i).total() == 0) {
                names.add(categories.get(i).name);
            }
        }
        return names;
    }

    /** 名稱或代碼包含 query（不分大小寫）的項目，依樹的順序。 */
    List<Hit> search(String query) {
        List<Hit> hits = new ArrayList<Hit>();
        String needle = query == null ? "" : query.trim().toLowerCase();
        if (needle.length() == 0) {
            return hits;
        }
        for (int c = 0; c < categories.size(); c++) {
            Category category = categories.get(c);
            for (int g = 0; g < category.groups.size(); g++) {
                Group group = category.groups.get(g);
                for (int i = 0; i < group.items.size(); i++) {
                    CodeItem item = group.items.get(i);
                    if (item.name.toLowerCase().indexOf(needle) >= 0
                        || item.code.toLowerCase().indexOf(needle) >= 0) {
                        hits.add(new Hit(category.name, group.sub, i, item));
                    }
                }
            }
        }
        return hits;
    }

    /** 依樹的順序攤平，分類欄位以樹上的位置為準。空的分類自然不會出現。 */
    List<CodeItem> flatten() {
        List<CodeItem> items = new ArrayList<CodeItem>();
        for (int c = 0; c < categories.size(); c++) {
            Category category = categories.get(c);
            for (int g = 0; g < category.groups.size(); g++) {
                Group group = category.groups.get(g);
                for (int i = 0; i < group.items.size(); i++) {
                    CodeItem item = group.items.get(i);
                    items.add(CodeItem.of(category.name, group.sub, item.name, item.code,
                        item.pinned));
                }
            }
        }
        return items;
    }

    /** 依樹的順序找第一筆不合格的項目，全部合格回 null。 */
    Hit firstInvalid() {
        for (int c = 0; c < categories.size(); c++) {
            Category category = categories.get(c);
            for (int g = 0; g < category.groups.size(); g++) {
                Group group = category.groups.get(g);
                for (int i = 0; i < group.items.size(); i++) {
                    CodeItem item = CodeItem.of(category.name, group.sub,
                        group.items.get(i).name, group.items.get(i).code, false);
                    if (!item.isValid()) {
                        return new Hit(category.name, group.sub, i, item);
                    }
                }
            }
        }
        return null;
    }

    // -- 分類 --------------------------------------------------------------

    /** 分類名稱的規則：不能空白、不能含「|」「/」或換行。合格回 null。 */
    static String checkName(String name) {
        String text = name == null ? "" : name.trim();
        if (text.length() == 0) {
            return "名稱不能空白";
        }
        if (text.indexOf('|') >= 0 || text.indexOf('/') >= 0) {
            return "不能含有「|」或「/」符號";
        }
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return "不能含有換行";
        }
        return null;
    }

    String addCategory(String name) {
        String problem = checkName(name);
        if (problem != null) {
            return problem;
        }
        if (find(name.trim()) != null) {
            return "已經有「" + name.trim() + "」這個分類";
        }
        categories.add(new Category(name.trim()));
        return null;
    }

    String addSub(String category, String sub) {
        Category found = find(category);
        if (found == null) {
            return "找不到分類";
        }
        String problem = checkName(sub);
        if (problem != null) {
            return problem;
        }
        if (found.group(sub.trim()) != null) {
            return "「" + category + "」底下已經有「" + sub.trim() + "」";
        }
        found.groups.add(new Group(sub.trim()));
        return null;
    }

    /** 改成已經存在的名稱就是合併：代碼接在那個分類的同名群組後面。 */
    String renameCategory(String from, String to) {
        Category source = find(from);
        if (source == null) {
            return "找不到分類";
        }
        String problem = checkName(to);
        if (problem != null) {
            return problem;
        }
        String name = to.trim();
        if (name.equals(source.name)) {
            return null;
        }
        Category target = find(name);
        if (target == null) {
            source.name = name;
            return null;
        }
        for (int g = 0; g < source.groups.size(); g++) {
            Group group = source.groups.get(g);
            Group into = target.group(group.sub);
            if (into == null) {
                into = new Group(group.sub);
                target.groups.add(into);
            }
            into.items.addAll(group.items);
        }
        categories.remove(source);
        return null;
    }

    String renameSub(String category, String from, String to) {
        Category found = find(category);
        Group source = found == null ? null : found.group(from);
        if (source == null || LOOSE.equals(from)) {
            return "找不到子分類";
        }
        String problem = checkName(to);
        if (problem != null) {
            return problem;
        }
        String name = to.trim();
        if (name.equals(source.sub)) {
            return null;
        }
        Group target = found.group(name);
        if (target == null) {
            source.sub = name;
            return null;
        }
        target.items.addAll(source.items);
        found.groups.remove(source);
        return null;
    }

    void removeCategory(String category) {
        Category found = find(category);
        if (found != null) {
            categories.remove(found);
        }
    }

    void removeSub(String category, String sub) {
        Category found = find(category);
        Group group = found == null ? null : found.group(sub);
        if (group != null && !LOOSE.equals(sub)) {
            found.groups.remove(group);
        }
    }

    /** 回傳是否真的有移動。 */
    boolean moveCategory(String category, int delta) {
        Category found = find(category);
        if (found == null) {
            return false;
        }
        int from = categories.indexOf(found);
        int to = from + delta;
        if (to < 0 || to >= categories.size()) {
            return false;
        }
        categories.remove(from);
        categories.add(to, found);
        return true;
    }

    /** 子分類只在子分類之間移動，未分段永遠第一。 */
    boolean moveSub(String category, String sub, int delta) {
        Category found = find(category);
        Group group = found == null ? null : found.group(sub);
        if (group == null || LOOSE.equals(sub)) {
            return false;
        }
        int from = found.groups.indexOf(group);
        int to = from + delta;
        if (to < 1 || to >= found.groups.size()) {
            return false;
        }
        found.groups.remove(from);
        found.groups.add(to, group);
        return true;
    }

    // -- 代碼 --------------------------------------------------------------

    /** 加在群組最後面，回傳位置。群組不存在回 -1。 */
    int addItem(String category, String sub, CodeItem item) {
        Group group = group(category, sub);
        if (group == null) {
            return -1;
        }
        group.items.add(item);
        return group.items.size() - 1;
    }

    void setItem(String category, String sub, int index, CodeItem item) {
        Group group = group(category, sub);
        if (group != null && index >= 0 && index < group.items.size()) {
            group.items.set(index, item);
        }
    }

    void removeItem(String category, String sub, int index) {
        Group group = group(category, sub);
        if (group != null && index >= 0 && index < group.items.size()) {
            group.items.remove(index);
        }
    }

    /**
     * 群組內搬移。to 是「拿掉原本那筆之後」要插入的位置，超出範圍就夾到頭尾。
     * 回傳搬移後的位置；來源不合法回 -1。
     */
    int moveItem(String category, String sub, int from, int to) {
        Group group = group(category, sub);
        if (group == null || from < 0 || from >= group.items.size()) {
            return -1;
        }
        CodeItem item = group.items.remove(from);
        int target = Math.max(0, Math.min(to, group.items.size()));
        group.items.add(target, item);
        return target;
    }

    /** 搬到另一個群組的最後面。回傳是否成功。 */
    boolean moveItemTo(String category, String sub, int index,
        String toCategory, String toSub) {
        Group source = group(category, sub);
        Group target = group(toCategory, toSub);
        if (source == null || target == null || source == target
            || index < 0 || index >= source.items.size()) {
            return false;
        }
        target.items.add(source.items.remove(index));
        return true;
    }

    // -- 內部 --------------------------------------------------------------

    private Category find(String name) {
        for (int i = 0; i < categories.size(); i++) {
            if (categories.get(i).name.equals(name)) {
                return categories.get(i);
            }
        }
        return null;
    }

    private Group group(String category, String sub) {
        Category found = find(category);
        return found == null ? null : found.group(sub == null ? LOOSE : sub);
    }

    private Group ensureGroup(String category, String sub) {
        Category found = find(category);
        if (found == null) {
            found = new Category(category);
            categories.add(found);
        }
        Group group = found.group(sub);
        if (group == null) {
            group = new Group(sub);
            found.groups.add(group);
        }
        return group;
    }
}
