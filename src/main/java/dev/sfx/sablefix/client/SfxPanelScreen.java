package dev.sfx.sablefix.client;

import dev.sfx.sablefix.net.NetHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 双端 GUI 主界面 — 全自绘现代化深色仪表盘。
 * 圆角卡片 / 主题色高亮 / 悬停反馈 / 切页淡入动画。
 */
public class SfxPanelScreen extends Screen {

    // ---- 设计令牌（对比度拉满：文字用近白，卡片与背景明显分层） ----
    private static final int BG      = 0xFF0A0C10;
    private static final int TOPBAR  = 0xFF141922;
    private static final int SIDE    = 0xFF141922;
    private static final int CARD    = 0xFF232B38;
    private static final int CARD_HI = 0xFF2D3746;
    private static final int BORDER  = 0xFF3E4A5C;
    private static final int NAV_SEL = 0x4058A6FF;
    private static final int NAV_HOV = 0x22FFFFFF;
    private static final int ACC     = 0xFF7DBAFF;
    private static final int OK      = 0xFF63E08F;
    private static final int WARN    = 0xFFF0CB5A;
    private static final int BAD     = 0xFFFF8080;
    private static final int TEXT    = 0xFFFFFFFF;
    private static final int DIM     = 0xFFB8C2D0;
    private static final int FAINT   = 0xFF8B96A6;

    private static final String[] TABS = {"总览", "存档体检", "子层级浏览器", "区块诊断", "死锁监视", "运行日志", "关于"};
    private static final int SIDE_X = 8, SIDE_W = 108;
    private static final int NAV_TOP = 38, NAV_H = 20, NAV_GAP = 3;

    private int tab;
    private double logScroll;
    private long animStart = Util.getMillis();
    private NetHandler.PanelData data;
    private NetHandler.DiagRes diagRes;

    private EditBox xBox, zBox;
    private int diagBtnX, diagBtnY, diagBoxW;  // 诊断页控件位置（init 时按窗口算好，绘制/命中共用）

    public SfxPanelScreen() {
        super(Component.literal("Sable Fix Core"));
    }

    // ================= 演示数据（截图/录屏用） =================
    static NetHandler.PanelData demoData() {
        var files = java.util.List.of(
                new NetHandler.PanelData.FileRow("r.-4.7.slvlr", true, 2_936_012, 74, 3),
                new NetHandler.PanelData.FileRow("r.12.5.slvls", false, 1_052_672, -1, 1),
                new NetHandler.PanelData.FileRow("r.1.0.slvlr", true, 4_404_019, 118, 0),
                new NetHandler.PanelData.FileRow("r.-1.2.slvlr", true, 2_936_012, 64, 0),
                new NetHandler.PanelData.FileRow("r.10.5.slvlr", true, 4_443, 3, 0),
                new NetHandler.PanelData.FileRow("r.0.-3.slvlr", true, 1_990_656, 41, 0),
                new NetHandler.PanelData.FileRow("（其余 755 个文件全部健康）", true, -1, -1, 0));
        var uuids = java.util.List.of(
                "6f2b8e1c-4a3d-4f7e-9b21-08c5d3a7e642",
                "a91d47c2-6e8f-4b0a-8c3d-1f5e2b9a7d04",
                "c47e9013-2b5a-4d8c-a6f1-7e3d8b05c921");
        var dims = java.util.List.of(
                new NetHandler.PanelData.DimRow("minecraft:overworld", 85, 319, 4205),
                new NetHandler.PanelData.DimRow("minecraft:the_nether", 147, 1, 2523),
                new NetHandler.PanelData.DimRow("minecraft:the_end", 147, 0, 2523));
        var logs = java.util.List.of(
                "16:33:42.101 [INFO] Sable Fix Core v1.0.0 已加载（双端）",
                "16:33:43.884 [INFO] 网络通道已注册（panel/diag）",
                "16:34:21.990 [INFO] 服务器启动完成，开始被动巡检",
                "16:34:22.015 [INFO] 主线程监视器启动：卡死 20 秒自动抓全线程栈（原版看门狗 60 秒杀服）",
                "16:34:22.140 [INFO] 开始子层级体检…",
                "16:34:23.602 [WARN] r.-4.7.slvlr：3 个死指针（有效 74）",
                "16:34:23.605 [WARN] r.12.5.slvls：1 个坏条目",
                "16:34:23.608 [WARN] 子层级体检完成：共 3 死指针 / 1 坏条目，用面板「存档体检」页查看",
                "16:34:23.711 [INFO] 票据体检完成：19 条 force-load 票据，gzip/NBT 解析正常（707 字节）",
                "16:51:08.332 [INFO] 面板请求：Dev@[/127.0.0.1]，数据打包 7 文件 + 19 票据");
        return new NetHandler.PanelData(files, 19, uuids, 41, 0, 22_320_000L, dims, logs);
    }

