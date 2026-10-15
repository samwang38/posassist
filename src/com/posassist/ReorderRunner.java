package com.posassist;

import java.util.List;

/**
 * 把一張舊單依序帶回 POS：先會員，再一件一件帶商品代碼。
 *
 * 每一步都走 PosnHook 既有的路徑（填欄位再送 Enter，跟店員手打一樣），由 POSN 自己驗證。
 * 一件帶完要看到 POS 明細真的變了才帶下一件；POSN 跳出任何視窗、或逾時沒反應，
 * 就停下來交給店員 —— 寧可停，也不要在店員看不懂的狀態下繼續塞。
 *
 * 全部跑在 EDT 上，用 javax.swing.Timer 推進，不阻塞畫面。
 */
final class ReorderRunner {

    /** PosnHook 提供的 POS 操作。全部在 EDT 上呼叫。 */
    interface Host {
        /** POS 會員欄目前的值。 */
        String vipId();
        String posNo();
        boolean applyVip(String vipId);
        boolean applyCode(String code);
        /** POS 明細的列數與總數量，用來判斷「有沒有加進去」。讀不到回 null。 */
        String lineSignature();
        /** POS 明細目前有幾列。讀不到回 -1。 */
        int lineCount();
        /** POS 明細最後一列的行號（ID 欄）。讀不到回空字串。 */
        String lastLineNo();
        /** 有沒有 modal 視窗開著（POSN 的提示、選項、錯誤訊息）。 */
        boolean modalOpen();
        /** 記住這一列原單的序號與 REF，等店員對 POS 那一列按 F10 時填入。 */
        void expectF10(String posLineNo, Reorder.Line line);
        void returnFocusToPos();
    }

    /** 進度回報，跑在 EDT 上。 */
    interface Listener {
        void progress(int done, int total, Reorder.Step current);
        void paused(String reason);
        void finished(int added, int skipped, int f10Pending);
    }

    private static final int TICK_MS = 150;
    /** 帶一件之後等 POS 明細變動的時間。POSN 是同步處理的，正常一兩拍就看得到。 */
    private static final int LINE_TIMEOUT_MS = 4000;
    /** 帶會員之後等 POSN 驗證完。驗證是同步的，這段時間只是讓畫面喘口氣。 */
    private static final int VIP_SETTLE_MS = 800;

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
    /**
     * applyCode／applyVip 執行中。POSN 若在裡面開 modal 視窗，modal 的事件迴圈會繼續送 Timer 事件，
     * 這一拍就會在 applyCode 還沒回來時重入 —— 用這個旗標擋掉。
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

    /** 店員處理完 POS 的視窗後按「繼續」：重試目前這一件。 */
    void resume() {
        if (state != State.PAUSED) {
            return;
        }
        // 暫停期間 POS 明細若已經變了（例如店員在視窗裡按了確定，品項其實加進去了），算成功
        if (before != null && changed()) {
            succeed();
        }
        state = index < steps.size() ? State.NEXT : State.DONE;
        timer.start();
    }

    /** 略過目前這一件，接著帶下一件。 */
    void skip() {
        if (state != State.PAUSED) {
            return;
        }
        PosLog.info("重開單據 略過第 " + (index + 1) + " 件 " + currentCode());
        skipped++;
        index++;
        before = null;
        state = State.NEXT;
        timer.start();
    }

    void stop() {
        if (state == State.DONE) {
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
        if (host.modalOpen()) {
            pause("POS 有視窗開著，處理完後按「繼續」");
            return;
        }
        switch (state) {
            case VIP:
                if (vipId.length() != 0 && host.vipId().trim().length() == 0) {
                    busy = true;
                    try {
                        host.applyVip(vipId);
                    } finally {
                        busy = false;
                    }
                    deadline = System.currentTimeMillis() + VIP_SETTLE_MS;
                    state = State.VIP_WAIT;
                } else {
                    state = State.NEXT;
                }
                return;
            case VIP_WAIT:
                if (System.currentTimeMillis() >= deadline) {
                    if (host.vipId().trim().length() == 0) {
                        PosLog.info("重開單據 會員沒有帶上，繼續帶商品");
                    }
                    state = State.NEXT;
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
        listener.progress(index, steps.size(), steps.get(index));
        before = host.lineSignature();
        boolean ok;
        busy = true;
        try {
            ok = host.applyCode(steps.get(index).line.code.trim());
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
        // 序號只對應原單那一列的第一件；代碼被改過就不是原來那個商品，不能填
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
        PosLog.info("重開單據 完成：帶入 " + added + " 件，略過 " + skipped
            + " 件，待 F10 " + f10Pending + " 列");
        listener.finished(added, skipped, f10Pending);
        host.returnFocusToPos();
    }

    private String currentCode() {
        return index < steps.size() ? steps.get(index).line.code.trim() : "";
    }
}
