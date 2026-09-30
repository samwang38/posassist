package com.posassist;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DropMode;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.ListSelectionModel;
import javax.swing.TransferHandler;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

/**
 * 結帳代碼編輯器。modal 視窗，改完要按「儲存」才會寫檔。
 *
 * 左邊是分類樹（主分類 → 子分類），右邊只列選中那一群的代碼。分類由樹上的位置決定、
 * 不再手打，打錯字多出一個頁籤的事就不會發生；排序可以直接拖曳，或一鍵移到頂／底。
 * 資料放在 CodeTree，存檔時攤平回 codes.txt，格式與以前完全相同。
 *
 * 誤觸防護：編輯藏在面板的「編輯」後面、關閉前若有未儲存變更會再問一次、
 * 每次儲存由 CodeStore 留一份 .bak。
 */
public final class CodeEditor {

    private static final String[] GROUP_COLUMNS = { "名稱", "代碼", "釘選" };
    private static final String[] SEARCH_COLUMNS = { "分類", "名稱", "代碼", "釘選" };

    private final JDialog dialog;
    private CodeTree tree;
    /** 有設同步資料夾才有值。base 是打開編輯器時本機對應的同步版本，存檔時拿來判斷別台改過沒有。 */
    private final CodeSync sync = CodeSync.configured();
    private String base;
    /** 背景存檔中：擋住畫面操作，也不讓人關視窗。 */
    private boolean busy;
    private final DefaultTreeModel treeModel = new DefaultTreeModel(new DefaultMutableTreeNode());
    private final JTree categoryTree = new JTree(treeModel);
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JTextField search = new JTextField();
    private final JLabel groupTitle = new JLabel(" ");
    private final JLabel message = new JLabel(" ");

    /** 需要「選中某一群」才能用的按鈕，以及搜尋模式下不能用的排序按鈕。 */
    private final List<JButton> groupButtons = new ArrayList<JButton>();
    private final List<JButton> orderButtons = new ArrayList<JButton>();
    private final List<JButton> nodeButtons = new ArrayList<JButton>();

    /** 目前右邊顯示的群組。currentCategory 為 null 代表還沒有任何分類。 */
    private String currentCategory;
    private String currentSub = CodeTree.LOOSE;
    /** 非 null 代表搜尋模式：右邊列的是跨分類的搜尋結果。 */
    private List<CodeTree.Hit> hits;
    /** 重建樹的時候會連發選取事件，那些不是使用者點的，不要反應。 */
    private boolean rebuilding;

    private boolean dirty;
    private boolean saved;

    public CodeEditor(Window owner) {
        tree = CodeTree.of(CodeStore.load());
        base = sync == null ? null : sync.syncedFingerprint();

        categoryTree.setRootVisible(false);
        categoryTree.setShowsRootHandles(true);
        categoryTree.setRowHeight(24);
        categoryTree.getSelectionModel().setSelectionMode(
            TreeSelectionModel.SINGLE_TREE_SELECTION);
        categoryTree.addTreeSelectionListener(event -> Safe.guard("代碼編輯：選分類", () -> {
            if (!rebuilding) {
                nodeSelected();
            }
        }));

        table.setRowHeight(26);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.setSurrendersFocusOnKeystroke(true);
        table.setFillsViewportHeight(true);
        table.setDragEnabled(true);
        table.setDropMode(DropMode.INSERT_ROWS);
        table.setTransferHandler(new RowMover());
        applyColumnWidths();

        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) {
                searchChanged();
            }

            public void removeUpdate(DocumentEvent event) {
                searchChanged();
            }