    static NetHandler.DiagRes demoDiag() {
        return new NetHandler.DiagRes(584, 12, true, "FULL 已加载", 12, 27, "minecraft:overworld");
    }

    // ================= 初始化 =================
    @Override
    protected void init() {
        // 导航/按钮全自绘（见 mouseClicked / drawSide），这里只放诊断页输入框
        // 布局：标题 34..42 → 输入行 44..60 → 结果卡片从 68 起（互不重叠）
        int ix = SIDE_X + SIDE_W + 20;
        int avail = width - ix - 12;              // 输入行可用宽
        int bw = 46;                               // 诊断按钮宽
        diagBoxW = Math.max(40, Math.min(88, (avail - bw - 24) / 2));
        diagBtnX = Math.min(ix + diagBoxW * 2 + 24, width - bw - 10);
        diagBtnY = 43;
        xBox = new EditBox(font, ix, 44, diagBoxW, 16, Component.literal("X"));
        xBox.setMaxLength(12);
        styleBox(xBox);
        zBox = new EditBox(font, ix + diagBoxW + 12, 44, diagBoxW, 16, Component.literal("Z"));
        zBox.setMaxLength(12);
        styleBox(zBox);
        addRenderableWidget(xBox);
        addRenderableWidget(zBox);

        if (Boolean.getBoolean("sfx.demo")) {
            if (data == null) data = demoData();
            if (diagRes == null) diagRes = demoDiag();
            tab = Integer.getInteger("sfx.demo.tab", 0);
        } else if (data == null) {
            request();
        }
        setBoxVisible();
    }

    private void styleBox(EditBox b) {
        b.setTextColor(0xFFD8DDE6);
        b.setTextColorUneditable(0xFF8A93A3);
        b.setBordered(true);
    }

    private void setBoxVisible() {
        boolean v = tab == 3;
        xBox.setVisible(v); zBox.setVisible(v);
        xBox.setEditable(v); zBox.setEditable(v);
    }

    private void switchTab(int t) {
        if (t == tab) return;
        tab = t;
        animStart = Util.getMillis();
        setBoxVisible();
    }

    private void request() {
        PacketDistributor.sendToServer(new NetHandler.PanelReq());
    }

    private void doDiag() {
        try {
            int x = Integer.parseInt(xBox.getValue().trim());
            int z = Integer.parseInt(zBox.getValue().trim());
            diagRes = null;
            PacketDistributor.sendToServer(new NetHandler.DiagReq(x, z));
        } catch (NumberFormatException ignored) {}
    }

    public void onData(NetHandler.PanelData d) { this.data = d; animStart = Util.getMillis(); }
    public void onDiag(NetHandler.DiagRes r) { this.diagRes = r; }

