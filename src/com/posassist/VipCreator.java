package com.posassist;

import java.util.HashMap;
import java.util.Map;

/**
 * 開啟原生 POSVIP 的橋接層。
 *
 * 這是整個會員建立輔助裡唯一碰到 EPB 的地方，界線很窄，刻意維持在兩件事：
 * 問一次權限、開一次原生視窗。會員資料的實際寫入從頭到尾都是原生 POSVIP 做的，
 * PosAssist 不送出、也不預填。
 *
 * 為什麼不預填：callEpbApplication 會把 Map 包成 ParameterMapWrapperValueContext
 * 交給 ApplicationPool.openApplication，但 POSVIP.action(ValueContext) 只認
 * GotoEnquiryActionValueContext，其他一律回 null。傳什麼進去都不會有作用，
 * 所以這裡就傳一個空 Map，不假裝我們能帶值。
 *
 * 明確不做的事：不呼叫 EpbApplicationUtility.execute、不下 INSERT/UPDATE、
 * 不反射 POSVIP 的 CreatorView/Block、不改 EPB 原廠檔案。
 */
public final class VipCreator {

    private static final String UTILITY = "com.ipt.epbtls.EpbApplicationUtility";
    private static final String SHARED = "com.ipt.epbfrw.EpbSharedObjects";

    private static final String APP_CODE = "POSVIP";
    private static final String PRIVILEGE = "NEW";

    private VipCreator() {
    }

    /**
     * 現在能不能開建立會員。
     *
     * @return null 代表可以；非 null 是給店員看的停用原因
     */
    public static String unavailableReason() {
        if (Safe.type(UTILITY) == null) {
            return "這台找不到 EPB 會員模組";
        }
        Object userId = Safe.staticCall(SHARED, "getUserId", new Class<?>[0], new Object[0]);
        String user = userId == null ? "" : String.valueOf(userId).trim();
        if (user.length() == 0) {
            return "尚未登入 EPB";
        }
        // 3 參數版會自己補上登入中的 LOC_ID，跟原生 POSVIP 判斷 isNewAllowed 同一條路
        Object allowed = Safe.staticCall(UTILITY, "checkPrivilege",
            new Class<?>[] { String.class, String.class, String.class },
            new Object[] { user, APP_CODE, PRIVILEGE });
        if (allowed == null) {
            return "無法確認會員建立權限";
        }
        if (!Boolean.TRUE.equals(allowed)) {
            return "這個帳號沒有建立會員的權限";
        }
        return null;
    }

    /**
     * 開啟原生 POSVIP。開啟前會重新確認一次權限 —— 面板可能開著很久，
     * 不能拿當初畫按鈕時的判斷當現在的依據。
     *
     * @return 失敗時回傳原因，成功回 null
     */
    public static String open() {
        String reason = unavailableReason();
        if (reason != null) {
            PosLog.warn("不開啟 POSVIP：" + reason);
            return reason;
        }
        // 空 Map：POSVIP 不吃參數（見類別註解），傳值只會給人「有帶入」的錯覺
        Object application = Safe.staticCall(UTILITY, "callEpbApplication",
            new Class<?>[] { String.class, Map.class },
            new Object[] { APP_CODE, new HashMap<String, Object>() });
        if (application == null) {
            PosLog.warn("開啟 POSVIP 失敗");
            return "開啟會員模組失敗";
        }
        PosLog.info("已開啟原生 POSVIP，等待店員自行送出");
        return null;
    }
}
