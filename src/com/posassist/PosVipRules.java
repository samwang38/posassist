package com.posassist;

/**
 * POSVIP 的門市設定。
 *
 * 現在只剩一項：會員代碼是不是自動產生。欄位的驗證（手機碼數、姓名唯一、
 * Email 格式）以前是我們自己先跑一次，改用原生建立表單之後就不必了 ——
 * POSVIP 的驗證器會在送出時自己擋，而且擋得比我們準。
 *
 * POSVIP 讀設定是用 BusinessUtility.getAppSetting，解析順序是
 * EP_APP_SETTING_LOC → EP_APP_SETTING_ORG → EP_APP_SETTING。我們直接反射呼叫
 * 同一支公開方法，不自己重寫那三段 SQL。
 *
 * 讀不到就當作「不是自動產生」，讓代碼欄顯示出來 —— 多一個欄位讓店員看到，
 * 比自動產生卻沒有欄位可填好收拾。
 */
public final class PosVipRules {

    private static final String BIZ = "com.epb.persistence.utl.BusinessUtility";
    private static final String APP_CODE = "POSVIP";

    private static final Class<?>[] SETTING_SIGNATURE = new Class<?>[] {
        String.class, String.class, String.class, String.class
    };

    /** 設定在一次登入裡不會變，每個 session 只讀一次。 */
    private static volatile PosVipRules cached;

    /** AUTOCODE：會員代碼是否自動產生。 */
    public final boolean autoCode;

    private PosVipRules(boolean autoCode) {
        this.autoCode = autoCode;
    }

    /** 這輪登入的設定。永遠非 null。 */
    public static PosVipRules current() {
        PosVipRules snapshot = cached;
        if (snapshot != null) {
            return snapshot;
        }
        snapshot = load();
        cached = snapshot;
        return snapshot;
    }

    private static PosVipRules load() {
        String locId = VipLookup.sessionLocId();
        if (locId == null) {
            PosLog.warn("讀不到登入中的 LOC_ID，會員代碼欄一律顯示");
            return new PosVipRules(false);
        }
        Object value = Safe.staticCall(BIZ, "getAppSetting", SETTING_SIGNATURE,
            new Object[] { APP_CODE, locId, VipLookup.sessionOrgId(), "AUTOCODE" });
        boolean autoCode = value != null
            && "Y".equalsIgnoreCase(String.valueOf(value).trim());
        PosLog.info("POSVIP 會員代碼自動產生：" + (autoCode ? "是" : "否"));
        return new PosVipRules(autoCode);
    }
}
