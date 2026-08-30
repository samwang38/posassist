package com.posassist;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;

/**
 * 結帳代碼編輯器。modal 視窗，改完要按「儲存」才會寫檔。
 *
 * 誤觸防護：編輯藏在面板的「編輯」後面、關閉前若有未儲存變更會再問一次、
 * 每次儲存由 CodeStore 留一份 .bak。表格順序就是面板顯示順序。
 */
public final class CodeEditor {

    private static final String[] COLUMNS =
        { "主分類", "子分類", "名稱", "代碼", "釘選" };
    private static final int PIN_COLUMN = 4;

    private final JDialog dialog;
    private final Model model;
    private final JTable table;
    private final JLabel message = new JLabel(" ");

    private boolean dirty;
    private boolean saved;

    public CodeEditor(Window owner) {
        model = new Model(CodeStore.load());
        table = new JTable(model);
        table.setRowHeight(24);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.getColumnModel().getColumn(0).setPreferredWidth(60);
        table.getColumnModel().getColumn(1).setPreferredWidth(60);
        table.getColumnModel().getColumn(2).setPreferredWidth(150);
        table.getColumnModel().getColumn(3).setPreferredWidth(105);
        table.getColumnModel().getColumn(PIN_COLUMN).setPreferredWidth(40);
        table.getColumnModel().getColumn(PIN_COLUMN).setMaxWidth(50);

        dialog = new JDialog(owner, "編輯結帳代碼", JDialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.setContentPane(buildContent());
        dialog.setSize(560, 420);
        dialog.setLocationRelativeTo(owner);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosing(java.awt.event.WindowEvent event) {
                Safe.guard("關閉代碼編輯器", new Runnable() {
                    public void run() {
                        cancel();
                    }
                });
            }
        });
    }

    /** 開啟並等待關閉。回傳是否有存檔（有的話呼叫端要重新載入面板）。 */
    public boolean showDialog() {
        dialog.setVisible(true);
        return saved;
    }

    // -- 版面 --------------------------------------------------------------

    private JPanel buildContent() {
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel hint = new JLabel(
            "<html>表格順序就是面板上的顯示順序；主分類相同的會排在同一個頁籤，"
            + "子分類會在頁籤底下分段（留白就是不分段）。<br>"
            + "勾「釘選」的會固定顯示在面板最上面，不受切換分類影響。</html>");
        hint.setFont(hint.getFont().deriveFont(11f));
        root.add(hint, BorderLayout.NORTH);

        JScrollPane scroller = new JScrollPane(table);
        scroller.setPreferredSize(new Dimension(520, 260));
        root.add(scroller, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.add(buildToolbar(), BorderLayout.NORTH);
        message.setFont(message.getFont().deriveFont(11f));
        message.setForeground(new java.awt.Color(0x9B, 0x2C, 0x2C));
        south.add(message, BorderLayout.CENTER);
        south.add(buildActions(), BorderLayout.SOUTH);
        root.add(south, BorderLayout.SOUTH);

        return root;
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        bar.add(button("新增", new Runnable() {
            public void run() {
                stopEditing();
                model.add(currentCategory());
                int row = model.getRowCount() - 1;
                table.setRowSelectionInterval(row, row);
                table.editCellAt(row, 2);
                dirty = true;
            }
        }));
        bar.add(button("刪除", new Runnable() {
            public void run() {
                stopEditing();
                int row = table.getSelectedRow();
                if (row < 0) {
                    message.setText("請先選一列");
                    return;
                }
                model.remove(row);
                dirty = true;
                message.setText(" ");
            }
        }));
        bar.add(button("上移", new Runnable() {
            public void run() {
                move(-1);
            }
        }));
        bar.add(button("下移", new Runnable() {
            public void run() {
                move(1);
            }
        }));
        return bar;
    }

    private JPanel buildActions() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        bar.add(button("取消", new Runnable() {
            public void run() {
                cancel();
            }
        }));
        bar.add(button("儲存", new Runnable() {
            public void run() {
                save();
            }
        }));
        return bar;
    }

    private JButton button(String text, final Runnable action) {
        JButton button = new JButton(text);
        button.setFocusable(false);
        button.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                Safe.guard("代碼編輯：" + text, action);
            }
        });
        return button;
    }

    // -- 動作 --------------------------------------------------------------

    private void move(int delta) {
        stopEditing();
        int row = table.getSelectedRow();
        int target = row + delta;
        if (row < 0) {
            message.setText("請先選一列");
            return;
        }
        if (target < 0 || target >= model.getRowCount()) {
            return;
        }
        model.swap(row, target);
        table.setRowSelectionInterval(target, target);
        dirty = true;
        message.setText(" ");
    }

    private void save() {
        stopEditing();
        List<CodeItem> items = model.snapshot();
        String problem = CodeStore.save(items);
        if (problem != null) {
            message.setText(problem);
            return;
        }
        saved = true;
        dirty = false;
        // 釘選是另一個檔：代碼已經存進去了，這裡失敗只是少了置頂，
        // 所以留在畫面上把話說清楚，讓人自己決定要不要再存一次
        String pinProblem = CodeStore.savePins(items);
        if (pinProblem != null) {
            message.setText("代碼已儲存，但釘選沒存起來：" + pinProblem);
            return;
        }
        dialog.dispose();
    }

    private void cancel() {
        stopEditing();
        if (dirty) {
            int answer = JOptionPane.showConfirmDialog(dialog,
                "有尚未儲存的變更，確定要放棄嗎？", "放棄變更",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) {
                return;
            }
        }
        dialog.dispose();
    }

    /** 正在編輯的儲存格要先收起來，否則最後一次輸入不會進 model。 */
    private void stopEditing() {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
    }

    /**
     * 新增列時要沿用的分類。連續建同一區的代碼是常態，主分類與子分類都跟著
     * 選取列（沒選就跟最後一列），店員不用每一筆重打。
     */
    private CodeItem currentCategory() {
        List<CodeItem> items = model.snapshot();
        int row = table.getSelectedRow();
        CodeItem source = null;
        if (row >= 0) {
            source = items.get(row);
        } else if (!items.isEmpty()) {
            source = items.get(items.size() - 1);
        }
        return source == null
            ? CodeItem.of(CodeItem.DEFAULT_CATEGORY, "", "", "", false)
            : CodeItem.of(source.category, source.sub, "", "", false);
    }

    // -- 表格模型 ----------------------------------------------------------

    private final class Model extends AbstractTableModel {
        private final List<Object[]> rows = new ArrayList<Object[]>();

        Model(List<CodeItem> items) {
            for (int i = 0; i < items.size(); i++) {
                rows.add(row(items.get(i)));
            }
        }

        private Object[] row(CodeItem item) {
            return new Object[] { item.category, item.sub, item.name, item.code,
                Boolean.valueOf(item.pinned) };
        }

        public int getRowCount() {
            return rows.size();
        }

        public int getColumnCount() {
            return COLUMNS.length;
        }

        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        /** 釘選欄回 Boolean，JTable 才會畫成打勾框而不是文字。 */
        public Class<?> getColumnClass(int column) {
            return column == PIN_COLUMN ? Boolean.class : String.class;
        }

        public boolean isCellEditable(int row, int column) {
            return true;
        }

        public Object getValueAt(int row, int column) {
            return rows.get(row)[column];
        }

        public void setValueAt(Object value, int row, int column) {
            if (column == PIN_COLUMN) {
                rows.get(row)[column] = Boolean.valueOf(Boolean.TRUE.equals(value));
            } else {
                rows.get(row)[column] = value == null ? "" : String.valueOf(value).trim();
            }
            dirty = true;
            message.setText(" ");
            fireTableCellUpdated(row, column);
        }

        void add(CodeItem item) {
            rows.add(row(item));
            fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
        }

        void remove(int row) {
            rows.remove(row);
            fireTableRowsDeleted(row, row);
        }

        void swap(int a, int b) {
            Object[] tmp = rows.get(a);
            rows.set(a, rows.get(b));
            rows.set(b, tmp);
            fireTableRowsUpdated(Math.min(a, b), Math.max(a, b));
        }

        List<CodeItem> snapshot() {
            List<CodeItem> items = new ArrayList<CodeItem>();
            for (int i = 0; i < rows.size(); i++) {
                Object[] row = rows.get(i);
                items.add(CodeItem.of(text(row[0]), text(row[1]), text(row[2]),
                    text(row[3]), Boolean.TRUE.equals(row[4])));
            }
            return items;
        }

        private String text(Object value) {
            return value == null ? "" : String.valueOf(value);
        }
    }
}
