package com.posassist;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 讀寫 config/codes.txt，以及釘選清單 config/codes.pins.txt。
 * 檔案順序就是顯示順序，分類與子分類都依首次出現排序。
 *
 * 釘選另外存一個檔、以代碼為鍵，是為了不動到 codes.txt 的欄位格式：
 * 舊版本讀到多一欄的行會整筆丟掉，代碼就從面板上消失；額外的檔案它只是看不到。
 * 釘選壞掉最多是少了置頂，代碼本身不會受影響。
 *
 * 寫檔採「先備份、寫暫存、再 rename」：
 * rename 在同一個檔案系統上是原子操作，所以不會出現寫到一半的半截檔案。
 * 上一版永遠留在 codes.txt.bak，改壞了可以直接拿回來。
 */
public final class CodeStore {

    static final String PATH = "config/codes.txt";
    private static final String BACKUP_PATH = "config/codes.txt.bak";
    private static final String TEMP_PATH = "config/codes.txt.tmp";
    static final String PINS_PATH = "config/codes.pins.txt";
    private static final String PINS_TEMP_PATH = "config/codes.pins.txt.tmp";
    private static final String CHARSET = "UTF-8";

    private static final String HEADER =
        "# PosAssist 自訂結帳代碼\n"
        + "# 一行一筆：分類|名稱|代碼\n"
        + "# 檔案順序就是面板上的顯示順序；分類依首次出現的先後排列。\n"
        + "# 三個欄位都不能含「|」或換行，名稱與代碼不能空白。\n"
        + "# 面板上的「編輯」會覆寫這個檔，並把前一版留成 codes.txt.bak。\n";

    private static final String PINS_HEADER =
        "# PosAssist 釘選的結帳代碼\n"
        + "# 一行一個代碼；有列在這裡的會固定顯示在面板最上面，不受分類切換影響。\n"
        + "# 對不上 codes.txt 的代碼會被忽略。\n";

    private CodeStore() {
    }

    public static boolean exists() {
        return Home.file(PATH).isFile();
    }

