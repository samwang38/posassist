package com.posassist;

/**
 * PosAssist 啟動器。
 *
 * 刻意「原封不動」呼叫 com.epb.shell.Main.main()，讓 EPB 的啟動流程
 * （splash、setupSystem、hotpatch 檢查、loginSystem、setupFrame、showFrame）
 * 跟平常用原本捷徑開完全一致。外掛只是另外開一條執行緒等登入完成再掛上去。
 *
 * 這代表：外掛出任何問題，店員改用原本的 EPB 捷徑就一切正常。
 */
public final class Launcher {

    private static final String SHELL_MAIN = "com.epb.shell.Main";
    private static final String SHARED = "com.ipt.epbfrw.EpbSharedObjects";

    private static final long POLL_INTERVAL_MS = 250;
    private static final long LOGIN_TIMEOUT_MS = 10 * 60 * 1000;

    private Launcher() {
    }

    public static void main(String[] args) throws Exception {
        PosLog.info("=== PosAssist 啟動 ===");
        startAttachThread();
        invokeShellMain(args);
    }

    private static void startAttachThread() {
        Thread thread = new Thread(new Runnable() {
            public void run() {
                Safe.guard("掛載外掛", new Runnable() {
                    public void run() {
                        awaitLoginThenInstall();
                    }
                });
            }
        }, "PosAssist-Attach");
        thread.setDaemon(true);
        thread.start();
    }

    private static void awaitLoginThenInstall() {
        long deadline = System.currentTimeMillis() + LOGIN_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (loggedIn()) {
                PosLog.info("偵測到登入完成");
                PosnHook hook = new PosnHook();
                if (!hook.install()) {
                    PosLog.warn("外掛未能掛上，EPB 照常運作");
                }
                // 預約索引：沒有設定檔就自己不啟用
                Safe.guard("啟動預約索引", new Runnable() {
                    public void run() {
                        ReservationCache.getInstance().start();
                    }
                });
                return;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        PosLog.warn("等待登入逾時，外掛不啟用");
    }

    private static boolean loggedIn() {
        Object userId = Safe.staticCall(SHARED, "getUserId", new Class<?>[0], new Object[0]);
        return userId != null && String.valueOf(userId).trim().length() != 0;
    }

    /** 直接跑 EPB 原本的 main，啟動路徑與正式捷徑完全相同。 */
    private static void invokeShellMain(String[] args) throws Exception {
        Class<?> main = Class.forName(SHELL_MAIN);
        main.getMethod("main", String[].class).invoke(null, (Object) args);
    }
}
