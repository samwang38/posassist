package com.posassist;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;

/**
 * 自訂結帳代碼面板：上面一列分類頁籤，下面 3 欄九宮格。
 *
 * 點一顆按鈕就把代碼交給呼叫端帶進 POS，不跳確認 —— 誤觸的後果是 POS 多一筆品項，
 * 店員當場看得到也刪得掉，不會有錯誤資料默默寫進交易。
 */
public final class CodePad extends JPanel {

    /** 由 FloatingPanel 提供：把代碼帶進 POS。回傳是否成功。 */
    public interface CodeApplier {
        boolean applyCode(String code);
    }

    private static final int COLUMNS = 3;
    private static final int BUTTON_HEIGHT = 54;
    /** 側欄 325px 扣掉內距與間隔，3 欄大約各 95px。 */
    private static final int CELL_WIDTH = 95;

    private static final Color ACCENT = new Color(0x1D, 0x4E, 0x89);
    private static final Color MUTED = new Color(0x66, 0x66, 0x70);
    private static final Color TAB_ON = new Color(0xE8, 0xEF, 0xF8);
    private static final Color BORDER = new Color(0xC3, 0xC9, 0xD2);

    private final JPanel tabBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final JPanel grid = new JPanel();
    private final JLabel status = new JLabel(" ");
    private final JScrollPane scroller;

    private CodeApplier applier;
    private Runnable onEditRequested;
    private List<CodeItem> items = new ArrayList<CodeItem>();
    private List<String> categories = new ArrayList<String>();
    private String selectedCategory;

    public CodePad() {
        setOpaque(false);
        setLayout(new BorderLayout(0, 4));
        setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);

