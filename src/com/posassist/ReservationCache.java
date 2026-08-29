package com.posassist;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 預約索引：背景每小時抓一次近 N 日的預約，在記憶體裡建 vipId → 預約清單。
 *
 * 為什麼要預抓：上游 API 沒有依會員過濾的參數，只能抓日期區間再自己篩。
 * 若每次查會員都現場抓，結帳會卡好幾秒。預抓之後，結帳路徑只做一次 map 查表。
 *
 * 沒有設定檔就整個不啟用，面板行為與未加這個功能時完全相同。
 * 索引只存在記憶體，EPB 關掉就沒了 —— 不在門市電腦留下客戶預約資料。
 */
public final class ReservationCache {

    private static final String CONFIG_NAME = "config/reservation.properties";
    private static final int DEFAULT_WINDOW_DAYS = 30;
    private static final int DEFAULT_REFRESH_MINUTES = 60;
    private static final int DEFAULT_MAX_ROWS = 3;
    // 梯次期間的查詢區間，跟預約站前端一樣用 ±180 天涵蓋所有現行梯次
    private static final int DEFAULT_API_WINDOW_DAYS = 180;

    /**
     * 面板排序用的狀態優先序：越前面越上面。用「包含」比對，因為實際狀態會有
     * 「已取貨(已遞補)」這種後綴。同一組之內再依登記日期新到舊。
     *
     * 這個順序是照店員的使用情境排的 —— 需要當場提醒客人的排最前面，
     * 已經結案的往後放。
     */
    private static final String[][] STATUS_PRIORITY = {
        { "已到貨", "保留" },   // 東西在店裡等客人拿，最該提醒
        { "已預約" },           // 還沒到貨
        { "已取貨" },           // 已完成
    };
    private static final int MIN_REFRESH_MINUTES = 5;

    private static final ReservationCache INSTANCE = new ReservationCache();

    private volatile Map<String, List<ReservationClient.Reservation>> index =
        Collections.emptyMap();
    private volatile Date updatedAt;
    private volatile boolean enabled;
    private volatile int maxRows = DEFAULT_MAX_ROWS;
    private boolean started;

    private ReservationCache() {
    }

    public static ReservationCache getInstance() {
        return INSTANCE;
    }

    // -- 對外 --------------------------------------------------------------

    /** 有沒有在跑。面板據此決定要不要顯示預約區塊。 */
    public boolean isEnabled() {
        return enabled;
    }

    public int maxRows() {
        return maxRows;
    }

    /** 資料時間；還沒抓到過回 null。 */
    public Date updatedAt() {
        return updatedAt;
    }

    /** 純記憶體查表，新→舊。查無回空清單。 */
    public List<ReservationClient.Reservation> lookup(String vipId) {
        if (vipId == null) {
            return Collections.emptyList();
        }
        List<ReservationClient.Reservation> found = index.get(vipId.trim().toUpperCase());
        return found == null ? Collections.<ReservationClient.Reservation>emptyList() : found;
    }

    // -- 啟動 --------------------------------------------------------------

    /** 由 Launcher 在登入完成後呼叫一次。沒設定檔就直接返回。 */
    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;

        File configFile = new File(home(), CONFIG_NAME);
        if (!configFile.isFile()) {
            PosLog.info("沒有 " + CONFIG_NAME + "，預約功能不啟用");
            return;
        }
        if (!Json.available()) {
            return;   // Json 自己已經記過 log
        }

        Properties config = read(configFile);
        if (config == null) {
            return;
        }

        String baseUrl = config.getProperty("baseUrl", "").trim();
        String userName = config.getProperty("userName", "").trim();
        String password = config.getProperty("password", "");
        if (baseUrl.length() == 0 || userName.length() == 0 || password.length() == 0) {
            PosLog.warn("預約設定檔缺 baseUrl / userName / password，功能不啟用");
            return;
        }
        if (!baseUrl.toLowerCase().startsWith("https://")) {
            PosLog.warn("預約 baseUrl 不是 https，功能不啟用");
            return;
        }

        final int windowDays = number(config, "windowDays", DEFAULT_WINDOW_DAYS, 1, 365);
        final int apiWindowDays =
            number(config, "apiWindowDays", DEFAULT_API_WINDOW_DAYS, 30, 730);
        final int refreshMinutes =
            number(config, "refreshMinutes", DEFAULT_REFRESH_MINUTES, MIN_REFRESH_MINUTES, 1440);
        maxRows = number(config, "maxRows", DEFAULT_MAX_ROWS, 1, 10);

        final ReservationClient client = new ReservationClient(baseUrl, userName, password);
        enabled = true;
        PosLog.info("預約功能啟用：近 " + windowDays + " 日，每 " + refreshMinutes + " 分鐘更新");

