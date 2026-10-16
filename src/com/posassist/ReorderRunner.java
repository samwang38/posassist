package com.posassist;

import java.util.List;

/**
 * 把一張舊單依序帶回 POS：先會員，再一件一件帶商品代碼。
 *
 * 每一步都走店員自己操作的路徑（填 PLU 欄再按 Enter），由 POSN 自己驗證。
 * 一件帶完要看到 POS 明細真的變了才帶下一件。
 *
 * 2026-10-16 實機踩到的三件事，決定了這裡的節奏：
 * - POSN 加商品是 PLU 欄的 keyPressed（POSN$5），不是 ActionEvent；所以 Host.submitCode
 *   要送按鍵事件，條碼才會被吃進去。
 * - 會員是焦點離開會員欄時才驗證，期間會短暫跳出處理視窗。所以帶完會員要等 POS 顯示出會員姓名，
 *   而且視窗要連續開著超過 MODAL_GRACE_MS 才算「需要店員處理」，一閃而過的不算。
 * - 主機在加入前就跳「Key in unicode」要序號（PosKeyinDialog）。PosnHook 會把原單序號填好，
 *   店員看一眼按 Enter；這段時間不算逾時，也不暫停。
 *
 * 全部跑在 EDT 上，用 javax.swing.Timer 推進，不阻塞畫面。
 */
final class ReorderRunner {

    /** PosnHook 提供的 POS 操作。全部在 EDT 上呼叫。 */
    interface Host {
        /** POS 會員欄目前的值。 */
        String vipId();
        /** POS 已經驗證並載入會員了嗎（會員姓名欄有值）。 */
        boolean vipLoaded();
        String posNo();
        /** 填會員並把焦點移到 PLU 欄，觸發 POSN 的會員驗證。 */
        boolean applyVip(String vipId);
        /** 填 PLU 欄並按 Enter（按鍵事件，跟店員或掃描槍同一條路）。 */
        boolean submitCode(String code);
        /** 記住下一個「Key in unicode」視窗要填的序號；null 清掉。 */
        void armSerial(String serial);
        /** POS 明細的列數與總數量，用來判斷「有沒有加進去」。讀不到回 null。 */
        String lineSignature();
        /** POS 明細目前有幾列。讀不到回 -1。 */
        int lineCount();
        /** POS 明細最後一列的行號（ID 欄）。讀不到回空字串。 */
        String lastLineNo();
        /** 開著的 modal 視窗：null 沒有，SERIAL 是序號輸入，其他是別的視窗。 */
        String openDialog();
        /** 記住這一列原單的 REF，等店員對 POS 那一列按 F10 時填入。 */
        void expectF10(String posLineNo, Reorder.Line line);
        void returnFocusToPos();
    }

    /** 序號輸入視窗（PosKeyinDialog「Key in unicode」）。 */
    static final String SERIAL = "serial";

    /** 進度回報，跑在 EDT 上。 */
    interface Listener {
        void progress(int done, int total, Reorder.Step current);
        /** 不是錯誤、正在等店員做一個動作（例如確認序號）。 */
        void waiting(String message);
        void paused(String reason);
        void finished(int added, int skipped, int f10Pending);
    }

    private static final int TICK_MS = 150;
    /** 帶一件之後等 POS 明細變動的時間。 */
    private static final int LINE_TIMEOUT_MS = 4000;
    /** 帶會員之後最多等 POSN 驗證多久。 */
    private static final int VIP_TIMEOUT_MS = 6000;
    /** 視窗連續開這麼久才算要店員處理；會員驗證的處理視窗一閃即逝。 */
    private static final int MODAL_GRACE_MS = 2500;

    private enum State { VIP, VIP_WAIT, NEXT, WAIT_LINE, PAUSED, DONE }

    private final Host host;
    private final Listener listener;
    private final List<Reorder.Step> steps;
    private final String vipId;
    private final javax.swing.Timer timer;

    private State state = State.VIP;
    private int index;
    private int added;
    private int skipped;
    private int f10Pending;
    private long deadline;
    private String before;
    /** 別的視窗從什麼時候開始開著；0 代表目前沒有。 */
    private long modalSince;
    private boolean waitingSerial;
    /**
     * submitCode／applyVip 執行中。POSN 若在裡面開 modal 視窗，modal 的事件迴圈會繼續送 Timer 事件，
     * 這一拍就會在呼叫還沒回來時重入 —— 用這個旗標擋掉。
     */
    private boolean busy;

    ReorderRunner(Host host, Listener listener, List<Reorder.Step> steps, String vipId) {
        this.host = host;
        this.listener = listener;
        this.steps = steps;
        this.vipId = vipId == null ? "" : vipId.trim();
        this.timer = new javax.swing.Timer(TICK_MS, e -> Safe.guard("重開單據帶入", () -> tick()));
        this.timer.setRepeats(true);
    }

    void start() {
        PosLog.info("重開單據 開始帶入 " + steps.size() + " 件"
            + (vipId.length() == 0 ? "" : "，含會員"));
        timer.start();
    }