    // ================= 交互 =================
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        for (int i = 0; i < TABS.length; i++) {
            if (inBox(mx, my, SIDE_X, NAV_TOP + i * (NAV_H + NAV_GAP), SIDE_W, NAV_H)) {
                switchTab(i);
                return true;
            }
        }
        // 顶栏右侧：刷新 / 关闭
        if (inBox(mx, my, width - 146, 6, 66, 16)) { request(); return true; }
        if (inBox(mx, my, width - 74, 6, 66, 16)) { onClose(); return true; }
        if (tab == 3 && inBox(mx, my, diagBtnX, diagBtnY, 46, 18)) { doDiag(); return true; }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 数字键 1-6 切页（输入框聚焦时不抢）
        if (xBox != null && !xBox.isFocused() && !zBox.isFocused()
                && keyCode >= 0x31 && keyCode <= 0x30 + TABS.length) {
            switchTab(keyCode - 0x31);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (tab == 5) {
            logScroll = Math.max(0, logScroll - dy * 3);
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    private static boolean inBox(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ================= 渲染 =================
    /**
     * 干掉原版毛玻璃背景。
     * super.render() 会调用 renderBackground —— 在 1.21.1 里它对游戏画面做高斯模糊
     * 并盖在我们自绘内容之上，界面因此永远像隔了层磨砂玻璃。这里直接掏空。
     */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 故意留空：背景由 render() 里的不透明 fill 提供
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, BG);

        float anim = Math.min(1f, (Util.getMillis() - animStart) / 220f);
        int alpha = (int) (anim * 255) << 24;

        drawTopBar(g, mx, my, alpha);
        drawSide(g, mx, my);

        int px = SIDE_X + SIDE_W + 10, py = 30, pw = width - px - 10, ph = height - 38;
        switch (tab) {
            case 0 -> pageHome(g, px, py, pw, ph, mx, my, alpha);
            case 1 -> pageHealth(g, px, py, pw, ph, mx, my, alpha);
            case 2 -> pageBrowse(g, px, py, pw, ph, mx, my, alpha);
            case 3 -> pageDiag(g, px, py, pw, ph, mx, my, alpha);
            case 4 -> pageWatch(g, px, py, pw, ph, mx, my, alpha);
            case 5 -> pageLog(g, px, py, pw, ph, mx, my, alpha);
            case 6 -> pageAbout(g, px, py, pw, ph, mx, my, alpha);
        }
        super.render(g, mx, my, pt);
        demoAutoCycle();
    }

    // ---- 演示模式：自动轮播 5 页并逐页截图到 screenshots/ ----
    private int demoNext = -1;
    private long demoLast;

    private void demoAutoCycle() {
        if (!Boolean.getBoolean("sfx.demo") || data == null) return;
        long now = Util.getMillis();
        if (demoNext < 0) { demoNext = tab; demoLast = now; return; }
        if (now - demoLast < 4000) return;
        autoShot();
        demoNext = (demoNext + 1) % TABS.length;
        if (demoNext == 0) {
            Minecraft.getInstance().stop();
            return;
        }
        switchTab(demoNext);
        demoLast = now;
    }

    private void autoShot() {
        try {
            var img = net.minecraft.client.Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget());
            java.nio.file.Files.createDirectories(java.nio.file.Path.of("screenshots"));
            img.writeToFile(java.nio.file.Path.of("screenshots", "sfx-tab" + tab + ".png"));
            img.close();
        } catch (Throwable t) {
            dev.sfx.sablefix.SableFixMod.LOGGER.warn("自动截图失败", t);
        }
    }

    // ---- 顶栏 ----
    private void drawTopBar(GuiGraphics g, int mx, int my, int alpha) {
        g.fill(0, 0, width, 24, TOPBAR | alpha);
        g.fill(0, 24, width, 25, BORDER | alpha);
        g.drawString(font, "Sable", SIDE_X, 8, withA(ACC, alpha), true);
        g.drawString(font, "Fix Core", SIDE_X + font.width("Sable ") + 2, 8, withA(TEXT, alpha), true);
        g.drawString(font, "v1.0.0", SIDE_X + font.width("Sable Fix Core ") + 4, 9, withA(FAINT, alpha), false);

        // 右上：刷新 / 关闭
        topBtn(g, mx, my, width - 146, "刷新", alpha);
        topBtn(g, mx, my, width - 74, "关闭", alpha);
    }

    private void topBtn(GuiGraphics g, int mx, int my, int x, String label, int alpha) {
        boolean hov = inBox(mx, my, x, 6, 66, 16);
        roundRect(g, x, 6, 66, 16, 7, hov ? 0x2AFFFFFF : 0x14FFFFFF, BORDER | alpha);
        g.drawString(font, label, x + (66 - font.width(label)) / 2, 10, withA(hov ? TEXT : DIM, alpha), false);
    }