        JLabel heading = new JLabel("結帳代碼");
        heading.setForeground(ACCENT);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 12f));
        header.add(heading, BorderLayout.WEST);
        header.add(editLink(), BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(0, 4));
        body.setOpaque(false);
        tabBar.setOpaque(false);
        body.add(tabBar, BorderLayout.NORTH);

        grid.setOpaque(false);
        JPanel gridHolder = new JPanel(new BorderLayout());
        gridHolder.setOpaque(false);
        gridHolder.add(grid, BorderLayout.NORTH);   // 讓格子貼齊上方，不要被拉高

        scroller = new JScrollPane(gridHolder,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroller.setBorder(BorderFactory.createEmptyBorder());
        scroller.setOpaque(false);
        scroller.getViewport().setOpaque(false);
        scroller.getVerticalScrollBar().setUnitIncrement(16);
        body.add(scroller, BorderLayout.CENTER);
        add(body, BorderLayout.CENTER);

        status.setForeground(MUTED);
        status.setFont(status.getFont().deriveFont(11f));
        add(status, BorderLayout.SOUTH);
    }

    public void setCodeApplier(CodeApplier applier) {
        this.applier = applier;
    }

    public void setOnEditRequested(Runnable action) {
        this.onEditRequested = action;
    }

    private JButton editLink() {
        JButton button = new JButton("編輯");
        button.setFont(button.getFont().deriveFont(11f));
        button.setForeground(MUTED);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setFocusable(false);
        button.setMargin(new Insets(0, 0, 0, 0));
        button.setBorder(BorderFactory.createEmptyBorder());
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setToolTipText("新增、修改、刪除或調整順序");
        button.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                Safe.guard("開啟代碼編輯", new Runnable() {
                    public void run() {
                        if (onEditRequested != null) {
                            onEditRequested.run();
                        }
                    }
                });
            }
        });
        return button;
    }

    // -- 資料 --------------------------------------------------------------

    /** 重新讀檔並重繪。編輯器存檔後也走這裡。 */
    public void reload() {
        items = CodeStore.load();
        categories = CodeStore.categories(items);
        if (selectedCategory == null || !categories.contains(selectedCategory)) {
            selectedCategory = categories.isEmpty() ? null : categories.get(0);
        }
        rebuildTabs();
        rebuildGrid();
    }

    private void rebuildTabs() {
        tabBar.removeAll();
        for (int i = 0; i < categories.size(); i++) {
            final String category = categories.get(i);
            JButton tab = new JButton(category);
            tab.setFont(tab.getFont().deriveFont(11f));
            tab.setFocusable(false);
            tab.setMargin(new Insets(2, 6, 2, 6));
            boolean active = category.equals(selectedCategory);
            tab.setForeground(active ? ACCENT : MUTED);
            tab.setBackground(active ? TAB_ON : null);
            tab.setContentAreaFilled(active);
            tab.setOpaque(active);
            tab.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent event) {
                    Safe.guard("切換代碼分類", new Runnable() {
                        public void run() {
                            selectedCategory = category;
                            rebuildTabs();
                            rebuildGrid();
                        }
                    });
                }
            });
            tabBar.add(tab);
        }
        // 只有一個分類就不必顯示頁籤列，省一行空間
        tabBar.setVisible(categories.size() > 1);
        tabBar.revalidate();
        tabBar.repaint();
    }

    private void rebuildGrid() {
        grid.removeAll();
        List<CodeItem> shown = selectedCategory == null
            ? new ArrayList<CodeItem>()
            : CodeStore.inCategory(items, selectedCategory);

        if (shown.isEmpty()) {
            grid.setLayout(new BoxLayout(grid, BoxLayout.Y_AXIS));
            // 分類是從項目推出來的，不會有空分類 —— 走到這裡就是整份清單都空的，
            // 不論檔案在不在，該說的都是「去新增」而不是「這個分類沒有」
            JLabel hint = new JLabel("尚未設定代碼，按右上角「編輯」新增");
            hint.setForeground(MUTED);
            hint.setFont(hint.getFont().deriveFont(11f));
            hint.setAlignmentX(Component.LEFT_ALIGNMENT);
            grid.add(hint);
        } else {
            int rows = (shown.size() + COLUMNS - 1) / COLUMNS;
            grid.setLayout(new GridLayout(rows, COLUMNS, 4, 4));
            for (int i = 0; i < shown.size(); i++) {
                grid.add(keyButton(shown.get(i)));
            }
            // 補滿最後一列，避免按鈕被拉寬
            for (int i = shown.size(); i < rows * COLUMNS; i++) {
                JPanel filler = new JPanel();
                filler.setOpaque(false);
                grid.add(filler);
            }
        }
        grid.revalidate();
        grid.repaint();
    }

    /**
     * 一顆代碼鍵：名稱在上、代碼在下。
     *
     * 刻意不用 HTML 按鈕文字 —— 各家 Look and Feel 給 JButton 的內距差很多，
     * HTML 排版器會拿被壓縮的可用寬度去算換行，五個字的名稱就被斷成 4+1。
     * 改成把兩個置中的 JLabel 放進按鈕裡，寬度由我們自己控制，各種 L&F 都一致。
     */
    private JButton keyButton(final CodeItem item) {
        JButton button = new JButton();
        button.setFocusable(false);
        button.setLayout(new BoxLayout(button, BoxLayout.Y_AXIS));
        button.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER),
            BorderFactory.createEmptyBorder(6, 2, 6, 2)));
        button.setMargin(new Insets(0, 0, 0, 0));
        button.setPreferredSize(new Dimension(CELL_WIDTH, BUTTON_HEIGHT));
        button.setToolTipText(item.name + "（" + item.code + "）點一下帶入 POS");
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JLabel name = new JLabel(item.name);
        name.setFont(name.getFont().deriveFont(Font.BOLD, 12f));
        name.setAlignmentX(Component.CENTER_ALIGNMENT);
        button.add(name);

        JLabel code = new JLabel(item.code);
        code.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
        code.setForeground(MUTED);
        code.setAlignmentX(Component.CENTER_ALIGNMENT);
        button.add(javax.swing.Box.createVerticalStrut(2));
        button.add(code);

        button.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                Safe.guard("帶入結帳代碼", new Runnable() {
                    public void run() {
                        press(item);
                    }
                });
            }
        });
        return button;
    }

    private void press(CodeItem item) {
        if (applier == null) {
            status.setText("這個畫面不支援帶入");
            return;
        }
        boolean ok = applier.applyCode(item.code);
        status.setText(ok
            ? "已帶入 " + item.name
            : "POS 目前不接受帶入");
    }

}
