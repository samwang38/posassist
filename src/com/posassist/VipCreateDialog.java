package com.posassist;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingWorker;

/**
 * 會員建立輔助。
 *
 * 這個視窗**不會建立任何會員**。它做的事只有三件：把店員填的資料驗一次、
 * 逐欄整理成可以複製的樣子、然後開啟 EPB 原生的 POSVIP。實際的新增從頭到尾
 * 都是店員在原生畫面上按新增、貼上、送出，走 EPB 自己的驗證。
 *
 * 為什麼要多這一層而不是直接叫店員開 POSVIP：原生畫面欄位多、驗證在送出後才跳，
 * 手機碼數不對或姓名撞名要整輪重來。先在這裡用門市當下的設定擋一次，
 * 店員貼過去的東西就大致上是會過的。
 *
 * 個資只留在記憶體與店員自己按下複製時的剪貼簿 —— 不寫檔、不進 log。
 */
public final class VipCreateDialog {

    private final JDialog dialog;

    private final JTextField nameField = new JTextField();
    private final JTextField phoneField = new JTextField();
    private final JTextField emailField = new JTextField();
    private final JTextField birthdayField = new JTextField();

    private final JLabel message = new JLabel(" ");
    private final JButton checkButton = new JButton("檢查資料");
    private final JButton openButton = new JButton("開啟 POSVIP");
    private final JButton backButton = new JButton("回去修改");

    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private final JPanel copyRows = new JPanel();

    /** 檢查過的草稿；還沒檢查或檢查沒過時是 null。 */
    private VipCreateDraft draft;
    /** 已經開過原生畫面的電話。非 null 代表回到側欄後值得重查一次。 */
    private String openedPhone;

    public VipCreateDialog(Window owner, String presetPhone) {
        dialog = new JDialog(owner, "建立會員（輔助）",
            JDialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        if (presetPhone != null) {
            phoneField.setText(presetPhone);
        }
        dialog.setContentPane(buildContent());
        dialog.pack();
        dialog.setSize(Math.max(480, dialog.getWidth()), dialog.getHeight());
        dialog.setLocationRelativeTo(owner);
    }

    /**
     * @return 有開過原生畫面就回傳當時的電話（呼叫端據此重查），否則 null
     */
    public String showDialog() {
        dialog.setVisible(true);
        return openedPhone;
    }

    // -- 版面 --------------------------------------------------------------

    private JPanel buildContent() {
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        JLabel heading = new JLabel("整理資料，再到原生畫面送出");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 14f));
        heading.setForeground(Style.ACCENT);
        root.add(heading, BorderLayout.NORTH);

        body.add(buildForm(), "form");
        body.add(buildCopyPanel(), "copy");
        root.add(body, BorderLayout.CENTER);

        message.setFont(message.getFont().deriveFont(12f));
        message.setForeground(Style.MUTED);

        JPanel south = new JPanel(new BorderLayout(0, 8));
        south.add(message, BorderLayout.NORTH);
        south.add(buildButtons(), BorderLayout.SOUTH);
        root.add(south, BorderLayout.SOUTH);

