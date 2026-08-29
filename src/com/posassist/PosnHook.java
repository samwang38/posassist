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
    /** 自己填進去造成的變動，不要再回頭觸發一次查詢。 */
    private boolean applyingToPos;
    /** 使用者點過的預約單號，等 F10 視窗開啟時填入，用完就清掉。 */
    private volatile String armedReservationRef;
    private boolean serialWatcherInstalled;

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
        scanExisting(pool);
        return true;
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
            if (targetAppCode.equals(appCodeOf(application))) {
                PosLog.info("補接已開啟的 " + targetAppCode);
                attach(application);
                return;
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
        Safe.guard("處理 " + event, new Runnable() {
            public void run() {
                Object application = args != null && args.length > 0 ? args[0] : null;
                if (application == null) {
                    return;
                }
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
        attachedApplication = null;
        posnInstance = null;
        attachedView = null;
        armedReservationRef = null;
        unbindVipField();

        // 先還原側欄，再處理面板 —— 還原是最不能失敗的一步，放最前面
        final SidebarHost host = sidebar;
        sidebar = null;
        if (host != null) {
            Safe.guard("還原側欄", new Runnable() {
                public void run() {
                    host.restore();
                }
            });
        }

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

        // 先把 EPB 主視窗拉回焦點，否則 requestFocusInWindow 只會排隊等視窗被啟用
        focusShellWindow();

        applyingToPos = true;
        try {
            Safe.call(field, "setText", new Class<?>[] { String.class }, new Object[] { code });
            Safe.call(field, "requestFocusInWindow");
            // 等同按下 Enter，讓 POSN 走它自己的載入流程
            Safe.call(field, "postActionEvent");
        } finally {
            applyingToPos = false;
        }
        PosLog.info("已帶入會員代碼到 POS");

        // POSN 處理完 Enter 之後可能自己移動焦點，讓它先決定；它沒接手才補位
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

    private static void focusShellWindow() {
        Window shell = shellWindow();
        if (shell == null) {
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
                FloatingPanel.onEdt(new Runnable() {
                    public void run() {
                        if (panel != null) {
                            panel.showMember(Safe.text(field));
                        }
                    }
                });
            }
        };
        watchedDocument.addDocumentListener(watcher);

        // 接上當下就先讀一次現值
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
