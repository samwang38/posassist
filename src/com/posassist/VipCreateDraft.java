package com.posassist;

import java.util.Calendar;

/**
 * 會員建立草稿：把店員在側欄填的東西驗過一遍，變成可以逐欄複製的乾淨值。
 *
 * 這個類別刻意完全不碰 EPB，也不碰 Swing —— 驗證規則是整個功能裡最需要被
 * 反覆測的部分，SelfTest 在 headless、沒有登入的情況下就要能全部跑完。
 *
 * 規則是照 POSVIP 原生驗證器對齊的（手機長度 CustomizeVipPhone1LengthValidator、
 * Email 格式 CustomizeEmailAddrValidator）。這裡先擋一次不是為了取代原生驗證，
 * 而是為了不讓店員在原生畫面填完一整輪才被退。最終仍然以原生送出時的驗證為準。
 */
public final class VipCreateDraft {

    /** POS_VIP_MAS.NAME 的長度上限。 */
    public static final int MAX_NAME = 128;
    /** POS_VIP_MAS.EMAIL_ADDR 的長度上限。 */
    public static final int MAX_EMAIL = 512;

    /** 生日不收比這更早的年份 —— 再早幾乎都是打錯，不是真的生日。 */
    private static final int MIN_BIRTH_YEAR = 1900;

    /** 姓名，已 trim，非空。 */
    public final String name;
    /** 電話，已正規化（VipLookup.normalizePhone 的輸出）。 */
    public final String phone;
    /** Email，已 trim；沒填是空字串。 */
    public final String email;
    /** 生日，西元 YYYY-MM-DD；沒填是空字串。 */
    public final String birthDate;

    private VipCreateDraft(String name, String phone, String email, String birthDate) {
        this.name = name;
        this.phone = phone;
        this.email = email;
        this.birthDate = birthDate;
    }

    /**
     * 驗證結果。過了就有 draft，沒過就有 error，兩者永遠只有一個非 null。
     */
    public static final class Result {
        public final VipCreateDraft draft;
        public final String error;

        private Result(VipCreateDraft draft, String error) {
            this.draft = draft;
            this.error = error;
        }

        public boolean ok() {
            return draft != null;
        }

        static Result pass(VipCreateDraft draft) {
            return new Result(draft, null);
        }

        static Result fail(String error) {
            return new Result(null, error);
        }
    }

    /**
     * 驗證並建立草稿。
     *
     * @param phoneLength POSVIP 的 HPCHECKLTH 設定值；0 代表讀不到或沒設定，
     *                    此時只做通用的長度檢查，不另外擋。
     */
    public static Result of(String name, String phone, String email, String birthday,
        int phoneLength) {
        return of(name, phone, email, birthday, phoneLength, today());
    }

    /** 測試用：把「今天」拉出來，才能穩定驗證未來生日這條規則。 */
    static Result of(String name, String phone, String email, String birthday,
        int phoneLength, int[] today) {

        String cleanName = trim(name);
        if (cleanName.length() == 0) {
            return Result.fail("請填姓名");
        }
        if (cleanName.length() > MAX_NAME) {
            return Result.fail("姓名太長（最多 " + MAX_NAME + " 字）");
        }

        String cleanPhone = VipLookup.normalizePhone(phone);
        if (cleanPhone == null) {
            return Result.fail("電話格式不正確");
        }
        if (phoneLength > 0 && cleanPhone.length() != phoneLength) {
            return Result.fail("電話需要 " + phoneLength + " 碼（門市設定）");
        }

        String cleanEmail = trim(email);
        if (cleanEmail.length() > MAX_EMAIL) {
            return Result.fail("Email 太長（最多 " + MAX_EMAIL + " 字）");
        }
        if (cleanEmail.length() != 0 && !looksLikeEmail(cleanEmail)) {
            return Result.fail("Email 格式不正確");
        }

        String cleanBirthday = trim(birthday);
        if (cleanBirthday.length() != 0) {
            String problem = checkBirthday(cleanBirthday, today);
            if (problem != null) {
                return Result.fail(problem);
            }
        }

        return Result.pass(
            new VipCreateDraft(cleanName, cleanPhone, cleanEmail, cleanBirthday));
    }

    // -- 生日 --------------------------------------------------------------

    /**
     * 嚴格檢查西元 YYYY-MM-DD。不用 SimpleDateFormat：它預設 lenient，
     * 2 月 31 日會被默默滾成 3 月 3 日，那正好是我們最想擋下來的那種輸入。
     *
     * 回 null 代表沒問題。
     */
    private static String checkBirthday(String text, int[] today) {
        if (text.length() != 10 || text.charAt(4) != '-' || text.charAt(7) != '-') {
            return "生日請用西元 YYYY-MM-DD";
        }
        for (int i = 0; i < text.length(); i++) {
            if (i == 4 || i == 7) {
                continue;
            }
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return "生日請用西元 YYYY-MM-DD";
            }
        }

        int year = number(text, 0, 4);
        int month = number(text, 5, 7);
        int day = number(text, 8, 10);

        if (year < MIN_BIRTH_YEAR) {
            return "生日年份不合理";
        }
        if (month < 1 || month > 12) {
            return "生日月份不正確";
        }
        if (day < 1 || day > daysInMonth(year, month)) {
            return "生日日期不正確";
        }
        if (isAfter(year, month, day, today)) {
            return "生日不能晚於今天";
        }
        return null;
    }

    static int daysInMonth(int year, int month) {
        switch (month) {
            case 1: case 3: case 5: case 7: case 8: case 10: case 12:
                return 31;
            case 4: case 6: case 9: case 11:
                return 30;
            case 2:
                return isLeapYear(year) ? 29 : 28;
            default:
                return 0;
        }
    }

    static boolean isLeapYear(int year) {
        return (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
    }

    private static boolean isAfter(int year, int month, int day, int[] today) {
        if (year != today[0]) {
            return year > today[0];
        }
        if (month != today[1]) {
            return month > today[1];
        }
        return day > today[2];
    }

    /** 今天的 {年, 月, 日}。 */
    private static int[] today() {
        Calendar now = Calendar.getInstance();
        return new int[] {
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH) + 1,
            now.get(Calendar.DAY_OF_MONTH)
        };
    }

    private static int number(String text, int from, int to) {
        int value = 0;
        for (int i = from; i < to; i++) {
            value = value * 10 + (text.charAt(i) - '0');
        }
        return value;
    }

    // -- Email -------------------------------------------------------------

    /**
     * 只做基本格式：一個 @、前後都有東西、網域帶點且點不在頭尾、沒有空白。
     * 刻意不寫完整的 RFC 規則 —— 真正的把關在原生那一關，這裡擋的是打錯字。
     */
    static boolean looksLikeEmail(String text) {
        int at = text.indexOf('@');
        if (at <= 0 || at != text.lastIndexOf('@') || at == text.length() - 1) {
            return false;
        }
        String domain = text.substring(at + 1);
        int dot = domain.indexOf('.');
        if (dot <= 0 || dot == domain.length() - 1) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c <= ' ' || c == ',' || c == ';') {
                return false;
            }
        }
        return true;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
