package com.posassist;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

import java.text.SimpleDateFormat;
import java.util.Date;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * 置頂浮動面板，自動貼在 POSN 畫面旁邊。
 *
 * 內容：常駐輸入框（打完按 Enter 送出）＋ 會員代碼／姓名／電話／Email／等級。
 * 會員代碼可以點一下直接帶入 POS 的會員欄位。
 *
 * 焦點處理（POS 環境的重點）：
 * 視窗設 setAutoRequestFocus(false)，出現或移動時都不會自己搶焦點，
 * 只有店員主動點輸入框才會拿到焦點。帶入 POS 之後、按 Enter 或 Esc 之後，
 * 焦點都會主動還給 POSN，避免條碼掃描器的輸入跑進面板。
 */
public final class FloatingPanel {

    /** 由 PosnHook 提供：把會員代碼填進 POS，並把焦點還回去。 */
    public interface VipApplier {
        boolean apply(String vipId);
        void returnFocusToPos();
        /** 記住預約單號，等 F10 序號視窗開啟時自動填入。回傳是否記住成功。 */
        boolean armReservationRef(String orderNo);
        /** 把結帳代碼填進 POS 的 PLU 欄並送出，等同店員自己打代碼按 Enter。 */
        boolean applyCode(String code);
    }

    private static final int WIDTH = 340;
    private static final int GAP = 8;
    private static final int TRACK_INTERVAL_MS = 400;
    private static final int DRIFT_TOLERANCE = 4;
    private static final int MIN_QUERY_LENGTH = 3;
    /**
     * 品項名的 HTML 換行寬度。要同時容得下兩種模式裡最窄的那個：
     * 嵌入側欄是 325 扣掉左右內距 24、再扣掉可能出現的垂直捲軸約 15，剩約 286。
     * 寫成浮動視窗的寬度會讓嵌入時被切掉（不是換行，是直接看不到）。
     */
    private static final int WRAP_WIDTH = 280;
    /** 一般設定：會員建立輔助的開關放在這裡。 */
    private static final String PANEL_PATH = "config/posassist.properties";
    /** 面板記住的狀態（分隔位置）。跟設定分開放：設定視窗存檔是整份覆寫的。 */
    private static final String STATE_PATH = "config/panel.state";
    private static final String SPLIT_KEY = "verticalSplit";
    /** 上半（會員資料）預設佔的比例。剩下的都給結帳代碼。 */
    private static final double DEFAULT_SPLIT = 0.40;
    /** 拖到極端位置會有一邊完全看不到，夾在這個範圍裡。 */
    private static final double MIN_SPLIT = 0.15;
    private static final double MAX_SPLIT = 0.85;
    /** 拖曳時位置會連續變動，停手一秒才寫檔。 */
    private static final int SPLIT_SAVE_DELAY_MS = 1000;
    /** 再窄也不讓品項名擠成一個字一行。 */
    private static final int MIN_WRAP_WIDTH = 120;

    private static final Color BG = Style.PAGE;
    private static final Color MUTED = Style.MUTED;
    private static final Color ACCENT = Style.ACCENT;

    /** 嵌入模式時為 null —— 判斷模式一律用 embedded()，不要直接看這個。 */
    private final JDialog dialog;
    private final JPanel content;
    /**
     * 不能只靠 dialog == null 判斷模式：兩個建構子都是先 buildContent() 才指派 dialog，
     * 組版面的當下 dialog 還是 null，兩種模式會被當成同一種。這個旗標在建構子第一行就設好。
     */
    private final boolean embeddedMode;
    private final JTextField searchField = new JTextField();
    private final JButton codeValue = codeButton();
    private final JLabel nameValue = copyValue();
    private final JLabel phoneValue = copyValue();
    private final JLabel emailValue = copyValue();
    private final JLabel levelValue = value();
    private final JLabel lineValue = new Tag();
    private final JLabel status = new JLabel(" ");
    private final JLabel footer = new JLabel(" ");
    /** 查無會員時才出現的建立入口。平常隱藏，不佔版面。 */
    private final JButton createVip = createVipButton();

    private final CodePad codePad = new CodePad();
    private final JPanel reservationBox = new JPanel();
    private final JLabel reservationHeader = new JLabel();
    private final JPanel reservationList = new JPanel();

    private Component anchor;
    private Timer tracker;
    private Point lastPlaced;
    private boolean userMoved;
    private VipApplier applier;

    /** 用來丟掉慢回來的舊查詢結果。 */
    private int querySequence;
    /** POS 上目前的會員，輸入框清空時回復顯示。 */
    private String posVipId = "";
    /** 目前畫面上的結果是不是使用者自己查出來的。 */
    private boolean shownFromSearch;
    /**
     * 建立入口現在對應的電話。null 代表入口是收起來的。
     * 權限與模組是在背景執行緒確認過才設值的，按下去不會再碰資料庫。
     */
    private String createPhone;
    /** 預約區現在顯示的是哪個會員；側欄寬度變了要照新寬度重畫。 */
    private String reservationVip = "";
    /** 上次重畫預約區時的寬度，避免同一個寬度重畫兩次。 */
    private int lastWrapWidth = -1;
    /** 嵌入模式的上下分隔；浮動視窗模式是 null。 */
    private JSplitPane verticalSplit;
    /** 預約區外面那張卡片；預約區隱藏時整張要跟著收掉，不然會留一塊空白卡片。 */
    private JPanel reservationCardHolder;
    private Timer splitSaver;

    /** 浮動視窗模式：自己開一個置頂、不搶焦點的視窗，並追蹤 POSN 位置。 */
    public FloatingPanel(Window owner) {
        embeddedMode = false;
        content = buildContent();
        dialog = new JDialog(owner, "POS 輔助面板");
        dialog.setAlwaysOnTop(true);
        dialog.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);
        // 可以打字，但視窗永遠不主動搶焦點
        try {
            dialog.setAutoRequestFocus(false);
        } catch (Throwable ignored) {
            // 舊版 JDK 沒有這個方法就算了
        }

