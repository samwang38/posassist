package com.posassist;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Properties;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

/**
 * 首次設定視窗。門市人員不必碰文字檔就能設定預約系統帳密。
 *
 * 兩種開啟方式：
 * 1. 安裝完由安裝器直接叫起（`java -cp posassist.jar com.posassist.SettingsDialog`），
 *    所以不能依賴 EPB 已經啟動 —— 這個類別完全不碰 EPB 的任何東西。
 * 2. 面板上預約區未設定時的「設定」入口。
 *
 * 已存在的密碼不回填到畫面上，只顯示「已設定」。要換就重打，避免密碼在畫面上被看到。
 */
public final class SettingsDialog {

    private static final String RESERVATION_PATH = "config/reservation.properties";
    private static final String PANEL_PATH = "config/posassist.properties";
    private static final String DEFAULT_BASE_URL = "https://www.studioa.com.tw/backend/api/";

    private static final Color OK_COLOR = new Color(0x1F, 0x6F, 0x4A);
    private static final Color BAD_COLOR = new Color(0x9B, 0x2C, 0x2C);
    private static final Color MUTED = new Color(0x66, 0x66, 0x70);

    private final JDialog dialog;
    private final JTextField baseUrlField = new JTextField(DEFAULT_BASE_URL);
    private final JTextField userNameField = new JTextField();
    private final JPasswordField passwordField = new JPasswordField();
    private final JTextField windowDaysField = new JTextField("30");
    private final JRadioButton embeddedRadio = new JRadioButton("嵌進 EPB 左側欄");
    private final JRadioButton floatingRadio = new JRadioButton("獨立浮動視窗");
    private final JCheckBox autoUpdateBox = new JCheckBox("開啟時自動更新到最新版");
    private final JLabel passwordHint = new JLabel(" ");
    private final JLabel message = new JLabel(" ");
    private final JButton testButton = new JButton("測試連線");

    private boolean hadPassword;
    private boolean saved;

