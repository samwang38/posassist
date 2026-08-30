package com.posassist;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/**
 * 叫出 EPB 原生的會員建立表單。
 *
 * 這裡不自己畫表單、不自己驗證、也不自己寫資料庫。整段就是照著 EPB 自己的
 * com.epb.framework.CreatorAction.actionPerformed() 走一遍 —— 那是 EPB 模組之間
 * 互相叫「建立畫面」本來就在用的路（例如某張單要新增客戶時就是這樣叫 CUSTOMER）。
 *
 * 實際寫入發生在 CreatorView.doCreate() 裡的 BlockFormPM.commitChanges()，
 * 是框架自己的持久化。POSVIP 的驗證器、自動帶值（BirthDateAutomator）、
 * 預設值（PosVipMasDefaultsApplier）與權限控制（PosVipMasSecurityControl）
 * 全程有效，跟店員自己開 POSVIP 按新增是同一套。
 *
 * 我們只多做兩件事：把用不到的欄位藏起來（只留 5~6 欄），
 * 以及把店員剛查的電話預先填好。
 */
public final class VipCreator {

    private static final String UTILITY = "com.ipt.epbtls.EpbApplicationUtility";
    private static final String SHARED = "com.ipt.epbfrw.EpbSharedObjects";
    private static final String POOL = "com.epb.framework.ApplicationPool";
    private static final String HOME = "com.epb.framework.ApplicationHome";
    private static final String VALUE_CONTEXT = "com.epb.framework.ValueContext";
    private static final String BLOCK = "com.epb.framework.Block";
    private static final String CONFIG_UTILITY = "com.epb.framework.ConfigUtility";
    private static final String CREATOR_VIEW = "com.epb.framework.CreatorView";
    private static final String VIEW = "com.epb.framework.View";
    private static final String APPLICATION = "com.epb.framework.Application";
    private static final String DEFAULTS_APPLIER = "com.epb.framework.DefaultsApplier";
    private static final String PROPERTY_UTILS = "org.apache.commons.beanutils.PropertyUtils";
    private static final String PROPERTY_UTILITY = "com.epb.framework.PropertyUtility";

    /** Block.DEFAULT_FORM_GROUP_ID。表單只有一組，就是這個空字串。 */
    private static final String FORM_GROUP_DEFAULT = "";

    private static final String APP_CODE = "POSVIP";
    private static final String PRIVILEGE = "NEW";

    /** 建立表單上要留下來的欄位。門市可用 vipCreateFields 覆寫。 */
    private static final String[] DEFAULT_FIELDS = {
        "name", "vipPhone1", "emailAddr", "birthDate", "gender"
    };

    /** 會員代碼欄位。自動產生代碼時不顯示，要手打時才補進來。 */
    private static final String FIELD_VIP_ID = "vipId";

    /** Application.CLOSE_CONDITION_FORCE。CreatorAction 也是傳這個。 */
    private static final int CLOSE_FORCE = 0;

    private VipCreator() {
    }

    /** 建立結果。vipId 非 null 代表建好了；problem 非 null 代表沒建成。 */
    public static final class Result {
        public final String vipId;
        public final String problem;

        private Result(String vipId, String problem) {
            this.vipId = vipId;
            this.problem = problem;
        }

        /** 店員自己按取消：不是錯誤，也沒有建立任何東西。 */
        public boolean cancelled() {
            return vipId == null && problem == null;
        }

        static Result created(String vipId) {
            return new Result(vipId, null);
        }

        static Result cancel() {
            return new Result(null, null);
        }

        static Result fail(String problem) {
            return new Result(null, problem);
        }
    }

    // -- 可用性 ------------------------------------------------------------

