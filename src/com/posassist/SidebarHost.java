package com.posassist;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * 把輔助面板掛進 EPB 左側欄（MainView 的 JSplitPane 左元件）。
 *
 * 做法：把左元件換成一個 CardLayout 容器，裡面兩張卡 —— 我們的面板、以及
 * EPB 原本的側欄。原本那個元件**只是換父容器，內容一個字都不改**。
 *
 * 可回復性是這個類別的第一要務：
 * 1. 全程只呼叫 setLeftComponent，不 remove、不 dispose、不改原元件屬性
 * 2. 原元件同時被欄位持有，還原就是把同一個物件掛回去
 * 3. 看門狗（Swing Timer，跑在 EDT 上，不依賴外掛自己的執行緒）會在該還原時還原
 * 4. shutdown hook 收尾
 * 5. 任何一步不確定就完全不動側欄
 */
public final class SidebarHost {

    /** 由呼叫端回答「現在還該掛著嗎」。回 false 看門狗就會還原。 */
    public interface Guard {
        boolean shouldStayMounted();

        /**
         * 看門狗自己動手還原之後通知一聲。
         *
         * 沒有這個回呼的話，呼叫端（PosnHook）會以為面板還掛著：它的 sidebar／panel
         * 都還是非 null，而重新掛載的唯一入口只在 panel == null 時才會跑 ——
         * 結果側欄還原了、輔助面板卻永遠回不來，連重開 POSN 都沒用。
         *
         * 給預設空實作，讓 Guard 仍然是 functional interface（測試用 lambda 建它）。
         */
        default void sidebarRestored() {
        }
    }

    private static final String CARD_ASSIST = "assist";
    private static final String CARD_HOME = "home";
    private static final String CARD_INVENTORY = "inventory";
    private static final int WATCHDOG_INTERVAL_MS = 5000;
    private static final int MAX_SEARCH_DEPTH = 12;
    /**
     * 側欄窄到這個寬度以下就當成「不見了」。
     *
     * 切換列的藥丸按鈕在 8px 下已經完全點不到，不可能是店員刻意拖的，
     * 所以可以放心當成異常狀態處理。
     */
    private static final int MIN_VISIBLE_WIDTH = 8;
    /** EPB 離開全螢幕時把 divider 條設回 6（見 MainView.doToggleFullScreen）。 */
    private static final int DEFAULT_DIVIDER_SIZE = 6;
    private static final String MAIN_VIEW = "com.epb.shell.MainView";

    // 面板卡片化之後，切換列的底色要跟面板同一階，否則兩塊灰對不上
    private static final Color BAR_BG = Style.PAGE;
    private static final Color ACCENT = Style.ACCENT;
    private static final Color MUTED = Style.MUTED;

    private final Guard guard;

    private JSplitPane splitPane;
    private Component originalLeft;
    /** 我們塞進 splitPane 左邊的那個容器。要比對「現在左邊還是我們嗎」時用。 */
    private JPanel hostPanel;
    /**
     * 最後一次看到的正常側欄寬度。側欄被壓成 0 時就是還原到這個值。
     * 店員自己拖過的寬度也會更新它 —— 救回來的要是他習慣的寬度。
     */
    private int lastGoodDivider;
    /** 上一輪看門狗看到的收起狀態，避免同一個狀態每 5 秒記一次 log。 */
    private boolean reportedCollapse;
    private PropertyChangeListener dividerWatcher;
    private JPanel cards;
    private CardLayout cardLayout;
    private JButton assistButton;
    private JButton homeButton;
    private JButton inventoryButton;
    private JComponent inventoryContent;
    private String selectedCard = CARD_ASSIST;
    private Timer watchdog;
    private Thread shutdownHook;
    private volatile boolean mounted;

    public SidebarHost(Guard guard) {
        this.guard = guard;
    }

    public boolean isMounted() {
        return mounted;
    }

    // -- 掛上 --------------------------------------------------------------

    /**
     * 把面板掛進側欄。必須在 EDT 上呼叫。
     * 回傳 false 代表沒動側欄（呼叫端應改用浮動視窗）。
     */
    public boolean mount(JComponent assistContent) {
        if (mounted || assistContent == null) {
            return mounted;
        }
        JSplitPane found = findShellSplitPane();
        if (found == null) {
            PosLog.warn("找不到 EPB 側欄的 JSplitPane，改用浮動視窗");
            return false;
        }
        return mountOn(found, assistContent);
    }

