package com.posassist;

import java.awt.*;
import java.awt.event.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;

/** Session-only product selection and stock comparison. No transfer form or export flow. */
final class InventoryPanel extends JPanel {
    final Inventory.Draft draft;
    private InventorySource source;
    private final InventoryTasks tasks = new InventoryTasks();
    private final JComboBox<Inventory.Store> destination = new JComboBox<Inventory.Store>();
    private List<String> waitingSelection;
    private final Window epbOwner;
    private boolean loadingStores;
    private final ProductModel productModel = new ProductModel();
    private final JTable products = table(productModel);
    private final JLabel selectionLabel = new JLabel("尚未選取商品");
    private List<Inventory.Candidate> candidates = new ArrayList<Inventory.Candidate>();
    private final JCheckBox partial = new JCheckBox("顯示所有門市");
    private final JComboBox<Inventory.Category> category = new JComboBox<Inventory.Category>(Inventory.Category.values());
    private final JComboBox<String> shortageFilter = new JComboBox<String>(new String[]{"全部庫存偏低", "本店缺貨、其他店有貨", "低於同店型中位數"});
    private final ShortageModel shortageModel = new ShortageModel();
    private final JTable shortages = table(shortageModel);
    private final JLabel peerLabel = new JLabel("同店型比較包含零庫存門市");
    private final JLabel status = new JLabel("載入門市後開始查詢");
    private final JTabbedPane tabs = new JTabbedPane();
    private Inventory.Snapshot stock, mainStock;
    private JDialog comparison;
    private JTable comparisonTable, expandedProducts;
    private JLabel expandedSelection;
    private final DetailsModel detailsModel = new DetailsModel();
    private final MatrixModel matrixModel = new MatrixModel();
    private boolean changing, closed;
    private final String loginIdentity;

