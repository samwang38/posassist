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
    }

    private static final String CARD_ASSIST = "assist";
    private static final String CARD_HOME = "home";
    private static final int WATCHDOG_INTERVAL_MS = 5000;
    private static final int MAX_SEARCH_DEPTH = 12;

    // 面板卡片化之後，切換列的底色要跟面板同一階，否則兩塊灰對不上
    private static final Color BAR_BG = Style.PAGE;
    private static final Color ACCENT = Style.ACCENT;
    private static final Color MUTED = Style.MUTED;

    private final Guard guard;

    private JSplitPane splitPane;
    private Component originalLeft;
    private JPanel cards;
    private CardLayout cardLayout;
    private JButton assistButton;
    private JButton homeButton;
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
            cards.add(originalLeft, CARD_HOME);      // 換父容器，物件本身不動

            JPanel host = new JPanel(new BorderLayout());
            host.add(buildSwitcher(), BorderLayout.NORTH);
            host.add(cards, BorderLayout.CENTER);
            // 沒有這行，divider 拖不動：JSplitPane 不讓人拖過左元件的最小寬度，
            // 而面板內容算出來的最小寬度比原本的應用程式清單寬得多。
            host.setMinimumSize(new Dimension(0, 0));

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
            restoreDivider(divider);
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
            restore();
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
        boolean assist = CARD_ASSIST.equals(card);
        style(assistButton, assist);
        style(homeButton, !assist);
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

    // -- 還原 --------------------------------------------------------------

    /**
     * 把側欄還原成原狀。可重複呼叫，也可在任何執行緒呼叫。
     * 這是整個設計最重要的一條路徑，所以每一步都各自 try 住，
     * 前一步失敗不能擋住後一步。
     */
    public void restore() {
        final JSplitPane pane = splitPane;
        final Component original = originalLeft;
        final JPanel container = cards;

        mounted = false;
        stopWatchdog();

        if (pane == null || original == null) {
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
                    PosLog.info("左側欄已還原");
                } catch (Throwable t) {
                    PosLog.warn("還原左側欄失敗", t);
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
        cards = null;
        cardLayout = null;
        assistButton = null;
        homeButton = null;
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
                            restore();
                        }
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