    /** 讀清單；沒有檔案或讀不到都回空清單，面板照常運作。 */
    public static List<CodeItem> load() {
        List<CodeItem> items = new ArrayList<CodeItem>();
        File file = Home.file(PATH);
        if (!file.isFile()) {
            return items;
        }
        BufferedReader reader = null;
        int skipped = 0;
        try {
            reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), CHARSET));
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.length() == 0 || trimmed.charAt(0) == '#') {
                    continue;
                }
                CodeItem item = CodeItem.parse(line);
                if (item == null) {
                    skipped++;
                } else {
                    items.add(item);
                }
            }
        } catch (Throwable t) {
            PosLog.warn("讀取代碼清單失敗，面板改為空清單");
            return new ArrayList<CodeItem>();
        } finally {
            close(reader);
        }
        if (skipped > 0) {
            PosLog.warn("代碼清單有 " + skipped + " 行格式不符，已略過");
        }
        return applyPins(items, loadPins());
    }

    /** 讀釘選的代碼；沒有檔案或讀不到都回空集合，面板就是全部都沒釘。 */
    public static Set<String> loadPins() {
        Set<String> codes = new LinkedHashSet<String>();
        File file = Home.file(PINS_PATH);
        if (!file.isFile()) {
            return codes;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), CHARSET));
            String line;
            while ((line = reader.readLine()) != null) {
                String text = line.trim();
                if (text.length() != 0 && text.charAt(0) != '#') {
                    codes.add(text);
                }
            }
        } catch (Throwable t) {
            PosLog.warn("讀取釘選清單失敗，這次全部視為未釘選");
            return new LinkedHashSet<String>();
        } finally {
            close(reader);
        }
        return codes;
    }

    private static List<CodeItem> applyPins(List<CodeItem> items, Set<String> pinned) {
        if (pinned.isEmpty()) {
            return items;
        }
        List<CodeItem> marked = new ArrayList<CodeItem>();
        for (int i = 0; i < items.size(); i++) {
            CodeItem item = items.get(i);
            marked.add(item.withPinned(pinned.contains(item.code)));
        }
        return marked;
    }

    /** 依原本順序取出被釘選的項目。 */
    public static List<CodeItem> pinned(List<CodeItem> items) {
        List<CodeItem> subset = new ArrayList<CodeItem>();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).pinned) {
                subset.add(items.get(i));
            }
        }
        return subset;
    }

    /**
     * 依首次出現順序取分類。釘選的不算 —— 它們已經固定在最上面，
     * 再讓它撐出一個空頁籤只會多一個點進去什麼都沒有的分類。
     */
    public static List<String> categories(List<CodeItem> items) {
        Set<String> seen = new LinkedHashSet<String>();
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).pinned) {
                seen.add(items.get(i).category);
            }
        }
        return new ArrayList<String>(seen);
    }

    /** 某個分類底下沒被釘選的項目（釘選的另外顯示在最上面，不重複列一次）。 */
    public static List<CodeItem> inCategory(List<CodeItem> items, String category) {
        List<CodeItem> subset = new ArrayList<CodeItem>();
        for (int i = 0; i < items.size(); i++) {
            CodeItem item = items.get(i);
            if (!item.pinned && item.category.equals(category)) {
                subset.add(item);
            }
        }
        return subset;
    }

    /**
     * 某個主分類底下的子分類，依首次出現排序。
     * 沒填子分類的那一群用空字串代表，而且永遠排最前面 —— 面板上先列沒歸類的，
     * 再一段一段列分好的，不會因為檔案裡的順序讓小標插在中間。
     */
    public static List<String> subCategories(List<CodeItem> items, String category) {
        Set<String> seen = new LinkedHashSet<String>();
        boolean loose = false;
        for (int i = 0; i < items.size(); i++) {
            CodeItem item = items.get(i);
            if (item.pinned || !item.category.equals(category)) {
                continue;
            }
            if (item.sub.length() == 0) {
                loose = true;
            } else {
                seen.add(item.sub);
            }
        }
        List<String> subs = new ArrayList<String>();
        if (loose) {
            subs.add("");
        }
        subs.addAll(seen);
        return subs;
    }

    /** 主分類與子分類都相符、且沒被釘選的項目。sub 傳空字串就是沒填子分類的那一群。 */
    public static List<CodeItem> inCategory(List<CodeItem> items, String category,
        String sub) {
        List<CodeItem> subset = new ArrayList<CodeItem>();
        for (int i = 0; i < items.size(); i++) {
            CodeItem item = items.get(i);
            if (!item.pinned && item.category.equals(category) && item.sub.equals(sub)) {
                subset.add(item);
            }
        }
        return subset;
    }

    /**
     * 存代碼清單（不含釘選，那是 savePins 的事）。成功回 null，失敗回可讀原因。
     * 先備份 → 寫暫存 → rename，任何一步失敗都不會動到原檔。
     */
    public static String save(List<CodeItem> items) {
        if (items == null) {
            return "沒有可儲存的內容";
        }
        for (int i = 0; i < items.size(); i++) {
            String problem = items.get(i).validate();
            if (problem != null) {
                return "第 " + (i + 1) + " 筆：" + problem;
            }
        }

        File target = Home.file(PATH);
        File temp = Home.file(TEMP_PATH);
        File backup = Home.file(BACKUP_PATH);
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            return "建立不了設定目錄";
        }

        if (!writeTo(temp, items)) {
            temp.delete();
            return "寫入暫存檔失敗";
        }
        if (target.isFile() && !copy(target, backup)) {
            temp.delete();
            return "備份舊檔失敗，未變更任何內容";
        }
        // rename 是原子的：要嘛整份換過去，要嘛完全沒動
        if (!rename(temp, target)) {
            temp.delete();
            return "更新檔案失敗，原本的設定沒有被動到";
        }
        PosLog.info("代碼清單已儲存：" + items.size() + " 筆");
        return null;
    }

    /**
     * 只寫釘選清單，不動 codes.txt。成功回 null，失敗回可讀原因。
     * 面板上直接切換釘選走這裡 —— 沒必要為了一個置頂就整份代碼重寫一次。
     */
    public static String savePins(List<CodeItem> items) {
        if (items == null) {
            return "沒有可儲存的內容";
        }
        File target = Home.file(PINS_PATH);
        File temp = Home.file(PINS_TEMP_PATH);
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            return "建立不了設定目錄";
        }

        List<CodeItem> marked = pinned(items);
        if (marked.isEmpty() && !target.isFile()) {
            return null;   // 本來就沒釘過，不必生一個空檔
        }
        if (!writePins(temp, marked)) {
            temp.delete();
            return "寫入釘選暫存檔失敗";
        }
        if (!rename(temp, target)) {
            temp.delete();
            return "更新釘選清單失敗";
        }
        PosLog.info("釘選清單已儲存：" + marked.size() + " 筆");
        return null;
    }

    private static boolean writePins(File file, List<CodeItem> marked) {
        PrintWriter writer = null;
        try {
            writer = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(file), CHARSET));
            writer.print(PINS_HEADER);
            Set<String> written = new LinkedHashSet<String>();
            for (int i = 0; i < marked.size(); i++) {
                // 同一個代碼在兩個分類各放一筆時，釘選檔只需要一行
                if (written.add(marked.get(i).code)) {
                    writer.println(marked.get(i).code);
                }
            }
            writer.flush();
            return !writer.checkError();
        } catch (Throwable t) {
            return false;
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    private static boolean writeTo(File file, List<CodeItem> items) {
        PrintWriter writer = null;
        try {
            writer = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(file), CHARSET));
            writer.print(HEADER);
            for (int i = 0; i < items.size(); i++) {
                writer.println(items.get(i).toLine());
            }
            writer.flush();
            return !writer.checkError();
        } catch (Throwable t) {
            return false;
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    /** 逐位元組複製，備份的是原檔本身（含使用者自己加的註解）。 */
    private static boolean copy(File from, File to) {
        java.io.InputStream in = null;
        java.io.OutputStream out = null;
        try {
            in = new FileInputStream(from);
            out = new FileOutputStream(to);
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                out.write(chunk, 0, read);
            }
            out.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
                // 關不掉就算了
            }
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Throwable ignored) {
                // 關不掉就算了
            }
        }
    }

    private static boolean rename(File from, File to) {
        try {
            if (to.isFile() && !to.delete()) {
                return false;
            }
            return from.renameTo(to);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void close(BufferedReader reader) {
        if (reader != null) {
            try {
                reader.close();
            } catch (Throwable ignored) {
                // 關不掉就算了
            }
        }
    }
}