    // ---- 侧栏 ----
    private void drawSide(GuiGraphics g, int mx, int my) {
        roundRect(g, SIDE_X - 4, 30, SIDE_W + 8, height - 38, 10, SIDE, BORDER);
        for (int i = 0; i < TABS.length; i++) {
            int y = NAV_TOP + i * (NAV_H + NAV_GAP);
            boolean sel = i == tab;
            boolean hov = inBox(mx, my, SIDE_X, y, SIDE_W, NAV_H);
            if (sel) {
                roundRect(g, SIDE_X, y, SIDE_W, NAV_H, 7, NAV_SEL, ACC);
                roundRect(g, SIDE_X + 2, y + 5, 3, NAV_H - 10, 2, ACC, 0);
            } else if (hov) {
                roundRect(g, SIDE_X, y, SIDE_W, NAV_H, 7, NAV_HOV, 0);
            }
            g.drawString(font, TABS[i], SIDE_X + 13, y + (NAV_H - 8) / 2,
                    withA(sel ? ACC : (hov ? TEXT : DIM), 0xFFFFFFFF), false);
        }
    }

    // ---- 总览 ----
    private void pageHome(GuiGraphics g, int x, int y, int w, int h, int mx, int my, int a) {
        if (data == null) { loadingCard(g, x, y, a); return; }
        boolean tickOk = data.lastTickGapMs() < 500;
        int cy = y + 4;
        int ch = 46;

        // 卡片：空间够 4 列，窄窗口降级 2x2
        String v0 = fmtDur(data.uptimeMs());
        String v1 = data.lastTickGapMs() + " ms";
        String v2 = String.valueOf(data.dumpCount());
        String v3 = data.ticketCount() < 0 ? "读取失败" : String.valueOf(data.ticketCount());
        int c3 = data.ticketCount() < 0 ? BAD : ACC;
        if (w >= 420) {
            int cw = (w - 30) / 4;
            statCard(g, x, cy, cw, ch, "运行时间", v0, TEXT, 1.0f, a, mx, my);
            statCard(g, x + cw + 10, cy, cw, ch, "tick 间隔", v1, tickOk ? OK : BAD, 1.0f, a, mx, my);
            statCard(g, x + (cw + 10) * 2, cy, cw, ch, "抓栈次数", v2, data.dumpCount() == 0 ? OK : WARN, 1.0f, a, mx, my);
            statCard(g, x + (cw + 10) * 3, cy, cw, ch, "票据数", v3, c3, 1.0f, a, mx, my);
        } else {
            int cw = (w - 10) / 2;
            statCard(g, x, cy, cw, ch, "运行时间", v0, TEXT, 0.95f, a, mx, my);
            statCard(g, x + cw + 10, cy, cw, ch, "tick 间隔", v1, tickOk ? OK : BAD, 0.95f, a, mx, my);
            statCard(g, x, cy + ch + 8, cw, ch, "抓栈次数", v2, data.dumpCount() == 0 ? OK : WARN, 0.95f, a, mx, my);
            statCard(g, x + cw + 10, cy + ch + 8, cw, ch, "票据数", v3, c3, 0.95f, a, mx, my);
            cy += ch + 8;
        }

        // 维度表卡片
        int ty = cy + ch + 12;
        int rowH = 15;
        int th = 22 + data.dims().size() * rowH + 6;
        roundRect(g, x, ty, w, th, 10, CARD, BORDER | a);
        g.drawString(font, "维度统计", x + 12, ty + 8, withA(DIM, a), false);
        int ly = ty + 22;
        int chkX = x + w - 70;   // 区块列
        int entX = x + w - 150;  // 实体列
        int nameMax = Math.max(40, entX - x - 160);  // 防负数
        for (NetHandler.PanelData.DimRow d : data.dims()) {
            dtr(g, d.name(), x + 12, ly, nameMax, withA(TEXT, a), false);
            dtr(g, String.valueOf(d.entities()), entX, ly, 60, withA(DIM, a), false);
            dtr(g, String.valueOf(d.chunks()), chkX, ly, 66, withA(DIM, a), false);
            ly += rowH;
        }
        g.drawString(font, "实体", entX, ty + 8, withA(FAINT, a), false);
        g.drawString(font, "区块", chkX, ty + 8, withA(FAINT, a), false);

        // 底部横幅（剩余空间才画）
        long dead = data.files().stream().filter(f -> f.dead() > 0).mapToLong(NetHandler.PanelData.FileRow::dead).sum();
        int by = ty + th + 10;
        if (by + 26 <= y + h) {
            banner(g, x, by, w, 26, dead == 0,
                    dead == 0 ? "✓ 存档体检未发现问题" : "⚠ 发现 " + dead + " 个死指针 — 详见「存档体检」页", a);
        }
    }

