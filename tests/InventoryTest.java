package com.posassist;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.rowset.*;
import javax.swing.*;

/** Run without EPB or login; behavior tests use independent fixtures. */
public final class InventoryTest {
    private static int checks;
    static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
    interface Checked{void run()throws Exception;}
    static void fails(String name,Checked run)throws Exception{boolean failed=false;try{run.run();}catch(Exception e){failed=true;}check(failed,name);}
    static Inventory.Product p(String id){return new Inventory.Product(id,"商品 "+id,"共同型號","PCS","3001","4002");}
    static List<Inventory.Store> stores(){return Arrays.asList(
        new Inventory.Store("SA004","士林","1_APR"),new Inventory.Store("SA005","甲店","1_APR"),
        new Inventory.Store("SA024","乙店","1_APR"),new Inventory.Store("SA054","丙店","1_APR"),
        new Inventory.Store("SA009","不同店型","2_AAR"));}
    static Inventory.Snapshot snapshot(){
        Map<String,BigDecimal> q=new HashMap<String,BigDecimal>();
        q.put("SA005|A",new BigDecimal("5"));q.put("SA005|B",new BigDecimal("3"));
        q.put("SA024|A",new BigDecimal("2"));q.put("SA024|B",new BigDecimal("1"));
        q.put("SA054|A",new BigDecimal("8"));q.put("SA054|B",new BigDecimal("-1"));
        q.put("SA009|A",new BigDecimal("100"));
        return new Inventory.Snapshot(stores(),Arrays.asList(p("A"),p("B")),q,1000);
    }
    public static void main(String[] args)throws Exception{
        domain();sql();source();async();ui();selectionUi();NativeInventoryTest.run();
        System.out.println("InventoryTest: "+checks+" checks passed");
    }
    static void domain()throws Exception{
        Inventory.Draft d=new Inventory.Draft("SA004");d.add(p("A"),1);d.add(p("A"),1);d.add(p("B"),1);
        check(d.lines().size()==2&&d.lines().get(0).quantity==2,"duplicate adds quantity; same model remains separate");
        fails("zero quantity",()->d.quantity("A",0));fails("overflow",()->d.add(p("A"),Integer.MAX_VALUE));
        Inventory.Snapshot s=snapshot();List<Inventory.Candidate> cs=Inventory.candidates(s,d.lines(),"SA004",true);
        check(cs.size()==2,"only two fully sufficient stores");check(cs.get(0).store.id.equals("SA005"),"nonempty remainder ranked first");
        check(cs.get(1).exhausted==2,"two exhausted SKUs");
        check(Inventory.candidates(s,d.lines(),"SA004",false).size()==4,"partial mode retains stores and excludes destination");
        check(s.qty("SA004","A").signum()==0,"missing local row is zero in complete snapshot");
        check(s.qty("SA054","B").intValue()==-1,"negative quantity retained");
        fails("unknown SKU is not zero",()->s.qty("SA004","C"));
        fails("unknown store is not zero",()->s.qty("SA999","A"));
        List<Inventory.Shortage> gaps=Inventory.shortages(s,"SA004");
        check(gaps.size()==2,"local absent goods discovered");
        Inventory.Shortage b=null;for(Inventory.Shortage row:gaps)if(row.product.id.equals("B"))b=row;
        check(b.peers==3&&b.stocked==2&&b.median.compareTo(BigDecimal.ONE)==0,"median includes negatives and excludes AAR");
        check(b.difference.intValue()==1,"ceil median difference");
        Map<String,BigDecimal> q=new HashMap<String,BigDecimal>();q.put("SA005|Z",BigDecimal.ONE);
        Inventory.Snapshot zeroMedian=new Inventory.Snapshot(stores(),Arrays.asList(p("Z")),q,1000);
        Inventory.Shortage z=Inventory.shortages(zeroMedian,"SA004").get(0);
        check(z.median.signum()==0&&z.difference.signum()==0&&z.outOfStock,"other stores stocked yet median zero");
        List<Inventory.Store> four=new ArrayList<Inventory.Store>(stores());four.add(new Inventory.Store("SA063","第四店","1_APR"));
        q.put("SA024|Z",new BigDecimal("2"));q.put("SA054|Z",new BigDecimal("3"));q.put("SA063|Z",new BigDecimal("4"));
        Inventory.Shortage even=Inventory.shortages(new Inventory.Snapshot(four,Arrays.asList(p("Z")),q,1000),"SA004").get(0);
        check(even.median.compareTo(new BigDecimal("2.5"))==0&&even.difference.intValue()==3,"even median ceiling");
        d.source("SA005");String text=Inventory.export(s,d.lines(),d.source(),d.destination(),"備貨","",false);
        check(text.equals("A\t2\nB\t1\n"),"copy exact code and quantity TSV");
        check(Inventory.export(s,d.lines(),"SA005","SA004","客訂","需確認",true).contains("尚未建立 EPB 單據"),"full export clearly draft");
        fails("fresh insufficient stock blocks export",()->Inventory.export(s,d.lines(),"SA054","SA004","備貨","",false));
        fails("same source destination",()->d.source("SA004"));
        long revision=d.revision();d.destination("SA024");check(d.source().isEmpty()&&d.revision()>revision,"destination invalidates source");
        d.quantity("A",6);check(Inventory.candidates(s,d.lines(),"SA004",true).isEmpty(),"larger demand excludes stores");
        d.clear();check(d.lines().isEmpty()&&d.source().isEmpty(),"logout clears draft");
        fails("special warehouse rejected",()->new Inventory.Store("SA999","在途","1_APR"));
        fails("SAS rejected",()->new Inventory.Store("SAS004","校園","1_APR"));
        List<Inventory.Store> dup=new ArrayList<Inventory.Store>(stores());dup.add(stores().get(0));
        fails("duplicate roster rejected",()->new Inventory.Snapshot(dup,Arrays.asList(p("A")),Collections.emptyMap(),1));
        check(Inventory.candidates(s,Collections.emptyList(),"SA004",true).isEmpty(),"empty basket never recommends");
        Inventory.Snapshot empty=new Inventory.Snapshot(stores(),Arrays.asList(p("A"),p("B")),Collections.emptyMap(),1);
        check(Inventory.candidates(empty,Arrays.asList(new Inventory.Line(p("A"),1)),"SA004",true).isEmpty(),"all zero inventory has no supplier");
        check(Inventory.shortages(empty,"SA004").isEmpty(),"all zero has no comparison gap");
    }
    static void sql()throws Exception{
        String search=InventorySql.search(stores(),"1=1","x' OR 1=1 --");
        check(search.contains("X''"),"quoted search is escaped literal");
        check(InventorySql.bounded(search,100).endsWith("ROWNUM <= 101"),"limit sentinel");
        check(InventorySql.like("a_b%!").equals("'%A!_B!%!!%'"),"LIKE wildcard escaping");
        fails("multiple statements rejected",()->InventorySql.bounded("SELECT 1 FROM DUAL; DELETE FROM T",1));
        fails("too broad search rejected",()->InventorySql.search(stores(),"1=1","a"));
        fails("control characters rejected",()->InventorySql.literal("a\nb"));
        String sql=InventorySql.category(stores(),"STORESUM.CAT2_ID='2001'",Inventory.Category.MAC);
        check(sql.contains("STORESUM.CAT3_ID='3001'")&&sql.contains("'4001','4002'"),"Mac classification");
        check(sql.contains("SUM(NVL(STORESUM.STK_QTY,0))")&&!sql.contains("HAVING SUM"),"aggregate includes zero and negative rows");
        check(sql.contains("STORESUM.CAT2_ID='2001'"),"item access applied");
        String acl=EpbInventorySource.storeAccess("a'b","m.STORE_ID");
        check(acl.contains("a''b")&&acl.contains("STOREMAS_LOC")&&acl.contains("EP_USER_STORE"),"native store ACL both routes");
        check(InventorySql.roster(acl).contains("COUNT(DISTINCT p.SHOPTYPE_ID)=1"),"conflicting store type not silently selected");
        String catalog=InventorySql.catalog("X' OR 1=1 --");
        check(InventorySql.bounded(catalog,1000).contains("X''"),"catalog input remains escaped data");
        check(catalog.contains("UPPER(s.BAR_CODE1)=")&&catalog.contains("UPPER(s.BAR_CODE2)="),"SKU barcodes included in candidate narrowing");
        String restricted=InventorySql.restrict(InventorySql.search(stores(),"STORESUM.CAT2_ID='2001'","model"),Arrays.asList("A","B"));
        check(restricted.contains("STORESUM.STK_ID IN ('A','B')")&&restricted.contains("STORESUM.CAT2_ID='2001'"),"literal candidate IDs retain final access restrictions");
        check(InventorySql.terms("ipad   air 256").equals(Arrays.asList("IPAD","AIR","256")),"space-separated keywords match independently");
        check(InventorySql.terms("ｉｐａｄ　Ａｉｒ 256 ipad").equals(Arrays.asList("IPAD","AIR","256")),"fullwidth and repeated keywords normalized");
        String multi=InventorySql.search(stores(),"1=1","iphone 17 pro 256");
        check(multi.contains("'%IPHONE%'")&&multi.contains("'%17%'")&&multi.contains("'%PRO%'")&&multi.contains("'%256%'")&&!multi.contains("'%IPHONE 17 PRO 256%'"),"keywords need not be adjacent in a product name");
    }
    static Map<String,String> row(String... kv){Map<String,String> r=new LinkedHashMap<String,String>();for(int i=0;i<kv.length;i+=2)r.put(kv[i],kv[i+1]);return r;}
    static Map<String,String> stockRow(){return row("STORE_ID","SA005","STK_ID","A","PRODUCT_NAME","商品 A","MODEL","型號","UOM_ID","PCS","CAT3_ID","3001","CAT4_ID","4002","STOCK_QTY","5","UNIT_COUNT","1");}
    static List<Map<String,String>> rosterRows(){List<Map<String,String>> rs=new ArrayList<Map<String,String>>();for(Inventory.Store s:stores())rs.add(row("STORE_ID",s.id,"STORE_NAME",s.name,"SHOP_TYPE",s.type));return rs;}
    static void source()throws Exception{
        final List<Map<String,String>> stockRows=new ArrayList<Map<String,String>>();stockRows.add(stockRow());
        EpbInventorySource source=new EpbInventorySource((sql,limit)->sql.contains("FROM STOREMAS m")?rosterRows():stockRows,"1=1","1=1");
        List<Inventory.Line> lines=Arrays.asList(new Inventory.Line(p("A"),1));
        check(source.stock(lines).qty("SA004","A").signum()==0,"source accepts absent local with known product");
        fails("missing SKU cannot become zero stock",()->source.stock(Arrays.asList(new Inventory.Line(p("B"),1))));
        stockRows.add(stockRow());fails("duplicate stock rows rejected",()->source.stock(lines));stockRows.remove(1);
        stockRows.get(0).remove("STOCK_QTY");fails("missing quantity rejected",()->source.stock(lines));
        EpbInventorySource broken=new EpbInventorySource((sql,limit)->{throw new Exception("offline");},"1=1","1=1");
        fails("connection error retained",()->broken.stock(lines));
        CachedRowSet rs=RowSetProvider.newFactory().createCachedRowSet();RowSetMetaDataImpl md=new RowSetMetaDataImpl();md.setColumnCount(1);md.setColumnName(1,"A");md.setColumnLabel(1,"A");md.setColumnType(1,java.sql.Types.VARCHAR);rs.setMetaData(md);
        for(int i=0;i<3;i++){rs.moveToInsertRow();rs.updateString(1,"x");rs.insertRow();rs.moveToCurrentRow();}rs.beforeFirst();
        fails("truncation never yields partial snapshot",()->EpbInventorySource.read(rs,2));rs.close();
        CachedRowSet truncated=countedRows(2,3);
        fails("silent transport truncation detected by count",()->EpbInventorySource.read(truncated,10));truncated.close();
        CachedRowSet complete=countedRows(2,2);
        check(EpbInventorySource.read(complete,10).size()==2,"complete transport rows accepted");complete.close();
        CachedRowSet sentinel=countedRows(3,3);
        fails("limit sentinel blocks partial answer",()->EpbInventorySource.read(sentinel,2));sentinel.close();
    }
    static CachedRowSet countedRows(int actual,int expected)throws Exception{
        CachedRowSet rs=RowSetProvider.newFactory().createCachedRowSet();RowSetMetaDataImpl md=new RowSetMetaDataImpl();md.setColumnCount(2);
        md.setColumnName(1,"A");md.setColumnLabel(1,"A");md.setColumnType(1,java.sql.Types.VARCHAR);
        md.setColumnName(2,"PA_RESULT_ROWS");md.setColumnLabel(2,"PA_RESULT_ROWS");md.setColumnType(2,java.sql.Types.INTEGER);rs.setMetaData(md);
        for(int i=0;i<actual;i++){rs.moveToInsertRow();rs.updateString(1,"x");rs.updateInt(2,expected);rs.insertRow();rs.moveToCurrentRow();}
        rs.beforeFirst();return rs;
    }
    static void async()throws Exception{
        InventoryTasks tasks=new InventoryTasks(5000);CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1),done=new CountDownLatch(1);
        AtomicInteger old=new AtomicInteger(),newResult=new AtomicInteger();
        tasks.run(()->{started.countDown();release.await();return 1;},(v,e)->old.incrementAndGet());
        check(started.await(2,TimeUnit.SECONDS),"worker started");
        tasks.run(()->2,(v,e)->{if(v!=null)newResult.set(v);done.countDown();});release.countDown();
        check(done.await(5,TimeUnit.SECONDS)&&newResult.get()==2&&old.get()==0,"stale response suppressed");tasks.close();
        InventoryTasks timeout=new InventoryTasks(50);CountDownLatch expired=new CountDownLatch(1),unblock=new CountDownLatch(1);AtomicInteger callbacks=new AtomicInteger();
        timeout.run(()->{unblock.await();return 1;},(v,e)->{if(e!=null&&v==null)callbacks.incrementAndGet();expired.countDown();});
        check(expired.await(2,TimeUnit.SECONDS)&&callbacks.get()==1,"timeout produces error without zero data");unblock.countDown();timeout.close();
        InventoryTasks closing=new InventoryTasks(1000);CountDownLatch hold=new CountDownLatch(1);AtomicInteger afterClose=new AtomicInteger();
        closing.run(()->{hold.await();return 1;},(v,e)->afterClose.incrementAndGet());closing.close();hold.countDown();
        SwingUtilities.invokeAndWait(()->{});check(afterClose.get()==0,"closed login ignores completions");
    }
    static void ui()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            InventoryPanel panel=new InventoryPanel("SA004",null);panel.setSize(360,760);panel.doLayout();
            check(panel.draft.lines().isEmpty(),"UI constructs without EPB");
            panel.draft.add(p("A"),1);panel.close();check(panel.draft.lines().isEmpty(),"UI logout clears draft");
            JPanel nativeLeft=new JPanel(),right=new JPanel();JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,nativeLeft,right);split.setDividerLocation(300);
            SidebarHost host=new SidebarHost(()->true);JPanel assist=new JPanel(),inventory=new JPanel();
            check(host.mountOn(split,assist,inventory),"three-card sidebar mounts");host.showInventory();check(host.inventorySelected(),"inventory selected");
            host.showHome();check(!host.inventorySelected(),"native home selectable");host.restore();check(split.getLeftComponent()==nativeLeft,"same native component restored");host.restore();
            sidebarCollapseGuard();
        });
    }
    /**
     * 側欄被壓成 0 寬時要自己站回來，但 EPB 自己的全螢幕（dividerSize 也是 0）不能干涉。
     * 對應門市回報「關閉其他應用程式時整個左側欄消失」。
     */
    static void sidebarCollapseGuard(){
        JPanel nativeLeft=new JPanel(),right=new JPanel();
        JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,nativeLeft,right);
        split.setSize(900,600);split.setDividerLocation(300);
        SidebarHost host=new SidebarHost(()->true);
        check(host.mountOn(split,new JPanel(),new JPanel()),"guard fixture mounts");
        // 店員自己拖出來的寬度要被記住，救回來的才是他習慣的寬度
        split.setDividerLocation(240);
        split.setDividerLocation(0);
        host.checkCollapse();
        check(split.getDividerLocation()==240,"collapsed sidebar restored to the last dragged width");
        // EPB 全螢幕：divider 條也收掉了，那是刻意的，不要對打
        split.setDividerSize(0);split.setDividerLocation(0);
        host.checkCollapse();
        check(split.getDividerLocation()==0,"EPB full screen left alone");
        // 離開全螢幕後又被壓扁，就該再救一次
        split.setDividerSize(6);
        host.checkCollapse();
        check(split.getDividerLocation()==240,"guard resumes once the divider is back");
        host.restore("test");
        // 已還原就不該再動 split pane
        split.setDividerLocation(0);host.checkCollapse();
        check(split.getDividerLocation()==0,"restored sidebar is no longer guarded");
    }
    static void awaitUi(java.util.function.BooleanSupplier condition)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);AtomicBoolean ready=new AtomicBoolean();
        while(System.nanoTime()<deadline){SwingUtilities.invokeAndWait(()->ready.set(condition.getAsBoolean()));if(ready.get())return;Thread.sleep(10);}
        throw new AssertionError("UI completion timed out");
    }
    static void panelCall(InventoryPanel panel,String name){
        try{InventoryVisualCheck.call(panel,name);}catch(Exception e){throw new RuntimeException(e);}
    }
    static void selectionUi()throws Exception { NativeInventoryTest.selectionUi(); }
}
