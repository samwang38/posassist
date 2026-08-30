package com.posassist;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

import javax.swing.JComponent;

/**
 * 會換行的 FlowLayout。
 *
 * 原本的 FlowLayout 排版時會折行，但 preferredLayoutSize 永遠只回報「排成一列」
 * 的高度，容器就只拿到一行的高度，折到第二行的頁籤被畫在可視範圍外 ——
 * 看不到，也因為超出父容器範圍而收不到滑鼠事件，等於那個分類消失了。
 */
final class WrapFlow extends FlowLayout {

    WrapFlow(int hgap, int vgap) {
        super(FlowLayout.LEFT, hgap, vgap);
    }

    /**
     * 裝上換行版面，並在寬度變動時要求重新排版。
     *
     * 高度是「這個寬度下會折成幾行」算出來的，而父容器是先問高度再決定寬度 ——
     * 少了這個 revalidate，拖窄的當下折下去的那一行還是照舊高度被切掉，
     * 要等下一次別的原因觸發排版才會對。
     */
    static void install(JComponent target, int hgap, int vgap) {
        target.setLayout(new WrapFlow(hgap, vgap));
        target.addComponentListener(new ComponentAdapter() {
            public void componentResized(ComponentEvent event) {
                ((JComponent) event.getComponent()).revalidate();
            }
        });
    }

    public Dimension preferredLayoutSize(Container target) {
        return wrapped(target);
    }

    public Dimension minimumLayoutSize(Container target) {
        return wrapped(target);
    }

    private Dimension wrapped(Container target) {
        synchronized (target.getTreeLock()) {
            Insets in = target.getInsets();
            int width = target.getWidth();
            Container up = target.getParent();
            while (width == 0 && up != null) {
                width = up.getWidth();
                up = up.getParent();
            }
            // 問不到寬度就退回單列，跟原本的行為一樣
            int max = width == 0 ? Integer.MAX_VALUE
                : width - in.left - in.right - getHgap() * 2;

            int x = 0, rowHeight = 0, widest = 0, height = 0;
            for (int i = 0; i < target.getComponentCount(); i++) {
                Component c = target.getComponent(i);
                if (!c.isVisible()) {
                    continue;
                }
                Dimension d = c.getPreferredSize();
                if (x > 0 && x + getHgap() + d.width > max) {
                    widest = Math.max(widest, x);
                    height += rowHeight + getVgap();
                    x = 0;
                    rowHeight = 0;
                }
                x += (x > 0 ? getHgap() : 0) + d.width;
                rowHeight = Math.max(rowHeight, d.height);
            }
            widest = Math.max(widest, x);
            height += rowHeight;
            return new Dimension(
                widest + in.left + in.right + getHgap() * 2,
                height + in.top + in.bottom + getVgap() * 2);
        }
    }
}
