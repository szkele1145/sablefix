package dev.sfx.sablefix.client;

import dev.sfx.sablefix.SableFixMod;
import dev.sfx.sablefix.client.ui.Format;
import dev.sfx.sablefix.client.ui.Widgets;
import dev.sfx.sablefix.net.NetHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import static dev.sfx.sablefix.client.ui.Theme.*;
import static dev.sfx.sablefix.client.ui.Widgets.dot;
import static dev.sfx.sablefix.client.ui.Widgets.inBox;
import static dev.sfx.sablefix.client.ui.Widgets.roundRect;

/**
 * 双端 GUI 主界面 — 全自绘现代化深色仪表盘。
 * 圆角卡片 / 主题色高亮 / 悬停反馈 / 切页淡入动画。
 * 全部文案走 lang key（zh_cn/en_us），L() 为解析助手。
 * 设计令牌在 ui.Theme、控件在 ui.Widgets、格式化在 ui.Format（模块化，改主题一处生效）。
 */
public class SfxPanelScreen extends Screen {

    private static final String[] TAB_KEYS = {
            "sablefix.panel.tab.home", "sablefix.panel.tab.health", "sablefix.panel.tab.browse",
            "sablefix.panel.tab.diag", "sablefix.panel.tab.watch", "sablefix.panel.tab.log",
            "sablefix.panel.tab.about"};
    private static final int SIDE_X = 8, SIDE_W = 108;
    private static final int NAV_TOP = 38, NAV_H = 20, NAV_GAP = 3;

    /** lang 解析助手 */
    private static String L(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private int tab;
    private double logScroll;
    private long animStart = Util.getMillis();
    private NetHandler.PanelData data;
    private NetHandler.DiagRes diagRes;

    private EditBox xBox, zBox;
    private int diagBtnX, diagBtnY, diagBoxW;  // 诊断页控件位置（init 时按窗口算好，绘制/命中共用）

    public SfxPanelScreen() {
        super(Component.translatable("sablefix.panel.title"));
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
                new NetHandler.PanelData.FileRow(L("sablefix.panel.browse_healthy", 755), true, -1, -1, 0));
        var uuids = java.util.List.of(
                "6f2b8e1c-4a3d-4f7e-9b21-08c5d3a7e642",
                "a91d47c2-6e8f-4b0a-8c3d-1f5e2b9a7d04",
                "c47e9013-2b5a-4d8c-a6f1-7e3d8b05c921");
        var dims = java.util.List.of(
                new NetHandler.PanelData.DimRow("minecraft:overworld", 85, 319, 4205),
                new NetHandler.PanelData.DimRow("minecraft:the_nether", 147, 1, 2523),
                new NetHandler.PanelData.DimRow("minecraft:the_end", 147, 0, 2523));
        var logs = java.util.List.of(
                "16:33:42.101 [INFO] Sable Fix Core v1.0.0 loaded",
                "16:34:22.015 [INFO] watchdog up: auto-dump after 20s stall",
                "16:34:23.602 [WARN] r.-4.7.slvlr: 3 dead pointers (74 live)",
                "16:34:23.711 [INFO] tickets: 19 force-load tickets, gzip/NBT OK (707 B)",
                "16:51:08.332 [INFO] panel request: Dev@[/127.0.0.1]");
        return new NetHandler.PanelData(files, 19, uuids, 41, 0, 22_320_000L, dims, logs);
    }

