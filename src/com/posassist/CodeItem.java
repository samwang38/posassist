package com.posassist;

/**
 * 一筆自訂結帳代碼：分類、名稱、代碼。
 *
 * 檔案格式是一行一筆的 `分類|名稱|代碼`，所以三個欄位都不能含 `|` 或換行；
 * 名稱與代碼不能空白。驗證集中在這裡，讀檔與編輯器共用同一套規則。
 */
public final class CodeItem {

    public static final String DEFAULT_CATEGORY = "常用";
    private static final char SEPARATOR = '|';

    public final String category;
    public final String name;
    public final String code;

    public CodeItem(String category, String name, String code) {
        this.category = normalize(category, DEFAULT_CATEGORY);
        this.name = normalize(name, "");
        this.code = normalize(code, "");
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
        if (contains(category, SEPARATOR) || contains(name, SEPARATOR)
            || contains(code, SEPARATOR)) {
            return "不能含有「" + SEPARATOR + "」符號";
        }
        if (hasLineBreak(category) || hasLineBreak(name) || hasLineBreak(code)) {
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
        return category + SEPARATOR + name + SEPARATOR + code;
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
