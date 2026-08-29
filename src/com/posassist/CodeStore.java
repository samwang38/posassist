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
 * 讀寫 config/codes.txt。檔案順序就是顯示順序，分類依首次出現排序。
 *
 * 寫檔採「先備份、寫暫存、再 rename」：
 * rename 在同一個檔案系統上是原子操作，所以不會出現寫到一半的半截檔案。
 * 上一版永遠留在 codes.txt.bak，改壞了可以直接拿回來。
 */
public final class CodeStore {

    static final String PATH = "config/codes.txt";
    private static final String BACKUP_PATH = "config/codes.txt.bak";
    private static final String TEMP_PATH = "config/codes.txt.tmp";
    private static final String CHARSET = "UTF-8";

    private static final String HEADER =
        "# PosAssist 自訂結帳代碼\n"
        + "# 一行一筆：分類|名稱|代碼\n"
        + "# 檔案順序就是面板上的顯示順序；分類依首次出現的先後排列。\n"
        + "# 三個欄位都不能含「|」或換行，名稱與代碼不能空白。\n"
        + "# 面板上的「編輯」會覆寫這個檔，並把前一版留成 codes.txt.bak。\n";

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
        return items;
    }

    /** 依首次出現順序取分類。 */
    public static List<String> categories(List<CodeItem> items) {
        Set<String> seen = new LinkedHashSet<String>();
        for (int i = 0; i < items.size(); i++) {
            seen.add(items.get(i).category);
        }
        return new ArrayList<String>(seen);
    }

    public static List<CodeItem> inCategory(List<CodeItem> items, String category) {
        List<CodeItem> subset = new ArrayList<CodeItem>();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).category.equals(category)) {
                subset.add(items.get(i));
            }
        }
        return subset;
    }

    /**
     * 存檔。成功回 null，失敗回可讀原因。
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
