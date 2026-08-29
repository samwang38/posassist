package com.posassist;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 極簡檔案 log。刻意不用 EPB 的 log4j，避免跟原廠設定綁在一起。
 * 任何寫檔失敗都靜默忽略 —— log 壞掉不可以影響結帳。
 */
public final class PosLog {

    private static final String DIR_PROPERTY = "posassist.logDir";
    private static final long MAX_BYTES = 2L * 1024 * 1024;

    private static final SimpleDateFormat STAMP =
        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    private static File logFile;
    private static boolean resolved;

    private PosLog() {
    }

    public static void info(String message) {
        write("INFO ", message, null);
    }

    public static void warn(String message) {
        write("WARN ", message, null);
    }

    public static void warn(String message, Throwable throwable) {
        write("WARN ", message, throwable);
    }

    private static synchronized void write(String level, String message, Throwable throwable) {
        try {
            File file = resolveFile();
            if (file == null) {
                return;
            }
            if (file.length() > MAX_BYTES) {
                File rolled = new File(file.getParentFile(), "posassist.log.1");
                if (rolled.exists() && !rolled.delete()) {
                    return;
                }
                if (!file.renameTo(rolled)) {
                    return;
                }
            }
            PrintWriter writer = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(file, true), "UTF-8"));
            try {
                writer.println(STAMP.format(new Date()) + " " + level + " " + message);
                if (throwable != null) {
                    throwable.printStackTrace(writer);
                }
            } finally {
                writer.close();
            }
        } catch (Throwable ignored) {
            // log 失敗就算了，不能往上冒
        }
    }

    private static File resolveFile() {
        if (resolved) {
            return logFile;
        }
        resolved = true;
        try {
            String configured = System.getProperty(DIR_PROPERTY);
            File dir = configured != null && configured.trim().length() != 0
                ? new File(configured.trim())
                // 啟動器把工作目錄設在 EPB/Shell，所以 ../PosAssist/logs
                : new File(System.getProperty("user.dir"), "../PosAssist/logs");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return null;
            }
            logFile = new File(dir, "posassist.log");
        } catch (Throwable ignored) {
            logFile = null;
        }
        return logFile;
    }
}
