package com.posassist;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 不需要登入、不需要 POS 權限就能跑的自我檢查。
 *
 * 逐點確認外掛依賴的每個掛載點在這台機器的 EPB 上真的存在，
 * 以及電話正規化規則跟 member-lookup 一致。
 *
 * 用法（工作目錄要在 EPB/Shell）：
 *   java -cp "../PosAssist/posassist.jar:shell.jar:lib/*" com.posassist.SelfTest
 */
public final class SelfTest {

    private static final List<String> FAILURES = new ArrayList<String>();
    private static int checks;

    private SelfTest() {
    }

    public static void main(String[] args) {
        System.out.println("PosAssist 自我檢查");
        System.out.println("========================================");

        System.out.println();
        System.out.println("[1] 掛載鏈");
        clazz("com.epb.shell.Main");
        staticMethod("com.epb.shell.Main", "main", String[].class);
        clazz("com.epb.framework.ApplicationPool");
        staticMethod("com.epb.framework.ApplicationPool", "getInstance");
        method("com.epb.framework.ApplicationPool", "addApplicationPoolListener",
            "com.epb.framework.ApplicationPoolListener");
        declaredField("com.epb.framework.ApplicationPool", "pooledApplications");
        declaredField("com.epb.framework.ApplicationPool", "applicationPoolListeners");

        iface("com.epb.framework.ApplicationPoolListener",
            new String[] { "applicationOpened", "applicationClosed", "applicationActivated" });

        method("com.epb.framework.Application", "getApplicationHome");
        method("com.epb.framework.Application", "getApplicationView");
        method("com.epb.framework.ApplicationHome", "getAppCode");

        method("com.ipt.epbtls.framework.DefaultApplicationBuilder$WrapperApplication",
            "getEpbApplication");

        System.out.println();
        System.out.println("[2] EPB 共用物件");
        staticMethod("com.ipt.epbfrw.EpbSharedObjects", "getUserId");
        staticMethod("com.ipt.epbfrw.EpbSharedObjects", "getShellFrame");
        staticMethod("com.ipt.epbfrw.EpbSharedObjects", "getOrgId");

        System.out.println();
        System.out.println("[3] 唯讀查詢入口");
        method("com.ipt.epbtls.EpbApplicationUtility", "getResultList",
            "java.lang.String", "java.util.List", "int");

        System.out.println();
        System.out.println("[4] POSN 欄位");
        clazz("com.ipt.app.posn.ui.POSN");
        publicField("com.ipt.app.posn.ui.POSN", "vipIdTextField");
        publicField("com.ipt.app.posn.ui.POSN", "vipNameTextField");
        publicField("com.ipt.app.posn.ui.POSN", "vipDiscTextField");
        publicField("com.ipt.app.posn.ui.POSN", "posNoTextField");
        publicField("com.ipt.app.posn.ui.POSN", "pluIdTextField");   // 帶入後焦點要回到這裡
        // F10 序號視窗：預約單號要填進這裡
        clazz("com.ipt.app.posn.ui.PosSerialNoDialog");
        publicField("com.ipt.app.posn.ui.PosSerialNoDialog", "lineRef7Label");
        publicField("com.ipt.app.posn.ui.PosSerialNoDialog", "lineRef7TextField");

        System.out.println();
        System.out.println("[5] 側欄嵌入");
        clazz("javax.swing.JSplitPane");
        System.out.println("       面板模式：" + Home.value(
            "config/posassist.properties", "panelMode", "embedded（預設）"));
        // 這項只有 EPB 執行中才驗得到；自我檢查是獨立跑的，所以只做說明
        System.out.println("       側欄結構：EPB 未執行時無法檢查。"
            + "實際掛載時若找不到會自動退回浮動視窗，並在 log 留一筆。");

        System.out.println();
        System.out.println("[6] 結帳代碼");
        System.out.println("       清單：" + (CodeStore.exists()
            ? CodeStore.load().size() + " 筆，"
              + CodeStore.categories(CodeStore.load()).size() + " 個分類，"
              + CodeStore.pinned(CodeStore.load()).size() + " 筆釘選"
            : "未設定（面板會提示按編輯新增）"));
        // 欄位規則：分隔符號是 |，所以三個欄位都不能含它
        codeValid("常用|環保紙袋|07310011 可用", "常用", "環保紙袋", "07310011", true);
        codeValid("名稱空白要擋", "常用", "", "07310011", false);
        codeValid("代碼空白要擋", "常用", "紙袋", "", false);
        codeValid("名稱含 | 要擋", "常用", "紙|袋", "07310011", false);
        codeValid("代碼含 | 要擋", "常用", "紙袋", "073|11", false);
        record("註解行會被略過", CodeItem.parse("# 註解") == null);
        record("欄位不足的行會被略過", CodeItem.parse("常用|只有兩欄") == null);
        // 釘選存在 codes.pins.txt，代碼行維持三欄，舊版本讀得懂
        record("釘選不會多寫一欄進 codes.txt",
            "常用|紙袋|07310011".equals(
                new CodeItem("常用", "紙袋", "07310011", true).toLine()));
        // 子分類寫在分類欄裡：配件/袋類
        CodeItem twoLevel = new CodeItem("配件/袋類", "環保紙袋", "07310011");
        record("子分類切得出主分類",
            "配件".equals(twoLevel.category) && "袋類".equals(twoLevel.sub));
        record("子分類寫回去還是原本那一行",
            "配件/袋類|環保紙袋|07310011".equals(twoLevel.toLine()));
        record("沒有 / 時子分類是空的",
            "".equals(new CodeItem("配件", "保護殼", "07320111").sub));
        record("子分類不能再分層",
            !new CodeItem("配件/袋類/紙袋", "環保紙袋", "07310011").isValid());

        List<CodeItem> mixed = new ArrayList<CodeItem>();
        mixed.add(new CodeItem("配件/包膜", "全機包膜", "07320200"));
        mixed.add(new CodeItem("配件", "保護殼", "07320111"));
        mixed.add(new CodeItem("配件/袋類", "環保紙袋", "07310011"));
        List<String> subs = CodeStore.subCategories(mixed, "配件");
        record("沒填子分類的那段排最前面",
            subs.size() == 3 && "".equals(subs.get(0))
            && "包膜".equals(subs.get(1)) && "袋類".equals(subs.get(2)));
        record("依主分類加子分類取得項目",
            CodeStore.inCategory(mixed, "配件", "袋類").size() == 1
            && CodeStore.inCategory(mixed, "配件", "").size() == 1);

        List<CodeItem> onlyPinned = new ArrayList<CodeItem>();
        onlyPinned.add(new CodeItem("配件", "傳輸線", "07320112", true));
        record("整個分類都被釘選時不留空頁籤",
            CodeStore.categories(onlyPinned).isEmpty());
        record("釘選的不會在分類裡再列一次",
            CodeStore.inCategory(onlyPinned, "配件").isEmpty());

        System.out.println();
        System.out.println("[7] 預約整合");
        record("org.json 可用（解析預約回應）", Json.available());
        System.out.println("       設定：" + ReservationCache.configStatus());
        record("設定檔權限不外流（非 group/other 可讀）",
            ReservationCache.configPermissionsOk());
        // 狀態排序：用「包含」比對，已取消／已取貨這種相近字串最容易搞錯
        rank("已到貨", 0);
        rank("保留", 0);
        rank("已預約", 1);
        rank("已取貨", 2);
        rank("已取貨(已遞補)", 2);
        rank("已取消", 3);
        rank("已取消(已遞補)", 3);
        rank("放棄", 3);
        rank("放棄(已遞補)", 3);
        rank("已送達", 3);
        rank("已配貨", 3);
        rank("待付款", 3);

        String probe = ReservationCache.probeConfiguredHost();
        if (probe == null) {
            System.out.println("       連線檢查：略過（未設定）");
        } else {
            record("預約主機連線與 TLS 交握"
                + (probe.length() == 0 ? "" : "：" + probe), probe.length() == 0);
        }

        System.out.println();
        System.out.println("[8] SQL 相容性（本機 client DB 可能是 Postgres 或 Oracle）");
        sqlPortable("精確查詢", VipLookup.buildExactSql(1, 1));
        sqlPortable("電話備援", VipLookup.buildFallbackSql());
        bindCount("精確查詢", VipLookup.buildExactSql(1, 1), 4);
        bindCount("電話備援", VipLookup.buildFallbackSql(), 3);
        // 備註4（LINE 會員）：帶與不帶兩種 SQL 都要能用，欄位不存在時才降得下去
        // 慢查詢診斷用的 SQL 也要兩邊都能跑，而且不能把原句改壞
        sqlPortable("診斷 會員數", "SELECT COUNT(*) FROM POS_VIP_MAS");
        sqlPortable("診斷 等級數", "SELECT COUNT(*) FROM POS_VIP_CLASS");
        String plain = VipLookup.buildExactSql(1, 1);
        record("EXPLAIN 只是加前綴，不動原句",
            ("EXPLAIN " + plain).substring("EXPLAIN ".length()).equals(plain));

        record("精確查詢有帶備註4",
            VipLookup.buildExactSql(1, 1).indexOf(VipLookup.REMARK_COLUMN) > 0);
        record("備註4 可以拿掉",
            VipLookup.buildExactSql(1, 1, false).indexOf(VipLookup.REMARK_COLUMN) < 0);
        sqlPortable("精確查詢（無備註4）", VipLookup.buildExactSql(1, 1, false));
        sqlPortable("電話備援（無備註4）", VipLookup.buildFallbackSql(false));
        bindCount("精確查詢（無備註4）", VipLookup.buildExactSql(1, 1, false), 4);

        System.out.println();
        System.out.println("[9] 電話正規化（規則須與 member-lookup 一致）");
        phone("0912345678", "0912345678");
        phone("0912-345-678", "0912345678");
        phone("0912 345 678", "0912345678");
        phone("(02) 2345-6789", "0223456789");
        phone("+886912345678", "0912345678");
        phone("886912345678", "0912345678");
        phone("12345", null);
        phone("abcdefgh", null);
        phone("", null);

        System.out.println();
        System.out.println("[10] 會員建立輔助（PosAssist 只開原生畫面，不寫入任何資料）");
        // 開原生 POSVIP 用的兩支公開方法。3 參數的 checkPrivilege 會自己補 LOC_ID，
        // 跟 POSVIP 自己判斷 isNewAllowed 是同一條路
        staticMethod("com.ipt.epbtls.EpbApplicationUtility", "checkPrivilege",
            String.class, String.class, String.class);
        staticMethod("com.ipt.epbtls.EpbApplicationUtility", "callEpbApplication",
            String.class, java.util.Map.class);
        // 門市規則（手機碼數、姓名唯一、Email 檢查）跟原生讀同一支，順序才會一致
        staticMethod("com.epb.persistence.utl.BusinessUtility", "getAppSetting",
            String.class, String.class, String.class, String.class);
        sqlPortable("姓名重複", PosVipRules.nameConflictSql());
        bindCount("姓名重複", PosVipRules.nameConflictSql(), 1);

        draft("姓名必填", "", "0912345678", "", "", 0, false);
        draft("姓名不得超過上限", repeat("陳", VipCreateDraft.MAX_NAME + 1),
            "0912345678", "", "", 0, false);
        draft("最短的合格資料", "陳小明", "0912345678", "", "", 0, true);
        draft("電話格式不對", "陳小明", "abcdefgh", "", "", 0, false);
        draft("電話碼數不符門市設定", "陳小明", "091234567", "", "", 10, false);
        draft("電話碼數符合門市設定", "陳小明", "0912345678", "", "", 10, true);
        draft("沒設定碼數就不檢查", "陳小明", "091234567", "", "", 0, true);
        draft("Email 缺 @", "陳小明", "0912345678", "abc.example.com", "", 0, false);
        draft("Email 網域沒有點", "陳小明", "0912345678", "abc@example", "", 0, false);
        draft("Email 正常", "陳小明", "0912345678", "abc@example.com", "", 0, true);
        draft("閏年 2 月 29 日", "陳小明", "0912345678", "", "2024-02-29", 0, true);
        draft("非閏年沒有 2 月 29 日", "陳小明", "0912345678", "", "2023-02-29", 0, false);
        draft("百年不閏", "陳小明", "0912345678", "", "1900-02-29", 0, false);
        draft("四百年又閏", "陳小明", "0912345678", "", "2000-02-29", 0, true);
        draft("月份不合理", "陳小明", "0912345678", "", "1990-13-01", 0, false);
        draft("生日不能晚於今天", "陳小明", "0912345678", "", "2026-12-31", 0, false);
        draft("生日就是今天", "陳小明", "0912345678", "", "2026-08-31", 0, true);
        draft("生日格式必須是 YYYY-MM-DD", "陳小明", "0912345678", "", "1990/01/01", 0, false);
        draft("生日選填", "陳小明", "0912345678", "", "", 0, true);

        System.out.println();
        System.out.println("========================================");
        if (FAILURES.isEmpty()) {
            System.out.println("結果：全部通過（" + checks + " 項）");
            System.exit(0);
        }
        System.out.println("結果：" + FAILURES.size() + " / " + checks + " 項失敗");
        for (String failure : FAILURES) {
            System.out.println("  - " + failure);
        }
        System.exit(1);
    }

