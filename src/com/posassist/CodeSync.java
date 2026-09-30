package com.posassist;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 結帳代碼的多機同步：同一間門市的幾台 POS 共用一個資料夾（NAS／iCloud）。
 *
 * 規則：
 * - 一個同步資料夾＝一間門市。裡面放跟本機同名同格式的 codes.txt／codes.pins.txt。
 * - 畫面永遠讀本機 config/，它是同步下來的快取。資料夾連不上時面板照常用最後一份，結帳不受影響。
 * - 拉取：同步資料夾的內容跟上次同步的不同，就整份搬進本機。檔案裡有一行不合格就整份不收 ——
 *   寧可暫時舊一點，也不要讓某台手改壞的檔案把別台的代碼吃掉。同步資料夾裡「沒有 codes.txt」
 *   永遠不當成「清空」。
 * - 推送：以編輯器打開時的版本為基準；存檔時同步資料夾已經被別台改過，就回報衝突讓人決定，不默默蓋掉。
 * - 連不上時存本機並記成「待上傳」；之後連上時，資料夾還是當時的版本就自動上傳，
 *   已經被別台改過就以資料夾為準，本機那份另存 conflict 檔，不默默丟資料。
 *
 * 所有碰同步資料夾的動作都會先用有逾時的探測確認資料夾活著 —— SMB 斷線時 isDirectory()
 * 可以卡好幾十秒，那種卡法不是例外，try/catch 接不到。呼叫端也一律從背景執行緒叫
 * （見 submit()），不可以在 EDT 上呼叫這裡的實例方法。
 */
final class CodeSync {

    static final String DIR_KEY = "codesSyncDir";
    static final String STATE_FILE = "codes.sync.state";
    /** 同步資料夾裡沒有 codes.txt 時的指紋。狀態檔的預設值也是它 —— 從沒同步過。 */
    static final String NONE = "none";

    private static final long PROBE_TIMEOUT_MS = 3000;
    private static final long POLL_SECONDS = 30;

    enum Outcome {
        /** 沒有變化。 */
        UNCHANGED,
        /** 從同步資料夾拿到新版本，本機已更新。 */
        UPDATED,
        /** 已寫進同步資料夾（也寫了本機）。 */
        SAVED,
        /** 別台已經改過，這次沒寫（推送），或離線修改與別台衝突、已改用資料夾版本（拉取）。 */
        CONFLICT,
        /** 同步資料夾連不上。推送時代表已存本機、等連上再傳。 */
        OFFLINE,
        FAILED
    }

    static final class Result {
        final Outcome outcome;
        /** 給人看的一句話；沒什麼好說的時候是 null。 */
        final String message;

        Result(Outcome outcome, String message) {
            this.outcome = outcome;
            this.message = message;
        }

        /** 本機的代碼有沒有變，面板要不要重新載入。 */
        boolean localChanged() {
            return outcome == Outcome.UPDATED || outcome == Outcome.SAVED
                || outcome == Outcome.CONFLICT;
        }
    }

    /** 同步資料夾連不上。跟「資料夾裡沒有檔案」是兩回事，要分開處理。 */
    static final class Offline extends IOException {
        Offline(String message) {
            super(message);
        }
    }

    /** 同步資料夾當下的內容。codes 為 null 代表資料夾裡沒有 codes.txt。 */
    private static final class Snapshot {
        final byte[] codes;
        final byte[] pins;
        final String fingerprint;

        Snapshot(byte[] codes, byte[] pins) {
            this.codes = codes;
            this.pins = pins;
            this.fingerprint = codes == null ? NONE : fingerprint(codes, pins);
        }
    }

    private final File remoteDir;
    private final File localDir;

    CodeSync(File remoteDir, File localDir) {
        this.remoteDir = remoteDir;
        this.localDir = localDir;
    }

    /** 依設定檔建立；沒設同步資料夾就回 null，呼叫端照舊只用本機。 */
    static CodeSync configured() {
        String dir = Home.value("config/posassist.properties", DIR_KEY, "").trim();
        if (dir.length() == 0) {
            return null;
        }
        return new CodeSync(new File(dir), CodeStore.localDir());
    }

    File remoteDir() {
        return remoteDir;
    }

