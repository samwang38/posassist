package com.posassist;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridLayout;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

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
    private final JCheckBox enableVipCreateBox =
        new JCheckBox("會員建立（試用）");

    /**
     * 建立表單可以勾的欄位：{屬性名, 畫面上的名稱}。
     *
     * 中文名稱是照 EPB 自己的 lang/posvip_zht.properties 抄的，不是自己翻的
     * —— 店員在原生表單上看到什麼，這裡就寫什麼，才對得起來。
     * 直接寫死而不去讀那個檔：這個視窗安裝時就會被叫起來，那時 EPB 還不一定在。
     */
    private static final String[][] VIP_FIELDS = {
        { "name", "名稱" },
        { "vipPhone1", "VIP電話1" },
        { "emailAddr", "郵件地址" },
        { "birthDate", "生日" },
        { "gender", "性別" },
        { "vipId", "VIP代碼" },
        { "vipPhone2", "VIP電話2" },
        { "cardNo", "卡號" },
        { "classId", "等級代碼" },
        { "address1", "地址1" },
        { "self1Id", "辦卡條件代碼" },
        { "self2Id", "有無小孩代碼" },
        { "self3Id", "活動通知代碼" },
        { "remark4", "備註4" },
        { "empId", "員工代碼" },
        { "custId", "客戶代碼" },
    };

    /** 沒特別設定時預設會出現的欄位。跟 VipCreator 的預設保持一致。 */
    private static final String[] VIP_FIELDS_DEFAULT = {
        "name", "vipPhone1", "emailAddr", "birthDate", "gender"
    };

    /** 屬性名 → 勾選盒。順序就是寫進設定檔的順序，也是表單上的順序。 */
    private final Map<String, JCheckBox> vipFieldBoxes =
        new LinkedHashMap<String, JCheckBox>();
    private final JPanel vipFieldPanel = new JPanel();
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
        // 這個跟 autoUpdate 相反，預設關閉：會員建立還在試行，要門市自己開
        boolean vipCreate = panel != null
            && "true".equalsIgnoreCase(panel.getProperty("enableVipCreate", "false").trim());
        enableVipCreateBox.setSelected(vipCreate);
        buildVipFieldBoxes(panel == null ? "" : panel.getProperty("vipCreateFields", ""));
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
        addRow(form, row++, "建立會員", enableVipCreateBox);
        JLabel vipCreateHint = new JLabel(
            "查無會員時多一個入口，直接叫出 EPB 原生的會員建立表單，電話會先幫你填好。");
        vipCreateHint.setForeground(MUTED);
        vipCreateHint.setFont(vipCreateHint.getFont().deriveFont(11f));
        addHint(form, row - 1, vipCreateHint);

        addRow(form, row++, "表單欄位", vipFieldPanel);
        JLabel vipFieldHint = new JLabel(
            "勾要出現在建立表單上的欄位。必填欄位就算沒勾，EPB 還是會自己補回來。");
        vipFieldHint.setForeground(MUTED);
        vipFieldHint.setFont(vipFieldHint.getFont().deriveFont(11f));
        addHint(form, row - 1, vipFieldHint);
        syncVipFieldEnablement();

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

    // -- 建立表單的欄位挑選 ------------------------------------------------

    /**
     * 長出勾選盒。
     *
     * 設定檔裡如果有清單上沒有的欄位，也要長一個給它並勾起來 —— 門市可能自己
     * 手打了某個欄位，不能因為在這裡按了一次儲存就把它洗掉。
     */
    private void buildVipFieldBoxes(String configured) {
        java.util.List<String> selected = split(configured);

        // 清單上的擺前面，設定檔裡多出來的補在後面
        java.util.List<String[]> entries =
            new java.util.ArrayList<String[]>(java.util.Arrays.asList(VIP_FIELDS));
        for (String field : selected) {
            if (!known(field)) {
                entries.add(new String[] { field, field });
            }
        }

        boolean useDefaults = selected.isEmpty();
        java.util.Set<String> on = new LinkedHashSet<String>(
            useDefaults ? java.util.Arrays.asList(VIP_FIELDS_DEFAULT) : selected);

        // 固定三欄，不要用 WrapFlow：那個是靠容器實際寬度算高度的，
        // 但這裡是 dialog.pack() 決定寬度，pack 的當下還沒有寬度可問，
        // 它會退回「排成一列」，16 個勾選盒就把設定視窗撐到一千多 px 寬。
        vipFieldPanel.setLayout(new GridLayout(0, 3, 10, 0));
        vipFieldPanel.setOpaque(false);
        for (String[] entry : entries) {
            JCheckBox box = new JCheckBox(entry[1], on.contains(entry[0]));
            box.setToolTipText("屬性名 " + entry[0]);
            box.setOpaque(false);
            vipFieldBoxes.put(entry[0], box);
            vipFieldPanel.add(box);
        }

        // 功能沒開的時候，這排勾選盒沒有意義，跟著一起灰掉
        enableVipCreateBox.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                syncVipFieldEnablement();
            }
        });
    }

    private void syncVipFieldEnablement() {
        boolean on = enableVipCreateBox.isSelected();
        for (JCheckBox box : vipFieldBoxes.values()) {
            box.setEnabled(on);
        }
    }

    /**
     * 勾好的欄位，逗號分隔。
     *
     * 剛好就是預設那組時回 null，讓設定檔不要出現這個 key —— 沒有這個 key，
     * 程式才會用它自己的預設（包含「代碼不是自動產生時補上 VIP代碼」那段判斷）。
     * 寫死一份一模一樣的清單只會讓那段判斷失效。
     */
    private String selectedVipFields() {
        java.util.List<String> chosen = new java.util.ArrayList<String>();
        for (Map.Entry<String, JCheckBox> entry : vipFieldBoxes.entrySet()) {
            if (entry.getValue().isSelected()) {
                chosen.add(entry.getKey());
            }
        }
        if (chosen.isEmpty()) {
            return null;   // 一個都沒勾＝回到預設，而不是變成一張空表單
        }
        java.util.Set<String> asSet = new LinkedHashSet<String>(chosen);
        java.util.Set<String> defaults =
            new LinkedHashSet<String>(java.util.Arrays.asList(VIP_FIELDS_DEFAULT));
        if (asSet.equals(defaults)) {
            return null;
        }
        StringBuilder joined = new StringBuilder();
        for (String field : chosen) {
            if (joined.length() != 0) {
                joined.append(',');
            }
            joined.append(field);
        }
        return joined.toString();
    }

    private static boolean known(String field) {
        for (String[] entry : VIP_FIELDS) {
            if (entry[0].equals(field)) {
                return true;
            }
        }
        return false;
    }

    private static java.util.List<String> split(String value) {
        java.util.List<String> parts = new java.util.ArrayList<String>();
        if (value == null) {
            return parts;
        }
        for (String part : value.split(",")) {
            String field = part.trim();
            if (field.length() != 0 && !parts.contains(field)) {
                parts.add(field);
            }
        }
        return parts;
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

    /**
     * 寫回一般設定。
     *
     * 只覆蓋這個視窗管得到的 key，其餘原封不動抄回去 —— 門市可能自己加了
     * vipDiagnose、startEpbHelper 這些畫面上沒有的設定，整份重寫會把它們洗掉，
     * 而且是存一次設定就悄悄消失，現場很難聯想到原因。
     */
    private String writePanel() {
        Map<String, String> managed = new LinkedHashMap<String, String>();
        managed.put("panelMode", floatingRadio.isSelected() ? "floating" : "embedded");
        managed.put("autoUpdate", String.valueOf(autoUpdateBox.isSelected()));
        managed.put("enableVipCreate", String.valueOf(enableVipCreateBox.isSelected()));
        String vipFields = selectedVipFields();
        if (vipFields != null) {
            managed.put("vipCreateFields", vipFields);
        }

        /*
         * 這個視窗管得到的 key，跟「這次要寫出去的 key」不是同一件事：
         * vipCreateFields 在勾選＝預設時會刻意不寫，好讓程式用它自己的預設。
         * 但它仍然是我們管的 —— 不放進這個集合的話，下面保留未知 key 那段
         * 會把舊值原封不動抄回去，畫面上取消勾選就等於沒有作用。
         */
        Set<String> owned = new LinkedHashSet<String>(managed.keySet());
        owned.add("vipCreateFields");

        StringBuilder body = new StringBuilder();
        body.append("# PosAssist 一般設定（由設定視窗產生）\n\n");
        for (Map.Entry<String, String> entry : managed.entrySet()) {
            body.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }

        Properties existing = Home.props(PANEL_PATH);
        if (existing != null) {
            List<String> others = new ArrayList<String>(existing.stringPropertyNames());
            Collections.sort(others);
            boolean first = true;
            for (String key : others) {
                if (owned.contains(key)) {
                    continue;
                }
                if (first) {
                    body.append("\n# 以下是設定視窗沒有的項目，照原樣保留\n");
                    first = false;
                }
                body.append(escape(key)).append('=')
                    .append(escape(existing.getProperty(key, ""))).append('\n');
            }
        }
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