    public SettingsDialog(Window owner) {
        dialog = new JDialog(owner, "PosAssist 設定", JDialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        loadExisting();
        dialog.setContentPane(buildContent());
        dialog.pack();
        dialog.setSize(Math.max(520, dialog.getWidth()), dialog.getHeight());
        dialog.setLocationRelativeTo(owner);
    }

    /** 回傳是否有存檔。 */
    public boolean showDialog() {
        dialog.setVisible(true);
        return saved;
    }

    /** 安裝器用：不需要 EPB 就能單獨叫起設定視窗。 */
    public static void main(String[] args) {
        try {
            javax.swing.UIManager.setLookAndFeel(
                javax.swing.UIManager.getSystemLookAndFeelClassName());
        } catch (Throwable ignored) {
            // 用預設外觀就好
        }
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                boolean ok = new SettingsDialog(null).showDialog();
                System.out.println(ok ? "設定已儲存" : "未變更設定");
                System.exit(0);
            }
        });
    }

    // -- 讀既有設定 --------------------------------------------------------

    private void loadExisting() {
        Properties reservation = Home.props(RESERVATION_PATH);
        if (reservation != null) {
            String base = reservation.getProperty("baseUrl", "").trim();
            if (base.length() != 0) {
                baseUrlField.setText(base);
            }
            userNameField.setText(reservation.getProperty("userName", "").trim());
            hadPassword = reservation.getProperty("password", "").length() != 0;
            String days = reservation.getProperty("windowDays", "").trim();
            if (days.length() != 0) {
                windowDaysField.setText(days);
            }
        }
        Properties panel = Home.props(PANEL_PATH);
        boolean floating = panel != null
            && "floating".equalsIgnoreCase(panel.getProperty("panelMode", "").trim());
        embeddedRadio.setSelected(!floating);
        floatingRadio.setSelected(floating);
        boolean autoUpdate = panel == null
            || !"false".equalsIgnoreCase(panel.getProperty("autoUpdate", "true").trim());
        autoUpdateBox.setSelected(autoUpdate);
    }

    // -- 版面 --------------------------------------------------------------

    private JPanel buildContent() {
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        JLabel heading = new JLabel("預約系統");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 14f));
        root.add(heading, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        addField(form, row++, "網址", baseUrlField, null);
        addField(form, row++, "帳號", userNameField, "這家門市自己的預約系統帳號");
        passwordHint.setText(hadPassword ? "已設定，留空表示不變更" : "尚未設定");
        passwordHint.setForeground(MUTED);
        passwordHint.setFont(passwordHint.getFont().deriveFont(11f));
        addField(form, row++, "密碼", passwordField, null);
        addHint(form, row++, passwordHint);
        addField(form, row++, "顯示天數", windowDaysField,
            "面板顯示最近幾天內登記的預約。士林實測：30 天約 4 筆、90 天約 44 筆");

        JLabel panelHeading = new JLabel("面板");
        panelHeading.setFont(panelHeading.getFont().deriveFont(Font.BOLD, 14f));
        addSection(form, row++, panelHeading);

        ButtonGroup group = new ButtonGroup();
        group.add(embeddedRadio);
        group.add(floatingRadio);
        JPanel modes = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        modes.add(embeddedRadio);
        modes.add(floatingRadio);
        addRow(form, row++, "顯示位置", modes);
        addRow(form, row++, "更新", autoUpdateBox);

        root.add(form, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(0, 8));
        message.setFont(message.getFont().deriveFont(12f));
        south.add(message, BorderLayout.NORTH);

        JPanel buttons = new JPanel(new BorderLayout());
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        testButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                Safe.guard("測試連線", new Runnable() {
                    public void run() {
                        testConnection();
                    }
                });
            }
        });
        left.add(testButton);
        buttons.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(button("取消", new Runnable() {
            public void run() {
                dialog.dispose();
            }
        }));
        right.add(button("儲存", new Runnable() {
            public void run() {
                save();
            }
        }));
        buttons.add(right, BorderLayout.EAST);
        south.add(buttons, BorderLayout.SOUTH);
        root.add(south, BorderLayout.SOUTH);

        return root;
    }

    private void addField(JPanel form, int row, String label, JTextField field, String hint) {
        field.setPreferredSize(new Dimension(300, 26));
        addRow(form, row, label, field);
        if (hint != null) {
            JLabel hintLabel = new JLabel(hint);
            hintLabel.setForeground(MUTED);
            hintLabel.setFont(hintLabel.getFont().deriveFont(11f));
            addHint(form, row, hintLabel);
        }
    }

    private void addRow(JPanel form, int row, String label, java.awt.Component field) {
        GridBagConstraints left = new GridBagConstraints();
        left.gridx = 0;
        left.gridy = row * 2;
        left.anchor = GridBagConstraints.WEST;
        left.insets = new Insets(4, 0, 4, 10);
        form.add(new JLabel(label), left);

        GridBagConstraints right = new GridBagConstraints();
        right.gridx = 1;
        right.gridy = row * 2;
        right.weightx = 1;
        right.fill = GridBagConstraints.HORIZONTAL;
        right.insets = new Insets(4, 0, 4, 0);
        form.add(field, right);
    }

    private void addHint(JPanel form, int row, JLabel hint) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 1;
        c.gridy = row * 2 + 1;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 6, 0);
        form.add(hint, c);
    }

    private void addSection(JPanel form, int row, JLabel heading) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row * 2;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(14, 0, 4, 0);
        form.add(heading, c);
    }

    private JButton button(String text, final Runnable action) {
        JButton button = new JButton(text);
        button.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                Safe.guard("設定：" + text, action);
            }
        });
        return button;
    }

    // -- 測試連線 ----------------------------------------------------------

    /**
     * 實際登入一次，讓設定的人當場知道帳密對不對，而不是存完才發現沒資料。
     * 只回報成敗與原因 —— 帳密不進 log、不顯示在訊息裡。
     */
    private void testConnection() {
        final String baseUrl = baseUrlField.getText().trim();
        final String userName = userNameField.getText().trim();
        final String password = currentPassword();

        if (baseUrl.length() == 0 || userName.length() == 0 || password.length() == 0) {
            say("網址、帳號、密碼都要填才能測試", false);
            return;
        }
        if (!baseUrl.toLowerCase().startsWith("https://")) {
            say("網址必須是 https://", false);
            return;
        }

        testButton.setEnabled(false);
        say("測試中…", true);
        new SwingWorker<String, Void>() {
            protected String doInBackground() {
                String tlsProblem = ReservationClient.probeTls(baseUrl);
                if (tlsProblem != null) {
                    return "連不上：" + tlsProblem;
                }
                return new ReservationClient(baseUrl, userName, password).probeLogin();
            }

            protected void done() {
                testButton.setEnabled(true);
                String problem;
                try {
                    problem = get();
                } catch (Throwable t) {
                    problem = "測試失敗";
                }
                say(problem == null ? "連線成功，帳密正確" : problem, problem == null);
            }
        }.execute();
    }

    private String currentPassword() {
        char[] typed = passwordField.getPassword();
        if (typed != null && typed.length != 0) {
            return new String(typed);
        }
        Properties existing = Home.props(RESERVATION_PATH);
        return existing == null ? "" : existing.getProperty("password", "");
    }

    private void say(String text, boolean good) {
        message.setText(text);
        message.setForeground(good ? OK_COLOR : BAD_COLOR);
    }

    // -- 儲存 --------------------------------------------------------------

    private void save() {
        String baseUrl = baseUrlField.getText().trim();
        String userName = userNameField.getText().trim();
        String password = currentPassword();
        boolean wantsReservation = userName.length() != 0 || password.length() != 0;

        if (wantsReservation) {
            if (!baseUrl.toLowerCase().startsWith("https://")) {
                say("網址必須是 https://", false);
                return;
            }
            if (userName.length() == 0 || password.length() == 0) {
                say("帳號與密碼要一起填", false);
                return;
            }
            String problem = writeReservation(baseUrl, userName, password);
            if (problem != null) {
                say(problem, false);
                return;
            }
        }

        String problem = writePanel();
        if (problem != null) {
            say(problem, false);
            return;
        }
        saved = true;
        dialog.dispose();
    }

    private String writeReservation(String baseUrl, String userName, String password) {
        int days = 30;
        try {
            days = Integer.parseInt(windowDaysField.getText().trim());
        } catch (Throwable ignored) {
            // 填壞了就用預設
        }
        days = days < 1 ? 1 : (days > 365 ? 365 : days);

        StringBuilder body = new StringBuilder();
        body.append("# PosAssist 預約整合設定\n");
        body.append("# 由設定視窗產生。密碼是明文，請保持這個檔案的 600 權限。\n\n");
        body.append("baseUrl=").append(escape(baseUrl)).append('\n');
        body.append("userName=").append(escape(userName)).append('\n');
        body.append("password=").append(escape(password)).append('\n');
        body.append("windowDays=").append(days).append('\n');
        body.append("refreshMinutes=60\n");
        body.append("maxRows=3\n");
        return Home.write(RESERVATION_PATH, body.toString(), true);
    }

    private String writePanel() {
        StringBuilder body = new StringBuilder();
        body.append("# PosAssist 一般設定（由設定視窗產生）\n\n");
        body.append("panelMode=")
            .append(floatingRadio.isSelected() ? "floating" : "embedded").append('\n');
        body.append("autoUpdate=").append(autoUpdateBox.isSelected()).append('\n');
        return Home.write(PANEL_PATH, body.toString(), false);
    }


    /** properties 格式裡這些字元要跳脫，否則值會被讀錯。 */
    private static String escape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '=' || c == ':' || c == '#' || c == '!') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }
}