    InventoryPanel(String defaultStore) { this(defaultStore,null); }
    InventoryPanel(String defaultStore, InventorySource source) {this(defaultStore,source,null);}
    InventoryPanel(String defaultStore, InventorySource source,Window epbOwner) {
        super(new BorderLayout(0,8));
        this.epbOwner=epbOwner;this.source = source; draft = new Inventory.Draft(defaultStore);
        loginIdentity = source == null ? InventoryWorkspace.currentIdentity() : "";
        productModel.addTableModelListener(e -> detailsModel.fireTableDataChanged());
        setBackground(Style.PAGE); setBorder(BorderFactory.createEmptyBorder(10,10,8,10));
        JPanel header = vertical();
        JLabel title = new JLabel("庫存工具"); title.setFont(title.getFont().deriveFont(Font.BOLD,17f));
        header.add(title); header.add(Box.createVerticalStrut(5));
        header.add(Box.createVerticalStrut(6));
        destination.setPrototypeDisplayValue(new Inventory.Store("SA004","士林門市","1_APR"));
        header.add(fieldRow("收貨店",destination));
        header.add(actions(button("更新門市",new Runnable(){public void run(){loadStores();}}),
            button("設定",new Runnable(){public void run(){new SettingsDialog(owner()).showDialog();}})));
        add(header,BorderLayout.NORTH);
        tabs.addTab("庫存比較",comparisonBody()); tabs.addTab("主機備貨",mainPage());
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT); tabs.setBackground(Style.PAGE); add(tabs,BorderLayout.CENTER);
        status.setFont(status.getFont().deriveFont(11f)); status.setBorder(BorderFactory.createEmptyBorder(6,0,0,0));
        add(status,BorderLayout.SOUTH);
        setMinimumSize(new Dimension(0,0)); setPreferredSize(new Dimension(1180,760));
        destination.addActionListener(e -> {
            if (changing) return;
            Inventory.Store s=(Inventory.Store)destination.getSelectedItem();
            if (s!=null) { draft.destination(s.id); changed(); mainStock=null; refreshShortages(); consumeWaitingSelection(); }
        });
        partial.addActionListener(e -> {refreshCandidates();stockStatus();});
        category.addActionListener(e -> { cancelWork(); mainStock=null; refreshShortages(); say("請按比較庫存載入此分類",false); });
        shortageFilter.addActionListener(e -> refreshShortages());

    }
    void start() { if (!closed) loadStores(); }
    private InventorySource source() throws Exception { if (source==null) source=new EpbInventorySource(); return source; }
    private Window owner() { return comparison==null?epbOwner:comparison; }

    private JPanel mainPage() {
        JPanel page=new JPanel(new BorderLayout(0,6)); page.setOpaque(false);
        JPanel top=vertical(); top.add(row(category,button("比較庫存",()->loadMain())));
        top.add(shortageFilter); top.add(peerLabel); page.add(top,BorderLayout.NORTH);
        shortages.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        int[] widths={190,70,80,80,85,180};
        for(int i=0;i<widths.length;i++) shortages.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        page.add(scroll(shortages),BorderLayout.CENTER);
        JPanel bottom=vertical(); bottom.add(hint("比較差額依庫存中位數計算，未含銷量、客訂與在途"));
        bottom.add(hint("右捲可看全部欄位；差額為 0 時請自行決定數量"));
        bottom.add(actions(button("用這項商品找貨",()->addShortage()))); page.add(bottom,BorderLayout.SOUTH); return page;
    }
    private <T> void run(String message,Callable<T> work, java.util.function.Consumer<T> done) {
        if (closed) return; say(message,false);
        tasks.run(work,(value,error)->{
            if (closed) return;
            if (!loginIdentity.isEmpty() && !loginIdentity.equals(InventoryWorkspace.currentIdentity())) { close(); return; }
            if (error!=null) { loadingStores=false; say(errorMessage(error)+" · 庫存未知／需更新",true); return; }
            try { done.accept(value); } catch(Exception e) {say(errorMessage(e),true);}
        });
    }
    static String errorMessage(Exception error) {
        if (error instanceof ReflectiveOperationException || error instanceof java.sql.SQLException || error instanceof NumberFormatException)
            return "EPB 庫存模組或回應不相容，請執行自我檢查";
        String message=error.getMessage();
        return message==null ? "庫存查詢失敗，已勾選商品已保留" : message;
    }
    private void loadStores() {
        cancelWork(); loadingStores=true;stock=null; mainStock=null; refreshCandidates(); refreshShortages();
        run("載入可查詢的 SA 門市…",()->source().stores(),stores->{
            loadingStores=false;changing=true;
            destination.removeAllItems(); Inventory.Store selected=null;
            for(Inventory.Store s:stores){destination.addItem(s); if(s.id.equals(draft.destination())) selected=s;}
            destination.setSelectedItem(selected); changing=false;
            say(selected==null?"請選擇收貨店（預設店不在權限範圍內）":"已載入 "+stores.size()+" 間一般門市",false);
            consumeWaitingSelection();
        });
    }
    private boolean ready() {
        if(destination.getSelectedItem()==null){say("請先載入門市並選擇收貨店",true);return false;}
        return true;
    }
    private void openStoresum() {
        if(closed)return;
        try { source().openStoresum(); }
        catch(Exception error){say(errorMessage(error),true);}
    }
    void nativeProblem(String message){say(message,true);}
    /** Each click replaces membership with the native selection; latest pending request wins. */
    void importNative(List<String> rawIds) {
        if(closed||!finishEditing())return;
        try {
            final List<String> requested=rawIds.isEmpty()?new ArrayList<String>():NativeInventorySelection.ids(rawIds);
            showComparison();tabs.setSelectedIndex(0);
            // Deselect immediately, including when resolving a newly selected product later fails.
            draft.retainProducts(requested);stock=null;
            if(destination.getSelectedItem()==null){
                productModel.refresh();refreshCandidates();selectionLabel.setText("已選 "+requested.size()+" 項");
                waitingSelection=requested;
                if(!loadingStores)loadStores();
                return;
            }
            waitingSelection=null;changed();
            if(requested.isEmpty()){say("尚未選取商品",false);return;}
            final long revision=draft.revision();
            run("確認原生選取的 "+requested.size()+" 項商品…",()->source().products(requested),items->{
                if(draft.revision()!=revision)return;
                java.util.LinkedHashMap<String,Inventory.Product> received=new java.util.LinkedHashMap<String,Inventory.Product>();
                for(Inventory.Product item:items)if(received.put(item.id,item)!=null)throw new IllegalArgumentException("商品回應重複，本次未帶入");
                if(!received.keySet().equals(new java.util.HashSet<String>(requested)))throw new IllegalArgumentException("商品資料不完整，本次未帶入");
                List<Inventory.Product> ordered=new ArrayList<Inventory.Product>();for(String id:requested)ordered.add(received.get(id));
                draft.replaceProducts(ordered);changed();loadStock();
            });
        }catch(Exception error){say(errorMessage(error),true);}
    }
    private void consumeWaitingSelection(){
        if(waitingSelection!=null&&destination.getSelectedItem()!=null){List<String> ids=waitingSelection;waitingSelection=null;importNative(ids);}
    }
    private void cancelWork(){tasks.invalidate();loadingStores=false;}
    private void removeSelected(JTable table){
        int row=table.getSelectedRow();if(row<0){say("請先點選要移除的商品",true);return;}
        String id=productModel.rows.get(table.convertRowIndexToModel(row)).id;
        if(!finishEditing())return;
        draft.remove(id);changed();
    }
    private void clearSelection(){
        if(!finishEditing())return;
        waitingSelection=null;draft.clear();stock=null;changed();say("已清空，可回原生重新選取商品",false);
    }
    private Inventory.Line selectedLine(String id){
        for(Inventory.Line line:draft.lines())if(line.product.id.equals(id))return line;
        return null;
    }
    private boolean finishEditing(){
        if(products.isEditing()&&!products.getCellEditor().stopCellEditing())return false;
        return expandedProducts==null||!expandedProducts.isEditing()||expandedProducts.getCellEditor().stopCellEditing();
    }
    private void changed(){
        cancelWork();
        if(draft.lines().isEmpty())stock=null;
        if(stock!=null)for(Inventory.Line line:draft.lines())if(!stock.products.containsKey(line.product.id)){stock=null;break;}
        productModel.refresh();refreshCandidates();
        selectionLabel.setText("已選 "+draft.lines().size()+" 項");
        if(expandedSelection!=null)expandedSelection.setText(selectionLabel.getText());
        if(stock!=null)stockStatus();
        else say(draft.lines().isEmpty()?"請從原生 STORESUM 選取商品":"庫存未知／需更新；已選商品已保留",false);
    }
    private void stockStatus(){
        if(stock==null)return;
        String count=candidates.isEmpty()&&!partial.isSelected()?"沒有單店全部足量，可勾選「顯示所有門市」":candidates.size()+" 間符合目前篩選";
        say("帳面庫存 "+Inventory.stamp(stock.checkedAt)+" · "+count,false);
    }
    private void loadStock(){
        if(!finishEditing()||!ready())return;
        cancelWork();
        if(draft.lines().isEmpty()){say("請先勾選商品",true);return;}
        final List<Inventory.Line> lines=draft.lines(); final long revision=draft.revision();
        stock=null;refreshCandidates();
        run("查詢所有商品的門市庫存…",()->source().stock(lines),snapshot->{
            if(draft.revision()!=revision)return;
            stock=snapshot;refreshCandidates();
            stockStatus();
        });
    }
    private void refreshCandidates(){
        changing=true;candidates=new ArrayList<Inventory.Candidate>();
        if(stock!=null && !draft.lines().isEmpty()){
            try{
                candidates=Inventory.candidates(stock,draft.lines(),draft.destination(),!partial.isSelected());

            }catch(Exception e){stock=null;say(e.getMessage(),true);}
        }
        changing=false;matrixModel.fireTableStructureChanged();sizeComparisonColumns();
    }
    private void loadMain(){
        if(!ready())return; cancelWork(); final String dest=draft.destination();
        final Inventory.Category cat=(Inventory.Category)category.getSelectedItem();
        mainStock=null;refreshShortages();
        run("比較 "+cat+" 庫存…",()->source().category(cat),snapshot->{
            if(!dest.equals(draft.destination())||cat!=category.getSelectedItem())return;
            mainStock=snapshot;refreshShortages();say("主機帳面庫存 "+Inventory.stamp(snapshot.checkedAt),false);
        });
    }
    private void refreshShortages(){
        shortageModel.rows.clear();peerLabel.setText("同店型比較包含零庫存門市");
        if(mainStock!=null){
            try{
                Inventory.Store home=mainStock.store(draft.destination());int peers=0;
                for(Inventory.Store s:mainStock.stores)if(!s.id.equals(home.id)&&s.type.equals(home.type))peers++;
                peerLabel.setText("比較 "+peers+" 間同店型門市 · "+Inventory.stamp(mainStock.checkedAt));
                for(Inventory.Shortage s:Inventory.shortages(mainStock,home.id)){
                    int filter=shortageFilter.getSelectedIndex();
                    if(filter==0||(filter==1&&s.outOfStock)||(filter==2&&s.local.compareTo(s.median)<0))shortageModel.rows.add(s);
                }
                if(peers==0)peerLabel.setText("沒有可比較的同店型門市");
            }catch(Exception e){mainStock=null;say(e.getMessage(),true);}
        }
        shortageModel.fireTableDataChanged();
    }
    private void addShortage(){
        int row=shortages.getSelectedRow();if(row<0){say("請先選擇主機商品",true);return;}
        Inventory.Shortage s=shortageModel.rows.get(row);
        try{
            int qty=s.difference.setScale(0,java.math.RoundingMode.CEILING).intValueExact();
            Inventory.Line existing=selectedLine(s.product.id);
            int wanted=Math.max(1,qty);
            if(existing==null)draft.add(s.product,wanted);
            else if(!existing.product.unit.equals(s.product.unit))throw new IllegalArgumentException("商品單位變更");
            changed();tabs.setSelectedIndex(0);showComparison();loadStock();
        }catch(Exception e){say("比較差額超出可加入數量，請手動輸入",true);}
    }
    private void showComparison(){
        if(closed||GraphicsEnvironment.isHeadless())return;
        if(comparison!=null){comparison.toFront();return;}
        comparison=new JDialog(epbOwner,"庫存工具",Dialog.ModalityType.MODELESS);
        comparison.setName("PosAssist-InventoryComparison");comparison.setMinimumSize(new Dimension(850,520));
        comparison.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);comparison.setResizable(true);
        comparison.setContentPane(this);comparison.setSize(1180,760);comparison.setLocationRelativeTo(epbOwner);
        comparison.addWindowListener(new WindowAdapter(){
            public void windowClosing(WindowEvent e){if(finishEditing())comparison.dispose();}
            public void windowClosed(WindowEvent e){comparison=null;}
        });
        comparison.setVisible(true);
    }
    private JPanel comparisonBody(){
        JPanel body=new JPanel(new BorderLayout(0,8));body.setOpaque(false);
        JPanel top=new JPanel(new BorderLayout());top.setOpaque(false);
        top.add(actions(button("回原生搜尋",()->openStoresum()),button("更新庫存",()->loadStock())),BorderLayout.WEST);
        top.add(partial,BorderLayout.EAST);partial.setOpaque(false);partial.setToolTipText("未勾選時只顯示全部足量門市；比較使用可見 SA 一般門市，不沿用原生倉庫及數量篩選");body.add(top,BorderLayout.NORTH);

        expandedProducts=table(detailsModel);expandedProducts.setRowHeight(44);
        expandedProducts.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        int[] widths={90,200,115,55};
        for(int i=0;i<widths.length;i++)expandedProducts.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        expandedProducts.setPreferredScrollableViewportSize(new Dimension(460,0));
        comparisonTable=table(matrixModel);comparisonTable.setRowHeight(44);
        comparisonTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        comparisonTable.setSelectionModel(expandedProducts.getSelectionModel());
        comparisonTable.getTableHeader().setPreferredSize(new Dimension(0,44));
        comparisonTable.getTableHeader().setReorderingAllowed(false);
        expandedProducts.getTableHeader().setPreferredSize(new Dimension(460,44));
        expandedProducts.getTableHeader().setReorderingAllowed(false);
        comparisonTable.setTableHeader(new javax.swing.table.JTableHeader(comparisonTable.getColumnModel()){
            public String getToolTipText(java.awt.event.MouseEvent e){int col=columnAtPoint(e.getPoint());
                return col>=0&&col<candidates.size()?candidates.get(col).store.toString():null;}
        });
        comparisonTable.getTableHeader().setPreferredSize(new Dimension(0,44));
        comparisonTable.getTableHeader().setReorderingAllowed(false);
        JScrollPane matrix=scroll(comparisonTable);matrix.setRowHeaderView(expandedProducts);
        matrix.setCorner(JScrollPane.UPPER_LEFT_CORNER,expandedProducts.getTableHeader());
        matrix.getRowHeader().setPreferredSize(new Dimension(460,0));
        body.add(matrix,BorderLayout.CENTER);sizeComparisonColumns();
        JPanel bottom=new JPanel(new BorderLayout());bottom.setOpaque(false);expandedSelection=new JLabel(selectionLabel.getText());
        bottom.add(expandedSelection,BorderLayout.WEST);
        bottom.add(actions(button("移除選取商品",()->removeSelected(expandedProducts)),button("清空比較",()->clearSelection())),BorderLayout.EAST);
        body.add(bottom,BorderLayout.SOUTH);return body;
    }
    private void sizeComparisonColumns(){
        if(comparisonTable!=null)for(int i=0;i<comparisonTable.getColumnCount();i++)
            comparisonTable.getColumnModel().getColumn(i).setPreferredWidth(145);
    }
    void close(){closed=true;tasks.close();waitingSelection=null;draft.clear();stock=null;mainStock=null;
        changing=true;destination.removeAllItems();candidates.clear();shortageModel.rows.clear();
        productModel.refresh();shortageModel.fireTableDataChanged();matrixModel.fireTableDataChanged();
        if(comparison!=null)comparison.dispose();}
    private void say(String message,boolean error){status.setText("<html>"+html(message)+"</html>");status.setForeground(error?new Color(0xA83F35):Style.MUTED);}
    static String html(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}
    private static JPanel vertical(){
        JPanel p=new JPanel(){protected void addImpl(Component c,Object constraints,int index){
            if(c instanceof JComponent)((JComponent)c).setAlignmentX(Component.LEFT_ALIGNMENT);
            super.addImpl(c,constraints,index);
        }};
        p.setOpaque(false);p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS));return p;
    }
    private static JPanel fieldRow(String label,Component input){
        JPanel p=new JPanel(new BorderLayout(6,0));p.setOpaque(false);
        p.add(new JLabel(label),BorderLayout.WEST);p.add(input,BorderLayout.CENTER);return p;
    }
    private static JScrollPane scroll(JTable table){
        JScrollPane pane=new JScrollPane(table);pane.setColumnHeaderView(table.getTableHeader());return pane;
    }
    private static JPanel row(Component center,Component right){JPanel p=new JPanel(new BorderLayout(6,0));p.setOpaque(false);p.add(center,BorderLayout.CENTER);p.add(right,BorderLayout.EAST);return p;}
    private static JLabel hint(String text){
        JLabel l=new JLabel("<html>"+html(text)+"</html>");l.setForeground(Style.MUTED);l.setFont(l.getFont().deriveFont(11f));
        l.setMinimumSize(new Dimension(0,32));l.setPreferredSize(new Dimension(220,32));return l;
    }
    private static JPanel actions(JButton...buttons){JPanel p=new JPanel();p.setOpaque(false);WrapFlow.install(p,4,4);for(JButton b:buttons)p.add(b);return p;}
    private JButton button(String text,Runnable action){JButton b=new JButton(text);b.setFocusable(false);b.addActionListener(e->{try{action.run();}catch(Exception x){PosLog.warn("庫存操作未完成");say("操作未完成，已勾選商品已保留；請重試",true);}});return b;}
    private static JTable table(AbstractTableModel model){JTable t=new JTable(model){
        public String getToolTipText(java.awt.event.MouseEvent e){
            int r=rowAtPoint(e.getPoint()),c=columnAtPoint(e.getPoint());
            return r<0||c<0?null:String.valueOf(getValueAt(r,c));
        }
    };t.setRowHeight(28);t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);t.setFillsViewportHeight(true);t.putClientProperty("terminateEditOnFocusLost",Boolean.TRUE);return t;}
    private final class ProductModel extends AbstractTableModel {
        final List<Inventory.Product> rows=new ArrayList<Inventory.Product>();
        void refresh(){rows.clear();if(draft!=null)for(Inventory.Line line:draft.lines())rows.add(line.product);fireTableDataChanged();}
        public int getRowCount(){return rows.size();}public int getColumnCount(){return 2;}
        public String getColumnName(int c){return c==0?"已選商品／型號":"需求";}
        public Class<?> getColumnClass(int c){return c==0?Inventory.Product.class:Integer.class;}
        public Object getValueAt(int r,int c){Inventory.Product p=rows.get(r);return c==0?p:selectedLine(p.id).quantity;}
        public boolean isCellEditable(int r,int c){return c==1;}
        public void setValueAt(Object value,int r,int c){
            if(c!=1)return;
            try{draft.quantity(rows.get(r).id,Integer.parseInt(value.toString()));changed();}
            catch(Exception e){say(e instanceof NumberFormatException?"需求須為 1–9999 的整數":e.getMessage(),true);fireTableDataChanged();}
        }
    }
    private final class DetailsModel extends AbstractTableModel {
        public int getRowCount(){return productModel.getRowCount();}public int getColumnCount(){return 4;}
        public String getColumnName(int c){return new String[]{"商品代碼","品名","型號","需求"}[c];}
        public Class<?> getColumnClass(int c){return c==3?Integer.class:String.class;}
        public Object getValueAt(int r,int c){Inventory.Product p=productModel.rows.get(r);return c==0?p.id:c==1?p.name:c==2?p.model:productModel.getValueAt(r,1);}
        public boolean isCellEditable(int r,int c){return c==3;}
        public void setValueAt(Object value,int r,int c){if(c==3)productModel.setValueAt(value,r,1);}
    }
    private final class MatrixModel extends AbstractTableModel {
        public int getRowCount(){return draft==null?0:draft.lines().size();}
        public int getColumnCount(){return candidates.size();}
        public Class<?> getColumnClass(int c){return BigDecimal.class;}
        public String getColumnName(int c){Inventory.Store store=candidates.get(c).store;
            String name=store.name.replace("晶實科技(股)","").replace("晶實科技（股）","");
            return "<html><center>"+html(store.id)+"<br>"+html(name)+"</center></html>";}
        public Object getValueAt(int r,int c){
            if(stock==null)return null;
            return stock.qty(candidates.get(c).store.id,draft.lines().get(r).product.id);
        }
    }
    private static final class ShortageModel extends AbstractTableModel {
        final List<Inventory.Shortage> rows=new ArrayList<Inventory.Shortage>();
        public int getRowCount(){return rows.size();}public int getColumnCount(){return 6;}
        public String getColumnName(int c){return new String[]{"商品代碼／品名","本店庫存","同型中位數","有貨／比較店","比較差額","標記"}[c];}
        public Object getValueAt(int r,int c){Inventory.Shortage s=rows.get(r);switch(c){case 0:return s.product.toString();case 1:return Inventory.number(s.local);case 2:return Inventory.number(s.median);case 3:return s.stocked+" / "+s.peers;case 4:return Inventory.number(s.difference);default:return s.outOfStock?"本店缺貨、其他店有貨":"低於同店型中位數";}}
    }
}