    public boolean mount(JComponent assistContent, JComponent inventoryContent) {
        this.inventoryContent = inventoryContent;
        return mount(assistContent);
    }

    boolean mountOn(JSplitPane found, JComponent assistContent, JComponent inventoryContent) {
        this.inventoryContent = inventoryContent;
        return mountOn(found, assistContent);
    }

    boolean inventorySelected() { return CARD_INVENTORY.equals(selectedCard); }

    /** 指定 split pane 掛載。分出來是為了讓回復性測試可以注入受控的 split pane。 */
    boolean mountOn(JSplitPane found, JComponent assistContent) {
        if (mounted || found == null || assistContent == null) {
            return mounted;
        }
        Component left = found.getLeftComponent();
        if (left == null || found.getRightComponent() == null) {
            PosLog.warn("側欄結構與預期不符，改用浮動視窗");
            return false;
        }

        try {
            splitPane = found;
            originalLeft = left;

            cardLayout = new CardLayout();
            cards = new JPanel(cardLayout);
            cards.add(assistContent, CARD_ASSIST);
            if (inventoryContent != null) cards.add(inventoryContent, CARD_INVENTORY);
            cards.add(originalLeft, CARD_HOME);      // 換父容器，物件本身不動

            JPanel host = new JPanel(new BorderLayout());
            host.add(buildSwitcher(), BorderLayout.NORTH);
            host.add(cards, BorderLayout.CENTER);
            // 沒有這行，divider 拖不動：JSplitPane 不讓人拖過左元件的最小寬度，
            // 而面板內容算出來的最小寬度比原本的應用程式清單寬得多。
            // 代價是側欄真的可以被壓到 0 寬（EPB 原本的清單有非零最小寬度，
            // 所以原生情況下至少留一截）—— 靠 checkCollapse() 的看門狗救回來。
            host.setMinimumSize(new Dimension(0, 0));
            hostPanel = host;

            // 換元件前先記住 divider 在哪，換完放回去 ——
            // 不然版面會照新元件的偏好寬度重排，側欄突然變寬。
            int divider = found.getDividerLocation();
            if (divider <= 0) {
                divider = left.getPreferredSize().width;
            }
            // 偏好寬度也釘成原本的寬度：只要有一次「照偏好寬度重排」，
            // 我們比較寬的內容就會把側欄整個撐開。使用者自己拖過的寬度優先，不受影響。
            host.setPreferredSize(new Dimension(divider, left.getPreferredSize().height));
            splitPane.setLeftComponent(host);
            lastGoodDivider = divider;
            restoreDivider(divider);
            watchDivider();
            splitPane.revalidate();
            splitPane.repaint();

            mounted = true;
            select(CARD_ASSIST);
            startWatchdog();
            installShutdownHook();
            PosLog.info("輔助面板已掛進左側欄");
            return true;
        } catch (Throwable t) {
            PosLog.warn("掛載側欄失敗，立刻還原並改用浮動視窗", t);
            restore("掛載失敗");
            return false;
        }
    }