    // ---- 体检 ----
    private void pageHealth(GuiGraphics g, int x, int y, int w, int h, int mx, int my, int a) {
        if (data == null) { loadingCard(g, x, y, a); return; }
        title(g, x, y, "存档体检", a);
        int issues = (int) data.files().stream().filter(f -> f.dead() > 0).count();
        banner(g, x, y + 16, w, 26, issues == 0,
                issues == 0 ? "✓ 全部文件通过完整性检查" : "⚠ " + issues + " 个文件存在死指针/坏条目", a);

        int ly = y + 50;
        int cardH = h - (ly - y) - 4;
        if (cardH < 30) return;
        roundRect(g, x, ly, w, cardH, 10, CARD, BORDER | a);
        g.drawString(font, "异常文件", x + 12, ly + 8, withA(DIM, a), false);

        // 列位置按实际宽度比例分布（防窄窗口反超/超框）
        int typeX = x + w - 130, deadX = x + w - 92, sizeX = x + w - 52;
        int nameMax = typeX - (x + 28) - 8;
        int iy = ly + 22;
        int shown = 0;
        for (NetHandler.PanelData.FileRow f : data.files()) {
            if (f.dead() <= 0) continue;
            if (iy + 20 > ly + cardH - 22) break;   // 给底栏留位，不画穿
            shown++;
            roundRect(g, x + 8, iy - 4, w - 16, 20, 6, 0x14E05D5D, 0);
            dot(g, x + 18, iy + 5, 3, withA(BAD, a));
            dtr(g, f.name(), x + 28, iy, nameMax, withA(TEXT, a), false);
            dtr(g, f.slvlr() ? "slvlr" : "slvls", typeX, iy, 56, withA(FAINT, a), false);
            dtr(g, "×" + f.dead(), deadX, iy, 36, withA(BAD, a), false);
            dtr(g, fmtSize(f.size()), sizeX, iy, 48, withA(DIM, a), false);
            iy += 24;
        }
        if (shown == 0) {
            g.drawString(font, "✓ 没有需要修复的文件", x + 12, iy, withA(OK, a), false);
        }
        g.drawString(font, "当前版本只读 — 不会改动你的存档", x + 12, ly + cardH - 14, withA(FAINT, a), false);
    }

    // ---- 浏览 ----
    private void pageBrowse(GuiGraphics g, int x, int y, int w, int h, int mx, int my, int a) {
        if (data == null) { loadingCard(g, x, y, a); return; }
        title(g, x, y, "子层级浏览器", a);
        int ly = y + 16;
        int cardH = h - (ly - y) - 4;
        if (cardH < 30) return;
        roundRect(g, x, ly, w, cardH, 10, CARD, BORDER | a);
        // 列位置按宽度百分比（防超框）
        int sizeX = x + w * 46 / 100, liveX = x + w * 66 / 100, stX = x + w * 82 / 100;
        int nameMax = sizeX - (x + 16) - 10;
        g.drawString(font, "文件", x + 16, ly + 8, withA(FAINT, a), false);
        g.drawString(font, "大小", sizeX, ly + 8, withA(FAINT, a), false);
        g.drawString(font, "有效记录", liveX, ly + 8, withA(FAINT, a), false);
        g.drawString(font, "状态", stX, ly + 8, withA(FAINT, a), false);
        int iy = ly + 22;
        int row = 0;
        for (NetHandler.PanelData.FileRow f : data.files()) {
            if (iy + 16 > ly + cardH - 6) break;
            if (row % 2 == 0) roundRect(g, x + 8, iy - 4, w - 16, 16, 5, 0x0AFFFFFF, 0);
            boolean bad = f.dead() > 0;
            dtr(g, f.name(), x + 16, iy, nameMax, withA(bad ? BAD : (f.size() < 0 ? FAINT : TEXT), a), false);
            if (f.size() >= 0) {
                dtr(g, fmtSize(f.size()), sizeX, iy, liveX - sizeX - 8, withA(DIM, a), false);
                if (f.live() >= 0) dtr(g, String.valueOf(f.live()), liveX, iy, stX - liveX - 8, withA(DIM, a), false);
                dtr(g, bad ? "异常" : "健康", stX, iy, w - (stX - x) - 16, withA(bad ? BAD : OK, a), false);
            }
            iy += 16;
            row++;
        }
    }

