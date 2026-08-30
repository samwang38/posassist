package com.posassist;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.Properties;

/**
 * PosAssist 安裝目錄與設定檔的共用入口。
 *
 * 啟動器會設 -Dposassist.logDir=<安裝目錄>/logs，所以安裝目錄就是它的上一層。
 * 沒設的話（例如自我檢查直接跑）就從工作目錄 EPB/Shell 往回推。
 */
public final class Home {

    private Home() {
    }

    public static File dir() {
        String logDir = System.getProperty("posassist.logDir");
        if (logDir != null && logDir.trim().length() != 0) {
            File parent = new File(logDir.trim()).getParentFile();
            if (parent != null) {
                return parent;
            }
        }
        return new File(System.getProperty("user.dir"), "../PosAssist");
    }

    public static File file(String relativePath) {
        return new File(dir(), relativePath);
    }

    /** 讀設定檔；不存在或讀不到都回 null（呼叫端自行決定預設行為）。 */
    public static Properties props(String relativePath) {
        File file = file(relativePath);
        if (!file.isFile()) {
            return null;
        }
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            Properties config = new Properties();
            config.load(in);
            return config;
        } catch (Throwable t) {
            PosLog.warn("讀不到設定檔：" + relativePath);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // 關不掉就算了
                }
            }
        }
    }

    /** 讀單一設定值，去空白；沒有就回 fallback。 */
    public static String value(String relativePath, String key, String fallback) {
        Properties config = props(relativePath);
        if (config == null) {
            return fallback;
        }
        String value = config.getProperty(key, "").trim();
        return value.length() == 0 ? fallback : value;
    }

    /**
     * 寫設定／狀態檔：暫存檔 + rename，跟 CodeStore 同一套 ——
     * 寫到一半失敗不會留下半截設定。成功回 null，失敗回可讀原因。
     * ownerOnly 用在含密碼的檔案：先收緊權限再換上去。
     */
    public static String write(String relativePath, String body, boolean ownerOnly) {
        File target = Home.file(relativePath);
        File temp = Home.file(relativePath + ".tmp");
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            return "建立不了設定目錄";
        }
        PrintWriter writer = null;
        try {
            writer = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(temp), "UTF-8"));
            writer.print(body);
            writer.flush();
            if (writer.checkError()) {
                return "寫入失敗";
            }
        } catch (Throwable t) {
            return "寫入失敗";
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
        if (ownerOnly) {
            // 密碼檔：先收緊權限再換上去，避免有一瞬間是所有人可讀
            temp.setReadable(false, false);
            temp.setWritable(false, false);
            temp.setReadable(true, true);
            temp.setWritable(true, true);
        }
        try {
            if (target.isFile() && !target.delete()) {
                return "更新不了設定檔";
            }
            if (!temp.renameTo(target)) {
                return "更新不了設定檔";
            }
        } catch (Throwable t) {
            return "更新不了設定檔";
        }
        return null;
    }
}