    // -- 檢查項 ------------------------------------------------------------

    private static void clazz(String name) {
        record("類別 " + name, load(name) != null);
    }

    private static void iface(String name, String[] methods) {
        Class<?> type = load(name);
        if (type == null) {
            record("介面 " + name, false);
            return;
        }
        for (int i = 0; i < methods.length; i++) {
            boolean found = false;
            Method[] declared = type.getMethods();
            for (int j = 0; j < declared.length; j++) {
                if (declared[j].getName().equals(methods[i])) {
                    found = true;
                    break;
                }
            }
            record("介面方法 " + simple(name) + "." + methods[i], found);
        }
    }

    private static void staticMethod(String className, String methodName, Class<?>... signature) {
        Class<?> type = load(className);
        boolean ok = false;
        if (type != null) {
            try {
                type.getMethod(methodName, signature);
                ok = true;
            } catch (Throwable ignored) {
                ok = false;
            }
        }
        record("靜態方法 " + simple(className) + "." + methodName + "()", ok);
    }

    private static void method(String className, String methodName, String... parameterTypes) {
        Class<?> type = load(className);
        boolean ok = false;
        if (type != null) {
            try {
                Class<?>[] signature = new Class<?>[parameterTypes.length];
                for (int i = 0; i < parameterTypes.length; i++) {
                    signature[i] = resolve(parameterTypes[i]);
                }
                type.getMethod(methodName, signature);
                ok = true;
            } catch (Throwable ignored) {
                ok = false;
            }
        }
        record("方法 " + simple(className) + "." + methodName + "()", ok);
    }