    // ---- 诊断 ----
    private void pageDiag(GuiGraphics g, int x, int y, int w, int h, int mx, int my, int a) {
        title(g, x, y, "区块诊断", a);
        int ix = SIDE_X + SIDE_W + 20;
        g.drawString(font, "X", ix - 10, 48, withA(FAINT, 0xFFFFFFFF), false);
        g.drawString(font, "Z", x + diagBoxW + 16, 48, withA(FAINT, 0xFFFFFFFF), false);
        boolean hov = inBox(mx, my, diagBtnX, diagBtnY, 46, 18);
        roundRect(g, diagBtnX, diagBtnY, 46, 18, 8, hov ? 0x557DBAFF : 0x2A7DBAFF, ACC);
        g.drawString(font, "诊断", diagBtnX + (46 - font.width("诊断")) / 2, diagBtnY + 5,
                withA(0xFF0B1420, 0xFFFFFFFF), false);

        int cy2 = y + 38;  // 输入行(44..60)之下，绝不重叠
        int cardH = h - (cy2 - y) - 2;
        if (cardH < 30) return;
        roundRect(g, x, cy2, w, cardH, 10, CARD, BORDER | a);
        if (diagRes == null) {
            g.drawString(font, "等待查询…", x + 16, cy2 + 14, withA(FAINT, a), false);
            return;
        }
        boolean loaded = diagRes.loaded();
        dot(g, x + 22, cy2 + 18, 5, withA(loaded ? OK : WARN, a));
        dtr(g, "(" + diagRes.x() + ", " + diagRes.z() + ")  " + diagRes.status(),
                x + 34, cy2 + 12, w - 48, withA(loaded ? OK : WARN, a), false);
        g.drawString(font, "维度", x + 22, cy2 + 40, withA(FAINT, a), false);
        dtr(g, diagRes.dim(), x + 90, cy2 + 40, w - 104, withA(TEXT, a), false);
        g.drawString(font, "实体", x + 22, cy2 + 58, withA(FAINT, a), false);
        g.drawString(font, String.valueOf(diagRes.entities()), x + 90, cy2 + 58, withA(TEXT, a), false);
        g.drawString(font, "方块实体", x + 22, cy2 + 76, withA(FAINT, a), false);
        g.drawString(font, String.valueOf(diagRes.blockEntities()), x + 90, cy2 + 76, withA(TEXT, a), false);
    }

    // ---- 监视 ----
    private void pageWatch(GuiGraphics g, int x, int y, int w, int h, int mx, int my, int a) {
        if (data == null) { loadingCard(g, x, y, a); return; }
        title(g, x, y, "死锁监视", a);
        boolean ok = data.lastTickGapMs() < 500;
        banner(g, x, y + 16, w, 26, ok,
                ok ? "💓 主线程正常 — tick 间隔 " + data.lastTickGapMs() + " ms"
                   : "⚠ 主线程疑似卡顿 — tick 间隔 " + data.lastTickGapMs() + " ms", a);

        int cy2 = y + 50;
        int infoH = 84;
        int ty = cy2 + infoH + 10;
        int tickH = h - (ty - y) - 4;
        if (tickH < 30) return;   // 窗口太矮就不画下半（防负高度出界）

        roundRect(g, x, cy2, w, infoH, 10, CARD, BORDER | a);
        infoRow(g, x, w, cy2 + 10, "抓栈阈值", "卡死 20 秒自动抓栈（原版 60 秒杀服前抢下现场）", a);
        infoRow(g, x, w, cy2 + 28, "本服抓栈", data.dumpCount() + " 次", a);
        infoRow(g, x, w, cy2 + 46, "栈文件", "logs/sablefix/threaddump-*.txt", a);
        infoRow(g, x, w, cy2 + 64, "手动抓栈", "/sablefix dump", a);

        roundRect(g, x, ty, w, tickH, 10, CARD, BORDER | a);
        g.drawString(font, "票据 UUID（force-load）", x + 12, ty + 8, withA(DIM, a), false);
        int iy = ty + 22;
        if (data.ticketUuids().isEmpty()) {
            g.drawString(font, "（无票据或读取失败）", x + 12, iy, withA(FAINT, a), false);
        } else {
            for (String u : data.ticketUuids()) {
                if (iy + 16 > ty + tickH - 6) break;
                roundRect(g, x + 8, iy - 4, w - 16, 18, 6, 0x0A58A6FF, 0);
                dot(g, x + 18, iy + 4, 3, withA(ACC, a));
                dtr(g, u, x + 28, iy, w - 44, withA(TEXT, a), false);
                iy += 22;
            }
        }
    }

