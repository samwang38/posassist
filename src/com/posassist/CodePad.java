package com.posassist;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;

/**
 * 自訂結帳代碼面板：釘選的固定在最上面，接著分類頁籤，下面是代碼格子。
 * 主分類底下還能再分子分類，格子區就依子分類分段，每段一個小標。
 *
 * 欄數與頁籤列都跟著實際寬度走：側欄拉寬就多排幾個格子（不是把格子撐胖），
 * 頁籤一列排不下就換行。各店的 EPB 分隔線拉到哪不一樣，寫死寬度撐不住。
 *
 * 點一顆按鈕就把代碼交給呼叫端帶進 POS，不跳確認 —— 誤觸的後果是 POS 多一筆品項，
 * 店員當場看得到也刪得掉，不會有錯誤資料默默寫進交易。
 *
 * 釘選改在右鍵選單裡，理由同上反過來：左鍵是店員一天按幾十次的動作，
 * 不能讓「調整版面」跟「帶入品項」共用同一個手勢。
 */
public final class CodePad extends JPanel {

    /** 由 FloatingPanel 提供：把代碼帶進 POS。回傳是否成功。 */
    public interface CodeApplier {
        boolean applyCode(String code);
    }

    private static final int GAP = 3;
    /** 名稱一行、代碼一行，剛好包住文字，不留多餘的白。 */
    private static final int BUTTON_HEIGHT = 40;
    /**
     * 一格最少要這麼寬（5 個中文字的名稱放得下一行）。欄數由實際可用寬度算，
     * 不是寫死的 —— 側欄有多寬要看各店 EPB 分隔線拉到哪，寫死只會讓寬的機器
     * 把每個格子撐胖、空一大片白。
     */
    private static final int MIN_CELL = 68;

    private static final Color ACCENT = new Color(0x1D, 0x4E, 0x89);
    private static final Color MUTED = new Color(0x66, 0x66, 0x70);
    private static final Color TAB_ON = new Color(0xE8, 0xEF, 0xF8);
    private static final Color BORDER = new Color(0xC3, 0xC9, 0xD2);

    private final JPanel tabBar = new JPanel();
    private final JPanel pinnedGrid = new JPanel(new CellGrid()) {
        // 跟 section() 同一個理由：不鎖最大高度，垂直 BoxLayout 會把它拉長
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    };
    private final Component pinnedGap = javax.swing.Box.createVerticalStrut(4);
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

        WrapFlow.install(tabBar, 4, 3);

        JPanel body = new JPanel(new BorderLayout(0, 4));
        body.setOpaque(false);

