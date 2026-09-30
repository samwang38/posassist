package com.posassist;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主機的關聯存貨（實務上就是對應的 AppleCare+）。
 *
 * 資料在 EPB 的 STKMAS_RET：STK_ID 是主機、STK_ID_RET 是關聯存貨。POSN 自己在加品項時
 * 也是用 LocalPersistence 讀這張表（EpbPosCheckUtility），所以本機資料庫一定有它。
 * 同一台主機可能掛著好幾筆舊的、已停用的 AppleCare，只取商品主檔 STATUS_FLG='A' 的；
 * 2026-09-30 用正式資料核對過：台灣正常品主機每台恰好一筆有效關聯。
 *
 * 只讀不寫。查詢走 VipLookup.query（先用獨立連線，不跟 POSN 搶共用連線），
 * 在自己的背景執行緒上跑，結果轉回 EDT 才交給呼叫端。
 */
final class RelatedStock {

    static final class Item {
        final String code;
        final String name;

        Item(String code, String name) {
            this.code = code;
            this.name = name;
        }
    }

    interface Callback {
        /** 跑在 EDT 上。查詢失敗時不會呼叫。 */
        void found(List<Item> items);
    }

    /**
     * 兩種資料庫都要能跑（本機 PostgreSQL，別台可能是 Oracle），所以只用 ANSI 寫法；
     * 日期由程式帶綁定參數（明天零點、今天零點），不用 SYSDATE／CURRENT_DATE。
     * 開始日用「早於明天零點」而不是「不晚於今天零點」：開始日帶時間時，當天就該生效。
     */
    static final String SQL =
        "SELECT r.STK_ID_RET, m.NAME FROM STKMAS_RET r "
        + "JOIN STKMAS m ON m.STK_ID = r.STK_ID_RET "
        + "WHERE r.STK_ID = ? AND m.STATUS_FLG = 'A' "
        + "AND (r.START_DATE IS NULL OR r.START_DATE < ?) "
        + "AND (r.END_DATE IS NULL OR r.END_DATE >= ?) "
        + "ORDER BY r.STK_ID_RET";

    private static final int MAX_ROWS = 10;
    private static final int CACHE_SIZE = 300;

    /** 同一台主機一個交易裡可能被掃好幾次，關聯資料一天內不會變，記在記憶體裡就好。 */
    private static final Map<String, List<Item>> CACHE =
        Collections.synchronizedMap(new LinkedHashMap<String, List<Item>>(64, 0.75f, true) {
            protected boolean removeEldestEntry(Map.Entry<String, List<Item>> eldest) {
                return size() > CACHE_SIZE;
            }
        });

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "PosAssist-RelatedStock");
        thread.setDaemon(true);
        return thread;
    });

    private RelatedStock() {
    }

    /** 背景查詢，結果在 EDT 上交給 callback。 */
    static void lookupAsync(final String stkId, final Callback callback) {
        if (stkId == null || stkId.trim().length() == 0) {
            return;
        }
        WORKER.execute(() -> Safe.guard("查關聯存貨", () -> {
            final List<Item> items = lookup(stkId.trim());
            if (items != null) {
                FloatingPanel.onEdt(() -> callback.found(items));
            }
        }));
    }

    /** 查詢失敗回 null（不快取，下次再試），查無關聯回空 list。 */
    static List<Item> lookup(String stkId) {
        List<Item> cached = CACHE.get(stkId);
        if (cached != null) {
            return cached;
        }
        java.util.Calendar day = java.util.Calendar.getInstance();
        day.set(java.util.Calendar.HOUR_OF_DAY, 0);
        day.set(java.util.Calendar.MINUTE, 0);
        day.set(java.util.Calendar.SECOND, 0);
        day.set(java.util.Calendar.MILLISECOND, 0);
        java.sql.Timestamp today = new java.sql.Timestamp(day.getTimeInMillis());
        day.add(java.util.Calendar.DAY_OF_MONTH, 1);
        java.sql.Timestamp tomorrow = new java.sql.Timestamp(day.getTimeInMillis());
        List<Object> params = new ArrayList<Object>();
        params.add(stkId);
        params.add(tomorrow);
        params.add(today);
        List<Vector> rows = VipLookup.query(SQL, params);
        if (rows == null) {
            PosLog.warn("查關聯存貨失敗：" + stkId);
            return null;
        }
        List<Item> items = new ArrayList<Item>();
        for (int i = 0; i < rows.size() && i < MAX_ROWS; i++) {
            Vector row = rows.get(i);
            String code = cell(row, 0);
            if (code.length() != 0) {
                items.add(new Item(code, cell(row, 1)));
            }
        }
        List<Item> frozen = Collections.unmodifiableList(items);
        CACHE.put(stkId, frozen);
        return frozen;
    }

    private static String cell(Vector row, int index) {
        if (row == null || index >= row.size() || row.get(index) == null) {
            return "";
        }
        return String.valueOf(row.get(index)).trim();
    }
}
