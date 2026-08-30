package com.posassist;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridLayout;
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

    private static final Color BG = new Color(0xF7, 0xF7, 0xF9);
    private static final Color MUTED = new Color(0x66, 0x66, 0x70);
    private static final Color ACCENT = new Color(0x1D, 0x4E, 0x89);

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
    private final JLabel lineValue = value();
    private final JLabel status = new JLabel(" ");
    private final JLabel footer = new JLabel(" ");

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
     * 嵌入模式用 GridLayout(2,1) 讓兩半各佔一半：分界固定在正中間，
     * 九宮格的位置不會因為預約筆數多寡而上下跳，店員按代碼才有肌肉記憶。
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
        status.setFont(status.getFont().deriveFont(11f));
        status.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel upper = new JPanel();
        upper.setOpaque(false);
        upper.setLayout(new BoxLayout(upper, BoxLayout.Y_AXIS));
        JPanel search = buildSearchArea();
        search.setAlignmentX(Component.LEFT_ALIGNMENT);
        upper.add(search);
        upper.add(Box.createVerticalStrut(8));
        upper.add(fields);
        upper.add(buildReservationBox());
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

            JPanel halves = new JPanel(new GridLayout(2, 1, 0, 6));
            halves.setOpaque(false);
            halves.add(upperScroll);
            halves.add(codePad);
            root.add(halves, BorderLayout.CENTER);
        } else {
            // 浮動視窗是依內容 pack 高度的，套 50/50 只會平白撐高，維持原本的做法
            root.add(upper, BorderLayout.NORTH);
            root.add(codePad, BorderLayout.CENTER);
        }

        footer.setForeground(MUTED);
        footer.setFont(footer.getFont().deriveFont(10f));
        root.add(footer, BorderLayout.SOUTH);

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
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
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
        status.setText(copyToClipboard(text)
            ? "已複製 " + text
            : "複製不成功，請手動選取");
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

    /** fromSearch 區分結果來自使用者輸入框，還是 POS 上的會員自動跟隨。 */
    private void lookupAsync(final String key, final boolean fromSearch) {
        status.setText("查詢中...");
        final int sequence = ++querySequence;
        new SwingWorker<VipLookup.Outcome, Void>() {
            protected VipLookup.Outcome doInBackground() {
                return VipLookup.lookup(key);
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
                VipLookup.Outcome outcome;
                try {
                    outcome = get();
                } catch (Throwable t) {
                    PosLog.warn("會員查詢失敗", t);
                    clear("查詢無法完成");
                    return;
                }
                if (outcome.message != null) {
                    shownFromSearch = false;
                    clear(outcome.message);
                    return;
                }
                shownFromSearch = fromSearch;
                show(outcome.results);
            }
        }.execute();
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

        reservationBox.setVisible(true);
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
        reservationBox.setVisible(true);
        relayout();
    }

    private void openSettings() {
        Window owner = embedded()
            ? SwingUtilities.getWindowAncestor(content)
            : dialog;
        if (new SettingsDialog(owner).showDialog()) {
            status.setText("設定已儲存，重開 EPB 後生效");
        }
    }

    private void hideReservations() {
        if (!reservationBox.isVisible()) {
            return;
        }
        reservationBox.setVisible(false);
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
    private static boolean copyToClipboard(final String text) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null);
            return true;
        } catch (Throwable t) {
            PosLog.warn("複製到剪貼簿失敗", t);
            return false;
        }
    }

    /** 用 HTML 讓長品項名換行。文字要跳脫，避免品項名裡的符號被當成標籤。 */
    private static String wrap(String text) {
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
        return "<html><table width=" + WRAP_WIDTH + " cellpadding=0 cellspacing=0>"
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
        status.setText(message == null ? " " : message);
        hideReservations();
    }

    // -- 帶入 POS ----------------------------------------------------------

    private void applyToPos() {
        String code = codeValue.getText();
        if (code == null || code.length() == 0 || "-".equals(code)) {
            return;
        }
        if (applier == null) {
            status.setText("這個畫面不支援帶入");
            return;
        }
        boolean ok = applier.apply(code);
        status.setText(ok ? "已帶入 POS：" + code : "POS 目前不接受帶入");
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