    private static void publicField(String className, String fieldName) {
        Class<?> type = load(className);
        boolean ok = false;
        if (type != null) {
            try {
                Field field = type.getField(fieldName);
                ok = field != null;
            } catch (Throwable ignored) {
                ok = false;
            }
        }
        record("public 欄位 " + simple(className) + "." + fieldName, ok);
    }

    private static void declaredField(String className, String fieldName) {
        Class<?> type = load(className);
        boolean ok = false;
        if (type != null) {
            try {
                type.getDeclaredField(fieldName);
                ok = true;
            } catch (Throwable ignored) {
                ok = false;
            }
        }
        // 補接功能才用得到，缺了只是少一個保險，不算致命
        record("內部欄位 " + simple(className) + "." + fieldName + "（補接用）", ok);
    }

    /**
     * 建立草稿的驗證。
     *
     * 「今天」固定成一個值，未來生日那條規則才驗得穩 —— 用真的今天的話，
     * 測試案例會隨著日曆自己失效。
     */
    private static final int[] TODAY = { 2026, 8, 31 };

    private static void draft(String label, String name, String phone, String email,
                              String birthday, int phoneLength, boolean expected) {
        VipCreateDraft.Result result = VipCreateDraft.of(
            name, phone, email, birthday, phoneLength, TODAY);
        boolean ok = result.ok() == expected;
        record("草稿 " + label
            + (ok ? "" : "（實得：" + (result.ok() ? "通過" : result.error) + "）"), ok);
    }

