package dev.sfx.sablefix.client.ui;

/** 面板设计令牌：全部颜色集中此处，页面代码只引用语义名。 */
public final class Theme {
    public static final int BG      = 0xFF0A0C10;
    public static final int TOPBAR  = 0xFF141922;
    public static final int SIDE    = 0xFF141922;
    public static final int CARD    = 0xFF232B38;
    public static final int CARD_HI = 0xFF2D3746;
    public static final int BORDER  = 0xFF3E4A5C;
    public static final int NAV_SEL = 0x4058A6FF;
    public static final int NAV_HOV = 0x22FFFFFF;
    public static final int ACC     = 0xFF7DBAFF;
    public static final int OK      = 0xFF63E08F;
    public static final int WARN    = 0xFFF0CB5A;
    public static final int BAD     = 0xFFFF8080;
    public static final int TEXT    = 0xFFFFFFFF;
    public static final int DIM     = 0xFFB8C2D0;
    public static final int FAINT   = 0xFF8B96A6;

    private Theme() {}

    /** 把 alpha（0..255，已移到高 8 位）套到颜色上 */
    public static int withA(int argb, int aOrFull) {
        int a = aOrFull >>> 24;
        return (argb & 0x00FFFFFF) | (a << 24);
    }
}
