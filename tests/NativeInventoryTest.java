package com.posassist;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import static com.posassist.InventoryTest.*;

/** Native adapter contracts and the user-visible cumulative comparison flow. */
public final class NativeInventoryTest {
    // Non-public class deliberately reproduces native inherited reflection accessibility.
    static final class Criterion {
        String field, keyword; Object value; Object[] values; boolean composed, includingNull;
        Criterion(String field,String keyword,Object value,Object...values){this.field=field;this.keyword=keyword;this.value=value;this.values=values;}
        public String getFieldName(){return field;} public String getKeyWord(){return keyword;}
        public Object getValue(){return value;} public Object[] getValuesCopy(){return values;}
        public boolean isComposed(){return composed;} public boolean isIncludingNull(){return includingNull;}
    }
    static Set<Object> criteria(Object...items){return new LinkedHashSet<Object>(Arrays.asList(items));}
    static void run()throws Exception {
        Criterion multi=new Criterion("stkId"," IN ",null,"09600040","50300018","09600040");
        check(NativeInventorySelection.read(criteria(multi,new Criterion("storeId","=","SA999"),new Criterion("stkQty","<>",0)))
            .equals(Arrays.asList("09600040","50300018")),"native IN values deduped independently of store and stock criteria");
        check(NativeInventorySelection.read(criteria(new Criterion("stkId"," = ","07312334"))).equals(Arrays.asList("07312334")),"native exact single selection");
        check(NativeInventorySelection.read(criteria()).isEmpty(),"empty native criteria clears comparison without popup");
        fails("LIKE must use native picker",()->NativeInventorySelection.read(criteria(new Criterion("stkId"," LIKE ","073%"))));
        fails("between unsupported",()->NativeInventorySelection.read(criteria(new Criterion("stkId"," BETWEEN ",null,"01","99"))));
        fails("not in unsupported",()->NativeInventorySelection.read(criteria(new Criterion("stkId"," NOT IN ",null,"01"))));
        fails("no parsing slash display text",()->NativeInventorySelection.read(criteria(new Criterion("stkId","=","01 / 02"))));
        fails("number coercion would lose leading zeros",()->NativeInventorySelection.read(criteria(new Criterion("stkId","=",7312334))));
        check(NativeInventorySelection.read(criteria(new Criterion("stkId","IN",null))).isEmpty(),"empty IN clears selection");
        fails("duplicate criteria ambiguous",()->NativeInventorySelection.read(criteria(multi,new Criterion("stkId","=","07312334"))));
        Criterion composed=new Criterion("stkId","=","07312334");composed.composed=true;
        fails("composed SQL not executed",()->NativeInventorySelection.read(criteria(composed)));
        composed.composed=false;composed.includingNull=true;
        fails("including null unsupported",()->NativeInventorySelection.read(criteria(composed)));
        List<String> tooMany=new ArrayList<String>();for(int i=0;i<31;i++)tooMany.add("P"+i);
        fails("native batch limit",()->NativeInventorySelection.ids(tooMany));
        Inventory.Draft d=new Inventory.Draft("SA004");d.add(p("A"),4);d.mergeProducts(Arrays.asList(p("A"),p("B")));
        check(d.lines().size()==2&&d.lines().get(0).quantity==4,"merge preserves existing demand");
        List<Inventory.Product> many=new ArrayList<Inventory.Product>();for(String id:tooMany)many.add(p(id));
        fails("merge validates union limit",()->d.mergeProducts(many));check(d.lines().size()==2,"oversize union atomic");
        fails("unit change rejects whole batch",()->d.mergeProducts(Arrays.asList(p("C"),new Inventory.Product("A","A","","BOX","3001","4002"))));
        check(d.lines().size()==2,"unit failure does not partially add C");
        String sql=InventorySql.products(stores(),"STORESUM.CAT2_ID='2001'",Arrays.asList("A'B"));
        check(sql.contains("IN ('A''B')")&&sql.contains("STORESUM.CAT2_ID='2001'")&&!sql.contains(" LIKE '%"),"exact product SQL escaped and retains ACL");
        List<Map<String,String>> rows=new ArrayList<Map<String,String>>();rows.add(stockRow());
        EpbInventorySource source=new EpbInventorySource((q,limit)->q.contains("FROM STOREMAS m")?rosterRows():rows,"1=1","1=1");
        check(source.products(Arrays.asList("A")).get(0).id.equals("A"),"exact metadata lookup");
        try {source.products(Arrays.asList("A","B"));throw new AssertionError("partial lookup accepted");}
        catch(Exception e){check(e.getMessage().contains("B")&&e.getMessage().contains("未帶入"),"missing code reported without partial import");}
        rows.get(0).put("UNIT_COUNT","2");fails("inconsistent metadata unit",()->source.products(Arrays.asList("A")));
        rows.get(0).put("UNIT_COUNT","1");rows.add(stockRow());fails("duplicate metadata rejected",()->source.products(Arrays.asList("A")));
        bridge();staleImport();interactionUi();replacementUi();
    }
    static void bridge()throws Exception {
        SwingUtilities.invokeAndWait(()->{
            JPanel view=new JPanel();AtomicInteger installs=new AtomicInteger(),reads=new AtomicInteger();List<List<String>> received=new ArrayList<List<String>>();
            NativeInventoryBridge.Access access=new NativeInventoryBridge.Access(){
                public Object view(Object app){return view;}
                public Object criteria(Object ignored){reads.incrementAndGet();return NativeInventoryTest.criteria(new Criterion("stkId","IN",null,"07312334","07312335"));}
                public void install(Object ignored,JButton button){installs.incrementAndGet();view.add(button);}
            };
            NativeInventoryBridge b=new NativeInventoryBridge(received::add,access);Object app=new Object();
            b.attach(app);b.attach(app);check(installs.get()==1&&view.getComponentCount()==1,"activation never duplicates button");
            check(reads.get()==0,"opening picker or cancel does not automatically import");
            JButton button=(JButton)view.getComponent(0);button.doClick();check(received.get(0).size()==2,"native button reads criteria without stock search");
            b.detach(app);button.doClick();check(view.getComponentCount()==0&&received.size()==1,"closed native app releases button callback");
            b.attach(new Object());check(installs.get()==2,"reopened native app gets a new button");b.close();check(view.getComponentCount()==0,"logout removes buttons");
            b.attach(new Object());check(installs.get()==2,"closed bridge cannot resurrect");
        });
    }
    static class Source implements InventorySource {
        final AtomicInteger opened=new AtomicInteger(),stockCalls=new AtomicInteger();
        volatile boolean failStock,partial;
        public List<Inventory.Store> stores(){return InventoryTest.stores();}
        public List<Inventory.Product> search(String q){throw new AssertionError("custom search must not run");}
        public List<Inventory.Product> products(List<String> ids)throws Exception {
            List<Inventory.Product> ps=new ArrayList<Inventory.Product>();for(String id:ids)ps.add(p(id));
            if(partial&&!ps.isEmpty())ps.remove(ps.size()-1);return ps;
        }
        public Inventory.Snapshot stock(List<Inventory.Line> lines)throws Exception {stockCalls.incrementAndGet();if(failStock)throw new Exception("查詢失敗");return snapshot();}
        public Inventory.Snapshot category(Inventory.Category c){return snapshot();}
        public void openTransfer(){throw new AssertionError("no transfer form");}
        public void openStoresum(){opened.incrementAndGet();}
    }
    static InventoryPanel panel(Source source)throws Exception {
        InventoryPanel[] p=new InventoryPanel[1];SwingUtilities.invokeAndWait(()->{p[0]=new InventoryPanel("SA004",source);p[0].start();});
        JComboBox<?> dest=(JComboBox<?>)InventoryVisualCheck.get(p[0],"destination");awaitUi(()->dest.getItemCount()==5);return p[0];
    }
    static Object get(InventoryPanel panel,String key){try{return InventoryVisualCheck.get(panel,key);}catch(Exception e){throw new RuntimeException(e);}}
    static boolean stocked(InventoryPanel panel){return get(panel,"stock")!=null;}
    static String status(InventoryPanel panel){return ((JLabel)get(panel,"status")).getText();}
    static void selectionUi()throws Exception {
        Source source=new Source();InventoryPanel panel=panel(source);JTable products=(JTable)get(panel,"products");
        SwingUtilities.invokeAndWait(()->{
            JTabbedPane tabs=(JTabbedPane)get(panel,"tabs");check(tabs.getTabCount()==2,"find and main-device pages only");
            panelCall(panel,"openStoresum");check(source.opened.get()==1,"sidebar opens native finder");
            panel.importNative(Arrays.asList("A"));
        });awaitUi(()->stocked(panel));
        SwingUtilities.invokeAndWait(()->{
            check(panel.draft.lines().size()==1&&panel.draft.lines().get(0).quantity==1,"native import selects and auto queries stock");
            JTable expanded=(JTable)get(panel,"expandedProducts");
            check(expanded.getColumnCount()==4&&expanded.getValueAt(0,0).equals("A"),"comparison has code name model demand without search");
            int calls=source.stockCalls.get();expanded.getModel().setValueAt(3,0,3);
            check(products.getValueAt(0,1).equals(3)&&stocked(panel)&&source.stockCalls.get()==calls,"demand edit immediately recomputes complete cached snapshot");
            expanded.getModel().setValueAt(0,0,3);check(panel.draft.lines().get(0).quantity==3,"invalid demand retained");
            panel.importNative(Arrays.asList("A","B"));
        });awaitUi(()->stocked(panel)&&panel.draft.lines().size()==2);
        SwingUtilities.invokeAndWait(()->{
            check(panel.draft.lines().get(0).quantity==3,"repeat import never increases demand");
            check(panel.draft.lines().get(1).product.id.equals("B"),"same model different code remains separate");
            products.getModel().setValueAt(99,0,1);check(status(panel).contains("沒有單店全部足量"),"empty sufficient result explains partial switch");
            source.failStock=true;panelCall(panel,"loadStock");
        });awaitUi(()->status(panel).contains("查詢失敗"));
        SwingUtilities.invokeAndWait(()->{
            check(panel.draft.lines().size()==2&&!stocked(panel),"stock failure preserves selection and cannot claim sufficient");
            source.partial=true;panel.importNative(Arrays.asList("C","D"));
        });awaitUi(()->status(panel).contains("資料不完整"));
        SwingUtilities.invokeAndWait(()->{
            check(panel.draft.lines().isEmpty(),"failed replacement does not restore deselected old products");
            panelCall(panel,"clearSelection");check(panel.draft.lines().isEmpty()&&products.getRowCount()==0,"clear selection resets table");
            panel.close();check(panel.draft.lines().isEmpty(),"logout clears all items");
        });
    }
    static void staleImport()throws Exception {
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        Source source=new Source(){public List<Inventory.Product> products(List<String> ids)throws Exception {
            if(calls.incrementAndGet()==1){started.countDown();release.await();}return super.products(ids);
        }};
        InventoryPanel panel=panel(source);
        SwingUtilities.invokeAndWait(()->panel.importNative(Arrays.asList("A")));
        check(started.await(2,TimeUnit.SECONDS),"first native lookup started");
        SwingUtilities.invokeAndWait(()->panel.importNative(Arrays.asList("B")));release.countDown();
        awaitUi(()->stocked(panel));
        SwingUtilities.invokeAndWait(()->{check(panel.draft.lines().size()==1&&panel.draft.lines().get(0).product.id.equals("B"),"latest native selection replaces earlier pending import");panel.close();});
        CountDownLatch started2=new CountDownLatch(1),release2=new CountDownLatch(1);
        AtomicInteger delayedCalls=new AtomicInteger();
        Source delayed=new Source(){public List<Inventory.Product> products(List<String> ids)throws Exception{if(delayedCalls.incrementAndGet()==1){started2.countDown();release2.await();}return super.products(ids);}};
        InventoryPanel cleared=panel(delayed);
        SwingUtilities.invokeAndWait(()->cleared.importNative(Arrays.asList("A")));check(started2.await(2,TimeUnit.SECONDS),"delayed import starts");
        SwingUtilities.invokeAndWait(()->{panelCall(cleared,"clearSelection");cleared.importNative(Arrays.asList("B"));});release2.countDown();
        awaitUi(()->stocked(cleared));
        SwingUtilities.invokeAndWait(()->{check(cleared.draft.lines().size()==1&&cleared.draft.lines().get(0).product.id.equals("B"),"clear prevents late import restoring A into new comparison");cleared.close();});
    }
    static void interactionUi()throws Exception {
        Source source=new Source();InventoryPanel panel=panel(source);
        SwingUtilities.invokeAndWait(()->panel.importNative(Arrays.asList("A","B")));awaitUi(()->stocked(panel));
        SwingUtilities.invokeAndWait(()->{
            JTable expanded=(JTable)get(panel,"expandedProducts");
            int calls=source.stockCalls.get();expanded.setRowSelectionInterval(1,1);
            try{java.lang.reflect.Method m=InventoryPanel.class.getDeclaredMethod("removeSelected",JTable.class);m.setAccessible(true);m.invoke(panel,expanded);}
            catch(Exception e){throw new RuntimeException(e);}
            check(panel.draft.lines().size()==1&&stocked(panel)&&source.stockCalls.get()==calls,"remove recomputes without another network query");
            panelCall(panel,"loadMain");
        });awaitUi(()->get(panel,"mainStock")!=null);
        SwingUtilities.invokeAndWait(()->{
            JTable gaps=(JTable)get(panel,"shortages");gaps.setRowSelectionInterval(0,0);panelCall(panel,"addShortage");
        });awaitUi(()->stocked(panel));
        SwingUtilities.invokeAndWait(()->{
            check(panel.draft.lines().size()==1&&panel.draft.lines().get(0).quantity==1,"main-device repeat retains manually chosen demand");
            JTable gaps=(JTable)get(panel,"shortages");gaps.setRowSelectionInterval(1,1);panelCall(panel,"addShortage");
        });awaitUi(()->stocked(panel)&&panel.draft.lines().size()==2);
        SwingUtilities.invokeAndWait(()->{
            check(panel.draft.lines().get(1).quantity==1,"new main-device item adds comparison difference and auto queries");panel.close();
        });
    }