    // ================= 绘图工具 =================
    /** 画文字，超宽自动截断加省略号 */
    private void dtr(GuiGraphics g, String s, int x, int y, int maxW, int color, boolean shadow) {
        if (maxW > 8 && font.width(s) > maxW) {
            s = font.plainSubstrByWidth(s, maxW - font.width("…")) + "…";
        }
        g.drawString(font, s, x, y, color, shadow);
    }

    private void title(GuiGraphics g, int x, int y, String t, int a) {
        g.drawString(font, t, x + 2, y + 4, withA(TEXT, a), true);
    }

    private void loadingCard(GuiGraphics g, int x, int y, int a) {
        roundRect(g, x, y + 22, 240, 40, 10, CARD, BORDER | a);
        g.drawString(font, "正在向服务端请求数据…", x + 16, y + 36, withA(DIM, a), false);
    }

    private void statCard(GuiGraphics g, int x, int y, int w, int h, String label, String value,
                          int color, float scale, int a, int mx, int my) {
        boolean hov = inBox(mx, my, x, y, w, h);
        roundRect(g, x, y, w, h, 10, hov ? CARD_HI : CARD, BORDER | a);
        g.drawString(font, label, x + 12, y + 10, withA(DIM, a), false);
        g.pose().pushPose();
        g.pose().translate(x + 12, y + 28, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, value, 0, 0, withA(color, a), false);
        g.pose().popPose();
    }

    private void banner(GuiGraphics g, int x, int y, int w, int h, boolean good, String text, int a) {
        int fill = good ? 0x144EC97F : 0x14E05D5D;
        int edge = good ? OK : BAD;
        roundRect(g, x, y, w, h, 8, fill, withA(edge, a));
        dot(g, x + 16, y + h / 2, 4, withA(edge, a));
        g.drawString(font, text, x + 28, y + (h - 8) / 2, withA(TEXT, a), false);
    }

    private void infoRow(GuiGraphics g, int x, int w, int y, String k, String v, int a) {
        g.drawString(font, k, x + 14, y, withA(FAINT, a), false);
        dtr(g, v, x + 100, y, w - 114, withA(TEXT, a), false);
    }

    private static int withA(int argb, int aOrFull) {
        int a = aOrFull >>> 24;
        return (argb & 0x00FFFFFF) | (a << 24);
    }