            public void changedUpdate(DocumentEvent event) {
                searchChanged();
            }
        });

        dialog = new JDialog(owner, "編輯結帳代碼", JDialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.setContentPane(buildContent());
        dialog.setGlassPane(buildBusyPane());
        dialog.setSize(820, 540);
        dialog.setMinimumSize(new Dimension(640, 400));
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

        List<String> categories = tree.categories();
        rebuildTree(categories.isEmpty() ? null : categories.get(0), CodeTree.LOOSE);
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
            "<html>左邊選分類，右邊編輯該分類的代碼；列可以直接拖曳調整順序。"
            + "這裡的順序就是面板上的顯示順序。<br>"
            + "點主分類編輯「未分段」的代碼，點子分類編輯該段；勾「釘選」的固定顯示在面板最上面。</html>");
        hint.setFont(Style.caption(hint.getFont()));
        root.add(hint, BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
            buildTreeSide(), buildItemSide());
        split.setDividerLocation(230);
        split.setBorder(BorderFactory.createEmptyBorder());
        root.add(split, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(8, 0));
        message.setFont(Style.caption(message.getFont()));
        message.setForeground(Style.DANGER);
        south.add(message, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.add(button("取消", this::cancel));
        actions.add(button("儲存", this::save));
        south.add(actions, BorderLayout.EAST);
        root.add(south, BorderLayout.SOUTH);
        return root;
    }

    private JPanel buildTreeSide() {
        JPanel side = new JPanel(new BorderLayout(0, 6));
        side.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));
        JLabel title = new JLabel("分類");
        title.setFont(Style.heading(title.getFont()));
        title.setForeground(Style.ACCENT);
        side.add(title, BorderLayout.NORTH);
        side.add(new JScrollPane(categoryTree), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new GridLayout(0, 3, 4, 4));
        buttons.add(button("＋分類", this::addCategory));
        buttons.add(node(button("＋子分類", this::addSub)));
        buttons.add(node(button("改名", this::renameNode)));
        buttons.add(node(button("刪除", this::removeNode)));
        buttons.add(node(button("上移", () -> moveNode(-1))));
        buttons.add(node(button("下移", () -> moveNode(1))));
        side.add(buttons, BorderLayout.SOUTH);
        return side;
    }

    private JPanel buildItemSide() {
        JPanel side = new JPanel(new BorderLayout(0, 6));
        side.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));

        JPanel top = new JPanel(new BorderLayout(8, 0));
        groupTitle.setFont(Style.heading(groupTitle.getFont()));
        groupTitle.setForeground(Style.ACCENT);
        top.add(groupTitle, BorderLayout.CENTER);
        JPanel find = new JPanel(new BorderLayout(4, 0));
        find.add(new JLabel("搜尋"), BorderLayout.WEST);
        search.setColumns(12);
        search.setToolTipText("輸入名稱或代碼，跨所有分類尋找");
        find.add(search, BorderLayout.CENTER);
        top.add(find, BorderLayout.EAST);
        side.add(top, BorderLayout.NORTH);

        side.add(new JScrollPane(table), BorderLayout.CENTER);

        // 兩列格狀：放一排的話視窗一窄最後幾顆就被切掉、點不到
        JPanel bar = new JPanel(new GridLayout(2, 4, 4, 4));
        bar.add(group(button("新增", this::addItem)));
        bar.add(group(button("刪除", this::removeItem)));
        bar.add(order(button("移到分類…", this::moveItemToGroup)));
        bar.add(new JLabel());
        bar.add(order(button("上移", () -> moveItem(-1))));
        bar.add(order(button("下移", () -> moveItem(1))));
        bar.add(order(button("移到頂", () -> moveItemToEnd(true))));
        bar.add(order(button("移到底", () -> moveItemToEnd(false))));
        side.add(bar, BorderLayout.SOUTH);
        return side;
    }

    private JButton button(String text, final Runnable action) {
        JButton button = new JButton(text);
        button.setFocusable(false);
        button.setMargin(new Insets(2, 4, 2, 4));
        button.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                Safe.guard("代碼編輯：" + text, action);
            }
        });
        return button;
    }

    private JButton node(JButton button) {
        nodeButtons.add(button);
        return button;
    }

    private JButton group(JButton button) {
        groupButtons.add(button);
        return button;
    }

    private JButton order(JButton button) {
        orderButtons.add(button);
        return button;
    }

    // -- 分類樹 ------------------------------------------------------------

    /** 樹上一個節點：主分類節點的 sub 是空字串，代表該分類的「未分段」群組。 */
    private final class Node {
        final String category;
        final String sub;

        Node(String category, String sub) {
            this.category = category;
            this.sub = sub;
        }

        public String toString() {
            return CodeTree.LOOSE.equals(sub)
                ? category + "（" + tree.total(category) + "）"
                : sub + "（" + tree.count(category, sub) + "）";
        }
    }

    /** 結構變動後整棵重建，再選回指定的群組（找不到就選第一個）。 */
    private void rebuildTree(String category, String sub) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        List<String> categories = tree.categories();
        for (int c = 0; c < categories.size(); c++) {
            String name = categories.get(c);
            DefaultMutableTreeNode parent = new DefaultMutableTreeNode(
                new Node(name, CodeTree.LOOSE));
            List<String> subs = tree.subs(name);
            for (int s = 0; s < subs.size(); s++) {
                parent.add(new DefaultMutableTreeNode(new Node(name, subs.get(s))));
            }
            root.add(parent);
        }
        rebuilding = true;
        try {
            treeModel.setRoot(root);
            for (int row = 0; row < categoryTree.getRowCount(); row++) {
                categoryTree.expandRow(row);
            }
            if (!tree.hasGroup(category, sub)) {
                category = categories.isEmpty() ? null : categories.get(0);
                sub = CodeTree.LOOSE;
            }
            currentCategory = category;
            currentSub = sub;
            TreePath path = pathOf(category, sub);
            if (path != null) {
                categoryTree.setSelectionPath(path);
                categoryTree.scrollPathToVisible(path);
            } else {
                categoryTree.clearSelection();
            }
        } finally {
            rebuilding = false;
        }
        refreshTable();
    }

    /** 只有數量變了（搬移、新增、刪除代碼），節點文字要跟著更新，但不需要重建。 */
    private void refreshTreeLabels() {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
        Enumeration<?> nodes = root.breadthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            treeModel.nodeChanged((DefaultMutableTreeNode) nodes.nextElement());
        }
    }

    private TreePath pathOf(String category, String sub) {
        if (category == null) {
            return null;
        }
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
        Enumeration<?> nodes = root.breadthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
            Object value = node.getUserObject();
            if (value instanceof Node && ((Node) value).category.equals(category)
                && ((Node) value).sub.equals(sub)) {
                return new TreePath(node.getPath());
            }
        }
        return null;
    }

    private Node selectedNode() {
        TreePath path = categoryTree.getSelectionPath();
        Object value = path == null ? null
            : ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        return value instanceof Node ? (Node) value : null;
    }

    private void nodeSelected() {
        stopEditing();
        Node node = selectedNode();
        if (node == null) {
            return;
        }
        currentCategory = node.category;
        currentSub = node.sub;
        if (search.getText().length() != 0) {
            search.setText("");     // 會觸發 searchChanged → refreshTable
        } else {
            refreshTable();
        }
        message.setText(" ");
    }

    private void addCategory() {
        stopEditing();
        String name = ask("新分類名稱", "");
        if (name == null) {
            return;
        }
        String problem = tree.addCategory(name);
        if (problem != null) {
            message.setText(problem);
            return;
        }
        dirty = true;
        rebuildTree(name.trim(), CodeTree.LOOSE);
        message.setText("已新增分類「" + name.trim() + "」，再按「新增」加入代碼");
    }

    private void addSub() {
        stopEditing();
        Node node = selectedNode();
        if (node == null) {
            return;
        }
        String name = ask("在「" + node.category + "」底下新增子分類", "");
        if (name == null) {
            return;
        }
        String problem = tree.addSub(node.category, name);
        if (problem != null) {
            message.setText(problem);
            return;
        }
        dirty = true;
        rebuildTree(node.category, name.trim());
        message.setText(" ");
    }

    private void renameNode() {
        stopEditing();
        Node node = selectedNode();
        if (node == null) {
            return;
        }
        boolean isSub = !CodeTree.LOOSE.equals(node.sub);
        String old = isSub ? node.sub : node.category;
        String name = ask(isSub ? "子分類改名" : "分類改名", old);
        if (name == null || name.trim().equals(old)) {
            return;
        }
        String next = name.trim();
        boolean merge = isSub ? tree.hasGroup(node.category, next) : tree.hasCategory(next);
        if (merge && !confirm("已經有「" + next + "」，要把「" + old + "」的代碼合併過去嗎？")) {
            return;
        }
        String problem = isSub
            ? tree.renameSub(node.category, old, next)
            : tree.renameCategory(old, next);
        if (problem != null) {
            message.setText(problem);
            return;
        }
        dirty = true;
        if (isSub) {
            rebuildTree(node.category, next);
        } else {
            rebuildTree(next, CodeTree.LOOSE);
        }
        message.setText(" ");
    }

    private void removeNode() {
        stopEditing();
        Node node = selectedNode();
        if (node == null) {
            return;
        }
        if (!CodeTree.LOOSE.equals(node.sub)) {
            int count = tree.count(node.category, node.sub);
            if (count > 0) {
                Object[] options = { "移到未分段", "一起刪除", "取消" };
                int answer = JOptionPane.showOptionDialog(dialog,
                    "子分類「" + node.sub + "」裡還有 " + count + " 筆代碼，要怎麼處理？",
                    "刪除子分類", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                    null, options, options[0]);
                if (answer == 0) {
                    while (tree.count(node.category, node.sub) > 0) {
                        tree.moveItemTo(node.category, node.sub, 0,
                            node.category, CodeTree.LOOSE);
                    }
                } else if (answer != 1) {
                    return;
                }
            }
            tree.removeSub(node.category, node.sub);
            dirty = true;
            rebuildTree(node.category, CodeTree.LOOSE);
            return;
        }
        int total = tree.total(node.category);
        if (total > 0 && !confirm("分類「" + node.category + "」裡的 " + total
            + " 筆代碼會一起刪除，確定嗎？")) {
            return;
        }
        List<String> categories = tree.categories();
        int at = categories.indexOf(node.category);
        tree.removeCategory(node.category);
        dirty = true;
        categories = tree.categories();
        String next = categories.isEmpty() ? null
            : categories.get(Math.min(Math.max(at, 0), categories.size() - 1));
        rebuildTree(next, CodeTree.LOOSE);
    }

    private void moveNode(int delta) {
        stopEditing();
        Node node = selectedNode();
        if (node == null) {
            return;
        }
        boolean moved = CodeTree.LOOSE.equals(node.sub)
            ? tree.moveCategory(node.category, delta)
            : tree.moveSub(node.category, node.sub, delta);
        if (moved) {
            dirty = true;
            rebuildTree(node.category, node.sub);
        }
    }

    // -- 代碼 --------------------------------------------------------------

    private void searchChanged() {
        Safe.guard("代碼編輯：搜尋", new Runnable() {
            public void run() {
                stopEditing();
                refreshTable();
            }
        });
    }

    private void refreshTable() {
        String query = search.getText().trim();
        boolean searching = query.length() != 0;
        boolean modeChanged = searching != (hits != null);
        hits = searching ? tree.search(query) : null;
        if (modeChanged) {
            model.fireTableStructureChanged();
            applyColumnWidths();
        } else {
            model.fireTableDataChanged();
        }
        if (searching) {
            groupTitle.setText("搜尋結果：" + hits.size() + " 筆");
        } else if (currentCategory == null) {
            groupTitle.setText("還沒有分類，先按左下「＋分類」");
        } else if (CodeTree.LOOSE.equals(currentSub)) {
            groupTitle.setText(tree.subs(currentCategory).isEmpty()
                ? currentCategory : currentCategory + " › 未分段");
        } else {
            groupTitle.setText(currentCategory + " › " + currentSub);
        }
        updateButtons();
    }

    private void applyColumnWidths() {
        int offset = hits != null ? 1 : 0;
        if (hits != null) {
            table.getColumnModel().getColumn(0).setPreferredWidth(140);
        }
        table.getColumnModel().getColumn(offset).setPreferredWidth(200);
        table.getColumnModel().getColumn(offset + 1).setPreferredWidth(130);
        table.getColumnModel().getColumn(offset + 2).setPreferredWidth(50);
        table.getColumnModel().getColumn(offset + 2).setMaxWidth(60);
    }

    private void updateButtons() {
        boolean hasGroup = currentCategory != null;
        for (int i = 0; i < groupButtons.size(); i++) {
            groupButtons.get(i).setEnabled(hasGroup);
        }
        for (int i = 0; i < orderButtons.size(); i++) {
            orderButtons.get(i).setEnabled(hasGroup && hits == null);
        }
        for (int i = 0; i < nodeButtons.size(); i++) {
            nodeButtons.get(i).setEnabled(hasGroup);
        }
    }

    private void addItem() {
        stopEditing();
        if (currentCategory == null) {
            message.setText("請先新增分類");
            return;
        }
        if (hits != null) {
            search.setText("");
        }
        int row = tree.addItem(currentCategory, currentSub,
            CodeItem.of(currentCategory, currentSub, "", "", false));
        dirty = true;
        refreshTable();
        refreshTreeLabels();
        select(row);
        table.editCellAt(row, 0);
        if (table.getEditorComponent() != null) {
            table.getEditorComponent().requestFocusInWindow();
        }
        message.setText(" ");
    }

    private void removeItem() {
        stopEditing();
        int row = table.getSelectedRow();
        if (row < 0) {
            message.setText("請先選一列");
            return;
        }
        if (hits != null) {
            CodeTree.Hit hit = hits.get(row);
            tree.removeItem(hit.category, hit.sub, hit.index);
        } else {
            tree.removeItem(currentCategory, currentSub, row);
        }
        dirty = true;
        refreshTable();
        refreshTreeLabels();
        if (model.getRowCount() > 0) {
            select(Math.min(row, model.getRowCount() - 1));
        }
        message.setText(" ");
    }

    private void moveItem(int delta) {
        stopEditing();
        int row = table.getSelectedRow();
        if (row < 0) {
            message.setText("請先選一列");
            return;
        }
        int target = row + delta;
        if (target < 0 || target >= model.getRowCount()) {
            return;
        }
        moveRow(row, target);
    }

    private void moveItemToEnd(boolean top) {
        stopEditing();
        int row = table.getSelectedRow();
        if (row < 0) {
            message.setText("請先選一列");
            return;
        }
        moveRow(row, top ? 0 : model.getRowCount() - 1);
    }

    private void moveRow(int from, int to) {
        int at = tree.moveItem(currentCategory, currentSub, from, to);
        if (at < 0 || at == from) {
            return;
        }
        dirty = true;
        refreshTable();
        select(at);
        message.setText(" ");
    }

    /** 下拉選單的一個選項：某個分類的某一群。 */
    private static final class Target {
        final String category;
        final String sub;

        Target(String category, String sub) {
            this.category = category;
            this.sub = sub;
        }

        public String toString() {
            return CodeTree.LOOSE.equals(sub) ? category : category + " › " + sub;
        }
    }

    private void moveItemToGroup() {
        stopEditing();
        int row = table.getSelectedRow();
        if (row < 0) {
            message.setText("請先選一列");
            return;
        }
        List<Target> targets = new ArrayList<Target>();
        List<String> categories = tree.categories();
        for (int c = 0; c < categories.size(); c++) {
            String name = categories.get(c);
            if (!(name.equals(currentCategory) && CodeTree.LOOSE.equals(currentSub))) {
                targets.add(new Target(name, CodeTree.LOOSE));
            }
            List<String> subs = tree.subs(name);
            for (int s = 0; s < subs.size(); s++) {
                if (!(name.equals(currentCategory) && subs.get(s).equals(currentSub))) {
                    targets.add(new Target(name, subs.get(s)));
                }
            }
        }
        if (targets.isEmpty()) {
            message.setText("沒有其他分類可以移過去，先新增一個");
            return;
        }
        Object choice = JOptionPane.showInputDialog(dialog,
            "把「" + model.item(row).name + "」移到：", "移到分類",
            JOptionPane.QUESTION_MESSAGE, null, targets.toArray(), targets.get(0));
        if (!(choice instanceof Target)) {
            return;
        }
        Target target = (Target) choice;
        if (tree.moveItemTo(currentCategory, currentSub, row, target.category, target.sub)) {
            dirty = true;
            refreshTable();
            refreshTreeLabels();
            message.setText("已移到「" + target + "」");
        }
    }

    private void select(int row) {
        if (row >= 0 && row < model.getRowCount()) {
            table.setRowSelectionInterval(row, row);
            table.scrollRectToVisible(table.getCellRect(row, 0, true));
        }
    }

    // -- 存檔／取消 ---------------------------------------------------------

    private void save() {
        stopEditing();
        CodeTree.Hit bad = tree.firstInvalid();
        if (bad != null) {
            search.setText("");
            rebuildTree(bad.category, bad.sub);
            select(bad.index);
            message.setText(new Target(bad.category, bad.sub) + " 第 " + (bad.index + 1)
                + " 筆：" + bad.item.validate());
            return;
        }
        List<String> empty = tree.emptyCategories();
        if (!empty.isEmpty() && !confirm("這些分類沒有任何代碼，存檔後不會保留：\n"
            + empty + "\n\n要繼續存檔嗎？")) {
            return;
        }
        List<CodeItem> items = tree.flatten();
        if (sync != null) {
            push(items, false);
            return;
        }
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

    /** 存到同步資料夾。碰資料夾的動作在背景做，結果回到 EDT 再處理。 */
    private void push(final List<CodeItem> items, final boolean force) {
        setBusy(true, "儲存中…");
        CodeSync.submit(new Runnable() {
            public void run() {
                CodeSync.Result result;
                try {
                    result = sync.push(items, base, force);
                } catch (Throwable t) {
                    PosLog.warn("同步存檔失敗", t);
                    result = new CodeSync.Result(CodeSync.Outcome.FAILED, "儲存失敗");
                }
                final CodeSync.Result done = result;
                FloatingPanel.onEdt(new Runnable() {
                    public void run() {
                        pushed(items, done);
                    }
                });
            }
        });
    }

    private void pushed(List<CodeItem> items, CodeSync.Result result) {
        setBusy(false, " ");
        switch (result.outcome) {
            case SAVED:
                saved = true;
                dirty = false;
                dialog.dispose();
                return;
            case OFFLINE:
                saved = true;
                dirty = false;
                JOptionPane.showMessageDialog(dialog, result.message, "編輯結帳代碼",
                    JOptionPane.INFORMATION_MESSAGE);
                dialog.dispose();
                return;
            case CONFLICT:
                Object[] options = { "用我的覆蓋", "載入別台的版本", "取消" };
                int answer = JOptionPane.showOptionDialog(dialog,
                    "你打開編輯器之後，別台已經改過結帳代碼。\n"
                    + "「用我的覆蓋」會蓋掉別台的修改；「載入別台的版本」會放棄你這次的修改。",
                    "別台已經改過", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                    null, options, options[2]);
                if (answer == 0) {
                    push(items, true);
                } else if (answer == 1) {
                    reloadFromFolder();
                } else {
                    message.setText("還沒儲存");
                }
                return;
            default:
                message.setText(result.message == null ? "儲存失敗" : result.message);
        }
    }

    /** 衝突時選了「載入別台的版本」：拉下來、整棵重建，讓人在最新版上重新改。 */
    private void reloadFromFolder() {
        setBusy(true, "載入中…");
        CodeSync.submit(new Runnable() {
            public void run() {
                final CodeSync.Result result = sync.pull();
                FloatingPanel.onEdt(new Runnable() {
                    public void run() {
                        setBusy(false, " ");
                        saved = true;      // 本機代碼換過了，關掉時面板要重新載入
                        dirty = false;
                        tree = CodeTree.of(CodeStore.load());
                        base = sync.syncedFingerprint();
                        search.setText("");
                        List<String> categories = tree.categories();
                        rebuildTree(categories.isEmpty() ? null : categories.get(0),
                            CodeTree.LOOSE);
                        message.setText(result.outcome == CodeSync.Outcome.UPDATED
                            ? "已載入別台的版本，剛才的修改沒有存，請重新修改"
                            : result.message == null ? "已重新載入" : result.message);
                    }
                });
            }
        });
    }

    /** 蓋在整個視窗上的透明層：存檔中吃掉滑鼠、顯示等待游標。 */
    private JComponent buildBusyPane() {
        JPanel pane = new JPanel();
        pane.setOpaque(false);
        pane.addMouseListener(new java.awt.event.MouseAdapter() {
        });
        pane.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
        });
        pane.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
        return pane;
    }

    private void setBusy(boolean value, String text) {
        busy = value;
        dialog.getGlassPane().setVisible(value);
        message.setText(text);
    }

    private void cancel() {
        if (busy) {
            return;
        }
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

    private String ask(String prompt, String initial) {
        Object value = JOptionPane.showInputDialog(dialog, prompt, "編輯結帳代碼",
            JOptionPane.PLAIN_MESSAGE, null, null, initial);
        return value == null ? null : String.valueOf(value);
    }

    private boolean confirm(String text) {
        return JOptionPane.showConfirmDialog(dialog, text, "編輯結帳代碼",
            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
            == JOptionPane.YES_OPTION;
    }

    // -- 表格模型 ----------------------------------------------------------

    /** 一般模式列出目前群組；搜尋模式列出搜尋結果，多一欄分類（唯讀）。 */
    private final class Model extends AbstractTableModel {

        private String[] columns() {
            return hits != null ? SEARCH_COLUMNS : GROUP_COLUMNS;
        }

        CodeItem item(int row) {
            if (hits != null) {
                return hits.get(row).item;
            }
            return tree.items(currentCategory, currentSub).get(row);
        }

        public int getRowCount() {
            if (hits != null) {
                return hits.size();
            }
            return currentCategory == null ? 0 : tree.count(currentCategory, currentSub);
        }

        public int getColumnCount() {
            return columns().length;
        }

        public String getColumnName(int column) {
            return columns()[column];
        }

        /** 釘選欄回 Boolean，JTable 才會畫成打勾框而不是文字。 */
        public Class<?> getColumnClass(int column) {
            return column == columns().length - 1 ? Boolean.class : String.class;
        }

        public boolean isCellEditable(int row, int column) {
            return !(hits != null && column == 0);
        }

        public Object getValueAt(int row, int column) {
            CodeItem item = item(row);
            if (hits != null) {
                if (column == 0) {
                    CodeTree.Hit hit = hits.get(row);
                    return new Target(hit.category, hit.sub).toString();
                }
                column--;
            }
            switch (column) {
                case 0:
                    return item.name;
                case 1:
                    return item.code;
                default:
                    return Boolean.valueOf(item.pinned);
            }
        }

        public void setValueAt(Object value, int row, int column) {
            String category = currentCategory;
            String sub = currentSub;
            int index = row;
            int field = column;
            if (hits != null) {
                CodeTree.Hit hit = hits.get(row);
                category = hit.category;
                sub = hit.sub;
                index = hit.index;
                field--;
            }
            CodeItem old = item(row);
            String text = value == null ? "" : String.valueOf(value).trim();
            CodeItem next = CodeItem.of(category, sub,
                field == 0 ? text : old.name,
                field == 1 ? text : old.code,
                field == 2 ? Boolean.TRUE.equals(value) : old.pinned);
            tree.setItem(category, sub, index, next);
            if (hits != null) {
                hits.set(row, new CodeTree.Hit(category, sub, index, next));
            }
            dirty = true;
            message.setText(" ");
            fireTableCellUpdated(row, column);
        }
    }

    /** 列內拖曳排序。只在一般模式有效 —— 搜尋結果跨分類，沒有「順序」可言。 */
    private final class RowMover extends TransferHandler {

        public int getSourceActions(JComponent component) {
            return MOVE;
        }

        protected Transferable createTransferable(JComponent component) {
            return new StringSelection(String.valueOf(table.getSelectedRow()));
        }

        public boolean canImport(TransferSupport support) {
            return support.isDrop() && hits == null && currentCategory != null
                && support.isDataFlavorSupported(DataFlavor.stringFlavor);
        }

        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }
            try {
                int from = Integer.parseInt(String.valueOf(
                    support.getTransferable().getTransferData(DataFlavor.stringFlavor)));
                int to = ((JTable.DropLocation) support.getDropLocation()).getRow();
                if (from < 0 || from >= model.getRowCount()) {
                    return false;
                }
                // 插入點在原位置下方時，拿掉原本那筆之後會往前挪一格
                if (to > from) {
                    to--;
                }
                stopEditing();
                moveRow(from, to);
                return true;
            } catch (Exception error) {
                PosLog.warn("代碼拖曳排序失敗", error);
                return false;
            }
        }
    }
}
