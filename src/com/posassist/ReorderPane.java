package com.posassist;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;

/**
 * 「重開單據」畫面。按了會員卡上的「重開單據」才出現，暫時佔掉結帳代碼的位置，關閉就換回去。
 *
 * 三段：選單據（預設清單＋單號搜尋）→ 編輯明細（勾選、改代碼、改數量、加一列）→ 帶入進度。
 * 按鈕一律不可聚焦（條碼掃描器的輸入要留在 POS）；只有搜尋框與明細表在店員點進去時拿焦點。
 */
final class ReorderPane extends JPanel implements ReorderRunner.Listener {

    private static final String LIST = "list";
    private static final String EDIT = "edit";
    private static final SimpleDateFormat DAY = new SimpleDateFormat("MM/dd");

    private final ReorderRunner.Host host;
    private final Runnable onClose;

    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);

    // 選單據
    private final JTextField searchField = new JTextField();
    private final JLabel scope = caption("");
    private final JPanel docList = new JPanel();

    // 編輯明細
    private final JLabel docTitle = new JLabel(" ");
    private final LineModel model = new LineModel();
    private final JTable table = new JTable(model);
    private final JLabel detail = caption(" ");
    private final JLabel notice = caption(" ");
    private final JButton addRow = link("+ 加一列");
    private final JButton back = link("← 回清單");
    private final JButton run = primary("帶入 POS ▶");
    private final JPanel pauseBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    private final JButton resume = primary("繼續");
    private final JButton skip = plain("略過這件");
    private final JButton stop = plain("停止");
    private final JButton done = primary("完成");

    private Reorder.Doc doc;
    private ReorderRunner runner;
    /** 「POS 已有品項」要再按一次才帶，這是第一次按下時看到的列數。 */
    private int confirmedExisting = -1;

    ReorderPane(ReorderRunner.Host host, Runnable onClose) {
        super(new BorderLayout(0, Style.GAP));
        this.host = host;
        this.onClose = onClose;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));

        JLabel heading = new JLabel("重開單據");
        heading.setForeground(Style.ACCENT);
        heading.setFont(Style.heading(heading.getFont()));
        JButton close = link("關閉");
        close.addActionListener(e -> Safe.guard("關閉重開單據", () -> close()));
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(heading, BorderLayout.WEST);
        top.add(close, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        body.setOpaque(false);
        body.add(buildList(), LIST);
        body.add(buildEdit(), EDIT);
        add(body, BorderLayout.CENTER);
    }

    // -- 選單據 ------------------------------------------------------------

    private JPanel buildList() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setOpaque(false);

        searchField.setFont(Style.body(searchField.getFont()));
        searchField.setToolTipText("輸入單號或發票號後按 Enter。Esc 回到預設清單");
        searchField.addActionListener(e -> Safe.guard("查單號", () -> search()));
        searchField.addKeyListener(new KeyAdapter() {
            public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    Safe.guard("Esc 回預設清單", () -> {
                        searchField.setText("");
                        loadRecent();
                        host.returnFocusToPos();
                    });
                }
            }
        });
        JPanel north = new JPanel();
        north.setOpaque(false);
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        searchField.setAlignmentX(Component.LEFT_ALIGNMENT);
        searchField.setMaximumSize(new Dimension(Integer.MAX_VALUE,
            searchField.getPreferredSize().height));
        scope.setAlignmentX(Component.LEFT_ALIGNMENT);
        north.add(searchField);
        north.add(Box.createVerticalStrut(4));
        north.add(scope);
        panel.add(north, BorderLayout.NORTH);

        docList.setOpaque(false);
        docList.setLayout(new BoxLayout(docList, BoxLayout.Y_AXIS));
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.add(docList, BorderLayout.NORTH);
        panel.add(scroll(holder), BorderLayout.CENTER);
        return panel;
    }

    /** 每次打開都重新查：剛結帳的單要馬上看得到。 */
    void open() {
        cards.show(body, LIST);
        searchField.setText("");
        loadRecent();
    }

    private void loadRecent() {
        final String vip = host.vipId().trim();
        scope.setText(vip.length() != 0
            ? "會員 " + vip + " 近 " + Reorder.RECENT_DAYS + " 天的單（查詢中…）"
            : "本機今天的單（查詢中…）");
        showDocs(null, "");
        Reorder.recentAsync(vip, host.posNo(), docs -> {
            scope.setText(vip.length() != 0
                ? "會員 " + vip + " 近 " + Reorder.RECENT_DAYS + " 天的單"
                : "本機今天的單（POS 沒有會員）");
            showDocs(docs, vip.length() != 0 ? "這位會員近期沒有銷售單" : "本機今天還沒有銷售單");
        });
    }

    private void search() {
        final String number = searchField.getText().trim();
        if (number.length() == 0) {
            loadRecent();
            return;
        }
        scope.setText("單號／發票號 " + number + "（查詢中…）");
        showDocs(null, "");
        Reorder.searchAsync(number, docs -> {
            scope.setText("單號／發票號 " + number);
            showDocs(docs, "找不到這張銷售單");
        });
    }

    /** docs 為 null 且 empty 為空字串代表查詢中；docs 為 null 而有訊息代表查詢失敗。 */
    private void showDocs(List<Reorder.Doc> docs, String empty) {
        docList.removeAll();
        if (docs == null && empty.length() != 0) {
            docList.add(message("查詢失敗，詳見 posassist.log", Style.DANGER));
        } else if (docs != null && docs.isEmpty()) {
            docList.add(message(empty, Style.MUTED));
        } else if (docs != null) {
            for (Reorder.Doc row : docs) {
                docList.add(docButton(row));
                docList.add(Box.createVerticalStrut(4));
            }
        }
        docList.revalidate();
        docList.repaint();
    }

    private JButton docButton(final Reorder.Doc row) {
        String date = row.date == null ? "" : DAY.format(row.date);
        String number = row.invoice.length() != 0 ? row.invoice : row.docId;
        String qty = row.qty == null ? "" : row.qty.abs().stripTrailingZeros().toPlainString() + " 件";
        String total = row.total == null ? "" : "$" + NumberFormat.getIntegerInstance().format(row.total);
        JButton button = new JButton("<html><b>" + date + "</b>&nbsp;&nbsp;" + escape(number)
            + "<br><font color='#6B7280'>" + qty + "&nbsp;&nbsp;" + total
            + (row.posNo.length() != 0 ? "&nbsp;&nbsp;機號 " + escape(row.posNo) : "")
            + "</font></html>");
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setFont(Style.body(button.getFont()));
        button.setFocusable(false);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, button.getPreferredSize().height));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setToolTipText("單號 " + row.docId);
        button.addActionListener(e -> Safe.guard("選單據", () -> pick(row)));
        return button;
    }

    private void pick(final Reorder.Doc row) {
        doc = row;
        docTitle.setText((row.date == null ? "" : DAY.format(row.date) + "  ")
            + (row.invoice.length() != 0 ? row.invoice : row.docId)
            + (row.vipId.length() != 0 ? "  會員 " + row.vipId : ""));
        model.set(new ArrayList<Reorder.Line>());
        notice.setText("讀取明細中…");
        notice.setForeground(Style.MUTED);
        detail.setText(" ");
        setRunning(false);
        cards.show(body, EDIT);
        Reorder.linesAsync(row.docId, lines -> {
            if (row != doc) {
                return;             // 讀的時候店員已經換了別張
            }
            if (lines == null) {
                notice.setText("讀明細失敗，詳見 posassist.log");
                notice.setForeground(Style.DANGER);
                return;
            }
            model.set(lines);
            notice.setText(lines.isEmpty() ? "這張單沒有可帶入的品項"
                : "勾選要帶的列，代碼與數量可以直接改");
        });
    }

    // -- 編輯明細與帶入 ----------------------------------------------------

    private JPanel buildEdit() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setOpaque(false);

        back.addActionListener(e -> Safe.guard("回單據清單", () -> {
            stopEditing();
            cards.show(body, LIST);
        }));
        docTitle.setFont(Style.value(docTitle.getFont()));
        docTitle.setForeground(Style.TEXT);
        JPanel north = new JPanel();
        north.setOpaque(false);
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        back.setAlignmentX(Component.LEFT_ALIGNMENT);
        docTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        north.add(back);
        north.add(docTitle);
        panel.add(north, BorderLayout.NORTH);

        table.setFont(Style.body(table.getFont()));
        table.setRowHeight(Math.max(22, table.getRowHeight() + 4));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.getTableHeader().setReorderingAllowed(false);
        int[] widths = { 26, 86, 0, 40, 28 };
        for (int i = 0; i < widths.length; i++) {
            if (widths[i] > 0) {
                table.getColumnModel().getColumn(i).setMinWidth(widths[i]);
                table.getColumnModel().getColumn(i).setMaxWidth(widths[i] + 20);
                table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
            }
        }
        table.getSelectionModel().addListSelectionListener(e -> Safe.guard("明細選取", () -> describeSelected()));
        panel.add(scroll(table), BorderLayout.CENTER);

        addRow.addActionListener(e -> Safe.guard("加一列", () -> {
            stopEditing();
            model.add(Reorder.Line.blank());
            int row = model.getRowCount() - 1;
            table.getSelectionModel().setSelectionInterval(row, row);
            table.editCellAt(row, LineModel.CODE);
            Component editor = table.getEditorComponent();
            if (editor != null) {
                editor.requestFocusInWindow();
            }
        }));
        run.addActionListener(e -> Safe.guard("帶入 POS", () -> startRun()));
        resume.addActionListener(e -> Safe.guard("繼續帶入", () -> {
            if (runner != null) {
                showProgressText("繼續帶入…", Style.MUTED);
                pauseBar.setVisible(false);
                runner.resume();
            }
        }));
        skip.addActionListener(e -> Safe.guard("略過這件", () -> {
            if (runner != null) {
                pauseBar.setVisible(false);
                runner.skip();
            }
        }));
        stop.addActionListener(e -> Safe.guard("停止帶入", () -> {
            if (runner != null) {
                pauseBar.setVisible(false);
                runner.stop();
            }
        }));
        done.addActionListener(e -> Safe.guard("完成重開單據", () -> close()));

        pauseBar.setOpaque(false);
        pauseBar.add(resume);
        pauseBar.add(skip);
        pauseBar.add(stop);
        pauseBar.setVisible(false);
        pauseBar.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel actions = new JPanel(new BorderLayout());
        actions.setOpaque(false);
        actions.add(addRow, BorderLayout.WEST);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        right.add(run);
        right.add(done);
        done.setVisible(false);
        actions.add(right, BorderLayout.EAST);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel south = new JPanel();
        south.setOpaque(false);
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
        detail.setAlignmentX(Component.LEFT_ALIGNMENT);
        notice.setAlignmentX(Component.LEFT_ALIGNMENT);
        south.add(detail);
        south.add(Box.createVerticalStrut(2));
        south.add(notice);
        south.add(Box.createVerticalStrut(4));
        south.add(pauseBar);
        south.add(actions);
        panel.add(south, BorderLayout.SOUTH);
        return panel;
    }

    /** 選到的列有原單的序號／REF 時，寫在表格下面讓店員核對。 */
    private void describeSelected() {
        int row = table.getSelectedRow();
        Reorder.Line line = row >= 0 && row < model.getRowCount() ? model.lines.get(row) : null;
        if (line == null || !line.hasF10Values()) {
            detail.setText(line != null && Reorder.typeLabel(line.lineType).length() != 0
                ? Reorder.typeLabel(line.lineType) + "：POSN 會自己重算，通常不用勾" : " ");
            return;
        }
        StringBuilder text = new StringBuilder("<html>F10：");
        if (line.serial.length() != 0) {
            text.append("序號 ").append(escape(line.serial)).append("　");
        }
        for (int i = 0; i < line.refs.length; i++) {
            if (line.refs[i].length() != 0) {
                text.append("REF").append(i + 1).append(" ").append(escape(line.refs[i])).append("　");
            }
        }
        if (line.codeChanged()) {
            text.append("<br><font color='#9B2C2C'>代碼改過，不會自動填序號</font>");
        }
        detail.setText(text.append("</html>").toString());
    }

    private void startRun() {
        stopEditing();
        List<Reorder.Step> steps = Reorder.plan(model.lines);
        if (steps.isEmpty()) {
            showProgressText("沒有勾選任何品項", Style.DANGER);
            return;
        }
        int existing = host.lineCount();
        if (existing > 0 && existing != confirmedExisting) {
            confirmedExisting = existing;
            showProgressText("POS 明細已有 " + existing + " 列，再按一次「帶入 POS」會接在後面", Style.HINT);
            return;
        }
        confirmedExisting = -1;
        setRunning(true);
        showProgressText("帶入中…", Style.MUTED);
        runner = new ReorderRunner(host, this, steps, doc == null ? "" : doc.vipId);
        runner.start();
    }

    public void progress(int done, int total, Reorder.Step current) {
        showProgressText("帶入中 " + (done + 1) + " / " + total + "：" + current.line.code.trim()
            + (current.line.name.length() != 0 ? " " + current.line.name : ""), Style.MUTED);
    }

    public void paused(String reason) {
        showProgressText(reason, Style.HINT);
        pauseBar.setVisible(true);
        revalidate();
    }

    public void finished(int added, int skipped, int f10Pending) {
        pauseBar.setVisible(false);
        runner = null;
        StringBuilder text = new StringBuilder("<html>完成：帶入 " + added + " 件");
        if (skipped > 0) {
            text.append("，略過 ").append(skipped).append(" 件");
        }
        if (f10Pending > 0) {
            text.append("<br>有 ").append(f10Pending)
                .append(" 列要補序號：在 POS 選那一列按 F10，序號與單號會自動填好");
        }
        showProgressText(text.append("</html>").toString(), Style.ACCENT);
        run.setVisible(false);
        done.setVisible(true);
        back.setEnabled(true);
        revalidate();
    }

    private void setRunning(boolean running) {
        table.setEnabled(!running);
        addRow.setEnabled(!running);
        back.setEnabled(!running);
        run.setVisible(!running);
        done.setVisible(false);
        pauseBar.setVisible(false);
    }

    private void showProgressText(String text, Color color) {
        notice.setText(text);
        notice.setForeground(color);
    }

    private void stopEditing() {
        if (table.isEditing() && table.getCellEditor() != null) {
            table.getCellEditor().stopCellEditing();
        }
    }

    /** 帶入中按關閉等於停止；換回結帳代碼。 */
    private void close() {
        stopEditing();
        if (runner != null && runner.running()) {
            runner.stop();
        }
        runner = null;
        onClose.run();
    }

    // -- 明細表 ------------------------------------------------------------

    private static final class LineModel extends AbstractTableModel {
        static final int INCLUDE = 0;
        static final int CODE = 1;
        static final int NAME = 2;
        static final int QTY = 3;
        static final int F10 = 4;
        private static final String[] NAMES = { "帶", "代碼", "品名", "數量", "F10" };

        List<Reorder.Line> lines = new ArrayList<Reorder.Line>();

        void set(List<Reorder.Line> lines) {
            this.lines = lines;
            fireTableDataChanged();
        }

        void add(Reorder.Line line) {
            lines.add(line);
            fireTableRowsInserted(lines.size() - 1, lines.size() - 1);
        }

        public int getRowCount() {
            return lines.size();
        }

        public int getColumnCount() {
            return NAMES.length;
        }

        public String getColumnName(int column) {
            return NAMES[column];
        }

        public Class<?> getColumnClass(int column) {
            return column == INCLUDE ? Boolean.class : column == QTY ? Integer.class : String.class;
        }

        public boolean isCellEditable(int row, int column) {
            return column == INCLUDE || column == CODE || column == QTY;
        }

        public Object getValueAt(int row, int column) {
            Reorder.Line line = lines.get(row);
            switch (column) {
                case INCLUDE: return Boolean.valueOf(line.include);
                case CODE: return line.code;
                case NAME:
                    String type = Reorder.typeLabel(line.lineType);
                    return type.length() == 0 ? line.name : "（" + type + "）" + line.name;
                case QTY: return Integer.valueOf(line.qty);
                default: return line.hasF10Values() ? (line.codeChanged() ? "—" : "✓") : "";
            }
        }

        public void setValueAt(Object value, int row, int column) {
            Reorder.Line line = lines.get(row);
            if (column == INCLUDE) {
                line.include = Boolean.TRUE.equals(value);
            } else if (column == CODE) {
                line.code = value == null ? "" : String.valueOf(value).trim();
                if (line.code.length() != 0) {
                    line.include = true;
                }
            } else if (column == QTY && value instanceof Integer) {
                line.qty = Math.max(1, Math.min(Reorder.MAX_QTY, ((Integer) value).intValue()));
            }
            fireTableRowsUpdated(row, row);
        }
    }

    // -- 小元件 ------------------------------------------------------------

    private static JScrollPane scroll(Component view) {
        JScrollPane scroll = new JScrollPane(view,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createLineBorder(Style.LINE));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setMinimumSize(new Dimension(0, 0));
        return scroll;
    }

    private static JLabel caption(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Style.caption(label.getFont()));
        label.setForeground(Style.MUTED);
        return label;
    }

    private static JLabel message(String text, Color color) {
        JLabel label = caption(text);
        label.setForeground(color);
        label.setBorder(BorderFactory.createEmptyBorder(6, 4, 6, 4));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static JButton link(String text) {
        JButton button = new JButton(text);
        button.setFont(Style.caption(button.getFont()));
        button.setForeground(Style.ACCENT);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setFocusable(false);
        button.setMargin(new Insets(0, 0, 0, 0));
        button.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private static JButton plain(String text) {
        JButton button = new JButton(text);
        button.setFont(Style.body(button.getFont()));
        button.setFocusable(false);
        return button;
    }

    private static JButton primary(String text) {
        JButton button = plain(text);
        button.setFont(button.getFont().deriveFont(Font.BOLD));
        button.setForeground(Style.ACCENT);
        return button;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
