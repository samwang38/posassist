package com.posassist;

import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

/**
 * POSVIP 的現場規則。
 *
 * 建立會員的規則不是寫死的，每家門市可以自己設：手機要幾碼、姓名要不要全域唯一、
 * Email 要不要檢查格式、會員代碼自動產生還是手打。這些設定 POSVIP 自己是用
 * BusinessUtility.getAppSetting 讀的，解析順序是
 * EP_APP_SETTING_LOC → EP_APP_SETTING_ORG → EP_APP_SETTING。
 *
 * 我們直接反射呼叫同一支公開方法，不自己重寫那三段 SQL —— 順序一旦跟原生不一致，
 * 側欄就會拿門市根本沒在用的設定去擋店員。
 *
 * 讀不到就當作「不檢查」。少擋一項，最多是店員在原生畫面被退一次；擋錯一項，
 * 是店員明明可以建卻建不了。前者比較好收拾。
 */
public final class PosVipRules {

    private static final String BIZ = "com.epb.persistence.utl.BusinessUtility";
    private static final String APP_CODE = "POSVIP";

    private static final Class<?>[] SETTING_SIGNATURE = new Class<?>[] {
        String.class, String.class, String.class, String.class
    };

    /** 設定在一次登入裡不會變，每個 session 只讀一輪。 */
    private static volatile PosVipRules cached;

    /** HPCHECKLTH：手機固定碼數。0 代表沒設定或讀不到，不檢查。 */
    public final int phoneLength;
    /** HPCHECKNAME：姓名是否需要全域唯一。 */
    public final boolean checkName;
    /** EMAILCHECKCONT：是否檢查 Email 格式。 */
    public final boolean checkEmail;
    /** AUTOCODE：會員代碼是否自動產生。 */
    public final boolean autoCode;
    /** GENDER：新會員的預設性別（M／F）；沒設定是空字串。 */
    public final String defaultGender;
    /** CLASSID：新會員的預設等級；沒設定是空字串。 */
    public final String defaultClassId;

    private PosVipRules(int phoneLength, boolean checkName, boolean checkEmail,
        boolean autoCode, String defaultGender, String defaultClassId) {
        this.phoneLength = phoneLength;
        this.checkName = checkName;
        this.checkEmail = checkEmail;
        this.autoCode = autoCode;
        this.defaultGender = defaultGender;
        this.defaultClassId = defaultClassId;
    }

    /** 全部關掉的保底值：EPB 還沒登入、或設定讀不到時用這個。 */
    private static PosVipRules disabled() {
        return new PosVipRules(0, false, false, false, "", "");
    }

    /** 這輪登入的規則。永遠非 null。 */
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
            // 沒有 LOC_ID 就讀不到門市層設定，猜 ORG 層只會拿到別人的規則
            PosLog.warn("讀不到登入中的 LOC_ID，會員建立輔助不做門市規則預檢");
            return disabled();
        }
        String orgId = VipLookup.sessionOrgId();

        PosVipRules rules = new PosVipRules(
            positiveInt(setting(locId, orgId, "HPCHECKLTH")),
            isYes(setting(locId, orgId, "HPCHECKNAME")),
            isYes(setting(locId, orgId, "EMAILCHECKCONT")),
            isYes(setting(locId, orgId, "AUTOCODE")),
            text(setting(locId, orgId, "GENDER")),
            text(setting(locId, orgId, "CLASSID")));

        PosLog.info("POSVIP 規則：手機碼數 "
            + (rules.phoneLength > 0 ? String.valueOf(rules.phoneLength) : "不限")
            + "、姓名唯一 " + (rules.checkName ? "是" : "否")
            + "、Email 檢查 " + (rules.checkEmail ? "是" : "否")
            + "、自動代碼 " + (rules.autoCode ? "是" : "否"));
        return rules;
    }

    /** 讀一項 app setting。讀不到回 null，由呼叫端當作「沒設定」。 */
    private static String setting(String locId, String orgId, String setId) {
        Object value = Safe.staticCall(BIZ, "getAppSetting", SETTING_SIGNATURE,
            new Object[] { APP_CODE, locId, orgId, setId });
        return value == null ? null : String.valueOf(value).trim();
    }

    // -- 姓名重複 ----------------------------------------------------------

    /**
     * 姓名在 POS_VIP_MAS 裡是不是已經有人用了。
     *
     * 對齊 POSVIP 的 CustomizeNameUniqueValidator：它的 SQL 同樣沒有 ORG_ID 條件，
     * 是全域比對。只有在門市開了 HPCHECKNAME 時才會真的送出查詢。
     *
     * @return TRUE 已被用、FALSE 沒被用、null 代表查不出來（別拿 null 當作沒被用）
     */
    public static Boolean nameTaken(String name) {
        String text = name == null ? "" : name.trim();
        if (text.length() == 0 || !current().checkName) {
            return Boolean.FALSE;
        }
        List<Object> params = new ArrayList<Object>();
        params.add(text);
        List<Vector> rows = VipLookup.query(nameConflictSql(), params);
        if (rows == null) {
            return null;
        }
        return rows.isEmpty() ? Boolean.FALSE : Boolean.TRUE;
    }

    /** 抽成方法讓 SelfTest 直接對這段 SQL 做可攜性與綁定數把關。 */
    static String nameConflictSql() {
        return "SELECT 1 FROM POS_VIP_MAS WHERE NAME = ?";
    }

    // -- 小工具 ------------------------------------------------------------

    private static boolean isYes(String value) {
        return value != null && "Y".equalsIgnoreCase(value.trim());
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    /** 設定值不是正整數就回 0（＝不檢查）。 */
    private static int positiveInt(String value) {
        if (value == null || value.length() == 0) {
            return 0;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : 0;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