        Thread worker = new Thread(new Runnable() {
            public void run() {
                while (true) {
                    Safe.guard("更新預約索引", new Runnable() {
                        public void run() {
                            refresh(client, windowDays, apiWindowDays);
                        }
                    });
                    try {
                        Thread.sleep((long) refreshMinutes * 60 * 1000L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, "PosAssist-Reservations");
        worker.setDaemon(true);
        worker.start();
    }

    // -- 抓取與建索引 ------------------------------------------------------

    private void refresh(ReservationClient client, int windowDays, int apiWindowDays) {
        List<ReservationClient.Reservation> rows;
        try {
            rows = client.fetchRecent(windowDays, apiWindowDays);
        } catch (ReservationClient.FetchException failure) {
            // 抓失敗保留上一份，不清空 —— 舊資料比沒資料有用
            PosLog.warn("預約更新失敗，沿用上次資料：" + failure.getMessage());
            return;
        }

        Map<String, List<ReservationClient.Reservation>> built =
            new HashMap<String, List<ReservationClient.Reservation>>();
        for (ReservationClient.Reservation row : rows) {
            String key = row.vipId.trim().toUpperCase();
            List<ReservationClient.Reservation> bucket = built.get(key);
            if (bucket == null) {
                bucket = new ArrayList<ReservationClient.Reservation>();
                built.put(key, bucket);
            }
            bucket.add(row);
        }

        Comparator<ReservationClient.Reservation> panelOrder =
            new Comparator<ReservationClient.Reservation>() {
                public int compare(ReservationClient.Reservation a,
                                   ReservationClient.Reservation b) {
                    int rankA = statusRank(a.status);
                    int rankB = statusRank(b.status);
                    if (rankA != rankB) {
                        return rankA < rankB ? -1 : 1;
                    }
                    if (a.registeredMillis != b.registeredMillis) {
                        return a.registeredMillis > b.registeredMillis ? -1 : 1;
                    }
                    return b.orderNo.compareTo(a.orderNo);
                }
            };
        for (List<ReservationClient.Reservation> bucket : built.values()) {
            Collections.sort(bucket, panelOrder);
        }

        index = built;                       // 原子替換
        updatedAt = new Date();
        PosLog.info("預約索引已更新：抓回 " + client.lastFetchedCount() + " 筆，"
            + "近 " + windowDays + " 日 " + rows.size() + " 筆，"
            + "涵蓋 " + built.size() + " 位會員"
            + (client.lastUndatedCount() == 0
                ? ""
                : "（另有 " + client.lastUndatedCount() + " 筆登記日期無法解析，已略過）"));
    }

    /** 狀態的排序名次；沒對到的一律排最後。 */
    static int statusRank(String status) {
        String text = status == null ? "" : status;
        for (int rank = 0; rank < STATUS_PRIORITY.length; rank++) {
            String[] group = STATUS_PRIORITY[rank];
            for (int i = 0; i < group.length; i++) {
                if (text.indexOf(group[i]) >= 0) {
                    return rank;
                }
            }
        }
        return STATUS_PRIORITY.length;
    }

    // -- 設定 --------------------------------------------------------------

    private static Properties read(File file) {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            Properties config = new Properties();
            config.load(in);
            return config;
        } catch (Throwable t) {
            PosLog.warn("讀不到預約設定檔，功能不啟用");
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

    private static int number(Properties config, String key, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(config.getProperty(key, "").trim());
            return value < min ? min : (value > max ? max : value);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * 給自我檢查用的設定狀態描述。只回結論，不回任何設定值。
     */
    static String configStatus() {
        File configFile = new File(home(), CONFIG_NAME);
        if (!configFile.isFile()) {
            return "未設定（預約功能關閉，面板其餘功能不受影響）";
        }
        Properties config = read(configFile);
        if (config == null) {
            return "設定檔讀不到";
        }
        String baseUrl = config.getProperty("baseUrl", "").trim();
        if (baseUrl.length() == 0) {
            return "缺 baseUrl";
        }
        if (!baseUrl.toLowerCase().startsWith("https://")) {
            return "baseUrl 不是 https";
        }
        if (config.getProperty("userName", "").trim().length() == 0) {
            return "缺 userName";
        }
        if (config.getProperty("password", "").length() == 0) {
            return "缺 password";
        }
        return "已設定（https、帳密齊全）";
    }

    /**
     * 對設定檔裡的主機做一次 TLS 交握檢查（不登入）。
     * 沒設定回 null；成功回空字串；失敗回原因。
     */
    static String probeConfiguredHost() {
        File configFile = new File(home(), CONFIG_NAME);
        if (!configFile.isFile()) {
            return null;
        }
        Properties config = read(configFile);
        if (config == null) {
            return "設定檔讀不到";
        }
        String baseUrl = config.getProperty("baseUrl", "").trim();
        if (baseUrl.length() == 0) {
            return "缺 baseUrl";
        }
        String failure = ReservationClient.probeTls(baseUrl);
        return failure == null ? "" : failure;
    }

    /** 設定檔權限是否夠嚴（只有擁有者可讀寫）。 */
    static boolean configPermissionsOk() {
        File configFile = new File(home(), CONFIG_NAME);
        if (!configFile.isFile()) {
            return true;   // 沒有檔案就沒有風險
        }
        return !isGroupOrOtherReadable(configFile);
    }

    private static boolean isGroupOrOtherReadable(File file) {
        try {
            java.util.Set<java.nio.file.attribute.PosixFilePermission> perms =
                java.nio.file.Files.getPosixFilePermissions(file.toPath());
            return perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ)
                || perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ);
        } catch (Throwable t) {
            return false;   // 判斷不了就不報錯
        }
    }

    /** PosAssist 安裝目錄。跟 PosLog 用同一套推算方式。 */
    private static File home() {
        String logDir = System.getProperty("posassist.logDir");
        if (logDir != null && logDir.trim().length() != 0) {
            return new File(logDir.trim()).getParentFile();
        }
        return new File(System.getProperty("user.dir"), "../PosAssist");
    }
}