    /**
     * 把 divider 放回原本的位置。
     *
     * 設一次不夠：setLeftComponent 之後還會有一輪版面計算，會照新元件的偏好寬度
     * 把 divider 推走，所以排完再設一次。之後使用者自己拖到哪就是哪，不再干涉。
     */
    private void restoreDivider(final int location) {
        if (splitPane == null || location <= 0) {
            return;
        }
        splitPane.setDividerLocation(location);
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                Safe.guard("回復側欄寬度", new Runnable() {
                    public void run() {
                        if (splitPane != null) {
                            splitPane.setDividerLocation(location);
                        }
                    }
                });
            }
        });
    }

    // -- 側欄寬度守衛 ------------------------------------------------------

    /**
     * 記住店員拖出來的寬度。
     *
     * 只在寬度正常時更新 —— 不然側欄一被壓成 0，這個值也跟著變 0，
     * 要還原的時候就沒有東西可以還原了。
     */
    private void watchDivider() {
        unwatchDivider();
        if (splitPane == null) {
            return;
        }
        dividerWatcher = new PropertyChangeListener() {
            public void propertyChange(PropertyChangeEvent event) {
                Object value = event.getNewValue();
                if (value instanceof Integer && ((Integer) value).intValue() > MIN_VISIBLE_WIDTH) {
                    lastGoodDivider = ((Integer) value).intValue();
                }
            }
        };
        splitPane.addPropertyChangeListener(
            JSplitPane.DIVIDER_LOCATION_PROPERTY, dividerWatcher);
    }

    private void unwatchDivider() {
        if (splitPane != null && dividerWatcher != null) {
            try {
                splitPane.removePropertyChangeListener(
                    JSplitPane.DIVIDER_LOCATION_PROPERTY, dividerWatcher);
            } catch (Throwable ignored) {
                // 拆不掉就算了，listener 本身只寫一個 int
            }
        }
        dividerWatcher = null;
    }

    /**
     * 側欄被壓成 0 寬時把它救回來。
     *
     * 門市回報「關閉其他應用程式時整個左側欄消失」，而實機 log 顯示那些情況下
     * 還原與看門狗各 0 次 —— 側欄物件一直都在 splitPane 裡，消失的是寬度。
     * 根因還沒抓到，所以這裡不猜是誰壓的，只負責「不論誰壓的都站回來」。
     *
     * 唯一不救的情況是 EPB 自己的全螢幕模式：MainView.doToggleFullScreen() 進全螢幕時
     * 會把 dividerLocation 與 dividerSize 一起設成 0、離開時設回 (325, 6)，
     * 而全專案只有它會動 dividerSize。所以「divider 條也不見了」就是刻意收起的，
     * 不該跟 EPB 的功能對打 —— 店員再按一次那顆鈕就回來了。
     *
     * 平常由看門狗每 5 秒呼叫一次；分出 package-private 是為了讓測試能直接驅動它，
     * 不必等 timer。
     */
    void checkCollapse() {
        if (!mounted || splitPane == null || hostPanel == null) {
            return;
        }
        if (splitPane.getLeftComponent() != hostPanel) {
            return;   // 左邊已經不是我們了，寬度不是我們該管的事
        }
        int location = splitPane.getDividerLocation();
        if (location > MIN_VISIBLE_WIDTH) {
            reportedCollapse = false;
            return;
        }
        int dividerSize = splitPane.getDividerSize();
        if (dividerSize == 0) {
            if (!reportedCollapse) {
                reportedCollapse = true;
                PosLog.info("側欄已收起：EPB 全螢幕模式（dividerSize=0），不介入");
            }
            return;
        }
        int target = lastGoodDivider > MIN_VISIBLE_WIDTH ? lastGoodDivider : 0;
        PosLog.warn("側欄寬度被壓成 " + location + "（dividerSize=" + dividerSize
            + "，EPB fullScreen=" + fullScreenState() + "），還原到 " + target);
        if (target > 0) {
            splitPane.setDividerLocation(target);
        }
        if (dividerSize < DEFAULT_DIVIDER_SIZE) {
            splitPane.setDividerSize(DEFAULT_DIVIDER_SIZE);
        }
        splitPane.revalidate();
        splitPane.repaint();
    }

    /**
     * 讀 EPB 的 MainView.fullScreen，純粹為了讓 log 有判斷依據。
     * 讀不到就回 "未知" —— 這一項影響不到任何行為。
     */
    private String fullScreenState() {
        for (Container parent = splitPane == null ? null : splitPane.getParent();
             parent != null; parent = parent.getParent()) {
            if (MAIN_VIEW.equals(parent.getClass().getName())) {
                Object value = Safe.declaredField(parent, "fullScreen");
                return value == null ? "未知" : String.valueOf(value);
            }
        }
        return "未知";
    }

    private JPanel buildSwitcher() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(BAR_BG);
        bar.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        // 側欄拖窄時按鈕要換行，不然第二顆會被切掉、點不到
        WrapFlow.install(buttons, 4, 3);

        assistButton = tab("輔助工具", CARD_ASSIST);
        homeButton = tab("應用程式", CARD_HOME);
        buttons.add(assistButton);
        if (inventoryContent != null) {
            inventoryButton = tab("庫存與調撥", CARD_INVENTORY);
            buttons.add(inventoryButton);
        }
        buttons.add(homeButton);

        // 要放 CENTER 不能放 WEST：WEST 只給偏好寬度，按鈕會被切掉而不是折行。
        // CENTER 拿得到整條的可用寬度，WrapFlow 才有機會換行（本來就靠左排）。
        bar.add(buttons, BorderLayout.CENTER);
        return bar;
    }

    private JButton tab(String text, final String card) {
        // 跟代碼面板的分類頁籤同一套藥丸樣式，兩處長得一樣才像同一個面板
        JButton button = new SwitchPill(text);
        button.setFocusable(false);
        button.setFont(button.getFont().deriveFont(12f));
        button.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        button.setCursor(java.awt.Cursor.getPredefinedCursor(
            java.awt.Cursor.HAND_CURSOR));
        button.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                Safe.guard("切換側欄分頁", new Runnable() {
                    public void run() {
                        select(card);
                    }
                });
            }
        });
        return button;
    }

    private void select(String card) {
        if (!mounted || cardLayout == null) {
            return;
        }
        cardLayout.show(cards, card);
        selectedCard = card;
        boolean assist = CARD_ASSIST.equals(card);
        style(assistButton, assist);
        style(homeButton, CARD_HOME.equals(card));
        style(inventoryButton, CARD_INVENTORY.equals(card));
    }

    private static void style(JButton button, boolean active) {
        if (button == null) {
            return;
        }
        button.setForeground(active ? ACCENT : MUTED);
        button.setFont(button.getFont().deriveFont(active ? Font.BOLD : Font.PLAIN, 12f));
        if (button instanceof SwitchPill) {
            ((SwitchPill) button).setActive(active);
        }
    }

    /** 切換列的藥丸按鈕。選中的填淺藍底，其餘只有文字，滑過才浮出底色。 */
    private static final class SwitchPill extends JButton {
        private boolean active;

        SwitchPill(String text) {
            super(text);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
        }

        void setActive(boolean value) {
            this.active = value;
            repaint();
        }

        protected void paintComponent(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            Style.antialias(g2);
            int w = getWidth();
            int h = getHeight();
            if (active) {
                g2.setColor(Style.SURFACE);
                g2.fillRoundRect(0, 0, w - 1, h - 1, h, h);
                g2.setColor(Style.ACCENT);
                g2.drawRoundRect(0, 0, w - 1, h - 1, h, h);
            } else if (getModel().isRollover() || getModel().isPressed()) {
                g2.setColor(getModel().isPressed() ? Style.KEY_PRESS : Style.KEY_HOVER);
                g2.fillRoundRect(0, 0, w - 1, h - 1, h, h);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** 讓外部（例如面板自己）切回應用程式清單。 */
    public void showHome() {
        select(CARD_HOME);
    }
    void showInventory() { select(CARD_INVENTORY); }

    // -- 還原 --------------------------------------------------------------

    /**
     * 把側欄還原成原狀。可重複呼叫，也可在任何執行緒呼叫。
     * 這是整個設計最重要的一條路徑，所以每一步都各自 try 住，
     * 前一步失敗不能擋住後一步。
     */
    public void restore() {
        restore("呼叫端要求");
    }

    /** reason 只進 log —— 之前這條路徑分不出是誰觸發的，出事時查不下去。 */
    public void restore(String reason) {
        final String why = reason == null ? "未指明" : reason;
        final JSplitPane pane = splitPane;
        final Component original = originalLeft;
        final JPanel container = cards;

        mounted = false;
        stopWatchdog();
        unwatchDivider();

        if (pane == null || original == null) {
            PosLog.info("側欄還原：沒有掛載中的狀態可還原（原因：" + why + "）");
            clear();
            return;
        }

        Runnable job = new Runnable() {
            public void run() {
                try {
                    if (container != null) {
                        container.remove(original);
                    }
                } catch (Throwable ignored) {
                    // 拿不掉沒關係，setLeftComponent 也會重新指定父容器
                }
                try {
                    pane.setLeftComponent(original);
                    pane.revalidate();
                    pane.repaint();
                    PosLog.info("左側欄已還原（原因：" + why + "）");
                } catch (Throwable t) {
                    PosLog.warn("還原左側欄失敗（原因：" + why + "）", t);
                }
                clear();
            }
        };

        if (SwingUtilities.isEventDispatchThread()) {
            job.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(job);
            } catch (Throwable t) {
                // EDT 已經沒了（例如關閉流程中），直接試一次
                Safe.guard("直接還原側欄", job);
            }
        }
    }

    private void clear() {
        splitPane = null;
        originalLeft = null;
        hostPanel = null;
        lastGoodDivider = 0;
        reportedCollapse = false;
        cards = null;
        cardLayout = null;
        assistButton = null;
        homeButton = null;
        inventoryButton = null;
        inventoryContent = null;
        removeShutdownHook();
    }

    // -- 看門狗與 shutdown hook -------------------------------------------

    /** 跑在 EDT 上，不依賴外掛自己的背景執行緒 —— 那條執行緒死了這裡照樣運作。 */
    private void startWatchdog() {
        stopWatchdog();
        watchdog = new Timer(WATCHDOG_INTERVAL_MS, new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                Safe.guard("側欄看門狗", new Runnable() {
                    public void run() {
                        if (mounted && guard != null && !guard.shouldStayMounted()) {
                            PosLog.info("看門狗偵測到目標 app 已不在，還原側欄");
                            Guard toNotify = guard;
                            restore("看門狗：目標 app 不在了");
                            // 一定要通知：呼叫端的狀態沒清掉的話，面板就再也掛不回來
                            toNotify.sidebarRestored();
                            return;
                        }
                        checkCollapse();
                    }
                });
            }
        });
        watchdog.setRepeats(true);
        watchdog.start();
    }

    private void stopWatchdog() {
        if (watchdog != null) {
            try {
                watchdog.stop();
            } catch (Throwable ignored) {
                // 停不掉就算了
            }
            watchdog = null;
        }
    }

    /**
     * 結束前的收尾。**絕對不能碰 Swing。**
     *
     * EPB 是在 EDT 上呼叫 System.exit 的（com.epb.shell.Main：印完 "exiting system"
     * 就 exit）。System.exit 會等所有 shutdown hook 跑完，而 EDT 正卡在 exit 裡面
     * —— 這時候 hook 若用 invokeAndWait 去等 EDT，就是互相等，JVM 永遠結束不了：
     * 畫面關掉了，java 行程卻還留著。外面包 try/catch 也沒用，卡住不是例外。
     *
     * 而且整個行程都要收了，側欄還不還原沒有任何差別 —— 畫面本來就跟著消失。
     * 所以這裡只清內部狀態、停掉看門狗，其餘什麼都不做。
     */
    private void installShutdownHook() {
        removeShutdownHook();
        try {
            shutdownHook = new Thread(new Runnable() {
                public void run() {
                    Safe.guard("結束前收尾", new Runnable() {
                        public void run() {
                            mounted = false;
                            stopWatchdog();
                        }
                    });
                }
            }, "PosAssist-Shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (Throwable ignored) {
            shutdownHook = null;
        }
    }

    private void removeShutdownHook() {
        if (shutdownHook == null) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (Throwable ignored) {
            // 已經在關閉流程中就移不掉，無所謂
        }
        shutdownHook = null;
    }

    // -- 尋找側欄 ----------------------------------------------------------

    /**
     * 從 shell 主視窗往下廣度優先，取最淺的 JSplitPane。
     * 應用程式自己的 split pane 都巢在 applicationPanel 更深處，所以最淺的是 MainView 的。
     */
    static JSplitPane findShellSplitPane() {
        Object frame = Safe.staticCall("com.ipt.epbfrw.EpbSharedObjects",
            "getShellFrame", new Class<?>[0], new Object[0]);
        if (!(frame instanceof Window)) {
            return null;
        }
        return findSplitPane((Container) frame);
    }

    private static JSplitPane findSplitPane(Container root) {
        List<Container> level = new ArrayList<Container>();
        level.add(root);
        for (int depth = 0; depth < MAX_SEARCH_DEPTH && !level.isEmpty(); depth++) {
            List<Container> next = new ArrayList<Container>();
            for (int i = 0; i < level.size(); i++) {
                Component[] children;
                try {
                    children = level.get(i).getComponents();
                } catch (Throwable t) {
                    continue;
                }
                for (int j = 0; j < children.length; j++) {
                    if (children[j] instanceof JSplitPane) {
                        return (JSplitPane) children[j];
                    }
                    if (children[j] instanceof Container) {
                        next.add((Container) children[j]);
                    }
                }
            }
            level = next;
        }
        return null;
    }
}
