package com.posassist;

/**
 * 一筆自訂結帳代碼：分類、名稱、代碼，外加是否釘選。
 *
 * 檔案格式是一行一筆的 `分類|名稱|代碼`，所以三個欄位都不能含 `|` 或換行；
 * 名稱與代碼不能空白。驗證集中在這裡，讀檔與編輯器共用同一套規則。
 *
 * 子分類寫在分類欄裡：`配件/袋類`，第一個 `/` 前面是主分類、後面是子分類。
 * 同樣是為了不動欄位數 —— 舊版本讀到只是看到一個名字長一點的分類，資料不會掉。
 *
 * 釘選刻意不寫進這一行 —— 舊版本讀到多一欄的行會整筆當成格式不符丟掉，
 * 代碼就這樣從店員的面板上消失。改存在另一個檔（見 CodeStore），
 * 舊版看不懂也只是忽略，代碼照樣在。
 */
public final class CodeItem {

    public static final String DEFAULT_CATEGORY = "常用";
    private static final char SEPARATOR = '|';
    private static final char SUB_SEPARATOR = '/';

    /** 主分類。分類欄若寫成「配件/袋類」，這裡只有「配件」。 */
    public final String category;
    /** 子分類，沒有就是空字串。面板拿它把格子分段。 */
    public final String sub;
    public final String name;
    public final String code;
    /** 釘選的會固定顯示在面板最上面，不受分類切換影響。 */
    public final boolean pinned;

    public CodeItem(String category, String name, String code) {
        this(category, name, code, false);
    }

    /** category 收的是檔案裡那一欄的原字串，主／子分類在這裡切開，規則只有這一份。 */
    public CodeItem(String category, String name, String code, boolean pinned) {
        String raw = category == null ? "" : category.trim();
        int cut = raw.indexOf(SUB_SEPARATOR);
        this.category = normalize(cut < 0 ? raw : raw.substring(0, cut), DEFAULT_CATEGORY);
        this.sub = cut < 0 ? "" : normalize(raw.substring(cut + 1), "");
        this.name = normalize(name, "");
        this.code = normalize(code, "");
        this.pinned = pinned;
    }

    /** 給編輯器用：主分類與子分類本來就是分開的兩欄，接起來走同一條切分規則。 */
    public static CodeItem of(String category, String sub, String name, String code,
        boolean pinned) {
        String tail = sub == null ? "" : sub.trim();
        String head = category == null ? "" : category.trim();
        return new CodeItem(
            tail.length() == 0 ? head : head + SUB_SEPARATOR + tail,
            name, code, pinned);
    }

    /** 只換釘選狀態，其餘照抄。 */
    public CodeItem withPinned(boolean value) {
        return value == pinned ? this : of(category, sub, name, code, value);
    }

    private static String normalize(String value, String fallback) {
        String text = value == null ? "" : value.trim();
        return text.length() == 0 ? fallback : text;
    }

    /** 不合格就回傳給人看的原因；合格回 null。 */
    public String validate() {
        if (name.length() == 0) {
            return "名稱不能空白";
        }
        if (code.length() == 0) {
            return "代碼不能空白";
        }
        if (contains(sub, SUB_SEPARATOR)) {
            return "子分類不能再分層";
        }
        if (contains(category, SEPARATOR) || contains(sub, SEPARATOR)
            || contains(name, SEPARATOR) || contains(code, SEPARATOR)) {
            return "不能含有「" + SEPARATOR + "」符號";
        }
        if (hasLineBreak(category) || hasLineBreak(sub)
            || hasLineBreak(name) || hasLineBreak(code)) {
            return "不能含有換行";
        }
        return null;
    }

    public boolean isValid() {
        return validate() == null;
    }

    /** 解析一行；空行、註解行、格式不對都回 null。 */
    public static CodeItem parse(String line) {
        if (line == null) {
            return null;
        }
        String text = line.trim();
        if (text.length() == 0 || text.charAt(0) == '#') {
            return null;
        }
        int first = text.indexOf(SEPARATOR);
        if (first < 0) {
            return null;
        }
        int second = text.indexOf(SEPARATOR, first + 1);
        if (second < 0) {
            return null;
        }
        CodeItem item = new CodeItem(
            text.substring(0, first),
            text.substring(first + 1, second),
            text.substring(second + 1));
        return item.isValid() ? item : null;
    }

    public String toLine() {
        String head = sub.length() == 0 ? category : category + SUB_SEPARATOR + sub;
        return head + SEPARATOR + name + SEPARATOR + code;
    }

    public String toString() {
        return toLine();
    }

    private static boolean contains(String value, char c) {
        return value.indexOf(c) >= 0;
    }

    private static boolean hasLineBreak(String value) {
        return value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
    }
}
