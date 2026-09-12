package com.posassist;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.*;

/** Uses EPB's online transport. All vendor dependencies are resolved at runtime. */
final class EpbInventorySource implements InventorySource {
    static final String SHARED = "com.ipt.epbfrw.EpbSharedObjects";
    static final String BUSINESS = "com.epb.persistence.utl.BusinessUtility";
    static final String HOME = "com.epb.framework.ApplicationHome";
    interface Rows { List<Map<String, String>> query(String sql, int limit) throws Exception; }
    private final Rows rows;
    private final String storeAccess, itemAccess;
    private final boolean live;
    private final String identity;

    EpbInventorySource() throws Exception {
        this.identity = identity();
        String user = shared("getUserId"), org = shared("getOrgId"), loc = shared("getLocId");
        if (user.isEmpty() || loc.isEmpty()) throw new Exception("尚未登入 EPB");
        if (!org.equals("01")) throw new Exception("第一版僅供 SA 公司登入使用");
        requireAccess("STORESUM");
        Object home = home("STORESUM");
        Object admin = invoke(BUSINESS, "isAdmin", new Class<?>[]{String.class}, user);
        if (!(admin instanceof Boolean)) throw new Exception("無法確認庫存權限");
        storeAccess = Boolean.TRUE.equals(admin) ? "1=1" : storeAccess(user, "m.STORE_ID");
        Object clause = invoke("com.epb.persistence.utl.UserAccessControl", "getStkRefCatClause",
            new Class<?>[]{Class.forName(HOME), String.class}, home, "STORESUM");
        if (clause != null && !(clause instanceof String)) throw new Exception("庫存權限格式異常");
        itemAccess = clause == null || clause.toString().trim().isEmpty() ? "1=1" : clause.toString();
        rows = new RemoteRows(); live = true;
    }
    // Injection for offline fixtures and Query Kit validation, never selected by production configuration.
    EpbInventorySource(Rows rows, String storeAccess, String itemAccess) {
        this.rows = rows; this.storeAccess = storeAccess; this.itemAccess = itemAccess; live = false; identity = "test";
    }
    static String storeAccess(String user, String column) {
        String u = InventorySql.literal(user);
        return "(" + column + " IN (SELECT STOREMAS_LOC.STORE_ID FROM STOREMAS_LOC, EP_USER_LOC "
            + "WHERE STOREMAS_LOC.LOC_ID=EP_USER_LOC.LOC_ID AND EP_USER_LOC.USER_ID=" + u + ") OR "
            + column + " IN (SELECT STORE_ID FROM EP_USER_STORE WHERE USER_ID=" + u + "))";
    }
    private void session() throws Exception {
        if (live && !identity.equals(identity())) throw new Exception("登入已變更，請重新查詢");
        if (live) requireAccess("STORESUM");
    }
    public List<Inventory.Store> stores() throws Exception {
        session();
        List<Map<String, String>> data = rows.query(InventorySql.roster(storeAccess), InventorySql.STORE_LIMIT);
        List<Inventory.Store> result = new ArrayList<Inventory.Store>(); Set<String> ids = new HashSet<String>();
        for (Map<String, String> r : data) {
            Inventory.Store s = new Inventory.Store(field(r,"STORE_ID"), field(r,"STORE_NAME"), field(r,"SHOP_TYPE"));
            if (!ids.add(s.id)) throw new Exception("門市對照重複，無法比較");
            result.add(s);
        }
        session();
        if (result.isEmpty()) throw new Exception("此帳號沒有可查詢的 SA 一般門市");
        return result;
    }
    public List<Inventory.Product> search(String text) throws Exception {
        List<Inventory.Store> stores = stores();
        List<String> ids=catalog(text);
        if(ids.isEmpty())return Collections.emptyList();
        List<Map<String,String>> data = rows.query(InventorySql.restrict(InventorySql.search(stores, itemAccess, text),ids), InventorySql.SEARCH_LIMIT);
        List<Inventory.Product> result = new ArrayList<Inventory.Product>();
        for (Map<String,String> row : data) result.add(product(row));
        session(); return result;
    }
    public Inventory.Snapshot stock(List<Inventory.Line> lines) throws Exception {
        List<Inventory.Store> stores = stores();
        Inventory.Snapshot snapshot = snapshot(stores, InventorySql.stock(stores, itemAccess, lines));
        for (Inventory.Line l : lines) {
            Inventory.Product current = snapshot.products.get(l.product.id);
            if (current == null || !current.unit.equals(l.product.unit))
                throw new Exception("商品已不在可查詢範圍或單位變更，請重新搜尋");
        }
        return snapshot;
    }
    public List<Inventory.Product> products(List<String> requested) throws Exception {
        List<String> ids=NativeInventorySelection.ids(requested);
        List<Inventory.Store> stores=stores();
        List<Map<String,String>> data=rows.query(InventorySql.products(stores,itemAccess,ids),Inventory.MAX_ITEMS);
        Map<String,Inventory.Product> found=new HashMap<String,Inventory.Product>();
        Set<String> invalid=new HashSet<String>();
        for(Map<String,String> row:data){
            String id=field(row,"STK_ID");
            if(!ids.contains(id)||found.containsKey(id)||invalid.contains(id))throw new Exception("商品回應重複或超出選取範圍，尚未帶入");
            try {
                if(!"1".equals(field(row,"UNIT_COUNT")))throw new Exception("單位不一致");
                found.put(id,product(row));
            }catch(Exception e){invalid.add(id);}
        }
        List<String> missing=new ArrayList<String>();List<Inventory.Product> result=new ArrayList<Inventory.Product>();
        for(String id:ids){if(!found.containsKey(id))missing.add(id);else result.add(found.get(id));}
        session();
        if(!missing.isEmpty())throw new Exception("本次未帶入；以下代碼不在可查詢／支援範圍、缺少資料或單位異常："+String.join("、",missing)+"。請調整原生勾選後重試");
        return result;
    }
    public Inventory.Snapshot category(Inventory.Category category) throws Exception {
        List<Inventory.Store> stores = stores();
        return snapshot(stores, InventorySql.category(stores, itemAccess, category));
    }
    private List<String> catalog(String text)throws Exception{
        List<Map<String,String>> data=rows.query(InventorySql.catalog(text),InventorySql.CATALOG_LIMIT);
        List<String> ids=new ArrayList<String>();
        for(Map<String,String> row:data)ids.add(field(row,"STK_ID"));
        session();return ids;
    }
    private Inventory.Snapshot snapshot(List<Inventory.Store> stores, String sql) throws Exception {
        List<Map<String,String>> data = rows.query(sql, InventorySql.STOCK_LIMIT);
        Map<String,Inventory.Product> products = new LinkedHashMap<String,Inventory.Product>();
        Map<String,BigDecimal> quantities = new HashMap<String,BigDecimal>();
        for (Map<String,String> row : data) {
            Inventory.Product p = product(row);
            if (!field(row,"UNIT_COUNT").equals("1")) throw new Exception("商品單位不一致，無法加總");
            Inventory.Product old = products.put(p.id, p);
            if (old != null && !old.unit.equals(p.unit)) throw new Exception("跨店商品單位不一致");
            String key = Inventory.key(field(row,"STORE_ID"), p.id);
            if (quantities.put(key, new BigDecimal(field(row,"STOCK_QTY"))) != null)
                throw new Exception("庫存結果重複，請重新查詢");
        }
        session();
        return new Inventory.Snapshot(stores, products.values(), quantities, System.currentTimeMillis());
    }
    static Inventory.Product product(Map<String,String> row) throws Exception {
        return new Inventory.Product(field(row,"STK_ID"),field(row,"PRODUCT_NAME"), nullable(row,"MODEL"),
            field(row,"UOM_ID"), nullable(row,"CAT3_ID"), nullable(row,"CAT4_ID"));
    }
    static String field(Map<String,String> row, String key) throws Exception {
        String value = nullable(row,key);
        if (value.isEmpty()) throw new Exception("庫存回應缺少必要欄位：" + key);
        return value;
    }
    static String nullable(Map<String,String> row, String key) throws Exception {
        if (!row.containsKey(key)) throw new Exception("庫存回應欄位不相容：" + key);
        return Inventory.clean(row.get(key));
    }
    public void openTransfer() throws Exception {
        if (!live) throw new Exception("離線驗證不開啟原生單據");
        session(); requireAccess("INVTRNRN");
        Object allowed = invoke("com.ipt.epbtls.EpbApplicationUtility", "checkPrivilege",
            new Class<?>[]{String.class,String.class,String.class}, shared("getUserId"), "INVTRNRN", "NEW");
        if (!Boolean.TRUE.equals(allowed)) throw new Exception("此帳號沒有調撥申請建立權限");
        Object pool = invoke("com.epb.framework.ApplicationPool","getInstance",new Class<?>[0]);
        Object app = pool.getClass().getMethod("openApplication", String.class, Class.forName(HOME),
            Class.forName("com.epb.framework.ValueContext")).invoke(pool,"INVTRNRN",home("INVTRNRN"),null);
        if (app == null) throw new Exception("原生調撥申請未能開啟，草稿已保留");
    }
    public void openStoresum() throws Exception {
        if(!live)throw new Exception("離線驗證不開啟原生畫面");
        session();requireAccess("STORESUM");
        Object pool=invoke("com.epb.framework.ApplicationPool","getInstance",new Class<?>[0]);
        Object app=pool.getClass().getMethod("openApplication",String.class,Class.forName(HOME),Class.forName("com.epb.framework.ValueContext"))
            .invoke(pool,"STORESUM",home("STORESUM"),null);
        if(app==null)throw new Exception("STORESUM 未能開啟，請從原生應用程式選單開啟");
    }
    static void requireAccess(String app) throws Exception {
        Object allowed = invoke(BUSINESS,"canViewApp",new Class<?>[]{String.class,String.class,String.class},
            shared("getLocId"),shared("getUserId"),app);
        if (!Boolean.TRUE.equals(allowed)) throw new Exception("此帳號沒有 " + app + " 使用權限");
    }
    static Object home(String app) throws Exception {
        return Class.forName(HOME).getConstructor(String.class,String.class,String.class,String.class,String.class)
            .newInstance(app,shared("getCharset"),shared("getLocId"),shared("getOrgId"),shared("getUserId"));
    }
    static String identity() throws Exception { return shared("getUserId")+"|"+shared("getOrgId")+"|"+shared("getLocId"); }
    static String shared(String method) throws Exception {
        Object v = invoke(SHARED,method,new Class<?>[0]); return v == null ? "" : v.toString().trim();
    }
    static Object invoke(String type, String method, Class<?>[] signature, Object... args) throws Exception {
        return Class.forName(type).getMethod(method, signature).invoke(null,args);
    }
    static final class RemoteRows implements Rows {
        private static final java.util.concurrent.locks.ReentrantLock TRANSPORT = new java.util.concurrent.locks.ReentrantLock();
        private final String login;
        RemoteRows() throws Exception { login=identity(); }
        public List<Map<String,String>> query(String sql, int limit) throws Exception {
            if(!TRANSPORT.tryLock())throw new Exception("前次庫存連線仍在結束，請稍後重試");
            try{
                if(!login.equals(identity()))throw new Exception("登入已變更，請重新查詢");
                return queryLocked(sql,limit);
            }finally{TRANSPORT.unlock();}
        }
        private List<Map<String,String>> queryLocked(String sql, int limit) throws Exception {
            String bounded = InventorySql.bounded(sql,limit);
            String wsdl = shared("getTransferWsdl");
            if (wsdl.isEmpty()) throw new Exception("EPB 遠端查詢連線尚未就緒");
            ResultSet result = null;
            try {
                Class<?> type = Class.forName("com.epb.trans.EPB_Trans_Client4");
                result = (ResultSet) type.getMethod("fGet_Recordset",String.class,String.class)
                    .invoke(type.getConstructor().newInstance(),wsdl,bounded);
                if (result == null) throw new Exception("遠端回應不完整");
                return read(result,limit);
            } catch (InvocationTargetException error) {
                // Vendor exceptions can contain connection details or SQL. Never expose them.
                throw new Exception("EPB 庫存連線失敗或逾時，請稍後重試");
            } finally { if (result != null) try { result.close(); } catch (Exception ignored) {} }
        }
    }
    static List<Map<String,String>> read(ResultSet result,int limit) throws Exception {
        List<Map<String,String>> data = new ArrayList<Map<String,String>>();
        int count = result.getMetaData().getColumnCount();
        boolean hasCount=false;int expected=-1;
        for(int i=1;i<=count;i++)if("PA_RESULT_ROWS".equalsIgnoreCase(result.getMetaData().getColumnLabel(i)))hasCount=true;
        if(!hasCount)throw new Exception("庫存回應缺少完整性欄位");
        while (result.next()) {
            if (data.size() >= limit) throw new Exception("結果超過上限，請縮小商品查詢範圍");
            Map<String,String> row = new LinkedHashMap<String,String>();
            for (int i=1;i<=count;i++) row.put(result.getMetaData().getColumnLabel(i).toUpperCase(Locale.ROOT),result.getString(i));
            int total=new BigDecimal(field(row,"PA_RESULT_ROWS")).intValueExact();
            if(total<1||(expected>=0&&expected!=total))throw new Exception("庫存回應筆數不一致");
            expected=total;row.remove("PA_RESULT_ROWS");
            data.add(row);
        }
        if(expected>=0&&expected!=data.size())throw new Exception("庫存回應遭截斷，請重新查詢");
        return data;
    }
}
