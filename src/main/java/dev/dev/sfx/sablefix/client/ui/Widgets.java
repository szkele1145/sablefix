package dev.sfx.sablefix.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** 自绘控件：圆角矩形 / 圆点 / 截断文字 / 状态横幅 / 信息行 / 统计卡。 */
public final class Widgets {

    private Widgets() {}

    /** 画文字，超宽自动截断加省略号 */
    public static void dtr(GuiGraphics g, Font font, String s, int x, int y, int maxW, int color, boolean shadow) {
        if (maxW > 8 && font.width(s) > maxW) {
            s = font.plainSubstrByWidth(s, maxW - font.width("…")) + "…";
        }
        g.drawString(font, s, x, y, color, shadow);
    }

    public static void dot(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int inset = (int) Math.round(r - Math.sqrt(Math.max(0, r * r - dy * dy)));
            g.fill(cx - r + inset, cy + dy, cx + r - inset, cy + dy + 1, color);
        }
    }

    /** 圆角矩形（描边色为 0 时只填充） */
    public static void roundRect(GuiGraphics g, int x, int y, int w, int h, int r, int fill, int stroke) {
        if (w <= 0 || h <= 0) return;
        r = Math.min(r, Math.min(w, h) / 2);
        g.fill(x + r, y, x + w - r, y + h, fill);
        g.fill(x, y + r, x + w, y + h - r, fill);
        for (int i = 0; i < r; i++) {
            int inset = (int) Math.round(r - Math.sqrt(Math.max(0, r * r - (r - i - 0.5) * (r - i - 0.5))));
            g.fill(x + inset, y + i, x + w - inset, y + i + 1, fill);
            g.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, fill);
        }
        if ((stroke >>> 24) == 0) return;
        // 描边：只画四条直边（圆角弧线由填充自然过渡，避免弧上杂点）
        g.fill(x + r, y, x + w - r, y + 1, stroke);
        g.fill(x + r, y + h - 1, x + w - r, y + h, stroke);
        g.fill(x, y + r, x + 1, y + h - r, stroke);
        g.fill(x + w - 1, y + r, x + w, y + h - r, stroke);
    }

    public static boolean inBox(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