    private static String repeat(String unit, int times) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < times; i++) {
            out.append(unit);
        }
        return out.toString();
    }

    /** 單邊專有的語法一律不准出現，否則換一台資料庫就炸。 */
    private static void sqlPortable(String label, String sql) {
        String upper = sql.toUpperCase();
        String[] banned = { "NVL(", "ROWNUM", "SYSDATE", "TRUNC(", "DECODE(",
                            "DATE_TRUNC(", "CURRENT_DATE", "LIMIT ", "TOP " };
        String hit = null;
        for (int i = 0; i < banned.length; i++) {
            if (upper.indexOf(banned[i]) >= 0) {
                hit = banned[i];
                break;
            }
        }
        record("SQL " + label + " 無單邊專有語法"
            + (hit == null ? "" : "（出現 " + hit + "）"), hit == null);
    }

    /** 佔位符數量要跟程式送出的參數數量對得起來，順序錯了會查到不相干的資料。 */
    private static void bindCount(String label, String sql, int expected) {
        int count = 0;
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == '?') {
                count++;
            }
        }
        record("SQL " + label + " 佔位符 " + expected + " 個"
            + (count == expected ? "" : "（實得 " + count + "）"), count == expected);
    }

    private static void codeValid(String label, String category, String name,
                                 String code, boolean expected) {
        boolean actual = new CodeItem(category, name, code).isValid();
        record(label, actual == expected);
    }

    private static void rank(String status, int expected) {
        int actual = ReservationCache.statusRank(status);
        record("狀態「" + status + "」排序 " + expected
            + (actual == expected ? "" : "（實得 " + actual + "）"), actual == expected);
    }

    private static void phone(String input, String expected) {
        String actual = VipLookup.normalizePhone(input);
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        record("電話 \"" + input + "\" -> " + (expected == null ? "拒絕" : expected)
            + (ok ? "" : "（實得 " + actual + "）"), ok);
    }

    // -- 工具 --------------------------------------------------------------

    /** initialize=false：只解析類別，不跑靜態初始化，避免自我檢查產生副作用。 */
    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, SelfTest.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    private static Class<?> resolve(String name) throws ClassNotFoundException {
        if ("int".equals(name)) {
            return int.class;
        }
        if ("boolean".equals(name)) {
            return boolean.class;
        }
        return Class.forName(name, false, SelfTest.class.getClassLoader());
    }

    private static String simple(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    private static void record(String label, boolean ok) {
        checks++;
        System.out.println((ok ? "  OK   " : "  FAIL ") + label);
        if (!ok) {
            FAILURES.add(label);
        }
    }
}
