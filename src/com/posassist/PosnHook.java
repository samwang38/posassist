package com.posassist;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowEvent;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Collection;

import java.awt.KeyboardFocusManager;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.Document;

/**
 * 掛在 EPB 的 ApplicationPool 上，認出目標 app 並接上浮動面板。
 *
 * 掛載鏈（全部是公開 API，實測過）：
 *   ApplicationPool.getInstance().addApplicationPoolListener(proxy)
 *     -> applicationOpened(Application)
 *     -> app.getApplicationHome().getAppCode()                     取得 "POSN"
 *     -> app.getEpbApplication()                                   解出 POSN 本體
 *        （app 實際型別是 DefaultApplicationBuilder$WrapperApplication）
 *     -> posn.vipIdTextField                                       public JTextField
 *
 * 目標 app code 用 -Dposassist.appCode 覆寫。本機沒有 POSN 權限時，
 * 可以指定 SHOPPOSB 之類已授權的 app 來驗證整套機制。
 */
public final class PosnHook implements FloatingPanel.VipApplier, SidebarHost.Guard {

    private static final String POOL = "com.epb.framework.ApplicationPool";
    private static final String LISTENER = "com.epb.framework.ApplicationPoolListener";
    private static final String SHARED = "com.ipt.epbfrw.EpbSharedObjects";

    private static final String DEFAULT_APP_CODE = "POSN";
    /** 帶入會員後，焦點要回到的 POS 輸入欄位（品項/條碼欄）。 */
    private static final String DEFAULT_POS_INPUT_FIELD = "pluIdTextField";

    /** F10 序號視窗。它是 modal，開著時面板點不動，所以只能先記住再自動填。 */
    private static final String SERIAL_DIALOG = "com.ipt.app.posn.ui.PosSerialNoDialog";
    /** 對話框裡「預約單號」那一欄的標籤文字，用來認出是第幾個 lineRef。 */
    private static final String RESERVATION_LABEL = "預約單號";
    private static final int MAX_LINE_REF = 8;
    /**
     * POS 會員欄變動後延遲多久才查。
     *
     * 逐字輸入或條碼機掃入時，中間那幾個字元查出來的結果沒有人會看到，
     * 卻每一次都是一趟資料庫往返。300ms 是「打完一碼的間隔」與「店員感覺不到延遲」
     * 之間的取捨：掃描器一瞬間打完只會觸發一次，手打也不會覺得慢。
     */
    private static final int VIP_FOLLOW_DELAY_MS = 300;
    /**
     * 庫存登入身分要連續幾次一致才承認變更（timer 每秒跑一次，所以等於幾秒）。
     * 只擋「拆側欄」這個方向 —— 建立工作區是無害的，不需要等。
     */
    private static final int IDENTITY_CONFIRM_TICKS = 3;

    private final String posInputField;

    private final String targetAppCode;
    private FloatingPanel panel;
    /** 嵌入側欄時才有值；浮動模式為 null。 */
    private SidebarHost sidebar;
    private Object attachedApplication;
    private Object posnInstance;
    private Component attachedView;
    private Document watchedDocument;
    private DocumentListener watcher;
    /** 會員欄輸入的去抖動計時器。跑在 EDT 上，所以回呼裡可以直接動面板。 */
    private javax.swing.Timer vipFollowTimer;
    /** 自己填進去造成的變動，不要再回頭觸發一次查詢。 */
    private boolean applyingToPos;
    /** 使用者點過的預約單號，等 F10 視窗開啟時填入，用完就清掉。 */
    private volatile String armedReservationRef;
    private boolean serialWatcherInstalled;
    private InventoryWorkspace inventoryWorkspace;
    private javax.swing.Timer inventorySessionTimer;
    private String failedInventoryIdentity = "";
    /** 正在確認的登入身分。null 代表目前讀到的跟掛著的一致，沒有待確認的變動。 */
    private String pendingIdentity;
    private int pendingIdentityTicks;
    private final boolean inventoryEnabled = "true".equalsIgnoreCase(
        Home.value("config/posassist.properties", "enableInventory", "false"));

    public PosnHook() {
        String configured = System.getProperty("posassist.appCode");
        this.targetAppCode = configured != null && configured.trim().length() != 0
            ? configured.trim()
            : DEFAULT_APP_CODE;

        String inputField = System.getProperty("posassist.posInputField");
        this.posInputField = inputField != null && inputField.trim().length() != 0
            ? inputField.trim()
            : DEFAULT_POS_INPUT_FIELD;
    }