    private void dot(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int inset = (int) Math.round(r - Math.sqrt(Math.max(0, r * r - dy * dy)));
            g.fill(cx - r + inset, cy + dy, cx + r - inset, cy + dy + 1, color);
        }
    }

    /** 圆角矩形（描边色为 0 时只填充） */
    private void roundRect(GuiGraphics g, int x, int y, int w, int h, int r, int fill, int stroke) {
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

    // ---- 运行日志 ----
    private void pageLog(GuiGraphics g, int x, int y, int w, int h, int mx, int my, int a) {
        if (data == null) { loadingCard(g, x, y, a); return; }
        int ly = y + 4;
        int cardH = h - 8;
        roundRect(g, x, ly, w, cardH, 10, CARD, BORDER | a);
        boolean scrolled = logScroll > 0;
        g.drawString(font, "被动功能的处理过程与结果" + (scrolled ? " · 已上翻 " + (int) logScroll + " 行" : ""),
                x + 10, ly + 6, withA(FAINT, a), false);
        int rowH = 12;
        // 给头部 16px、底部提示 14px 留位，防最后一行贴边/被压
        int maxRows = Math.max(1, (cardH - 32) / rowH);
        var lines = data.logLines();
        int total = lines.size();
        int start = Math.max(0, total - maxRows - (int) logScroll);
        int iy = ly + 18;
        int shown = 0;
        for (int i = start; i < total && shown < maxRows; i++, shown++) {
            String line = lines.get(i);
            boolean err = line.contains("[ERROR]");
            boolean wrn = line.contains("[WARN]");
            int cut = line.indexOf(']');
            if (cut > 0 && cut + 1 < line.length()) {
                String head = line.substring(0, cut + 1);
                String body = line.substring(cut + 2);
                dtr(g, head, x + 10, iy, 92, withA(FAINT, a), false);
                dtr(g, body, x + 106, iy, w - 120, withA(err ? BAD : (wrn ? WARN : TEXT), a), false);
            } else {
                dtr(g, line, x + 10, iy, w - 24, withA(TEXT, a), false);
            }
            iy += rowH;
        }
        if (scrolled) {
            g.drawString(font, "滚轮向下翻到底即复位", x + 10, ly + cardH - 12, withA(FAINT, a), false);
        }
    }

    // ---- 关于 ----
    private void pageAbout(GuiGraphics g, int x, int y, int w, int h, int mx, int my, int a) {
        int ly = y + 4;
        int cardH = h - 8;
        roundRect(g, x, ly, w, cardH, 10, CARD, BORDER | a);

        // 顶部：Q 群大字（最显眼）
        int qy = ly + 10;
        roundRect(g, x + 10, qy, w - 20, 30, 8, 0x2258A6FF, ACC);
        String qtext = "问题反馈Q群 1129543042";
        int qw = font.width(qtext);
        g.pose().pushPose();
        g.pose().translate(x + w / 2f - qw / 2f, qy + 10, 0);
        g.pose().scale(1.1f, 1.1f, 1);
        g.drawString(font, qtext, 0, 0, withA(ACC, a), true);
        g.pose().popPose();

        int ty = qy + 42;
        dtr(g, "Sable Fix Core v1.0.0 — Sable 存档自愈与诊断核心", x + 14, ty, w - 28, withA(TEXT, a), true);
        ty += 14;

        // 大白话原理
        ty = aboutSection(g, x, w, ty, "它是干嘛的", a,
                "Sable（航空学）的船和飞行器存在独立的子层级文件里。",
                "这类文件一旦指针损坏，游戏不会报错，只会悄悄丢结构；",
                "而多人服一旦卡死，原版看门狗等 60 秒就直接杀服，",
                "不留任何线索——你根本不知道卡在哪。这个模组专治这两件事。");
        ty = aboutSection(g, x, w, ty, "被动巡检（装上就干活）", a,
                "① 启动时把全部 .slvlr/.slvls 指针表逐个校验，坏的当场点名；",
                "② 解析 force-load 票据，条数、损坏一目了然；",
                "③ 全程记进「运行日志」页，出事可回溯。");
        ty = aboutSection(g, x, w, ty, "死锁监视（核心功能）", a,
                "主线程正常时每秒 tick 20 次。一旦 20 秒没动静，",
                "立刻把所有线程此刻在干什么写成报告（logs/sablefix/），",
                "赶在原版 60 秒杀服之前抢下现场——下次再卡，",
                "打开报告一眼看到是谁在等谁，不用再猜。");
        ty = aboutSection(g, x, w, ty, "只读承诺", a,
                "体检全程只读，绝不修改你的存档。修复功能未来推出，",
                "且永远需要你手动确认后才执行。");
        ty = aboutSection(g, x, w, ty, "命令", a,
                "/sablefix check 体检 · /sablefix list 问题文件",
                "/sablefix tickets 票据 · /sablefix dump 抓栈 · /sablefix help 帮助");
    }

    /** 画一个小节：标题 + 若干行说明，返回下一行 y */
    private int aboutSection(GuiGraphics g, int x, int w, int y, String head, int a, String... lines) {
        dtr(g, head, x + 14, y, w - 28, withA(ACC, a), false);
        y += 12;
        for (String line : lines) {
            dtr(g, line, x + 20, y, w - 34, withA(DIM, a), false);
            y += 11;
        }
        return y + 5;
    }

    private static String fmtSize(long bytes) {
        if (bytes < 0) return "-";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / 1048576.0);
    }

    private static String fmtDur(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format("%d小时%02d分", h, m) : String.format("%d分%02d秒", m, sec);
    }
}
