package com.posassist;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * 面板的視覺語彙：顏色、字級、間距集中在這裡。
 *
 * 以前四個檔各自定義 ACCENT／MUTED（MUTED 還一度有兩個不同的值），字級散落六種、
 * 圓角與間距各處各寫 —— 面板看起來不夠精緻，來源是這個，不是 Swing 老。
 *
 * 字級一律從傳進來的字型衍生，**不要指定字型家族**：指定了中文會掉字，
 * 而 EPB 是全域套系統 Look and Feel，字型本來就該跟著它走。
 */
final class Style {

    /** 面板底色。比卡片深一階，卡片才浮得出來。 */
    static final Color PAGE = new Color(0xEE, 0xF0, 0xF4);
    /** 卡片底色。 */
    static final Color SURFACE = new Color(0xFF, 0xFF, 0xFF);
    /** 主色：標題、可點的東西、選中狀態。 */
    static final Color ACCENT = new Color(0x1D, 0x4E, 0x89);
    /** 主要文字。純黑太重，往藍灰偏一點。 */
    static final Color TEXT = new Color(0x15, 0x18, 0x1D);
    /** 次要文字：欄位標籤、代碼、說明。 */
    static final Color MUTED = new Color(0x6B, 0x72, 0x80);
    /** 細邊線。 */
    static final Color LINE = new Color(0xE3, 0xE7, 0xEC);
    /** 選中的頁籤、標籤藥丸的底色。 */
    static final Color TAB_ON = new Color(0xE8, 0xF0, 0xFB);
    /** 代碼鍵滑過與按下的底色。觸控螢幕上按下的回饋比滑過重要。 */
    static final Color KEY_HOVER = new Color(0xF4, 0xF6, 0xF8);
    static final Color KEY_PRESS = new Color(0xDF, 0xE5, 0xEE);
    /** 出錯的訊息。 */
    static final Color DANGER = new Color(0x9B, 0x2C, 0x2C);

    /** 卡片內距。 */
    static final int PAD = 10;
    /** 元件之間的間距。 */
    static final int GAP = 6;
    static final int RADIUS_CARD = 10;
    static final int RADIUS_KEY = 8;

    private Style() {
    }

    /** 區塊標題（會員查詢、結帳代碼）。 */
    static Font heading(Font base) {
        return base.deriveFont(Font.BOLD, 12f);
    }

    /** 欄位的值（姓名、電話）。 */
    static Font value(Font base) {
        return base.deriveFont(Font.BOLD, 13f);
    }

    static Font body(Font base) {
        return base.deriveFont(12f);
    }

    /** 欄位標籤、分段小標、狀態列。 */
    static Font caption(Font base) {
        return base.deriveFont(11f);
    }

    /** 頁尾與版本號。 */
    static Font tiny(Font base) {
        return base.deriveFont(10f);
    }

    /** 自繪圓角一定要開，不然邊緣是鋸齒。 */
    static void antialias(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
            RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
            RenderingHints.VALUE_STROKE_PURE);
    }
}