    /**
     * 現在能不能開建立會員。
     *
     * @return null 代表可以；非 null 是給店員看的停用原因
     */
    public static String unavailableReason() {
        if (Safe.type(UTILITY) == null || Safe.type(CREATOR_VIEW) == null) {
            return "這台找不到 EPB 會員模組";
        }
        String user = sessionUserId();
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

    // -- 建立 --------------------------------------------------------------

    /**
     * 開啟原生建立表單，等店員填完送出。**這個方法會擋住呼叫的執行緒**
     * （原生表單是 modal 的），所以一定要在 EDT 上呼叫。
     *
     * @param phone 預先填進電話欄的號碼；null 表示不預填
     */
    public static Result create(String phone) {
        String reason = unavailableReason();
        if (reason != null) {
            PosLog.warn("不開啟建立表單：" + reason);
            return Result.fail(reason);
        }

        Object home = applicationHome();
        if (home == null) {
            return Result.fail("取不到登入資訊");
        }

        Object application = creatorApplication(home);
        if (application == null) {
            return Result.fail("開啟會員模組失敗");
        }

        try {
            Object block = Safe.call(application, "getCreatorBlock");
            if (block == null) {
                return Result.fail("取不到會員建立區塊");
            }

            // 把區塊的來源換成我們這邊的 home，ORG／LOC／USER 才會是登入中的這組。
            // CreatorAction 也是這樣做的。
            Class<?> valueContext = Safe.type(VALUE_CONTEXT);
            if (valueContext != null) {
                Safe.call(block, "removeValueContext",
                    new Class<?>[] { valueContext },
                    new Object[] { Safe.call(application, "getApplicationHome") });
                Safe.call(block, "addValueContext",
                    new Class<?>[] { valueContext }, new Object[] { home });
            }

            prefillPhone(block, phone);

            Properties config = config(application);
            limitFields(block, config);

            Object created = showDialog(block, config);
            if (created == null) {
                PosLog.info("店員取消了建立會員，沒有寫入任何資料");
                return Result.cancel();
            }

            String vipId = readVipId(created);
            if (vipId == null || vipId.length() == 0) {
                // 建立成功但讀不出代碼：資料已經進去了，別讓店員以為失敗
                PosLog.warn("會員已建立，但讀不到會員代碼");
                return Result.fail("會員已建立，請用電話重新查詢");
            }
            PosLog.info("原生表單已建立會員，代碼長度 " + vipId.length());
            return Result.created(vipId);
        } finally {
            Safe.call(application, "close",
                new Class<?>[] { int.class },
                new Object[] { Integer.valueOf(CLOSE_FORCE) });
        }
    }

    // -- 各步驟 ------------------------------------------------------------

    /** 用登入中的資訊組一個指向 POSVIP 的 ApplicationHome。 */
    private static Object applicationHome() {
        String locId = VipLookup.sessionLocId();
        if (locId == null) {
            PosLog.warn("讀不到登入中的 LOC_ID");
            return null;
        }
        return Safe.construct(HOME,
            new Class<?>[] { String.class, String.class, String.class,
                             String.class, String.class },
            new Object[] { APP_CODE, sessionCharset(), locId,
                           VipLookup.sessionOrgId(), sessionUserId() });
    }

    /**
     * 跟 ApplicationPool 要一個「只拿來建立」的 POSVIP。
     *
     * 用 getCreatorApplication 而不是 openApplication：前者不會把 POSVIP
     * 掛進使用者看得到的應用程式清單，關掉之後也不留痕跡。
     */
    private static Object creatorApplication(Object home) {
        Object pool = Safe.staticCall(POOL, "getInstance",
            new Class<?>[0], new Object[0]);
        if (pool == null) {
            PosLog.warn("取不到 ApplicationPool");
            return null;
        }
        Class<?> homeType = Safe.type(HOME);
        Class<?> valueContext = Safe.type(VALUE_CONTEXT);
        if (homeType == null || valueContext == null) {
            return null;
        }
        return Safe.call(pool, "getCreatorApplication",
            new Class<?>[] { String.class, homeType, valueContext },
            new Object[] { APP_CODE, home, null });
    }

    /**
     * 把建立表單收成只剩要填的那幾欄。
     *
     * 這裡曾經用 Block.registerInvisibleFieldNames() —— 那是錯的 API，它管的是
     * 表格的欄位，BlockFormPM 組表單時根本不看它，所以整張 50 幾欄照樣全出來。
     *
     * 真正決定表單長相的是 config 裡的「form sequence」：BlockFormPM.setupFormItems()
     * 會先問 PropertyUtility.containsFormSequence()，有就照那份清單與順序排，
     * 沒有才自己用 getPropertyDescriptors() 生一份全欄位的。我們就是先幫它寫好那一份。
     *
     * 清單裡放的是屬性名（name、vipPhone1…），跟 autoCreateFormSequences() 一致。
     */
    private static void limitFields(Object block, Properties config) {
        Object name = Safe.call(block, "getEffectiveName");
        if (name == null) {
            PosLog.warn("取不到區塊名稱，建立表單會顯示全部欄位");
            return;
        }
        String blockName = String.valueOf(name);

        Set<String> visible = new LinkedHashSet<String>(visibleFields());
        // 設定裡標成必填的欄位一定要留著，否則店員會卡在一個看不到的欄位上存不了檔。
        // BlockFormPM 自己也做同一件事，這裡先做是為了讓 log 反映真正的欄位數。
        Object required = Safe.staticCall(PROPERTY_UTILITY, "getRequiredFields",
            new Class<?>[] { Properties.class, String.class },
            new Object[] { config, blockName });
        if (required instanceof Collection) {
            for (Object field : (Collection<?>) required) {
                visible.add(String.valueOf(field));
            }
        }

        TreeMap<String, List<String>> sequences = new TreeMap<String, List<String>>();
        sequences.put(FORM_GROUP_DEFAULT, new ArrayList<String>(visible));
        Safe.staticCall(PROPERTY_UTILITY, "updateFormSequences",
            new Class<?>[] { Properties.class, String.class, TreeMap.class },
            new Object[] { config, blockName, sequences });
        PosLog.info("建立表單收成 " + visible.size() + " 個欄位：" + visible);
    }

    /** 這次要顯示哪些欄位。門市可以用 vipCreateFields 整份覆寫。 */
    static Set<String> visibleFields() {
        Set<String> fields = new LinkedHashSet<String>();
        String configured = Home.value("config/posassist.properties",
            "vipCreateFields", "");
        if (configured.length() != 0) {
            for (String part : configured.split(",")) {
                String field = part.trim();
                if (field.length() != 0) {
                    fields.add(field);
                }
            }
            return fields;
        }
        // 代碼自動產生時不必給店員看；要手打的門市才補上來，而且擺第一個
        if (!PosVipRules.current().autoCode) {
            fields.add(FIELD_VIP_ID);
        }
        fields.addAll(Arrays.asList(DEFAULT_FIELDS));
        return fields;
    }

    /**
     * 把電話預先填進表單。
     *
     * 走 Block 的 DefaultsApplier：先把 POSVIP 原本那個接回去（等級、性別、
     * 有效日期這些預設值都靠它），再補上電話。自己另外塞值會蓋掉原廠邏輯。
     */
    private static void prefillPhone(Object block, final String phone) {
        if (phone == null || phone.trim().length() == 0) {
            return;
        }
        final String number = phone.trim();
        final Object original = Safe.call(block, "getDefaultsApplier");

        Object wrapper = Safe.proxy(DEFAULTS_APPLIER, new InvocationHandler() {
            public Object invoke(Object proxy, Method method, Object[] args) {
                Object result = null;
                if (original != null) {
                    result = Safe.call(original, method.getName(),
                        method.getParameterTypes(), args);
                }
                if ("applyDefaults".equals(method.getName())
                    && args != null && args.length > 0 && args[0] != null) {
                    setPhone(args[0], number);
                }
                return result;
            }
        });
        if (wrapper == null) {
            PosLog.warn("預填電話失敗，表單電話欄會是空的");
            return;
        }
        Class<?> applierType = Safe.type(DEFAULTS_APPLIER);
        if (applierType != null) {
            Safe.call(block, "setDefaultsApplier",
                new Class<?>[] { applierType }, new Object[] { wrapper });
        }
    }

    private static void setPhone(Object record, String phone) {
        Safe.staticCall(PROPERTY_UTILS, "setProperty",
            new Class<?>[] { Object.class, String.class, Object.class },
            new Object[] { record, "vipPhone1", phone });
    }

    /** 表單的外觀設定，來源跟原生完全一樣。 */
    private static Properties config(Object application) {
        Properties merged = new Properties();
        Class<?> applicationType = Safe.type(APPLICATION);
        if (applicationType != null) {
            Object appConfig = Safe.staticCall(CONFIG_UTILITY, "loadAppConfig",
                new Class<?>[] { applicationType, boolean.class },
                new Object[] { application, Boolean.TRUE });
            if (appConfig instanceof Properties) {
                merged.putAll((Properties) appConfig);
            }
        }
        Object userConfig = Safe.staticCall(CONFIG_UTILITY, "loadAppUserConfig",
            new Class<?>[] { String.class, String.class },
            new Object[] { APP_CODE, sessionUserId() });
        if (userConfig instanceof Properties) {
            merged.putAll((Properties) userConfig);
        }
        return merged;
    }

    /**
     * 叫出原生的建立對話框，回傳建立好的物件（取消時是 null）。
     *
     * 第一個參數只用來往上找父對話框，傳 null 是它自己就處理得了的路徑。
     * 刻意不傳側欄的元件：CreatorView 完成後會 dispose 最靠近的 Window，
     * 傳側欄進去等於把 EPB 主視窗交給它關。
     */
    private static Object showDialog(Object block, Properties config) {
        Class<?> viewType = Safe.type(VIEW);
        Class<?> blockType = Safe.type(BLOCK);
        if (viewType == null || blockType == null) {
            return null;
        }
        return Safe.staticCall(CREATOR_VIEW, "showCreatorDialog",
            new Class<?>[] { viewType, String.class, blockType, Properties.class },
            new Object[] { null, "建立會員", block, config });
    }

    private static String readVipId(Object created) {
        Object value = Safe.staticCall(PROPERTY_UTILS, "getProperty",
            new Class<?>[] { Object.class, String.class },
            new Object[] { created, FIELD_VIP_ID });
        return value == null ? null : String.valueOf(value).trim();
    }

    // -- 登入資訊 ----------------------------------------------------------

    private static String sessionUserId() {
        return shared("getUserId", "");
    }

    private static String sessionCharset() {
        return shared("getCharset", "");
    }

    private static String shared(String method, String fallback) {
        Object value = Safe.staticCall(SHARED, method, new Class<?>[0], new Object[0]);
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.length() == 0 ? fallback : text;
    }
}