        cards.show(body, "form");
        return root;
    }

    private JPanel buildForm() {
        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        addField(form, row++, "姓名", nameField, "必填");
        addField(form, row++, "電話", phoneField, "必填，會再查一次有沒有重複");
        addField(form, row++, "Email", emailField, "選填");
        addField(form, row++, "生日", birthdayField, "選填，西元 YYYY-MM-DD");
        return form;
    }

    private JPanel buildCopyPanel() {
        JPanel wrapper = new JPanel(new BorderLayout(0, 8));

        copyRows.setLayout(new BoxLayout(copyRows, BoxLayout.Y_AXIS));
        copyRows.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrapper.add(copyRows, BorderLayout.CENTER);

        JLabel note = new JLabel("<html>在 POSVIP 按「新增」，逐欄複製貼上。"
            + "性別 POSVIP 會依門市設定先給一個預設值，送出前確認一次是否正確。"
            + "會員代碼與等級也由 POSVIP 自己帶。</html>");
        note.setForeground(Style.MUTED);
        note.setFont(note.getFont().deriveFont(11f));
        wrapper.add(note, BorderLayout.SOUTH);

        return wrapper;
    }

    private JPanel buildButtons() {
        JPanel buttons = new JPanel(new BorderLayout());

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        backButton.setVisible(false);
        backButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                Safe.guard("回去修改", new Runnable() {
                    public void run() {
                        showForm();
                    }
                });
            }
        });
        left.add(backButton);
        buttons.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton cancel = new JButton("取消");
        cancel.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                dialog.dispose();
            }
        });
        right.add(cancel);

        checkButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                Safe.guard("檢查會員資料", new Runnable() {
                    public void run() {
                        check();
                    }
                });
            }
        });
        right.add(checkButton);

        openButton.setVisible(false);
        openButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                Safe.guard("開啟 POSVIP", new Runnable() {
                    public void run() {
                        openNative();
                    }
                });
            }
        });
        right.add(openButton);

        buttons.add(right, BorderLayout.EAST);
        return buttons;
    }

    // -- 檢查 --------------------------------------------------------------

    /** 檢查的結果：過了有 draft，沒過有 problem。 */
    private static final class Checked {
        final VipCreateDraft draft;
        final String problem;

        Checked(VipCreateDraft draft, String problem) {
            this.draft = draft;
            this.problem = problem;
        }
    }

    /**
     * 檢查整份資料。**整個過程都待在背景執行緒。**
     *
     * 連欄位驗證都一起搬過去，是因為它需要門市的手機碼數設定，而讀設定會查
     * 資料庫（getAppSetting 最多要問三張表）。只要有一步留在 EDT 上，
     * 整個 EPB 畫面就會停住，店員看到的是 POS 沒反應。
     *
     * 設定第一次讀完就快取住，之後再按檢查只剩查重的時間。
     */
    private void check() {
        setBusy(true);
        say("檢查中…", Style.MUTED);

        final String name = nameField.getText();
        final String phone = phoneField.getText();
        final String email = emailField.getText();
        final String birthday = birthdayField.getText();

        new SwingWorker<Checked, Void>() {
            protected Checked doInBackground() {
                PosVipRules rules = PosVipRules.current();
                VipCreateDraft.Result result = VipCreateDraft.of(
                    name, phone, email, birthday, rules.phoneLength);
                if (!result.ok()) {
                    return new Checked(null, result.error);
                }
                String problem = conflict(result.draft, rules);
                return new Checked(problem == null ? result.draft : null, problem);
            }

            protected void done() {
                Safe.guard("顯示檢查結果", new Runnable() {
                    public void run() {
                        finish();
                    }
                });
            }

            private void finish() {
                setBusy(false);
                Checked checked;
                try {
                    checked = get();
                } catch (Throwable t) {
                    PosLog.warn("會員資料檢查失敗", t);
                    say("檢查沒能完成，請稍後再試", Style.DANGER);
                    return;
                }
                if (checked.draft == null) {
                    say(checked.problem, Style.DANGER);
                    return;
                }
                draft = checked.draft;
                showCopy();
            }
        }.execute();
    }

    /**
     * 查有沒有撞到既有會員。回 null 代表沒撞到，非 null 是擋下來的原因。
     *
     * 查不出來一律當作「不能建」—— 重複的會員清起來比讓店員多等一下麻煩太多。
     */
    private static String conflict(VipCreateDraft candidate, PosVipRules rules) {
        VipLookup.Outcome existing = VipLookup.lookup(candidate.phone);
        if (existing.status == VipLookup.Status.FOUND
            || existing.status == VipLookup.Status.TOO_MANY) {
            return "這支電話已經有會員了，請直接查詢帶入";
        }
        if (existing.status != VipLookup.Status.NOT_FOUND) {
            return "無法確認電話是否重複，請稍後再試";
        }

        if (rules.checkName) {
            Boolean taken = PosVipRules.nameTaken(candidate.name);
            if (taken == null) {
                return "無法確認姓名是否重複，請稍後再試";
            }
            if (Boolean.TRUE.equals(taken)) {
                return "這個姓名已經有人用了（門市設定要求姓名不重複）";
            }
        }
        return null;
    }

    // -- 兩個階段 ----------------------------------------------------------

    private void showForm() {
        draft = null;
        cards.show(body, "form");
        checkButton.setVisible(true);
        openButton.setVisible(false);
        backButton.setVisible(false);
        say(" ", Style.MUTED);
        dialog.pack();
        dialog.setSize(Math.max(480, dialog.getWidth()), dialog.getHeight());
    }

    private void showCopy() {
        copyRows.removeAll();
        addCopyRow("姓名", draft.name);
        addCopyRow("手機", draft.phone);
        if (draft.email.length() != 0) {
            addCopyRow("Email", draft.email);
        }
        if (draft.birthDate.length() != 0) {
            // 只給一欄：POSVIP 的 BirthDateAutomator 會自己從 birthDate
            // 推出年／月／日並補零，拆成三個複製鈕是白做工
            addCopyRow("生日", draft.birthDate);
        }

        cards.show(body, "copy");
        checkButton.setVisible(false);
        openButton.setVisible(true);
        backButton.setVisible(true);
        say("資料看起來沒問題，可以開啟 POSVIP 了", Style.ACCENT);
        dialog.pack();
        dialog.setSize(Math.max(480, dialog.getWidth()), dialog.getHeight());
    }

    /** 一列：標籤、值、複製鈕。點複製只進剪貼簿，不動 POS 的焦點。 */
    private void addCopyRow(String caption, final String value) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));

        JLabel label = new JLabel(caption);
        label.setForeground(Style.MUTED);
        label.setFont(Style.caption(label.getFont()));
        label.setPreferredSize(new Dimension(54, 24));
        row.add(label, BorderLayout.WEST);

        JLabel text = new JLabel(value);
        text.setFont(Style.value(text.getFont()));
        text.setForeground(Style.TEXT);
        row.add(text, BorderLayout.CENTER);

        JButton copy = new JButton("複製");
        copy.setFocusable(false);
        copy.setFont(Style.caption(copy.getFont()));
        copy.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                Safe.guard("複製建立欄位", new Runnable() {
                    public void run() {
                        boolean ok = FloatingPanel.copyToClipboard(value);
                        say(ok ? "已複製，貼到 POSVIP 對應欄位"
                               : "複製不成功，請手動選取",
                            ok ? Style.ACCENT : Style.DANGER);
                    }
                });
            }
        });
        row.add(copy, BorderLayout.EAST);

        copyRows.add(row);
        copyRows.add(Box.createVerticalStrut(2));
    }

    // -- 開啟原生 ----------------------------------------------------------

    private void openNative() {
        String problem = VipCreator.open();
        if (problem != null) {
            say(problem, Style.DANGER);
            return;
        }
        openedPhone = draft.phone;
        dialog.dispose();
    }

    // -- 小工具 ------------------------------------------------------------

    private void setBusy(boolean busy) {
        checkButton.setEnabled(!busy);
        openButton.setEnabled(!busy);
    }

    private void say(String text, java.awt.Color color) {
        message.setForeground(color);
        message.setText(text == null || text.length() == 0 ? " " : text);
    }

    private void addField(JPanel form, int row, String caption, JTextField field,
        String hint) {
        field.setPreferredSize(new Dimension(280, 26));

        GridBagConstraints left = new GridBagConstraints();
        left.gridx = 0;
        left.gridy = row * 2;
        left.anchor = GridBagConstraints.WEST;
        left.insets = new Insets(4, 0, 4, 10);
        form.add(new JLabel(caption), left);

        GridBagConstraints right = new GridBagConstraints();
        right.gridx = 1;
        right.gridy = row * 2;
        right.weightx = 1;
        right.fill = GridBagConstraints.HORIZONTAL;
        right.insets = new Insets(4, 0, 4, 0);
        form.add(field, right);

        if (hint == null) {
            return;
        }
        JLabel hintLabel = new JLabel(hint);
        hintLabel.setForeground(Style.MUTED);
        hintLabel.setFont(hintLabel.getFont().deriveFont(11f));
        GridBagConstraints below = new GridBagConstraints();
        below.gridx = 1;
        below.gridy = row * 2 + 1;
        below.anchor = GridBagConstraints.WEST;
        below.insets = new Insets(0, 0, 4, 0);
        form.add(hintLabel, below);
    }
}