    public String targetAppCode() {
        return targetAppCode;
    }

    // -- 註冊 --------------------------------------------------------------

    /** 回傳是否成功掛上。 */
    public boolean install() {
        Object pool = Safe.staticCall(POOL, "getInstance", new Class<?>[0], new Object[0]);
        if (pool == null) {
            PosLog.warn("取不到 ApplicationPool，外掛不啟用");
            return false;
        }

        Object listener = Safe.proxy(LISTENER, new InvocationHandler() {
            public Object invoke(Object proxy, Method method, Object[] args) {
                dispatch(method.getName(), args);
                return null;
            }
        });
        if (listener == null) {
            PosLog.warn("建立 listener 失敗，外掛不啟用");
            return false;
        }

        Class<?> listenerType = Safe.type(LISTENER);
        Safe.call(pool, "addApplicationPoolListener",
            new Class<?>[] { listenerType }, new Object[] { listener });
        if (!listenerRegistered(pool)) {
            PosLog.warn("註冊 listener 失敗，外掛不啟用");
            return false;
        }

        PosLog.info("外掛已掛上，目標 app: " + targetAppCode);
        FloatingPanel.onEdt(new Runnable() {
            public void run() {
                if (inventoryEnabled) {
                    syncInventorySession();
                    inventorySessionTimer = new javax.swing.Timer(1000, e -> Safe.guard("庫存登入狀態", () -> syncInventorySession()));
                    inventorySessionTimer.start();
                }
                scanExisting(pool);
            }
        });
        return true;
    }

    private void syncInventorySession() {
        String identity = InventoryWorkspace.currentIdentity();
        if (identity.isEmpty()) failedInventoryIdentity = "";
        if (inventoryWorkspace != null && !inventoryWorkspace.matches(identity)) {
            // 身分看起來變了，但這一秒讀到的值不一定可信：currentIdentity() 讀不到
            // getUserId/getOrgId/getLocId 任一項就回空字串，而據此 detach() 會把整條
            // 側欄拆掉。瞬時讀不到就拆，正是「側欄突然不見了」最容易發生的地方，
            // 所以要連續看到同一個新值才承認。
            if (!identity.equals(pendingIdentity)) {
                pendingIdentity = identity;
                pendingIdentityTicks = 1;
                PosLog.info("庫存登入身分與目前不符（"
                    + (identity.isEmpty() ? "這次讀不到" : "讀到另一組") + "），確認中");
                return;
            }
            if (++pendingIdentityTicks < IDENTITY_CONFIRM_TICKS) {
                return;
            }
            PosLog.info("庫存登入身分確認已變更（連續 " + IDENTITY_CONFIRM_TICKS
                + " 次一致），卸下面板並重建庫存工作區");
            detach();
            inventoryWorkspace.close();
            inventoryWorkspace = null;
        }
        pendingIdentity = null;
        pendingIdentityTicks = 0;
        if (inventoryWorkspace == null && !identity.isEmpty() && !identity.equals(failedInventoryIdentity)) {
            try { inventoryWorkspace = new InventoryWorkspace(identity); }
            catch (Exception error) {
                failedInventoryIdentity = identity;
                PosLog.warn("庫存工具掛載失敗；本次登入保留原有 POS 輔助模式");
            }
            Object pool = Safe.staticCall(POOL, "getInstance", new Class<?>[0], new Object[0]);
            if (pool != null) scanExisting(pool);
        }
    }

    /** addApplicationPoolListener 回 void，用實際清單確認有沒有加進去。 */
    private boolean listenerRegistered(Object pool) {
        Collection<?> listeners = readCollection(pool, "applicationPoolListeners");
        // 讀不到內部欄位時不當成失敗：那只代表無法確認，不代表沒掛上
        return listeners == null || !listeners.isEmpty();
    }

    /** 補接：listener 掛上前目標 app 就已經開著的情況。 */
    private void scanExisting(Object pool) {
        Collection<?> pooled = readCollection(pool, "pooledApplications");
        if (pooled == null) {
            return;
        }
        for (Object application : pooled) {
            if (inventoryWorkspace != null && "STORESUM".equals(appCodeOf(application)))
                inventoryWorkspace.nativeApplicationEvent("applicationOpened", application);
            if (targetAppCode.equals(appCodeOf(application))) {
                if (application != attachedApplication) {
                    PosLog.info("補接已開啟的 " + targetAppCode);
                    attach(application);
                }
            }
        }
    }