    static NetHandler.DiagRes demoDiag() {
        return new NetHandler.DiagRes(584, 12, true, L("sablefix.diag.loaded"), 12, 27, "minecraft:overworld");
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
        for (int i = 0; i < TAB_KEYS.length; i++) {
            if (inBox(mx, my, SIDE_X, NAV_TOP + i * (NAV_H + NAV_GAP), SIDE_W, NAV_H)) {
                switchTab(i);
                return true;
            }
        }
        // 顶栏右侧：刷新 / 关闭
        if (inBox(mx, my, width - 146, 6, 66, 16)) { request(); return true; }
        if (inBox(mx, my, width - 74, 6, 66, 16)) { onClose(); return true; }
        if (tab == 3 && inBox(mx, my, diagBtnX, diagBtnY, 46, 18)) { doDiag(); return true; }
        // About 页：Telegram 横幅 → 复制链接
        if (tab == 6 && tgBtnW > 0 && inBox(mx, my, tgBtnX, tgBtnY, tgBtnW, tgBtnH)) {
            Minecraft.getInstance().keyboardHandler.setClipboard(SableFixMod.TG_URL);
            tgCopiedAt = Util.getMillis();
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 数字键 1-7 切页（输入框聚焦时不抢）
        if (xBox != null && !xBox.isFocused() && !zBox.isFocused()
                && keyCode >= 0x31 && keyCode <= 0x30 + TAB_KEYS.length) {
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
        if (tab == 6) {
            // 上限 = 内容底端 - 可视底端（render 时记录）
            double max = Math.max(0, aboutContentEnd - aboutInnerBottom + 4);
            aboutScroll = Math.max(0, Math.min(max, aboutScroll - dy * 4));
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
        demoNext = (demoNext + 1) % TAB_KEYS.length;
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
            dev.sfx.sablefix.SableFixMod.LOGGER.warn("auto screenshot failed", t);
        }
    }

    // ---- 顶栏 ----
    private void drawTopBar(GuiGraphics g, int mx, int my, int alpha) {
        g.fill(0, 0, width, 24, TOPBAR | alpha);
        g.fill(0, 24, width, 25, BORDER | alpha);
        g.drawString(font, "Sable", SIDE_X, 8, withA(ACC, alpha), true);
        g.drawString(font, "Fix Core", SIDE_X + font.width("Sable ") + 2, 8, withA(TEXT, alpha), true);
        g.drawString(font, SableFixMod.VERSION, SIDE_X + font.width("Sable Fix Core ") + 4, 9, withA(FAINT, alpha), false);

        // 右上：刷新 / 关闭
        topBtn(g, mx, my, width - 146, L("sablefix.panel.refresh"), alpha);
        topBtn(g, mx, my, width - 74, L("sablefix.panel.close"), alpha);
    }

    private void topBtn(GuiGraphics g, int mx, int my, int x, String label, int alpha) {
        boolean hov = inBox(mx, my, x, 6, 66, 16);
        roundRect(g, x, 6, 66, 16, 7, hov ? 0x2AFFFFFF : 0x14FFFFFF, BORDER | alpha);
        g.drawString(font, label, x + (66 - font.width(label)) / 2, 10, withA(hov ? TEXT : DIM, alpha), false);
    }

    // ---- 侧栏 ----
    private void drawSide(GuiGraphics g, int mx, int my) {
        roundRect(g, SIDE_X - 4, 30, SIDE_W + 8, height - 38, 10, SIDE, BORDER);
        for (int i = 0; i < TAB_KEYS.length; i++) {
            int y = NAV_TOP + i * (NAV_H + NAV_GAP);
            boolean sel = i == tab;
            boolean hov = inBox(mx, my, SIDE_X, y, SIDE_W, NAV_H);
            if (sel) {
                roundRect(g, SIDE_X, y, SIDE_W, NAV_H, 7, NAV_SEL, ACC);
                roundRect(g, SIDE_X + 2, y + 5, 3, NAV_H - 10, 2, ACC, 0);
            } else if (hov) {
                roundRect(g, SIDE_X, y, SIDE_W, NAV_H, 7, NAV_HOV, 0);
            }
            g.drawString(font, L(TAB_KEYS[i]), SIDE_X + 13, y + (NAV_H - 8) / 2,
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
        String v3 = data.ticketCount() < 0 ? L("sablefix.panel.ticket_fail") : String.valueOf(data.ticketCount());
        int c3 = data.ticketCount() < 0 ? BAD : ACC;
        if (w >= 420) {
            int cw = (w - 30) / 4;
            statCard(g, x, cy, cw, ch, L("sablefix.panel.uptime"), v0, TEXT, 1.0f, a, mx, my);
            statCard(g, x + cw + 10, cy, cw, ch, L("sablefix.panel.tick_gap"), v1, tickOk ? OK : BAD, 1.0f, a, mx, my);
            statCard(g, x + (cw + 10) * 2, cy, cw, ch, L("sablefix.panel.dump_count"), v2, data.dumpCount() == 0 ? OK : WARN, 1.0f, a, mx, my);
            statCard(g, x + (cw + 10) * 3, cy, cw, ch, L("sablefix.panel.tickets"), v3, c3, 1.0f, a, mx, my);
        } else {
            int cw = (w - 10) / 2;
            statCard(g, x, cy, cw, ch, L("sablefix.panel.uptime"), v0, TEXT, 0.95f, a, mx, my);
            statCard(g, x + cw + 10, cy, cw, ch, L("sablefix.panel.tick_gap"), v1, tickOk ? OK : BAD, 0.95f, a, mx, my);
            statCard(g, x, cy + ch + 8, cw, ch, L("sablefix.panel.dump_count"), v2, data.dumpCount() == 0 ? OK : WARN, 0.95f, a, mx, my);
            statCard(g, x + cw + 10, cy + ch + 8, cw, ch, L("sablefix.panel.tickets"), v3, c3, 0.95f, a, mx, my);
            cy += ch + 8;
        }

        // 维度表卡片
        int ty = cy + ch + 12;
        int rowH = 15;
        int th = 22 + data.dims().size() * rowH + 6;
        roundRect(g, x, ty, w, th, 10, CARD, BORDER | a);
        g.drawString(font, L("sablefix.panel.dims"), x + 12, ty + 8, withA(DIM, a), false);
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
        g.drawString(font, L("sablefix.panel.entities"), entX, ty + 8, withA(FAINT, a), false);
        g.drawString(font, L("sablefix.panel.chunks"), chkX, ty + 8, withA(FAINT, a), false);

        // 底部横幅（剩余空间才画）
        long dead = data.files().stream().filter(f -> f.dead() > 0).mapToLong(NetHandler.PanelData.FileRow::dead).sum();
        int by = ty + th + 10;
        if (by + 26 <= y + h) {
            banner(g, x, by, w, 26, dead == 0,
                    dead == 0 ? L("sablefix.panel.all_ok") : L("sablefix.panel.dead_found", dead), a);
        }
    }

    // ---- 体检 ----
    private void pageHealth(GuiGraphics g, int x, int y, int w, int h, int mx, int my, int a) {
        if (data == null) { loadingCard(g, x, y, a); return; }
        title(g, x, y, L("sablefix.panel.tab.health"), a);
        int issues = (int) data.files().stream().filter(f -> f.dead() > 0).count();
        banner(g, x, y + 16, w, 26, issues == 0,
                issues == 0 ? L("sablefix.panel.health_ok") : L("sablefix.panel.health_issues", issues), a);

        int ly = y + 50;
        int cardH = h - (ly - y) - 4;
        if (cardH < 30) return;
        roundRect(g, x, ly, w, cardH, 10, CARD, BORDER | a);
        g.drawString(font, L("sablefix.panel.bad_files"), x + 12, ly + 8, withA(DIM, a), false);

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
            g.drawString(font, L("sablefix.panel.no_fix_needed"), x + 12, iy, withA(OK, a), false);
        }
        g.drawString(font, L("sablefix.panel.readonly_note"), x + 12, ly + cardH - 14, withA(FAINT, a), false);
    }

    // ---- 浏览 ----
    private void pageBrowse(GuiGraphics g, int x, int y, int w, int h, int mx, int my, int a) {
        if (data == null) { loadingCard(g, x, y, a); return; }
        title(g, x, y, L("sablefix.panel.tab.browse"), a);
        int ly = y + 16;
        int cardH = h - (ly - y) - 4;
        if (cardH < 30) return;
        roundRect(g, x, ly, w, cardH, 10, CARD, BORDER | a);
        // 列位置按宽度百分比（防超框）
        int sizeX = x + w * 46 / 100, liveX = x + w * 66 / 100, stX = x + w * 82 / 100;
        int nameMax = sizeX - (x + 16) - 10;
        g.drawString(font, L("sablefix.panel.col_file"), x + 16, ly + 8, withA(FAINT, a), false);
        g.drawString(font, L("sablefix.panel.col_size"), sizeX, ly + 8, withA(FAINT, a), false);
        g.drawString(font, L("sablefix.panel.col_live"), liveX, ly + 8, withA(FAINT, a), false);
        g.drawString(font, L("sablefix.panel.col_status"), stX, ly + 8, withA(FAINT, a), false);
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
                dtr(g, L(bad ? "sablefix.panel.status_bad" : "sablefix.panel.status_ok"), stX, iy, w - (stX - x) - 16, withA(bad ? BAD : OK, a), false);
            }
            iy += 16;
            row++;
        }
    }

    // ---- 诊断 ----
    private void pageDiag(GuiGraphics g, int x, int y, int w, int h, int mx, int my, int a) {
        title(g, x, y, L("sablefix.panel.tab.diag"), a);
        int ix = SIDE_X + SIDE_W + 20;
        g.drawString(font, "X", ix - 10, 48, withA(FAINT, 0xFFFFFFFF), false);
        g.drawString(font, "Z", ix + diagBoxW + 4, 48, withA(FAINT, 0xFFFFFFFF), false);
        boolean hov = inBox(mx, my, diagBtnX, diagBtnY, 46, 18);
        roundRect(g, diagBtnX, diagBtnY, 46, 18, 8, hov ? 0x557DBAFF : 0x2A7DBAFF, ACC);
        String diagLabel = L("sablefix.panel.diag");
        g.drawString(font, diagLabel, diagBtnX + (46 - font.width(diagLabel)) / 2, diagBtnY + 5,
                withA(0xFF0B1420, 0xFFFFFFFF), false);

        int cy2 = y + 38;  // 输入行(44..60)之下，绝不重叠
        int cardH = h - (cy2 - y) - 2;
        if (cardH < 30) return;
        roundRect(g, x, cy2, w, cardH, 10, CARD, BORDER | a);
        if (diagRes == null) {
            g.drawString(font, L("sablefix.panel.diag_wait"), x + 16, cy2 + 14, withA(FAINT, a), false);
            return;
        }
        boolean loaded = diagRes.loaded();
        dot(g, x + 22, cy2 + 18, 5, withA(loaded ? OK : WARN, a));
        dtr(g, "(" + diagRes.x() + ", " + diagRes.z() + ")  " + diagRes.status(),
                x + 34, cy2 + 12, w - 48, withA(loaded ? OK : WARN, a), false);
        g.drawString(font, L("sablefix.panel.diag_dim"), x + 22, cy2 + 40, withA(FAINT, a), false);
        dtr(g, diagRes.dim(), x + 90, cy2 + 40, w - 104, withA(TEXT, a), false);
        g.drawString(font, L("sablefix.panel.entities"), x + 22, cy2 + 58, withA(FAINT, a), false);
        g.drawString(font, String.valueOf(diagRes.entities()), x + 90, cy2 + 58, withA(TEXT, a), false);
        g.drawString(font, L("sablefix.panel.diag_be"), x + 22, cy2 + 76, withA(FAINT, a), false);
        g.drawString(font, String.valueOf(diagRes.blockEntities()), x + 90, cy2 + 76, withA(TEXT, a), false);
    }

    // ---- 监视 ----
    private void pageWatch(GuiGraphics g, int x, int y, int w, int h, int mx, int my, int a) {
        if (data == null) { loadingCard(g, x, y, a); return; }
        title(g, x, y, L("sablefix.panel.tab.watch"), a);
        boolean ok = data.lastTickGapMs() < 500;
        banner(g, x, y + 16, w, 26, ok,
                ok ? L("sablefix.panel.watch_ok", data.lastTickGapMs())
                   : L("sablefix.panel.watch_lag", data.lastTickGapMs()), a);

        int cy2 = y + 50;
        int infoH = 84;
        int ty = cy2 + infoH + 10;
        int tickH = h - (ty - y) - 4;
        if (tickH < 30) return;   // 窗口太矮就不画下半（防负高度出界）

        roundRect(g, x, cy2, w, infoH, 10, CARD, BORDER | a);
        infoRow(g, x, w, cy2 + 10, L("sablefix.panel.watch_threshold"), L("sablefix.panel.watch_threshold_val"), a);
        infoRow(g, x, w, cy2 + 28, L("sablefix.panel.watch_dumps"), data.dumpCount() + "", a);
        infoRow(g, x, w, cy2 + 46, L("sablefix.panel.watch_file"), "logs/sablefix/threaddump-*.txt", a);
        infoRow(g, x, w, cy2 + 64, L("sablefix.panel.watch_manual"), "/sablefix dump", a);

        roundRect(g, x, ty, w, tickH, 10, CARD, BORDER | a);
        g.drawString(font, L("sablefix.panel.ticket_uuids"), x + 12, ty + 8, withA(DIM, a), false);
        int iy = ty + 22;
        if (data.ticketUuids().isEmpty()) {
            g.drawString(font, L("sablefix.panel.tickets_empty"), x + 12, iy, withA(FAINT, a), false);
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

    // ================= 绘图工具（实现在 ui.Widgets / ui.Theme，此处仅转调） =================
    private static void dtr(GuiGraphics g, String s, int x, int y, int maxW, int color, boolean shadow) {
        Widgets.dtr(g, Minecraft.getInstance().font, s, x, y, maxW, color, shadow);
    }

    private void title(GuiGraphics g, int x, int y, String t, int a) {
        g.drawString(font, t, x + 2, y + 4, withA(TEXT, a), true);
    }

    private void loadingCard(GuiGraphics g, int x, int y, int a) {
        roundRect(g, x, y + 22, 240, 40, 10, CARD, BORDER | a);
        g.drawString(font, L("sablefix.panel.loading"), x + 16, y + 36, withA(DIM, a), false);
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
        dtr(g, text, x + 28, y + (h - 8) / 2, w - 44, withA(TEXT, a), false);
    }

    private void infoRow(GuiGraphics g, int x, int w, int y, String k, String v, int a) {
        g.drawString(font, k, x + 14, y, withA(FAINT, a), false);
        dtr(g, v, x + 100, y, w - 114, withA(TEXT, a), false);
    }


    // ---- 运行日志 ----
    private void pageLog(GuiGraphics g, int x, int y, int w, int h, int mx, int my, int a) {
        if (data == null) { loadingCard(g, x, y, a); return; }
        int ly = y + 4;
        int cardH = h - 8;
        roundRect(g, x, ly, w, cardH, 10, CARD, BORDER | a);
        boolean scrolled = logScroll > 0;
        g.drawString(font, L("sablefix.panel.log_title") + (scrolled ? L("sablefix.panel.log_scrolled", (int) logScroll) : ""),
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
            g.drawString(font, L("sablefix.panel.log_hint"), x + 10, ly + cardH - 12, withA(FAINT, a), false);
        }
    }

    // ---- 关于 ----
    private double aboutScroll;
    private int aboutContentEnd;   // 上次绘制的内容底端（用于滚动上限）
    private int aboutInnerBottom;  // 可视区底端（滚动 clamp 用）
    private int tgBtnX, tgBtnY, tgBtnW, tgBtnH;  // Telegram 横幅命中区
    private long tgCopiedAt = -1;                // 点击复制后的提示显示时间戳

    private void pageAbout(GuiGraphics g, int x, int y, int w, int h, int mx, int my, int a) {
        int ly = y + 4;
        int cardH = h - 8;
        if (cardH < 60) return;
        roundRect(g, x, ly, w, cardH, 10, CARD, BORDER | a);

        // 顶部：QQ 群 + Telegram 双横幅（固定，不随滚动；TG 可点击复制链接）
        int qy = ly + 8;
        int halfW = (w - 30) / 2;
        roundRect(g, x + 10, qy, halfW, 28, 8, 0x2258A6FF, ACC);
        String qtext = L("sablefix.panel.about_qq");
        int qw = font.width(qtext);
        g.pose().pushPose();
        g.pose().translate(x + 10 + halfW / 2f - qw / 2f, qy + 9, 0);
        g.pose().scale(1.1f, 1.1f, 1);
        g.drawString(font, qtext, 0, 0, withA(ACC, a), true);
        g.pose().popPose();

        boolean tgHov = inBox(mx, my, x + 20 + halfW, qy, halfW, 28);
        tgBtnX = x + 20 + halfW; tgBtnY = qy; tgBtnW = halfW; tgBtnH = 28;
        roundRect(g, tgBtnX, tgBtnY, tgBtnW, tgBtnH, 8, tgHov ? 0x3358A6FF : 0x2258A6FF, ACC);
        boolean copied = tgCopiedAt > 0 && Util.getMillis() - tgCopiedAt < 2000;
        String ttext = copied ? L("sablefix.panel.about_tg_copied") : L("sablefix.panel.about_tg");
        int tw = font.width(ttext);
        g.pose().pushPose();
        g.pose().translate(tgBtnX + tgBtnW / 2f - tw / 2f, tgBtnY + 9, 0);
        g.pose().scale(1.1f, 1.1f, 1);
        g.drawString(font, ttext, 0, 0, withA(tgHov ? TEXT : ACC, a), true);
        g.pose().popPose();

        // 正文区：裁剪到卡片内，超出走滚动（底部留 12px 给提示行，防压字）
        int innerTop = qy + 36;
        int innerBottom = ly + cardH - 18;
        aboutInnerBottom = innerBottom;
        int availH = innerBottom - innerTop;
        g.enableScissor(x + 1, innerTop, x + w - 1, innerBottom);

        int ty = innerTop - (int) aboutScroll;
        dtr(g, L("sablefix.panel.about_ver"), x + 14, ty, w - 28, withA(TEXT, a), true);
        ty += 13;

        ty = aboutSection(g, x, w, ty, "sablefix.panel.about_what", a,
                "sablefix.panel.about_what1", "sablefix.panel.about_what2",
                "sablefix.panel.about_what3", "sablefix.panel.about_what4");
        ty = aboutSection(g, x, w, ty, "sablefix.panel.about_passive", a,
                "sablefix.panel.about_passive1", "sablefix.panel.about_passive2", "sablefix.panel.about_passive3");
        ty = aboutSection(g, x, w, ty, "sablefix.panel.about_watch", a,
                "sablefix.panel.about_watch1", "sablefix.panel.about_watch2",
                "sablefix.panel.about_watch3", "sablefix.panel.about_watch4");
        ty = aboutSection(g, x, w, ty, "sablefix.panel.about_readonly", a,
                "sablefix.panel.about_readonly1", "sablefix.panel.about_readonly2");
        ty = aboutSection(g, x, w, ty, "sablefix.panel.about_cmds", a,
                "sablefix.panel.about_cmds1", "sablefix.panel.about_cmds2");
        aboutContentEnd = ty;
        g.disableScissor();

        // 溢出提示（画在裁剪区下方的预留带里，永不压正文）
        if (aboutContentEnd > availH) {
            String hint = aboutScroll < 1 ? "↓" : "↑↓";
            g.drawString(font, hint, x + w - font.width(hint) - 12, ly + cardH - 13, withA(FAINT, a), false);
        }
    }

    /** 画一个小节：标题 + 若干行说明（全部 lang key），返回下一行 y */
    private int aboutSection(GuiGraphics g, int x, int w, int y, String headKey, int a, String... lineKeys) {
        dtr(g, L(headKey), x + 14, y, w - 28, withA(ACC, a), false);
        y += 12;
        for (String key : lineKeys) {
            dtr(g, L(key), x + 20, y, w - 34, withA(DIM, a), false);
            y += 11;
        }
        return y + 5;
    }

    private static String fmtSize(long bytes) {
        return Format.fmtSize(bytes);
    }

    private static String fmtDur(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? L("sablefix.panel.dur_h", h, String.format("%02d", m))
                     : L("sablefix.panel.dur_ms", m, String.format("%02d", sec));
    }
}