        // 釘選區與頁籤列都固定在捲動區之外，切分類時位置不會動
        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        pinnedGrid.setOpaque(false);
        pinnedGrid.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(pinnedGrid);
        top.add(pinnedGap);
        tabBar.setOpaque(false);
        tabBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(tabBar);
        body.add(top, BorderLayout.NORTH);

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
        button.setToolTipText("新增、修改、刪除、釘選或調整順序");
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
        refresh();
    }

    /** 依現有清單重算分類並重繪三個區塊。 */
    private void refresh() {
        categories = CodeStore.categories(items);
        if (selectedCategory == null || !categories.contains(selectedCategory)) {
            selectedCategory = categories.isEmpty() ? null : categories.get(0);
        }
        rebuildPinned();
        rebuildTabs();
        rebuildGrid();
    }

    private void rebuildPinned() {
        pinnedGrid.removeAll();
        List<CodeItem> marked = CodeStore.pinned(items);
        fill(pinnedGrid, marked);
        pinnedGrid.setVisible(!marked.isEmpty());
        pinnedGap.setVisible(!marked.isEmpty());
        pinnedGrid.revalidate();
        pinnedGrid.repaint();
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

    /**
     * 格子區依子分類分段：一段一個小標，底下自己一塊 4 欄格子。
     * 沒填子分類的那一段不畫小標，所以沒在用子分類的人看到的畫面跟以前一樣。
     */
    private void rebuildGrid() {
        grid.removeAll();
        grid.setLayout(new BoxLayout(grid, BoxLayout.Y_AXIS));

        if (items.isEmpty()) {
            // 分類是從項目推出來的，不會有空分類 —— 走到這裡就是整份清單都空的，
            // 不論檔案在不在，該說的都是「去新增」而不是「這個分類沒有」
            JLabel hint = new JLabel("尚未設定代碼，按右上角「編輯」新增");
            hint.setForeground(MUTED);
            hint.setFont(hint.getFont().deriveFont(11f));
            hint.setAlignmentX(Component.LEFT_ALIGNMENT);
            grid.add(hint);
        } else if (selectedCategory != null) {
            // 清單非空但這裡沒東西，是因為代碼全被釘到上面去了，不必再說什麼
            List<String> subs = CodeStore.subCategories(items, selectedCategory);
            for (int i = 0; i < subs.size(); i++) {
                String sub = subs.get(i);
                if (i > 0) {
                    grid.add(javax.swing.Box.createVerticalStrut(6));
                }
                if (sub.length() > 0) {
                    grid.add(sectionLabel(sub));
                }
                grid.add(section(CodeStore.inCategory(items, selectedCategory, sub)));
            }
        }
        grid.revalidate();
        grid.repaint();
    }

    private JLabel sectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(MUTED);
        label.setFont(label.getFont().deriveFont(11f));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(BorderFactory.createEmptyBorder(0, 1, 2, 0));
        return label;
    }

    /**
     * 一段的格子。最大高度要鎖成 preferred —— 格子的最大高度沒有上限，
     * 直接丟進垂直 BoxLayout 會被拉長，按鈕跟著變高。
     * 高度隨欄數變，所以用 override 而不是 setMaximumSize 存一個當下的值。
     */
    private JPanel section(List<CodeItem> shown) {
        JPanel panel = new JPanel(new CellGrid()) {
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        panel.setOpaque(false);
        fill(panel, shown);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    /** 放進格子裡。欄數與每格寬度由 CellGrid 依實際可用寬度決定。 */
    private void fill(JPanel panel, List<CodeItem> shown) {
        for (int i = 0; i < shown.size(); i++) {
            panel.add(keyButton(shown.get(i)));
        }
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
            BorderFactory.createLineBorder(item.pinned ? ACCENT : BORDER),
            BorderFactory.createEmptyBorder(3, 1, 3, 1)));
        button.setMargin(new Insets(0, 0, 0, 0));
        button.setPreferredSize(new Dimension(MIN_CELL, BUTTON_HEIGHT));
        button.setToolTipText(item.name + "（" + item.code + "）點一下帶入 POS，"
            + (item.pinned ? "右鍵可取消釘選" : "右鍵可釘選到最上面"));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        if (item.pinned) {
            button.setBackground(TAB_ON);
        }

        // 上下各留一段可伸縮的空白，剩餘高度平均分掉，文字才會落在格子正中間
        button.add(javax.swing.Box.createVerticalGlue());

        JLabel name = new JLabel(item.name);
        name.setFont(name.getFont().deriveFont(Font.BOLD, 11f));
        name.setAlignmentX(Component.CENTER_ALIGNMENT);
        button.add(name);

        JLabel code = new JLabel(item.code);
        code.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 9));
        code.setForeground(MUTED);
        code.setAlignmentX(Component.CENTER_ALIGNMENT);
        button.add(javax.swing.Box.createVerticalStrut(1));
        button.add(code);
        button.add(javax.swing.Box.createVerticalGlue());

        button.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                Safe.guard("帶入結帳代碼", new Runnable() {
                    public void run() {
                        press(item);
                    }
                });
            }
        });
        button.addMouseListener(new MouseAdapter() {
            // 兩個事件都看：右鍵選單在 Windows 是放開時觸發，在 macOS 是按下時
            public void mousePressed(MouseEvent event) {
                maybePopup(event, item);
            }

            public void mouseReleased(MouseEvent event) {
                maybePopup(event, item);
            }
        });
        return button;
    }

    private void maybePopup(final MouseEvent event, final CodeItem item) {
        if (!event.isPopupTrigger()) {
            return;
        }
        Safe.guard("開啟代碼選單", new Runnable() {
            public void run() {
                JPopupMenu menu = new JPopupMenu();
                JMenuItem toggle = new JMenuItem(
                    item.pinned ? "取消釘選" : "釘選到最上面");
                toggle.addActionListener(new ActionListener() {
                    public void actionPerformed(ActionEvent ignored) {
                        Safe.guard("切換代碼釘選", new Runnable() {
                            public void run() {
                                togglePin(item);
                            }
                        });
                    }
                });
                menu.add(toggle);
                menu.show(event.getComponent(), event.getX(), event.getY());
            }
        });
    }

    /**
     * 切換釘選並立刻寫檔。以代碼為準，同一個代碼在多個分類都會一起改，
     * 跟釘選檔的存法（一行一個代碼）一致。
     */
    private void togglePin(CodeItem item) {
        boolean target = !item.pinned;
        List<CodeItem> updated = new ArrayList<CodeItem>();
        for (int i = 0; i < items.size(); i++) {
            CodeItem each = items.get(i);
            updated.add(each.code.equals(item.code) ? each.withPinned(target) : each);
        }
        String problem = CodeStore.savePins(updated);
        if (problem != null) {
            status.setText("釘選沒存起來：" + problem);
            return;
        }
        items = updated;
        refresh();
        status.setText(target ? "已釘選 " + item.name : "已取消釘選 " + item.name);
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

    // -- 版面 --------------------------------------------------------------

    /**
     * 代碼格子的排法：每格至少 MIN_CELL 寬，欄數由實際可用寬度算出來，
     * 除不盡的餘數平均補給前面幾欄。側欄拉寬是多排幾個，不是每個變胖。
     */
    private static final class CellGrid implements LayoutManager {

        public void addLayoutComponent(String name, Component comp) {
        }

        public void removeLayoutComponent(Component comp) {
        }

        public Dimension preferredLayoutSize(Container parent) {
            synchronized (parent.getTreeLock()) {
                int count = parent.getComponentCount();
                if (count == 0) {
                    return new Dimension(0, 0);
                }
                int cols = columns(parent);
                int rows = (count + cols - 1) / cols;
                Insets in = parent.getInsets();
                return new Dimension(
                    in.left + in.right + cols * MIN_CELL + (cols - 1) * GAP,
                    in.top + in.bottom + rows * BUTTON_HEIGHT + (rows - 1) * GAP);
            }
        }

        public Dimension minimumLayoutSize(Container parent) {
            Insets in = parent.getInsets();
            return new Dimension(in.left + in.right + MIN_CELL,
                in.top + in.bottom + BUTTON_HEIGHT);
        }

        public void layoutContainer(Container parent) {
            synchronized (parent.getTreeLock()) {
                Insets in = parent.getInsets();
                int cols = columns(parent);
                int width = available(parent);
                int cell = (width - (cols - 1) * GAP) / cols;
                int spare = (width - (cols - 1) * GAP) % cols;   // 餘數補給前幾欄
                int x = in.left;
                for (int i = 0; i < parent.getComponentCount(); i++) {
                    int col = i % cols;
                    if (col == 0) {
                        x = in.left;
                    }
                    int w = cell + (col < spare ? 1 : 0);
                    parent.getComponent(i).setBounds(x,
                        in.top + (i / cols) * (BUTTON_HEIGHT + GAP), w, BUTTON_HEIGHT);
                    x += w + GAP;
                }
            }
        }

        private int columns(Container parent) {
            return Math.max(1, (available(parent) + GAP) / (MIN_CELL + GAP));
        }

        /**
         * 可用寬度。第一次排版時容器自己還不知道多寬，就往上找第一個知道的祖先 ——
         * 跟 Swing 社群那個 WrapLayout 同一招，之後 revalidate 會用真正的寬度再算一次。
         */
        private int available(Container parent) {
            Insets in = parent.getInsets();
            int width = parent.getWidth();
            Container up = parent.getParent();
            while (width == 0 && up != null) {
                width = up.getWidth();
                up = up.getParent();
            }
            if (width == 0) {
                width = 4 * MIN_CELL + 3 * GAP;   // 還是問不到就先當成 4 欄
            }
            return Math.max(MIN_CELL, width - in.left - in.right);
        }
    }

}