    /** 店員處理完 POS 的視窗後按「繼續」：明細已經變了就算成功，否則重試目前這一件。 */
    void resume() {
        if (state != State.PAUSED) {
            return;
        }
        if (before != null && changed()) {
            succeed();
        }
        modalSince = 0;
        state = State.NEXT;          // 帶完了由 next() 收尾，不要在這裡直接設 DONE
        timer.start();
    }

    /** 略過目前這一件，接著帶下一件。 */
    void skip() {
        if (state != State.PAUSED) {
            return;
        }
        PosLog.info("重開單據 略過第 " + (index + 1) + " 件 " + currentCode());
        host.armSerial(null);
        skipped++;
        index++;
        before = null;
        modalSince = 0;
        state = State.NEXT;
        timer.start();
    }

    void stop() {
        if (state == State.DONE) {
            timer.stop();
            return;
        }
        PosLog.info("重開單據 店員停止，已帶 " + added + " 件");
        finish();
    }

    boolean running() {
        return state != State.DONE;
    }

    private void tick() {
        if (busy || state == State.PAUSED || state == State.DONE) {
            return;
        }
        String dialog = host.openDialog();
        if (SERIAL.equals(dialog)) {
            // 序號視窗是店員的回合：已經幫忙填好，等他看一眼按 Enter，不算逾時
            modalSince = 0;
            if (!waitingSerial) {
                waitingSerial = true;
                listener.waiting("序號已填好，請在 POS 確認後按 Enter");
            }
            deadline = System.currentTimeMillis() + LINE_TIMEOUT_MS;
            return;
        }
        waitingSerial = false;
        if (dialog != null) {
            long now = System.currentTimeMillis();
            if (modalSince == 0) {
                modalSince = now;
            } else if (now - modalSince >= MODAL_GRACE_MS) {
                pause("POS 跳出視窗，處理完後按「繼續」");
            }
            return;
        }
        modalSince = 0;
        switch (state) {
            case VIP:
                if (vipId.length() != 0 && host.vipId().trim().length() == 0) {
                    busy = true;
                    try {
                        host.applyVip(vipId);
                    } finally {
                        busy = false;
                    }
                    deadline = System.currentTimeMillis() + VIP_TIMEOUT_MS;
                    state = State.VIP_WAIT;
                } else {
                    state = State.NEXT;
                }
                return;
            case VIP_WAIT:
                if (host.vipLoaded()) {
                    state = State.NEXT;
                } else if (System.currentTimeMillis() >= deadline) {
                    pause("會員 " + vipId + " 沒有帶上，請確認 POS 會員欄後按「繼續」");
                    state = State.PAUSED;
                }
                return;
            case NEXT:
                next();
                return;
            case WAIT_LINE:
                if (changed()) {
                    succeed();
                    state = State.NEXT;
                } else if (System.currentTimeMillis() >= deadline) {
                    pause("第 " + (index + 1) + " 件 " + currentCode() + " 沒有加進 POS 明細");
                }
                return;
            default:
                return;
        }
    }

    private void next() {
        if (index >= steps.size()) {
            finish();
            return;
        }
        Reorder.Step step = steps.get(index);
        listener.progress(index, steps.size(), step);
        // 序號只對應原單那一列的第一件
        String serial = step.unit == 1 ? step.line.serialToFill() : "";
        host.armSerial(serial.length() == 0 ? null : serial);
        before = host.lineSignature();
        boolean ok;
        busy = true;
        try {
            ok = host.submitCode(step.line.code.trim());
        } finally {
            busy = false;
        }
        if (!ok) {
            pause("POS 品項欄目前不能輸入");
            return;
        }
        deadline = System.currentTimeMillis() + LINE_TIMEOUT_MS;
        state = State.WAIT_LINE;
    }

    private boolean changed() {
        String now = host.lineSignature();
        return now != null && !now.equals(before);
    }

    private void succeed() {
        Reorder.Step step = steps.get(index);
        host.armSerial(null);
        // REF 只對應原單那一列的第一件；代碼被改過就不是原來那個商品，不能填
        if (step.unit == 1 && step.line.hasF10Values() && !step.line.codeChanged()) {
            String lineNo = host.lastLineNo();
            if (lineNo.length() != 0) {
                host.expectF10(lineNo, step.line);
                f10Pending++;
            }
        }
        added++;
        index++;
        before = null;
    }

    private void pause(String reason) {
        timer.stop();
        state = State.PAUSED;
        PosLog.info("重開單據 暫停：" + reason);
        listener.paused(reason);
    }

    private void finish() {
        timer.stop();
        state = State.DONE;
        host.armSerial(null);
        PosLog.info("重開單據 完成：帶入 " + added + " 件，略過 " + skipped
            + " 件，待 F10 " + f10Pending + " 列");
        listener.finished(added, skipped, f10Pending);
        host.returnFocusToPos();
    }

    private String currentCode() {
        return index < steps.size() ? steps.get(index).line.code.trim() : "";
    }
}