    // -- 狀態檔 ------------------------------------------------------------

    private final class State {
        String synced = NONE;
        boolean pending;
        String pendingBase = NONE;
    }

    private File stateFile() {
        return new File(localDir, STATE_FILE);
    }

    /** 狀態是跟著某一個同步資料夾的；換了資料夾就當成從沒同步過。 */
    private State loadState() {
        State state = new State();
        Properties props = new Properties();
        InputStream in = null;
        try {
            in = new FileInputStream(stateFile());
            props.load(in);
        } catch (Throwable ignored) {
            return state;
        } finally {
            close(in);
        }
        if (!remoteDir.getAbsolutePath().equals(props.getProperty("dir", ""))) {
            return state;
        }
        state.synced = props.getProperty("synced", NONE);
        state.pending = "true".equals(props.getProperty("pending", "false"));
        state.pendingBase = props.getProperty("pendingBase", NONE);
        return state;
    }

    private void saveState(State state) {
        Properties props = new Properties();
        props.setProperty("dir", remoteDir.getAbsolutePath());
        props.setProperty("synced", state.synced);
        props.setProperty("pending", String.valueOf(state.pending));
        props.setProperty("pendingBase", state.pendingBase);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            props.store(out, "PosAssist codes sync state");
        } catch (IOException impossible) {
            return;
        }
        String problem = CodeStore.replaceFile(stateFile(), out.toByteArray(), null);
        if (problem != null) {
            PosLog.warn("寫不進代碼同步狀態：" + problem);
        }
    }

    /** 本機目前對應的同步版本。編輯器打開時拿它當基準。 */
    String syncedFingerprint() {
        return loadState().synced;
    }

    boolean hasPending() {
        return loadState().pending;
    }

    // -- 拉取 --------------------------------------------------------------

    /** 背景輪詢每 30 秒呼叫一次，面板開啟時也先叫一次。 */
    Result pull() {
        State state = loadState();
        Snapshot remote;
        try {
            remote = readRemote();
        } catch (IOException offline) {
            return new Result(Outcome.OFFLINE, state.pending
                ? "同步資料夾連不上，有修改還沒上傳"
                : "同步資料夾連不上，使用本機的代碼");
        }
        if (state.pending) {
            return resolvePending(state, remote);
        }
        if (remote.codes == null) {
            return new Result(Outcome.UNCHANGED, "同步資料夾裡還沒有代碼，在編輯器存一次就會放上去");
        }
        if (remote.fingerprint.equals(state.synced)) {
            return new Result(Outcome.UNCHANGED, null);
        }
        String problem = invalid(remote.codes);
        if (problem != null) {
            PosLog.warn("同步資料夾的代碼檔不合格，暫不套用：" + problem);
            return new Result(Outcome.FAILED, "同步資料夾的代碼檔有錯，暫不套用：" + problem);
        }
        problem = adopt(remote, state);
        if (problem != null) {
            return new Result(Outcome.FAILED, "更新本機代碼失敗：" + problem);
        }
        PosLog.info("已從同步資料夾更新結帳代碼");
        return new Result(Outcome.UPDATED, "已從同步資料夾更新代碼");
    }

    /** 離線時存過的修改：資料夾還是當時的版本就上傳，否則以資料夾為準、本機另存。 */
    private Result resolvePending(State state, Snapshot remote) {
        if (remote.codes == null || remote.fingerprint.equals(state.pendingBase)) {
            String problem = uploadLocal();
            if (problem != null) {
                return new Result(Outcome.OFFLINE, "離線時的修改還沒上傳：" + problem);
            }
            Snapshot now;
            try {
                now = readRemote();
            } catch (IOException offline) {
                return new Result(Outcome.OFFLINE, "同步資料夾連不上，有修改還沒上傳");
            }
            state.synced = now.fingerprint;
            state.pending = false;
            state.pendingBase = NONE;
            saveState(state);
            PosLog.info("離線時的代碼修改已上傳到同步資料夾");
            return new Result(Outcome.SAVED, "離線時的修改已上傳");
        }

        String problem = invalid(remote.codes);
        if (problem != null) {
            // 資料夾那份是壞的，更不能拿它蓋掉本機的修改；等別人修好再說
            PosLog.warn("有待上傳的修改，但同步資料夾的代碼檔不合格：" + problem);
            return new Result(Outcome.FAILED, "同步資料夾的代碼檔有錯，本機修改暫不上傳");
        }
        String kept = keepLocalCopy();
        problem = adopt(remote, state);
        if (problem != null) {
            return new Result(Outcome.FAILED, "更新本機代碼失敗：" + problem);
        }
        state.pending = false;
        state.pendingBase = NONE;
        saveState(state);
        PosLog.warn("離線時的代碼修改與別台衝突，改用同步資料夾版本；本機版本另存 " + kept);
        return new Result(Outcome.CONFLICT, "離線時的修改與別台衝突，已改用同步資料夾的版本"
            + (kept == null ? "" : "；你的版本另存在 config/" + kept));
    }

    // -- 推送 --------------------------------------------------------------

    /**
     * 編輯器存檔。base 是編輯器打開時的 syncedFingerprint()；force 為 true 時不管別台改過沒有都覆蓋。
     */
    Result push(List<CodeItem> items, String base, boolean force) {
        State state = loadState();
        Snapshot remote;
        try {
            remote = readRemote();
        } catch (IOException offline) {
            return saveOffline(items, state, base, "同步資料夾連不上");
        }
        if (!force && remote.codes != null && !remote.fingerprint.equals(base)) {
            return new Result(Outcome.CONFLICT, "別台已經改過代碼");
        }
        String problem = CodeStore.saveTo(remoteDir, items);
        if (problem == null) {
            problem = CodeStore.savePinsTo(remoteDir, items);
        }
        if (problem != null) {
            return saveOffline(items, state, base, "寫不進同步資料夾（" + problem + "）");
        }
        problem = saveLocal(items);
        if (problem != null) {
            // 資料夾已經是新的，本機下一輪拉取就會補上
            return new Result(Outcome.FAILED, "已存到同步資料夾，但本機沒存起來：" + problem);
        }
        try {
            state.synced = readRemote().fingerprint;
        } catch (IOException offline) {
            state.synced = NONE;   // 下一輪會重新比對
        }
        state.pending = false;
        state.pendingBase = NONE;
        saveState(state);
        PosLog.info("結帳代碼已存到同步資料夾：" + items.size() + " 筆");
        return new Result(Outcome.SAVED, null);
    }

    private Result saveOffline(List<CodeItem> items, State state, String base, String why) {
        String problem = saveLocal(items);
        if (problem != null) {
            return new Result(Outcome.FAILED, problem);
        }
        if (!state.pending) {
            state.pending = true;
            state.pendingBase = base == null ? state.synced : base;
        }
        saveState(state);
        PosLog.warn(why + "，代碼先存在本機，等連上再上傳");
        return new Result(Outcome.OFFLINE, why + "，這次先存在本機，連上後會自動上傳");
    }

    /**
     * 面板上切換一個釘選。只改這一個代碼，別台同時釘的其他代碼不會被蓋掉。
     * 回傳後本機已是最新（同步資料夾的代碼＋合併後的釘選）。
     */
    Result setPinned(String code, boolean pinned) {
        State state = loadState();
        Snapshot remote;
        try {
            remote = readRemote();
        } catch (IOException offline) {
            return pinLocally(code, pinned, state, "同步資料夾連不上");
        }
        if (remote.codes == null || state.pending) {
            // 資料夾還沒有代碼，或本機有待上傳的修改：釘選算在本機這份裡，之後一起上傳
            return pinLocally(code, pinned, state, null);
        }
        Set<String> codes = parsePins(remote.pins);
        if (pinned) {
            codes.add(code);
        } else {
            codes.remove(code);
        }
        String problem = CodeStore.savePinCodesTo(remoteDir, codes);
        if (problem != null) {
            return pinLocally(code, pinned, state, "寫不進同步資料夾（" + problem + "）");
        }
        try {
            remote = readRemote();
        } catch (IOException offline) {
            return pinLocally(code, pinned, state, "同步資料夾連不上");
        }
        problem = invalid(remote.codes);
        if (problem != null) {
            return pinLocally(code, pinned, state, null);
        }
        problem = adopt(remote, state);
        if (problem != null) {
            return new Result(Outcome.FAILED, "更新本機代碼失敗：" + problem);
        }
        return new Result(Outcome.SAVED, null);
    }

    private Result pinLocally(String code, boolean pinned, State state, String why) {
        Set<String> codes = CodeStore.loadPinsFrom(localDir);
        if (pinned) {
            codes.add(code);
        } else {
            codes.remove(code);
        }
        String problem = CodeStore.savePinCodesTo(localDir, codes);
        if (problem != null) {
            return new Result(Outcome.FAILED, problem);
        }
        if (why == null) {
            return new Result(Outcome.SAVED, null);
        }
        if (!state.pending) {
            state.pending = true;
            state.pendingBase = state.synced;
            saveState(state);
        }
        return new Result(Outcome.OFFLINE, why + "，釘選先存在本機");
    }

    // -- 檔案搬移 ----------------------------------------------------------

    /** 把資料夾的版本整份搬進本機（舊的留 codes.txt.bak），並記下已同步到這一版。 */
    private String adopt(Snapshot remote, State state) {
        String problem = CodeStore.replaceFile(new File(localDir, CodeStore.CODES_FILE),
            remote.codes, new File(localDir, CodeStore.BACKUP_FILE));
        if (problem != null) {
            return problem;
        }
        File localPins = new File(localDir, CodeStore.PINS_FILE);
        if (remote.pins != null) {
            problem = CodeStore.replaceFile(localPins, remote.pins, null);
        } else if (localPins.isFile() && !localPins.delete()) {
            problem = "清不掉本機的釘選檔";
        }
        if (problem != null) {
            return problem;
        }
        state.synced = remote.fingerprint;
        saveState(state);
        return null;
    }

    /** 把本機兩個檔原樣放上同步資料夾（資料夾的舊檔留 .bak）。 */
    private String uploadLocal() {
        File codes = new File(localDir, CodeStore.CODES_FILE);
        if (!codes.isFile()) {
            return "本機沒有代碼檔";
        }
        try {
            String problem = CodeStore.replaceFile(new File(remoteDir, CodeStore.CODES_FILE),
                readAll(codes), new File(remoteDir, CodeStore.BACKUP_FILE));
            if (problem != null) {
                return problem;
            }
            File pins = new File(localDir, CodeStore.PINS_FILE);
            File remotePins = new File(remoteDir, CodeStore.PINS_FILE);
            if (pins.isFile()) {
                return CodeStore.replaceFile(remotePins, readAll(pins), null);
            }
            if (remotePins.isFile() && !remotePins.delete()) {
                return "清不掉同步資料夾的釘選檔";
            }
            return null;
        } catch (IOException error) {
            return "讀不到本機代碼檔";
        }
    }

    /** 衝突時本機那份另存一個帶時間的檔，回傳檔名；存不了回 null（仍照樣改用資料夾版本）。 */
    private String keepLocalCopy() {
        File codes = new File(localDir, CodeStore.CODES_FILE);
        if (!codes.isFile()) {
            return null;
        }
        String name = "codes.txt.conflict-"
            + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        try {
            String problem = CodeStore.replaceFile(new File(localDir, name), readAll(codes), null);
            return problem == null ? name : null;
        } catch (IOException error) {
            return null;
        }
    }

    private String saveLocal(List<CodeItem> items) {
        String problem = CodeStore.saveTo(localDir, items);
        if (problem != null) {
            return problem;
        }
        return CodeStore.savePinsTo(localDir, items);
    }

    // -- 讀同步資料夾 ------------------------------------------------------

    private Snapshot readRemote() throws IOException {
        if (!reachable(remoteDir)) {
            throw new Offline("同步資料夾連不上");
        }
        File codes = new File(remoteDir, CodeStore.CODES_FILE);
        if (!codes.isFile()) {
            return new Snapshot(null, null);
        }
        File pins = new File(remoteDir, CodeStore.PINS_FILE);
        return new Snapshot(readAll(codes), pins.isFile() ? readAll(pins) : null);
    }

    /** 檔案裡有任何一行不合格就回原因；全部合格回 null。 */
    static String invalid(byte[] codes) {
        String[] lines = decode(codes).split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String text = lines[i].trim();
            if (text.length() == 0 || text.charAt(0) == '#') {
                continue;
            }
            if (CodeItem.parse(lines[i]) == null) {
                return "第 " + (i + 1) + " 行格式不符";
            }
        }
        return null;
    }

    private static Set<String> parsePins(byte[] pins) {
        Set<String> codes = new LinkedHashSet<String>();
        if (pins == null) {
            return codes;
        }
        String[] lines = decode(pins).split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String text = lines[i].trim();
            if (text.length() != 0 && text.charAt(0) != '#') {
                codes.add(text);
            }
        }
        return codes;
    }

    private static String decode(byte[] bytes) {
        try {
            String text = new String(bytes, "UTF-8");
            return text.length() > 0 && text.charAt(0) == '﻿' ? text.substring(1) : text;
        } catch (IOException impossible) {
            return "";
        }
    }

    static String fingerprint(byte[] codes, byte[] pins) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(codes);
            digest.update((byte) 0);
            if (pins != null) {
                digest.update(pins);
            }
            StringBuilder hex = new StringBuilder();
            byte[] hash = digest.digest();
            for (int i = 0; i < hash.length; i++) {
                hex.append(String.format("%02x", hash[i] & 0xff));
            }
            return hex.toString();
        } catch (Throwable t) {
            return NONE;
        }
    }

    private static byte[] readAll(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                out.write(chunk, 0, read);
            }
            return out.toByteArray();
        } finally {
            close(in);
        }
    }

    private static void close(java.io.Closeable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (Throwable ignored) {
            // 關不掉就算了
        }
    }

    // -- 探測與背景執行 ----------------------------------------------------

    /** 探測用的執行緒：卡住的那條就讓它卡著（daemon），不影響下一次探測。 */
    private static final ExecutorService PROBES = Executors.newCachedThreadPool(
        daemon("PosAssist-CodeSyncProbe"));

    /** 資料夾在不在、3 秒內問不到答案就當作連不上。 */
    static boolean reachable(final File dir) {
        try {
            Future<Boolean> answer = PROBES.submit(() -> Boolean.valueOf(dir.isDirectory()));
            return Boolean.TRUE.equals(answer.get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 實際寫一個檔再刪掉，給設定視窗的「測試」用。成功回 null。 */
    static String checkWritable(File dir) {
        if (!reachable(dir)) {
            return "資料夾不存在或連不上";
        }
        try {
            File probe = File.createTempFile(".posassist-", ".check", dir);
            OutputStream out = new FileOutputStream(probe);
            try {
                out.write('k');
            } finally {
                close(out);
            }
            if (!probe.delete()) {
                return "可以寫入，但刪不掉測試檔";
            }
            return null;
        } catch (Throwable t) {
            return "這個資料夾沒辦法寫入";
        }
    }

    /**
     * 所有同步動作排在同一條背景執行緒上：輪詢、存檔、釘選不會同時改狀態檔，
     * 而且都不在 EDT 上 —— 同步資料夾再慢也卡不到結帳畫面。
     */
    private static ScheduledExecutorService worker;
    private static ScheduledFuture<?> polling;

    private static synchronized ScheduledExecutorService worker() {
        if (worker == null) {
            worker = Executors.newSingleThreadScheduledExecutor(daemon("PosAssist-CodeSync"));
        }
        return worker;
    }

    static void submit(Runnable job) {
        worker().execute(() -> Safe.guard("代碼同步", job));
    }

    /** 收到結果的回呼，跑在背景執行緒上；要動畫面請自己轉回 EDT。 */
    interface Listener {
        void synced(Result result);
    }

    /** 有設同步資料夾才會真的去拉；設定是每一輪重讀的，改了設定不必重開。 */
    static synchronized void startPolling(final Listener listener) {
        stopPolling();
        polling = worker().scheduleWithFixedDelay(() -> Safe.guard("代碼同步輪詢", () -> {
            CodeSync sync = configured();
            if (sync != null) {
                listener.synced(sync.pull());
            }
        }), 0, POLL_SECONDS, TimeUnit.SECONDS);
    }

    static synchronized void stopPolling() {
        if (polling != null) {
            polling.cancel(false);
            polling = null;
        }
    }

    private static ThreadFactory daemon(final String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
