package com.posassist;

import java.lang.reflect.Method;
import java.util.*;

/** Reads native CriteriaItem values, never rendered text or native stock result rows. */
final class NativeInventorySelection {
    static List<String> read(Object criteria) throws Exception {
        if (!(criteria instanceof Set)) throw new Exception("無法讀取原生商品條件，請執行自我檢查");
        Object selected = null;
        for (Object item : (Set<?>) criteria) {
            if (!"stkId".equals(call(item, "getFieldName"))) continue;
            if (selected != null) throw new Exception("存貨代碼條件不唯一，請回原生重新勾選商品");
            selected = item;
        }
        if (selected == null) return Collections.emptyList();
        if (!Boolean.FALSE.equals(call(selected,"isComposed")) || !Boolean.FALSE.equals(call(selected,"isIncludingNull")))
            throw new Exception("請以存貨代碼「等於／包括」選取明確商品");
        Object rawKeyword = call(selected,"getKeyWord");
        String keyword = rawKeyword instanceof String ? ((String)rawKeyword).trim().toUpperCase(Locale.ROOT) : "";
        Object value = call(selected,"getValue"), values = call(selected,"getValuesCopy");
        if (!(values instanceof Object[])) throw new Exception("原生商品格式不相容，請停止帶入並回報");
        List<?> ids;
        if ("IN".equals(keyword) && value == null) ids = Arrays.asList((Object[])values);
        else if ("=".equals(keyword) && ((Object[])values).length == 0) ids = Collections.singletonList(value);
        else throw new Exception("請以存貨代碼「等於／包括」選取商品；不支援範圍或模糊條件");
        if(ids.isEmpty())return Collections.emptyList();
        if("=".equals(keyword)&&(value==null||"".equals(value)))return Collections.emptyList();
        return ids(ids);
    }
    static List<String> ids(Collection<?> input) {
        LinkedHashSet<String> unique = new LinkedHashSet<String>();
        for (Object value : input) {
            // Treat the native string as an opaque identifier. Never split '/', coerce numbers or run SQL from it.
            if (!(value instanceof String)) throw new IllegalArgumentException("原生商品代碼格式不相容，請停止帶入並回報");
            String id=(String)value;
            if (id.isEmpty() || id.length()>200 || id.indexOf('/')>=0 || id.indexOf('|')>=0)
                throw new IllegalArgumentException("原生商品代碼格式不相容，請停止帶入並回報");
            for (int i=0;i<id.length();i++) if(Character.isWhitespace(id.charAt(i)) || Character.isISOControl(id.charAt(i)))
                throw new IllegalArgumentException("原生商品代碼格式不相容，請停止帶入並回報");
            unique.add(id);
        }
        if (unique.isEmpty()) throw new IllegalArgumentException("請先用存貨代碼放大鏡勾選商品，按 OK 後再比較");
        if (unique.size()>Inventory.MAX_ITEMS) throw new IllegalArgumentException("每次比較最多 30 項，請縮小原生選取範圍");
        return new ArrayList<String>(unique);
    }
    private static Object call(Object target,String name)throws Exception {
        if(target==null)throw new Exception("原生條件資料不完整");
        Method method=target.getClass().getMethod(name);method.setAccessible(true);return method.invoke(target);
    }
}