        dialog.setContentPane(content);
        dialog.pack();
        dialog.setSize(WIDTH, dialog.getPreferredSize().height);
    }

    /**
     * 嵌入模式：只做出內容，不開視窗、不追蹤位置。
     * 面板變成 EPB 主視窗的一部分，焦點行為跟其他欄位一致，
     * 少掉浮動視窗那一套搶焦點的顧慮。
     */
    public FloatingPanel() {
        embeddedMode = true;
        content = buildContent();
        dialog = null;
    }

    /** 嵌入模式用：交出內容讓 SidebarHost 掛進側欄。 */
    public JPanel getContent() {
        return content;
    }

    private boolean embedded() {
        return embeddedMode;
    }

    public void setVipApplier(VipApplier applier) {
        this.applier = applier;
    }

    // -- 版面 --------------------------------------------------------------

    /**
     * 上半是會員、下半是結帳代碼。
     *
     * 嵌入模式用可拖曳的分隔線分開，位置是固定比例（預設上半 40%）：
     * 九宮格的位置不會因為預約筆數多寡而上下跳，店員按代碼才有肌肉記憶。
     * 拖過的位置會記在 config/panel.state，下次開啟沿用。
     * 浮動模式維持 NORTH + CENTER，因為那個視窗是依內容 pack 高度的。
     */
    private JPanel buildContent() {
        JPanel root = new JPanel(new BorderLayout(0, 6));
        root.setBackground(BG);
        root.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        JPanel fields = new JPanel(new GridBagLayout());
        fields.setOpaque(false);
        addRow(fields, 0, "會員代碼", codeValue);
        addRow(fields, 1, "姓名", nameValue);
        addRow(fields, 2, "電話", phoneValue);
        addRow(fields, 3, "Email", emailValue);
        addRow(fields, 4, "等級", levelValue);
        addRow(fields, 5, "LINE會員", lineValue);
        fields.setAlignmentX(Component.LEFT_ALIGNMENT);

        status.setForeground(MUTED);
        status.setFont(Style.caption(status.getFont()));
        status.setAlignmentX(Component.LEFT_ALIGNMENT);

        // 會員資料與預約各自一張白卡片，浮在較深的底色上，層次才分得開。
        // 代碼區刻意不包卡片：那一區本來就是滿版的格子，包起來拿到的層次最少，
        // 付出的內距卻會讓 400px 寬的側欄一列從 5 個掉到 4 個。
        JPanel memberCard = new Card();
        memberCard.setLayout(new BoxLayout(memberCard, BoxLayout.Y_AXIS));
        memberCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel search = buildSearchArea();
        search.setAlignmentX(Component.LEFT_ALIGNMENT);
        memberCard.add(search);
        memberCard.add(Box.createVerticalStrut(8));
        memberCard.add(fields);
        createVip.setAlignmentX(Component.LEFT_ALIGNMENT);
        memberCard.add(createVip);

        JPanel reservationCard = new Card();
        reservationCard.setLayout(new BorderLayout());
        reservationCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        reservationCard.add(buildReservationBox(), BorderLayout.CENTER);
        reservationCardHolder = reservationCard;
        reservationCard.setVisible(false);

        JPanel upper = new JPanel();
        upper.setOpaque(false);
        upper.setLayout(new BoxLayout(upper, BoxLayout.Y_AXIS));
        upper.add(memberCard);
        upper.add(Box.createVerticalStrut(Style.GAP));
        upper.add(reservationCard);
        upper.add(Box.createVerticalStrut(4));
        upper.add(status);

        codePad.setCodeApplier(new CodePad.CodeApplier() {
            public boolean applyCode(String code) {
                return applier != null && applier.applyCode(code);
            }
        });
        codePad.setOnEditRequested(new Runnable() {
            public void run() {
                openCodeEditor();
            }
        });
        codePad.reload();

        if (embedded()) {
            // 上下各佔一半，分界永遠在正中間 —— 九宮格不會因為預約筆數多寡而上下跳。
            // 上半包捲動：會員加 3 筆預約可能超過一半，沒有捲動就會把下半擠掉。
            JPanel upperHolder = new JPanel(new BorderLayout());
            upperHolder.setOpaque(false);
            upperHolder.add(upper, BorderLayout.NORTH);   // 貼齊頂部，不要垂直置中

            JScrollPane upperScroll = new JScrollPane(upperHolder,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            upperScroll.setBorder(BorderFactory.createEmptyBorder());
            upperScroll.setOpaque(false);
            upperScroll.getViewport().setOpaque(false);
            upperScroll.getVerticalScrollBar().setUnitIncrement(16);

            // 上下用可拖曳的分隔線分開。位置固定成比例（不是跟著內容長度跑），
            // 九宮格才不會因為預約筆數多寡而上下跳 —— 原本用固定分半就是為了這個，
            // 換成可拖曳之後靠 setResizeWeight 保住同一個性質。
            upperScroll.setMinimumSize(new Dimension(0, 0));
            codePad.setMinimumSize(new Dimension(0, 0));

            verticalSplit = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT, upperScroll, codePad);
            // 自己畫分隔線：Metal 與 Aqua 都只留一片空白，看不出那裡可以拖，
            // 而門市的 Look and Feel 不見得跟這裡一樣 —— 跟代碼鍵同一個理由，
            // 外觀自己控制才每台一致。
            verticalSplit.setUI(new BasicSplitPaneUI() {
                public BasicSplitPaneDivider createDefaultDivider() {
                    return new Grip(this);
                }
            });
            verticalSplit.setBorder(BorderFactory.createEmptyBorder());
            verticalSplit.setOpaque(false);
            verticalSplit.setDividerSize(9);
            verticalSplit.setContinuousLayout(true);
            // 不開一鍵收合的小箭頭：觸控螢幕上太容易誤按，一按整塊不見，
            // 店員只會覺得畫面壞了
            verticalSplit.setOneTouchExpandable(false);
            installSplit(verticalSplit);
            root.add(verticalSplit, BorderLayout.CENTER);
        } else {
            // 浮動視窗是依內容 pack 高度的，套 50/50 只會平白撐高，維持原本的做法
            root.add(upper, BorderLayout.NORTH);
            root.add(codePad, BorderLayout.CENTER);
        }

        root.addComponentListener(new java.awt.event.ComponentAdapter() {
            public void componentResized(java.awt.event.ComponentEvent event) {
                Safe.guard("面板寬度變動", new Runnable() {
                    public void run() {
                        reflowReservations();
                    }
                });
            }
        });

        footer.setForeground(MUTED);
        footer.setFont(footer.getFont().deriveFont(10f));

        JLabel version = new JLabel("v" + Version.NAME);
        version.setForeground(MUTED);
        version.setFont(version.getFont().deriveFont(10f));
        version.setToolTipText("PosAssist " + Version.NAME);

        // 設定放在版本號旁邊：一直看得到，但不佔一整列。
        // 以前只有「預約還沒設定過」時才有入口，已經設定過的門市要改任何一項
        // 都得去改文字檔。
        JPanel corner = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        corner.setOpaque(false);
        corner.add(settingsLink());
        corner.add(version);

        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        bottom.setOpaque(false);
        bottom.add(footer, BorderLayout.WEST);
        bottom.add(corner, BorderLayout.EAST);
        root.add(bottom, BorderLayout.SOUTH);

        return root;
    }

    private void openCodeEditor() {
        Window owner = embedded()
            ? SwingUtilities.getWindowAncestor(content)
            : dialog;
        CodeEditor editor = new CodeEditor(owner);
        if (editor.showDialog()) {
            codePad.reload();
            relayout();
        }
    }

    private JPanel buildSearchArea() {
        JPanel area = new JPanel(new BorderLayout(0, 4));
        area.setOpaque(false);

        JLabel heading = new JLabel("會員查詢");
        heading.setForeground(ACCENT);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 13f));
        area.add(heading, BorderLayout.NORTH);

        searchField.setToolTipText("輸入電話或會員代碼後按 Enter 查詢。Esc 清空並把焦點還給 POS");
        searchField.setFont(searchField.getFont().deriveFont(13f));
        // 打字期間不查詢，只把上一筆結果清掉，避免看著舊資料誤判
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) {
                typed();
            }

            public void removeUpdate(DocumentEvent event) {
                typed();
            }

            public void changedUpdate(DocumentEvent event) {
                typed();
            }

            private void typed() {
                Safe.guard("輸入中", new Runnable() {
                    public void run() {
                        if (shownFromSearch) {
                            shownFromSearch = false;
                            clear("按 Enter 查詢");
                        }
                    }
                });
            }
        });
        searchField.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                Safe.guard("送出查詢", new Runnable() {
                    public void run() {
                        runSearch(searchField.getText());
                    }
                });
            }
        });
        searchField.addKeyListener(new KeyAdapter() {
            public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    Safe.guard("Esc 還原焦點", new Runnable() {
                        public void run() {
                            searchField.setText("");
                            showMember(posVipId);
                            returnFocus();
                        }
                    });
                }
            }
        });
        area.add(searchField, BorderLayout.CENTER);

        return area;
    }

    /** 預約區塊：沒資料時整塊隱藏，面板高度自動縮回。 */
    private JPanel buildReservationBox() {
        reservationBox.setOpaque(false);
        reservationBox.setLayout(new BoxLayout(reservationBox, BoxLayout.Y_AXIS));
        reservationBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        reservationBox.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        reservationBox.setVisible(false);

        reservationHeader.setForeground(ACCENT);
        reservationHeader.setFont(reservationHeader.getFont().deriveFont(Font.BOLD, 12f));
        reservationHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
        reservationBox.add(reservationHeader);
        reservationBox.add(Box.createVerticalStrut(4));

        reservationList.setOpaque(false);
        reservationList.setLayout(new BoxLayout(reservationList, BoxLayout.Y_AXIS));
        reservationList.setAlignmentX(Component.LEFT_ALIGNMENT);
        reservationBox.add(reservationList);

        return reservationBox;
    }

    private void addRow(JPanel parent, int row, String caption, JComponent field) {
        GridBagConstraints left = new GridBagConstraints();
        left.gridx = 0;
        left.gridy = row;
        left.anchor = GridBagConstraints.WEST;
        left.insets = new Insets(2, 0, 2, 8);

        JLabel label = new JLabel(caption);
        label.setForeground(MUTED);
        label.setFont(label.getFont().deriveFont(11f));
        parent.add(label, left);

        GridBagConstraints right = new GridBagConstraints();
        right.gridx = 1;
        right.gridy = row;
        right.weightx = 1;
        right.fill = GridBagConstraints.HORIZONTAL;
        right.anchor = GridBagConstraints.WEST;
        right.insets = new Insets(2, 0, 2, 0);
        parent.add(field, right);
    }

    private static JLabel value() {
        JLabel label = new JLabel("-");
        label.setFont(Style.value(label.getFont()));
        label.setForeground(Style.TEXT);
        return label;
    }

    /**
     * 點一下就把內容複製起來的欄位（姓名、電話、Email）。
     *
     * 只放進剪貼簿，不動 POS 的焦點 —— JLabel 本來就不可聚焦，
     * 店員複製完游標還在 POS 的輸入框上，不必再點一次回去。
     */
    private JLabel copyValue() {
        final JLabel label = value();
        label.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent event) {
                Safe.guard("複製會員資料", new Runnable() {
                    public void run() {
                        copy(label);
                    }
                });
            }
        });
        return label;
    }

    /** 把欄位內容放進系統剪貼簿。沒內容就當作沒點到，不打擾。 */
    private void copy(JLabel label) {
        String text = label.getText();
        if (text == null || text.length() == 0 || "-".equals(text)) {
            return;
        }
        boolean copied = copyToClipboard(text);
        say(copied ? "已複製 " + text : "複製不成功，請手動選取",
            copied ? ACCENT : Style.DANGER);
    }

    /** 會員代碼做成看起來像連結的按鈕，點一下帶入 POS。 */
    private JButton codeButton() {
        JButton button = new JButton("-");
        button.setFont(button.getFont().deriveFont(Font.BOLD, 13f));
        button.setForeground(ACCENT);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setFocusable(false);
        button.setMargin(new Insets(0, 0, 0, 0));
        button.setHorizontalAlignment(JButton.LEFT);
        button.setEnabled(false);
        button.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                Safe.guard("帶入 POS", new Runnable() {
                    public void run() {
                        applyToPos();
                    }
                });
            }
        });
        return button;
    }

    // -- 對外 API ----------------------------------------------------------

    /** 綁定要跟隨的元件（POSN 的 View 或 JInternalFrame），並開始追蹤。 */
    public void attachTo(Component component, String posNo) {
        this.anchor = component;
        this.userMoved = false;
        footer.setText(posNo == null || posNo.length() == 0 ? " " : "POS 機號 " + posNo);
        clear("輸入電話或會員代碼後按 Enter");
        if (!embedded()) {
            startTracking();     // 嵌入模式不需要追蹤 POSN 位置
        }
    }

    public void detach() {
        stopTracking();
        anchor = null;
        if (!embedded()) {
            dialog.setVisible(false);
        }
    }

    public void dispose() {
        detach();
        if (!embedded()) {
            dialog.dispose();
        }
    }

    /** POS 上的會員代碼變動時呼叫。空字串代表交易上沒有會員。 */
    public void showMember(String vipId) {
        String key = vipId == null ? "" : vipId.trim();
        posVipId = key;
        // 店員正在自己查東西時，不要用 POS 的值蓋掉他的結果
        if (searchField.getText().trim().length() >= MIN_QUERY_LENGTH) {
            return;
        }
        if (key.length() == 0) {
            clear("目前交易沒有會員");
            return;
        }
        lookupAsync(key, false);
    }

    // -- 查詢 --------------------------------------------------------------

    private void runSearch(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.length() == 0) {
            showMember(posVipId);
            return;
        }
        if (text.length() < MIN_QUERY_LENGTH) {
            clear("至少輸入 3 碼");
            return;
        }
        lookupAsync(text, true);
    }

    /**
     * 一次查詢的結果，外加「這個結果能不能談建立會員」。
     *
     * 兩件事綁在一起算，是因為判斷能不能建立要問權限，而問權限會碰資料庫。
     * 那必須跟查詢一樣待在背景執行緒 —— 放到 EDT 上就是整個 EPB 畫面卡住。
     */
    private static final class Search {
        final VipLookup.Outcome outcome;
        /** 非 null 代表可以建立，值是要帶進建立視窗的電話。 */
        final String createPhone;

        Search(VipLookup.Outcome outcome, String createPhone) {
            this.outcome = outcome;
            this.createPhone = createPhone;
        }
    }

    /** fromSearch 區分結果來自使用者輸入框，還是 POS 上的會員自動跟隨。 */
    private void lookupAsync(final String key, final boolean fromSearch) {
        say("查詢中...", MUTED);
        final int sequence = ++querySequence;
        new SwingWorker<Search, Void>() {
            protected Search doInBackground() {
                VipLookup.Outcome outcome = VipLookup.lookup(key);
                return new Search(outcome, createPhoneFor(key, outcome));
            }

            protected void done() {
                Safe.guard("顯示查詢結果", new Runnable() {
                    public void run() {
                        render();
                    }
                });
            }

            private void render() {
                if (sequence != querySequence) {
                    return;   // 有更新的查詢在跑了，這筆結果丟掉
                }
                Search search;
                try {
                    search = get();
                } catch (Throwable t) {
                    PosLog.warn("會員查詢失敗", t);
                    clear("查詢無法完成");
                    return;
                }
                if (search.outcome.message != null) {
                    shownFromSearch = false;
                    clear(search.outcome.message);
                    offerCreate(search.createPhone);
                    return;
                }
                shownFromSearch = fromSearch;
                show(search.outcome.results);
            }
        }.execute();
    }

    /**
     * 這次查詢要不要顯示建立入口。跑在背景執行緒。
     *
     * 只有「查詢確實跑完、而且資料庫裡真的沒有」才給入口。查詢失敗時最不該
     * 冒出建立會員 —— 那正是最可能建出重複會員的時機。
     *
     * @return 可以建立時回傳要預填的電話，否則 null
     */
    private static String createPhoneFor(String key, VipLookup.Outcome outcome) {
        if (outcome.status != VipLookup.Status.NOT_FOUND || !vipCreateEnabled()) {
            return null;
        }
        String phone = VipLookup.normalizePhone(key);
        if (phone == null) {
            return null;   // 查的是會員代碼不是電話，沒有足夠資料建立
        }
        String reason = VipCreator.unavailableReason();
        if (reason != null) {
            PosLog.info("不顯示建立會員入口：" + reason);
            return null;
        }
        return phone;
    }

    /** 設定在一次登入裡不會變，讀一次就好，不要每查一次會員就開一次檔。 */
    private static volatile Boolean vipCreateEnabled;

    private static boolean vipCreateEnabled() {
        Boolean cached = vipCreateEnabled;
        if (cached == null) {
            cached = Boolean.valueOf("true".equalsIgnoreCase(
                Home.value(PANEL_PATH, "enableVipCreate", "false")));
            vipCreateEnabled = cached;
        }
        return cached.booleanValue();
    }

    private void show(List<VipLookup.Vip> results) {
        VipLookup.Vip first = results.get(0);
        codeValue.setText(first.memberCode);
        codeValue.setEnabled(true);
        codeValue.setToolTipText("點一下把 " + first.memberCode + " 帶入 POS");
        codeValue.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setCopyValue(nameValue, first.name);
        setCopyValue(phoneValue, first.phone);
        setCopyValue(emailValue, first.email);
        setValue(levelValue, first.level);
        setValue(lineValue, lineStatus(first.remark));
        status.setText(results.size() > 1
            ? "符合 " + results.size() + " 筆，顯示第 1 筆"
            : " ");
        hideCreate();
        showReservations(first.memberCode);
    }

    // -- 預約 --------------------------------------------------------------

    private void showReservations(String vipId) {
        ReservationCache cache = ReservationCache.getInstance();
        if (!cache.isEnabled()) {
            // 還沒設定過帳密時，給一個直接開設定視窗的入口 ——
            // 門市人員不必知道要去改哪個文字檔
            if (!Home.file("config/reservation.properties").isFile()) {
                showSetupPrompt();
            } else {
                hideReservations();
            }
            return;
        }
        java.util.List<ReservationClient.Reservation> rows = cache.lookup(vipId);
        if (rows.isEmpty()) {
            hideReservations();
            return;
        }
        reservationVip = vipId == null ? "" : vipId;
        lastWrapWidth = wrapWidth();

        Date at = cache.updatedAt();
        reservationHeader.setText("近期預約"
            + (at == null ? "" : " · " + new SimpleDateFormat("HH:mm").format(at) + " 更新"));

        reservationList.removeAll();
        int limit = Math.min(rows.size(), cache.maxRows());
        for (int i = 0; i < limit; i++) {
            reservationList.add(entry(rows.get(i)));
        }
        if (rows.size() > limit) {
            JLabel more = new JLabel("另有 " + (rows.size() - limit) + " 筆");
            more.setForeground(MUTED);
            more.setFont(more.getFont().deriveFont(11f));
            more.setAlignmentX(Component.LEFT_ALIGNMENT);
            more.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
            reservationList.add(more);
        }

        showReservationBox(true);
        relayout();
    }

    /** 預約未設定：顯示一句提示與「設定」連結，取代整個預約區。 */
    private void showSetupPrompt() {
        reservationHeader.setText("預約功能未設定");
        reservationList.removeAll();

        JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel hint = new JLabel("填入預約系統帳密就會顯示會員的近期預約　");
        hint.setForeground(MUTED);
        hint.setFont(hint.getFont().deriveFont(11f));
        row.add(hint);

        JButton link = new JButton("設定");
        link.setFont(link.getFont().deriveFont(11f));
        link.setForeground(ACCENT);
        link.setBorderPainted(false);
        link.setContentAreaFilled(false);
        link.setFocusPainted(false);
        link.setFocusable(false);
        link.setMargin(new Insets(0, 0, 0, 0));
        link.setBorder(BorderFactory.createEmptyBorder());
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                Safe.guard("開啟設定", new Runnable() {
                    public void run() {
                        openSettings();
                    }
                });
            }
        });
        row.add(link);

        reservationList.add(row);
        showReservationBox(true);
        relayout();
    }

    /** 設定入口。做成連結的樣子，跟結帳代碼那個「編輯」同一個語彙。 */
    private JButton settingsLink() {
        JButton button = new JButton("設定");
        button.setFont(Style.tiny(button.getFont()));
        button.setForeground(MUTED);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        // 跟面板上其他按鈕一樣不可聚焦：條碼掃描器的輸入必須留在 POS
        button.setFocusable(false);
        button.setMargin(new Insets(0, 0, 0, 0));
        button.setBorder(BorderFactory.createEmptyBorder());
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setToolTipText("預約帳密、面板位置、會員建立與表單欄位");
        button.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                Safe.guard("開啟設定", new Runnable() {
                    public void run() {
                        openSettings();
                    }
                });
            }
        });
        return button;
    }

    private void openSettings() {
        Window owner = embedded()
            ? SwingUtilities.getWindowAncestor(content)
            : dialog;
        if (new SettingsDialog(owner).showDialog()) {
            status.setText("設定已儲存，重開 EPB 後生效");
        }
    }

    /**
     * 有底色的小標籤。目前用在「LINE會員」：有綁定才浮出藥丸，
     * 沒有的話就是一個普通的「-」，不要讓空狀態也長出一塊色塊。
     */
    private static final class Tag extends JLabel {

        Tag() {
            setText("-");
            setForeground(Style.ACCENT);
            setBorder(BorderFactory.createEmptyBorder(1, 7, 1, 7));
        }

        private boolean filled() {
            String text = getText();
            return text != null && text.length() > 0 && !"-".equals(text);
        }

        public Insets getInsets() {
            // 沒內容時不要留藥丸的內距，否則「-」會比其他欄位往右一截
            return filled() ? super.getInsets() : new Insets(1, 0, 1, 0);
        }

        protected void paintComponent(Graphics g) {
            if (filled()) {
                Graphics2D g2 = (Graphics2D) g.create();
                Style.antialias(g2);
                // 只包住文字：欄位是用 GridBag 拉滿寬度的，
                // 直接用 getWidth() 會畫成一條橫跨整列的色帶
                int pill = Math.min(getPreferredSize().width, getWidth()) - 1;
                g2.setColor(Style.TAB_ON);
                g2.fillRoundRect(0, 0, pill, getHeight() - 1,
                    getHeight(), getHeight());
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    /** 白色圓角卡片。自繪而不是用 L&F 的邊框，各家厚度與顏色差太多。 */
    private static final class Card extends JPanel {

        Card() {
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(
                Style.PAD, Style.PAD, Style.PAD, Style.PAD));
        }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            Style.antialias(g2);
            int w = getWidth();
            int h = getHeight();
            g2.setColor(Style.SURFACE);
            g2.fillRoundRect(0, 0, w - 1, h - 1, Style.RADIUS_CARD, Style.RADIUS_CARD);
            g2.setColor(Style.LINE);
            g2.drawRoundRect(0, 0, w - 1, h - 1, Style.RADIUS_CARD, Style.RADIUS_CARD);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** 分隔線：一條細線加中間一小截握把，讓人看得出可以上下拖。 */
    private static final class Grip extends BasicSplitPaneDivider {
        private static final int HANDLE_WIDTH = 28;

        Grip(BasicSplitPaneUI ui) {
            super(ui);
        }

        public void paint(Graphics g) {
            int w = getWidth();
            int y = getHeight() / 2;
            g.setColor(BG);
            g.fillRect(0, 0, w, getHeight());
            g.setColor(new Color(0xD8, 0xDC, 0xE3));
            g.drawLine(0, y, w, y);
            // 中間畫兩條短線當握把，比一堆小點在低解析度螢幕上清楚
            g.setColor(new Color(0xA8, 0xAE, 0xB8));
            int x = (w - HANDLE_WIDTH) / 2;
            g.drawLine(x, y - 2, x + HANDLE_WIDTH, y - 2);
            g.drawLine(x, y + 2, x + HANDLE_WIDTH, y + 2);
        }
    }

    /**
     * 套用記住的分隔位置，並在使用者拖過之後存回去。
     *
     * 位置存成比例而不是像素：各店螢幕高度不一樣，存像素換一台機器就跑掉。
     * 第一次拿到高度時才套得上（setDividerLocation(double) 要有高度才算得出來），
     * 所以等第一次排版完成再套一次。
     */
    private void installSplit(final JSplitPane split) {
        final double ratio = savedSplit();
        split.setResizeWeight(ratio);
        split.addComponentListener(new java.awt.event.ComponentAdapter() {
            private boolean applied;

            public void componentResized(java.awt.event.ComponentEvent event) {
                if (applied || split.getHeight() <= 0) {
                    return;
                }
                applied = true;
                Safe.guard("套用分隔位置", new Runnable() {
                    public void run() {
                        split.setDividerLocation(ratio);
                    }
                });
            }
        });

        splitSaver = new Timer(SPLIT_SAVE_DELAY_MS, new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                Safe.guard("記住分隔位置", new Runnable() {
                    public void run() {
                        saveSplit();
                    }
                });
            }
        });
        splitSaver.setRepeats(false);

        split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY,
            new java.beans.PropertyChangeListener() {
                public void propertyChange(java.beans.PropertyChangeEvent event) {
                    // 拖曳過程每動一格就會觸發，停手一秒才真的寫檔
                    splitSaver.restart();
                }
            });
    }

    /** 讀回上次記住的比例；沒有或壞掉都回預設值。 */
    private static double savedSplit() {
        String text = Home.value(STATE_PATH, SPLIT_KEY, "");
        if (text.length() == 0) {
            return DEFAULT_SPLIT;
        }
        try {
            return clampSplit(Double.parseDouble(text));
        } catch (Throwable t) {
            return DEFAULT_SPLIT;
        }
    }

    private static double clampSplit(double value) {
        if (value < MIN_SPLIT) {
            return MIN_SPLIT;
        }
        return value > MAX_SPLIT ? MAX_SPLIT : value;
    }

    private void saveSplit() {
        if (verticalSplit == null) {
            return;
        }
        int span = verticalSplit.getHeight() - verticalSplit.getDividerSize();
        if (span <= 0) {
            return;
        }
        double ratio = clampSplit(verticalSplit.getDividerLocation() / (double) span);
        // 只留兩位小數：這是給人看的狀態檔，不需要 17 位浮點數尾巴
        String body = "# PosAssist 面板狀態（程式自己寫的，刪掉就回預設）\n"
            + SPLIT_KEY + "=" + (Math.round(ratio * 100) / 100.0) + "\n";
        Home.write(STATE_PATH, body, false);
    }

    /** 狀態列訊息。成功用主色、失敗用紅色，其餘維持次要文字色。 */
    private void say(String text, Color color) {
        status.setForeground(color);
        status.setText(text);
    }

    /** 預約區與外面那張卡片一起顯示或隱藏，免得留下一張空白卡片。 */
    private void showReservationBox(boolean visible) {
        reservationBox.setVisible(visible);
        if (reservationCardHolder != null) {
            reservationCardHolder.setVisible(visible);
        }
    }

    /** 側欄拖寬拖窄後，把預約區照新寬度重排一次。沒有預約在顯示就什麼都不做。 */
    private void reflowReservations() {
        if (reservationVip.length() == 0 || !reservationBox.isVisible()) {
            return;
        }
        if (wrapWidth() == lastWrapWidth) {
            return;
        }
        showReservations(reservationVip);
    }

    private void hideReservations() {
        reservationVip = "";
        if (!reservationBox.isVisible()) {
            return;
        }
        showReservationBox(false);
        reservationList.removeAll();
        relayout();
    }

    /** 一筆預約：產品名稱一行，狀態／登記日期／單號一行。 */
    private JPanel entry(ReservationClient.Reservation row) {
        JPanel box = new JPanel();
        box.setOpaque(false);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setAlignmentX(Component.LEFT_ALIGNMENT);
        box.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));

        // 品項名用 HTML 換行而不是截斷 —— 店員要看得出是哪一台
        JLabel name = new JLabel(wrap(row.productName));
        name.setFont(name.getFont().deriveFont(Font.BOLD, 12.5f));
        name.setAlignmentX(Component.LEFT_ALIGNMENT);
        name.setToolTipText(row.productName.length() == 0 ? null : row.productName);
        box.add(name);

        StringBuilder meta = new StringBuilder();
        if (row.status.length() != 0) {
            meta.append(row.status);
        }
        if (row.registeredAt.length() != 0) {
            if (meta.length() != 0) {
                meta.append(" · ");
            }
            meta.append(datePart(row.registeredAt));
        }
        if (meta.length() != 0) {
            JLabel detail = new JLabel(clip(meta.toString(), 26));
            detail.setForeground(MUTED);
            detail.setFont(detail.getFont().deriveFont(11f));
            detail.setAlignmentX(Component.LEFT_ALIGNMENT);
            box.add(detail);
        }

        // 單號獨立一行、不截斷，而且可以點 —— 點了會記住，按 F10 自動填入
        if (row.orderNo.length() != 0) {
            final String orderNo = row.orderNo;
            JButton order = new JButton(orderNo);
            order.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            order.setForeground(ACCENT);
            order.setBorderPainted(false);
            order.setContentAreaFilled(false);
            order.setFocusPainted(false);
            order.setFocusable(false);
            order.setMargin(new Insets(0, 0, 0, 0));
            order.setBorder(BorderFactory.createEmptyBorder());   // 去掉預設內距，跟上面切齊
            order.setHorizontalAlignment(JButton.LEFT);
            order.setAlignmentX(Component.LEFT_ALIGNMENT);
            order.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            order.setToolTipText("點一下記住這個單號，按 F10 開序號視窗時會自動填入預約單號欄");
            order.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent event) {
                    Safe.guard("記住預約單號", new Runnable() {
                        public void run() {
                            armOrder(orderNo);
                        }
                    });
                }
            });
            box.add(order);
        }

        return box;
    }

    private void armOrder(String orderNo) {
        copyToClipboard(orderNo);
        boolean armed = applier != null && applier.armReservationRef(orderNo);
        status.setText(armed
            ? "已記住單號，按 F10 會自動填入"
            : "單號已複製，可在 F10 視窗貼上");
    }

    /** 複製到剪貼簿。失敗回 false —— 剪貼簿被別的程式鎖住在 Windows 上是常態。 */
    static boolean copyToClipboard(final String text) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null);
            return true;
        } catch (Throwable t) {
            PosLog.warn("複製到剪貼簿失敗", t);
            return false;
        }
    }

    /**
     * 品項名換行用的寬度。側欄可以拖寬拖窄，所以照當下的實際寬度算，
     * 不能寫死 —— 寫死的話拖窄會被切掉、拖寬則右邊空一大片。
     * 還沒排版過（寬度 0）就先用原本的預設值。
     */
    private int wrapWidth() {
        int width = content == null ? 0 : content.getWidth();
        if (width <= 0) {
            return WRAP_WIDTH;
        }
        // 扣掉左右內距 24 與可能出現的垂直捲軸約 15
        return Math.max(MIN_WRAP_WIDTH, width - 24 - 15);
    }

    /** 用 HTML 讓長品項名換行。文字要跳脫，避免品項名裡的符號被當成標籤。 */
    private String wrap(String text) {
        if (text == null || text.length() == 0) {
            return "";
        }
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '&') {
                escaped.append("&amp;");
            } else if (c == '<') {
                escaped.append("&lt;");
            } else if (c == '>') {
                escaped.append("&gt;");
            } else {
                escaped.append(c);
            }
        }
        // 用 table 的 width 屬性而不是 CSS width：Swing 的 HTML 算 preferred size 時
        // 會忽略 style='width:...'（實測仍回傳整行未折的寬度），只有 table width 會生效。
        return "<html><table width=" + wrapWidth() + " cellpadding=0 cellspacing=0>"
            + "<tr><td>" + escaped + "</td></tr></table></html>";
    }

    /** 只留日期，時間對店員判斷沒幫助又佔空間。 */
    private static String datePart(String text) {
        int space = text.indexOf(' ');
        return space > 0 ? text.substring(0, space) : text;
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    /** 區塊顯示／隱藏之後重算版面。嵌入模式由側欄決定寬高，只需重新驗證。 */
    private void relayout() {
        if (embedded()) {
            content.revalidate();
            content.repaint();
            return;
        }
        dialog.pack();
        dialog.setSize(WIDTH, dialog.getHeight());
        dialog.validate();
    }

    /**
     * 備註4 拿來記 LINE 綁定，內容像「已有綁定STUDIO A LINE帳號」，
     * 一律照原文顯示只是佔位置，所以認得出來的就換成一句話。
     * 認不出來的照原文顯示 —— 那欄是自由文字，硬套「已綁定」會把不是綁定的內容講錯。
     */
    private static String lineStatus(String remark) {
        String text = remark == null ? "" : remark.trim();
        if (text.length() == 0) {
            return null;
        }
        return text.indexOf("綁定") >= 0 && text.indexOf("已") >= 0
            ? "已綁定 LINE" : text;
    }

    private static void setValue(JLabel label, String text) {
        boolean empty = text == null || text.length() == 0;
        label.setText(empty ? "-" : text);
        label.setToolTipText(empty ? null : text);
    }

    /** 可複製的欄位：有內容才給手指游標，沒內容點下去也不會有事。 */
    private static void setCopyValue(JLabel label, String text) {
        setValue(label, text);
        boolean empty = text == null || text.length() == 0;
        label.setCursor(Cursor.getPredefinedCursor(
            empty ? Cursor.DEFAULT_CURSOR : Cursor.HAND_CURSOR));
        label.setToolTipText(empty ? null : text + "（點一下複製）");
    }

    private void clear(String message) {
        codeValue.setText("-");
        codeValue.setEnabled(false);
        codeValue.setToolTipText(null);
        codeValue.setCursor(Cursor.getDefaultCursor());
        setCopyValue(nameValue, null);
        setCopyValue(phoneValue, null);
        setCopyValue(emailValue, null);
        setValue(levelValue, null);
        setValue(lineValue, null);
        say(message == null ? " " : message, MUTED);
        // 預設收起來。要顯示的話，由查詢結果在 clear() 之後自己叫 offerCreate()
        hideCreate();
        hideReservations();
    }

    // -- 建立會員輔助 ------------------------------------------------------

    /**
     * 建立入口。整個功能對 EPB 的唯一影響，就是這顆按鈕會去開原生 POSVIP；
     * PosAssist 自己不寫入任何會員資料。
     */
    private JButton createVipButton() {
        JButton button = new JButton("建立會員");
        button.setFont(Style.caption(button.getFont()));
        // 跟面板上其他按鈕一樣不可聚焦：條碼掃描器的輸入必須留在 POS
        button.setFocusable(false);
        button.setVisible(false);
        button.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                Safe.guard("建立會員入口", new Runnable() {
                    public void run() {
                        openVipCreator();
                    }
                });
            }
        });
        return button;
    }

    /** phone 為 null 就收起來。 */
    private void offerCreate(String phone) {
        if (phone == null) {
            hideCreate();
            return;
        }
        createPhone = phone;
        createVip.setToolTipText("開啟 EPB 原生的會員建立表單，電話會先幫你填好");
        createVip.setVisible(true);
        relayout();
    }

    private void hideCreate() {
        if (!createVip.isVisible() && createPhone == null) {
            return;
        }
        createPhone = null;
        createVip.setVisible(false);
        relayout();
    }

    /**
     * 開啟原生的會員建立表單。
     *
     * 表單是 modal 的，這個呼叫會擋到店員送出或取消為止 —— 這裡本來就在 EDT 上，
     * 而且原生表單自己會處理它那段的等待，不需要我們再包一層背景執行緒。
     *
     * 送出成功會直接拿到建立好的會員代碼，不必再查一次資料庫。
     */
    private void openVipCreator() {
        String phone = createPhone;
        if (phone == null) {
            return;
        }
        say("開啟會員建立表單…", MUTED);
        VipCreator.Result result = VipCreator.create(phone);

        if (result.cancelled()) {
            say("已取消，沒有建立任何資料", MUTED);
            return;
        }
        if (result.problem != null) {
            say(result.problem, Style.DANGER);
            return;
        }

        // 建好了就當作查到這個人：顯示出來，並用店員手打代碼的同一條路徑帶入 POS
        hideCreate();
        searchField.setText("");
        shownFromSearch = false;
        say("會員已建立", ACCENT);
        lookupAsync(result.vipId, false);
    }

    // -- 帶入 POS ----------------------------------------------------------

    private void applyToPos() {
        String code = codeValue.getText();
        if (code == null || code.length() == 0 || "-".equals(code)) {
            return;
        }
        if (applier == null) {
            say("這個畫面不支援帶入", Style.DANGER);
            return;
        }
        // 帶入之後 POSN 會在焦點離開會員欄時同步做驗證與載入（跑在 EDT 上），
        // 那段時間整個畫面都會停住。先把「帶入中」逼著畫出來，
        // 店員才知道是在等，而不是以為 POS 當掉了。
        say("帶入中…", MUTED);
        status.paintImmediately(0, 0, status.getWidth(), status.getHeight());

        long startedAt = System.currentTimeMillis();
        boolean ok = applier.apply(code);
        long ms = System.currentTimeMillis() - startedAt;
        PosLog.info("帶入會員代碼 " + (ok ? "完成" : "被拒絕") + "，耗時 " + ms
            + "ms（含 POSN 自己的驗證與載入）");

        say(ok ? "已帶入 POS：" + code : "POS 目前不接受帶入",
            ok ? ACCENT : Style.DANGER);
        if (ok) {
            searchField.setText("");
        }
        returnFocus();
    }

    private void returnFocus() {
        if (applier != null) {
            applier.returnFocusToPos();
        }
    }

    // -- 位置追蹤 ----------------------------------------------------------

    private void startTracking() {
        stopTracking();
        tracker = new Timer(TRACK_INTERVAL_MS, new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                Safe.guard("追蹤 POSN 位置", new Runnable() {
                    public void run() {
                        track();
                    }
                });
            }
        });
        tracker.setRepeats(true);
        tracker.start();
        track();
    }

    private void stopTracking() {
        if (tracker != null) {
            tracker.stop();
            tracker = null;
        }
    }

    private void track() {
        if (embedded()) {
            return;       // 嵌入模式沒有視窗可以追蹤
        }
        Component target = anchor;
        if (target == null || !target.isShowing()) {
            dialog.setVisible(false);
            return;
        }

        if (!dialog.isVisible()) {
            reposition(target);
            dialog.setVisible(true);
            return;
        }

        // 使用者自己拖過就不再自動貼齊
        if (!userMoved && lastPlaced != null
            && distance(dialog.getLocation(), lastPlaced) > DRIFT_TOLERANCE) {
            userMoved = true;
            PosLog.info("使用者移動了面板，停止自動貼齊");
        }
        if (!userMoved) {
            reposition(target);
        }
    }

    private void reposition(Component target) {
        Rectangle anchorBounds;
        try {
            Point origin = target.getLocationOnScreen();
            anchorBounds = new Rectangle(origin, target.getSize());
        } catch (Throwable t) {
            return;   // 元件此刻不在畫面上
        }

        Rectangle screen = screenBoundsFor(anchorBounds);
        Dimension size = dialog.getSize();

        int x = anchorBounds.x + anchorBounds.width + GAP;
        if (x + size.width > screen.x + screen.width) {
            x = anchorBounds.x - size.width - GAP;      // 右邊放不下就靠左
        }
        x = clamp(x, screen.x, screen.x + screen.width - size.width);

        int y = clamp(anchorBounds.y, screen.y, screen.y + screen.height - size.height);

        Point placed = new Point(x, y);
        if (!placed.equals(dialog.getLocation())) {
            dialog.setLocation(placed);
        }
        lastPlaced = placed;
    }

    private static Rectangle screenBoundsFor(Rectangle anchorBounds) {
        Rectangle best = null;
        try {
            java.awt.GraphicsDevice[] devices =
                GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
            int bestArea = -1;
            for (int i = 0; i < devices.length; i++) {
                Rectangle bounds = devices[i].getDefaultConfiguration().getBounds();
                Rectangle overlap = bounds.intersection(anchorBounds);
                int area = overlap.isEmpty() ? 0 : overlap.width * overlap.height;
                if (area > bestArea) {
                    bestArea = area;
                    best = bounds;
                }
            }
        } catch (Throwable ignored) {
            // 落到下面的預設值
        }
        if (best == null) {
            Dimension size = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
            best = new Rectangle(0, 0, size.width, size.height);
        }
        return best;
    }

    private static int clamp(int value, int min, int max) {
        if (max < min) {
            return min;
        }
        return value < min ? min : (value > max ? max : value);
    }

    private static int distance(Point a, Point b) {
        return Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
    }

    /** 給呼叫端確認一定在 EDT 上動 UI。 */
    public static void onEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            Safe.guard("EDT 動作", action);
        } else {
            final Runnable wrapped = action;
            SwingUtilities.invokeLater(new Runnable() {
                public void run() {
                    Safe.guard("EDT 動作", wrapped);
                }
            });
        }
    }
}