    static void replacementUi()throws Exception {
        Source source=new Source();InventoryPanel panel=panel(source);
        SwingUtilities.invokeAndWait(()->panel.importNative(Arrays.asList("A","B")));awaitUi(()->stocked(panel));
        SwingUtilities.invokeAndWait(()->{
            JTable matrix=(JTable)get(panel,"comparisonTable");
            check(matrix.getRowCount()==2&&matrix.getColumnCount()==2,"products are rows and sufficient stores are columns");
            check(matrix.getColumnName(0).contains("SA005")&&matrix.getValueAt(0,0).equals(new java.math.BigDecimal("5")),"matrix cell is stock only");
            JCheckBox all=(JCheckBox)get(panel,"partial");all.doClick();
            check(matrix.getColumnCount()==4&&matrix.getValueAt(1,3) instanceof java.math.BigDecimal,"all store columns keep numeric stock values");
            panel.importNative(Arrays.asList("B"));
            check(panel.draft.lines().size()==1&&panel.draft.lines().get(0).product.id.equals("B"),"native deselection removes A immediately");
        });awaitUi(()->stocked(panel));
        SwingUtilities.invokeAndWait(()->{
            check(panel.draft.lines().size()==1,"successful native replacement exactly matches B");
            panel.importNative(Collections.emptyList());
            check(panel.draft.lines().isEmpty()&&!stocked(panel),"clear all native selections clears comparison");panel.close();
            InventoryWorkspace workspace=new InventoryWorkspace("offline");
            try {check(InventoryVisualCheck.get(workspace,"inventory")==null,"login creates neither inventory panel nor automatic window");}
            catch(Exception e){throw new RuntimeException(e);}workspace.close();
        });
        Source pending=new Source();InventoryPanel[] first=new InventoryPanel[1];
        SwingUtilities.invokeAndWait(()->{first[0]=new InventoryPanel("SA004",pending);first[0].importNative(Arrays.asList("A"));});
        awaitUi(()->stocked(first[0]));SwingUtilities.invokeAndWait(()->{check(first[0].draft.lines().size()==1,"first click loads roster then processes native selection");first[0].close();});
    }

}