    private static Collection<?> readCollection(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(target);
            return value instanceof Collection ? (Collection<?>) value : null;
        } catch (Throwable t) {
            PosLog.warn("讀不到 ApplicationPool." + fieldName);
            return null;
        }
    }

    // -- 事件分派 ----------------------------------------------------------

    private void dispatch(final String event, final Object[] args) {
        FloatingPanel.onEdt(new Runnable() {
            public void run() {
                Object application = args != null && args.length > 0 ? args[0] : null;
                if (application == null) {
                    return;
                }
                if (inventoryWorkspace != null && "STORESUM".equals(appCodeOf(application)))
                    inventoryWorkspace.nativeApplicationEvent(event, application);
                if (!targetAppCode.equals(appCodeOf(application))) {
                    return;
                }
                if ("applicationOpened".equals(event) || "applicationActivated".equals(event)) {
                    if (application != attachedApplication) {
                        attach(application);
                    }
                } else if ("applicationClosed".equals(event)) {
                    detach();
                }
            }
        });
    }

    private static String appCodeOf(Object application) {
        Object home = Safe.call(application, "getApplicationHome");
        if (home == null) {
            return null;
        }
        Object code = Safe.call(home, "getAppCode");
        return code == null ? null : String.valueOf(code).trim();
    }

    // -- 接上／卸下 --------------------------------------------------------

    private void attach(final Object application) {
        PosLog.info("偵測到 " + targetAppCode + " 開啟");
        attachedApplication = application;

        final Object view = Safe.call(application, "getApplicationView");
        if (!(view instanceof Component)) {
            PosLog.warn("getApplicationView() 不是 Component，無法定位面板");
            return;
        }
        attachedView = (Component) view;

        // WrapperApplication.getEpbApplication() 解出 POSN 本體；
        // 若是別的 app（Phase 2 的替身）就沒有這個方法，面板照樣顯示，只是沒有會員跟隨。
        posnInstance = Safe.call(application, "getEpbApplication");

        FloatingPanel.onEdt(new Runnable() {
            public void run() {
                if (panel == null) {
                    createPanel();
                }
                panel.attachTo(attachedView, posNoOf(posnInstance));
                bindVipField(posnInstance);
            }
        });
    }

    /**
     * 依設定決定面板要嵌進側欄還是浮動。嵌入失敗一律退回浮動 ——
     * 面板可以換位置，但不能因此消失。
     */
    private void createPanel() {
        if (embeddedMode()) {
            FloatingPanel embedded = new FloatingPanel();
            SidebarHost host = new SidebarHost(this);
            if (host.mount(embedded.getContent())) {
                panel = embedded;
                sidebar = host;
                panel.setVipApplier(this);
                return;
            }
            PosLog.warn("嵌入側欄未成功，改用浮動視窗");
        }
        panel = new FloatingPanel(shellWindow());
        panel.setVipApplier(this);
    }

    private static boolean embeddedMode() {
        String mode = Home.value("config/posassist.properties", "panelMode", "embedded");
        return !"floating".equalsIgnoreCase(mode);
    }

    /** SidebarHost 的看門狗會問這個：目標 app 還開著嗎。 */
    public boolean shouldStayMounted() {
        if (attachedApplication == null) {
            return false;
        }
        Object pool = Safe.staticCall(POOL, "getInstance", new Class<?>[0], new Object[0]);
        Collection<?> pooled = pool == null ? null : readCollection(pool, "pooledApplications");
        if (pooled == null) {
            return true;      // 問不到就不要亂拆
        }
        return pooled.contains(attachedApplication);
    }

    private void detach() {
        PosLog.info(targetAppCode + " 已關閉");
        clearAttachment();

        // 先還原側欄，再處理面板 —— 還原是最不能失敗的一步，放最前面
        final SidebarHost host = sidebar;
        sidebar = null;
        if (host != null) {
            Safe.guard("還原側欄", new Runnable() {
                public void run() {
                    host.restore(targetAppCode + " 關閉");
                }
            });
        }

        disposePanel();
    }

    /**
     * 看門狗自己還原了側欄之後的收尾。
     *
     * 沒有這一步的話，sidebar 與 panel 都還是非 null，而 attach() 只有在
     * panel == null 時才會重建面板 —— 側欄還原了、輔助面板卻永遠回不來，
     * 連重開 POSN 都沒用，只能重開整個 EPB。
     */
    public void sidebarRestored() {
        FloatingPanel.onEdt(new Runnable() {
            public void run() {
                Safe.guard("看門狗還原後收尾", new Runnable() {
                    public void run() {
                        if (sidebar == null && panel == null) {
                            return;
                        }
                        PosLog.info("側欄已由看門狗還原，清掉面板狀態，"
                            + "下次開啟 " + targetAppCode + " 會重新掛上");
                        sidebar = null;
                        clearAttachment();
                        disposePanel();
                    }
                });
            }
        });
    }

    private void clearAttachment() {
        attachedApplication = null;
        posnInstance = null;
        attachedView = null;
        armedReservationRef = null;
        unbindVipField();
    }

    private void disposePanel() {
        final FloatingPanel closing = panel;
        panel = null;
        FloatingPanel.onEdt(new Runnable() {
            public void run() {
                if (closing != null) {
                    closing.dispose();
                }
            }
        });
    }

    private static Window shellWindow() {
        Object frame = Safe.staticCall(SHARED, "getShellFrame", new Class<?>[0], new Object[0]);
        return frame instanceof Window ? (Window) frame : null;
    }

    private static String posNoOf(Object posn) {
        if (posn == null) {
            return "";
        }
        return Safe.text(Safe.field(posn, "posNoTextField"));
    }

    // -- 帶入 POS（FloatingPanel.VipApplier）-------------------------------

    /**
     * 把會員代碼填進 POSN 的會員欄位，效果等同店員自己打進去再按 Enter。
     * 這不是直接寫資料庫 —— 後續驗證與載入都由 POSN 自己處理。
     */
    public boolean apply(String vipId) {
        if (posnInstance == null || vipId == null || vipId.trim().length() == 0) {
            return false;
        }
        Object field = Safe.field(posnInstance, "vipIdTextField");
        if (field == null) {
            return false;
        }
        // POS 鎖住欄位時（例如交易進行到不可改會員的階段）就不要硬塞
        Object enabled = Safe.call(field, "isEnabled");
        Object editable = Safe.call(field, "isEditable");
        if (Boolean.FALSE.equals(enabled) || Boolean.FALSE.equals(editable)) {
            PosLog.info("POS 會員欄位目前不可編輯，取消帶入");
            return false;
        }

        final String code = vipId.trim();

        // 視窗沒被啟用時 requestFocusInWindow 只會排隊等啟用，所以要先拉回來；
        // 但已經是使用中的視窗就別碰 —— 多一次 requestFocus 會多一輪焦點進出，
        // 而 POSN 的會員載入正是掛在「焦點離開」上（見下面），會被多觸發一次。
        focusShellWindow();

        applyingToPos = true;
        try {
            Safe.call(field, "setText", new Class<?>[] { String.class }, new Object[] { code });
            Safe.call(field, "requestFocusInWindow");
        } finally {
            applyingToPos = false;
        }
        PosLog.info("已把會員代碼填進 POS 欄位");

        // POSN 的 vipIdTextField 沒有 ActionListener（只有 KeyListener 與 FocusListener），
        // 真正跑驗證與載入的是 vipIdTextFieldFocusLost → changeAndCheckVipID，
        // 而它是同步跑在 EDT 上的（會呼叫 EpbPosCheckUtility.checkVip 等連線動作）。
        // 也就是說：焦點一離開會員欄，畫面就會停住到 POSN 做完為止。
        // 所以先讓面板把「帶入中」畫出來，再用 invokeLater 觸發，
        // 店員看得到是在等，而不是以為當掉了。
        returnFocusToPos();
        return true;
    }

    /**
     * 把焦點還給 POS。POSN 自己已經把焦點放在 EPB 視窗內的某個欄位時就不干預，
     * 只有焦點還留在面板（或不在 EPB 視窗內）時才主動送到品項輸入欄。
     */
    /** 面板點了單號會呼叫這裡。只是記住，實際填入等 F10 視窗開才做。 */
    public boolean armReservationRef(String orderNo) {
        if (posnInstance == null || orderNo == null || orderNo.trim().length() == 0) {
            return false;
        }
        armedReservationRef = orderNo.trim();
        installSerialWatcher();
        PosLog.info("已記住預約單號，等 F10 序號視窗開啟時填入");
        return true;
    }

    /** 監看 F10 序號視窗開啟。只掛一次。 */
    private synchronized void installSerialWatcher() {
        if (serialWatcherInstalled) {
            return;
        }
        serialWatcherInstalled = true;
        Safe.guard("掛上序號視窗監看", new Runnable() {
            public void run() {
                Toolkit.getDefaultToolkit().addAWTEventListener(new AWTEventListener() {
                    public void eventDispatched(AWTEvent event) {
                        if (!(event instanceof WindowEvent)) {
                            return;
                        }
                        int id = event.getID();
                        if (id != WindowEvent.WINDOW_OPENED
                            && id != WindowEvent.WINDOW_ACTIVATED) {
                            return;
                        }
                        final Window window = ((WindowEvent) event).getWindow();
                        Safe.guard("填入預約單號", new Runnable() {
                            public void run() {
                                fillSerialDialog(window);
                            }
                        });
                    }
                }, AWTEvent.WINDOW_EVENT_MASK);
            }
        });
    }

    private void fillSerialDialog(Window window) {
        String pending = armedReservationRef;
        if (pending == null || window == null) {
            return;
        }
        if (!SERIAL_DIALOG.equals(window.getClass().getName())) {
            return;
        }

        Object field = reservationRefField(window);
        if (field == null) {
            PosLog.warn("序號視窗裡找不到預約單號欄位，維持剪貼簿可貼上");
            return;
        }
        // 已經有值就不覆蓋，避免蓋掉店員自己打的
        if (Safe.text(field).length() != 0) {
            PosLog.info("預約單號欄已有值，不覆蓋");
            armedReservationRef = null;
            return;
        }
        Safe.call(field, "setText", new Class<?>[] { String.class }, new Object[] { pending });
        Safe.call(field, "requestFocusInWindow");
        armedReservationRef = null;
        PosLog.info("已把預約單號填入序號視窗");
    }

    /**
     * 找「預約單號」那一欄。lineRefN 的 N 各店可能不同（士林是 7），
     * 所以用標籤文字認，認不到才退回設定值。
     */
    private static Object reservationRefField(Window dialog) {
        for (int i = 1; i <= MAX_LINE_REF; i++) {
            Object label = Safe.field(dialog, "lineRef" + i + "Label");
            if (label == null) {
                continue;
            }
            if (Safe.text(label).indexOf(RESERVATION_LABEL) >= 0) {
                Object field = Safe.field(dialog, "lineRef" + i + "TextField");
                if (field != null) {
                    return field;
                }
            }
        }
        String configured = System.getProperty("posassist.reservationRefField");
        return Safe.field(dialog,
            configured != null && configured.trim().length() != 0
                ? configured.trim()
                : "lineRef7TextField");
    }

    /**
     * 把結帳代碼填進 POS 的 PLU 欄並送出，等同店員自己打代碼按 Enter
     * （POSN.pluIdTextFieldActionPerformed 會接手驗證與加品項）。
     * 焦點留在 PLU 欄，店員可以直接接著掃下一件。
     */
    public boolean applyCode(String code) {
        if (posnInstance == null || code == null || code.trim().length() == 0) {
            return false;
        }
        Object field = Safe.field(posnInstance, posInputField);
        if (field == null) {
            return false;
        }
        Object enabled = Safe.call(field, "isEnabled");
        Object editable = Safe.call(field, "isEditable");
        if (Boolean.FALSE.equals(enabled) || Boolean.FALSE.equals(editable)) {
            PosLog.info("POS 品項欄目前不可編輯，取消帶入");
            return false;
        }

        focusShellWindow();
        Safe.call(field, "setText",
            new Class<?>[] { String.class }, new Object[] { code.trim() });
        Safe.call(field, "requestFocusInWindow");
        Safe.call(field, "postActionEvent");
        PosLog.info("已帶入結帳代碼到 POS");
        return true;
    }

    public void returnFocusToPos() {
        FloatingPanel.onEdt(new Runnable() {
            public void run() {
                if (inventoryWorkspace != null && !inventoryWorkspace.allowsPosFocus()) return;
                // 先看焦點在不在 EPB 裡：已經在就什麼都別做。
                // 每多動一次焦點，POSN 的 focusLost 就可能多跑一次會員驗證，
                // 那是同步的連線動作，畫面會多停一次。
                if (focusInsideShell()) {
                    return;
                }
                focusShellWindow();
                if (focusInsideShell()) {
                    return;
                }
                Object target = Safe.field(posnInstance, posInputField);
                if (target == null) {
                    target = Safe.field(posnInstance, "vipIdTextField");
                }
                if (target != null) {
                    Safe.call(target, "requestFocusInWindow");
                    return;
                }
                if (attachedView instanceof JComponent) {
                    ((JComponent) attachedView).requestFocusInWindow();
                }
            }
        });
    }

    /** 把 EPB 主視窗拉到前面。已經是使用中的視窗就不動，避免多餘的焦點進出。 */
    private static void focusShellWindow() {
        Window shell = shellWindow();
        if (shell == null || shell.isActive()) {
            return;
        }
        Safe.guard("拉回 EPB 視窗焦點", new Runnable() {
            public void run() {
                Window window = shellWindow();
                if (window != null) {
                    window.toFront();
                    window.requestFocus();
                }
            }
        });
    }

    private static boolean focusInsideShell() {
        try {
            Component owner = KeyboardFocusManager
                .getCurrentKeyboardFocusManager().getFocusOwner();
            Window shell = shellWindow();
            return owner != null && shell != null
                && SwingUtilities.getWindowAncestor(owner) == shell;
        } catch (Throwable t) {
            return false;
        }
    }

    // -- 會員欄位跟隨 ------------------------------------------------------

    private void bindVipField(Object posn) {
        unbindVipField();
        if (posn == null) {
            return;
        }

        Object vipIdField = Safe.field(posn, "vipIdTextField");
        if (vipIdField == null) {
            PosLog.warn("找不到 vipIdTextField，會員自動跟隨停用（輸入框查詢仍可用）");
            return;
        }

        Object document = Safe.call(vipIdField, "getDocument");
        if (!(document instanceof Document)) {
            PosLog.warn("取不到 vipIdTextField 的 Document，會員自動跟隨停用");
            return;
        }

        final Object field = vipIdField;
        watchedDocument = (Document) document;
        // 計時器要先建好再掛 listener：反過來的話，兩件事之間進來的文件事件會撞到 null
        vipFollowTimer = new javax.swing.Timer(VIP_FOLLOW_DELAY_MS,
            e -> Safe.guard("會員自動跟隨", new Runnable() {
                public void run() {
                    if (panel != null) {
                        panel.showMember(Safe.text(field));
                    }
                }
            }));
        vipFollowTimer.setRepeats(false);
        watcher = new DocumentListener() {
            public void insertUpdate(DocumentEvent event) {
                push();
            }

            public void removeUpdate(DocumentEvent event) {
                push();
            }

            public void changedUpdate(DocumentEvent event) {
                push();
            }

            private void push() {
                if (applyingToPos) {
                    return;
                }
                // 不要每一個字元都查一次：店員或條碼機打 8 碼會員代碼就是 8 次查詢，
                // 而每次查詢都是一趟資料庫往返（實機 1–9 秒）。停手 300ms 才查。
                // 讀成區域變數：unbind 之後才進來的事件不能把 NPE 丟回 POSN 的文件通知裡。
                javax.swing.Timer timer = vipFollowTimer;
                if (timer != null) {
                    timer.restart();
                }
            }
        };
        watchedDocument.addDocumentListener(watcher);

        // 接上當下就先讀一次現值（這一次不延遲，面板要馬上有內容）
        FloatingPanel.onEdt(new Runnable() {
            public void run() {
                if (panel != null) {
                    panel.showMember(Safe.text(field));
                }
            }
        });
        PosLog.info("已接上 vipIdTextField，會員自動跟隨啟用");
    }

    private void unbindVipField() {
        if (vipFollowTimer != null) {
            try {
                vipFollowTimer.stop();
            } catch (Throwable ignored) {
                // 停不掉就算了
            }
            vipFollowTimer = null;
        }
        if (watchedDocument != null && watcher != null) {
            try {
                watchedDocument.removeDocumentListener(watcher);
            } catch (Throwable ignored) {
                // 卸不掉就算了
            }
        }
        watchedDocument = null;
        watcher = null;
    }
}
