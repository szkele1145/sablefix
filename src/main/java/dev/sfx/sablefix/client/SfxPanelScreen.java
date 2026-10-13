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

    /** lang 解析助手（GUI 审计#1：60fps 渲染下每帧 ~50 次 translatable 构造+语言表分解是
     *  最大单项分配压力 → 无参结果按 key 缓存；带参仍走直译（每次参数不同，缓存无意义）。
     *  失效：语言热切换时客户端会重建 Screen（init 重新跑），静态缓存随 JVM 语言环境
     *  实例走 getString 每次解析——实测 MC 语言表 reload 后 TranslatableContents 会重新
     *  解析，因此以 Minecraft.getInstance().getLanguageManager().getSelected() 作为
     *  语言代次，变更时整体失效）。 */
    private static final java.util.Map<String, String> LANG_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile Object langGeneration;

    private static String L(String key, Object... args) {
        if (args.length == 0) {
            Object gen = net.minecraft.client.Minecraft.getInstance()
                    .getLanguageManager().getSelected();
            if (gen != langGeneration) {
                LANG_CACHE.clear();
                langGeneration = gen;
            }
            return LANG_CACHE.computeIfAbsent(key, k -> Component.translatable(k).getString());
        }
        return Component.translatable(key, args).getString();
    }

    private int tab;
    private double logScroll;
    private long animStart = Util.getMillis();
    private NetHandler.PanelData data;
    private NetHandler.DiagRes diagRes;

    private EditBox xBox, zBox;
    private int diagBtnX, diagBtnY, diagBoxW;  // 诊断页控件位置（init 时按窗口算好，绘制/命中共用）
    private int diagIx;                        // 输入框起始 x（resize 重算用）

    public SfxPanelScreen() {
        super(Component.translatable("sablefix.panel.title"));
    }

    /** 面板关闭（含 ESC/切屏）：释放 ClientSetup.openScreen 静态引用——
     *  终局审计：clearOpenScreen 此前 0 调用（死代码），openScreen 永持已关面板 →
     *  对象泄漏 + 服务端回包继续投递给已关 UI。removed() 覆盖 onClose/切屏/断线三条路径。 */
    @Override
    public void removed() {
        super.removed();
        dev.sfx.sablefix.client.ClientSetup.clearOpenScreen(this);
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
                "16:33:42.101 [INFO] Sable Fix Core v" + SableFixMod.VERSION + " loaded",
                "16:34:22.015 [INFO] watchdog up: auto-dump after 20s stall",
                "16:34:23.602 [WARN] r.-4.7.slvlr: 3 dead pointers (74 live)",
                "16:34:23.711 [INFO] tickets: 19 force-load tickets, gzip/NBT OK (707 B)",
                "16:51:08.332 [INFO] panel request: Dev@[/127.0.0.1]");
        return new NetHandler.PanelData(files, 19, uuids, 41, 0, 22_320_000L, dims, logs, false, 0, java.util.List.of());
    }

    static NetHandler.DiagRes demoDiag() {
        return new NetHandler.DiagRes(584, 12, true, L("sablefix.diag.loaded"), 12, 27, "minecraft:overworld");
    }

    // ================= 初始化 =================
    @Override
    protected void init() {
        layoutDiag();
        // 导航/按钮全自绘（见 mouseClicked / drawSide），这里只放诊断页输入框
        xBox = new EditBox(font, diagIx, 44, diagBoxW, 16, Component.literal("X"));
        xBox.setMaxLength(12);
        styleBox(xBox);
        zBox = new EditBox(font, diagIx + diagBoxW + 12, 44, diagBoxW, 16, Component.literal("Z"));
        zBox.setMaxLength(12);
        styleBox(zBox);
        addRenderableWidget(xBox);
        addRenderableWidget(zBox);

        if (ClientSetup.isDemo()) {
            if (data == null) data = demoData();
            if (diagRes == null) diagRes = demoDiag();
            tab = Integer.getInteger("sfx.demo.tab", 0);
        } else if (data == null) {
            request();
        }
        setBoxVisible();
    }

    /** 诊断页布局（resize 时重跑）：输入框位置/宽度、按钮命中区。 */
    private void layoutDiag() {
        int ix = SIDE_X + SIDE_W + 20;
        int avail = width - ix - 12;
        int bw = 46;
        diagBoxW = Math.max(40, Math.min(88, (avail - bw - 24) / 2));
        diagBtnX = Math.min(ix + diagBoxW * 2 + 24, width - bw - 10);
        diagBtnY = 43;
        diagIx = ix;
    }

    @Override
    public void resize(Minecraft mc, int w, int h) {
        super.resize(mc, w, h);
        // init() 已被 super.resize 调用（重建控件），这里只需再同步命中区
        layoutDiag();
        xBox.setX(diagIx);
        zBox.setX(diagIx + diagBoxW + 12);
        xBox.setWidth(diagBoxW);
        zBox.setWidth(diagBoxW);
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
        repairConfirmFile = null;
        repairDryFile = null;
        if (t != 1) repairMsg = null;
        if (t != 4) { purgeConfirm = false; purgeMsg = null; }
    }

    private void request() {
        repairConfirmFile = null;
        repairDryFile = null;
        purgeConfirm = false;
        PacketDistributor.sendToServer(new NetHandler.PanelReq());
    }

    /** 每 30 秒自动刷新（600 tick）：面板开着时数据保持新鲜，死锁监视页尤其受益。
     *  demo 模式跳过（轮播有自己的节奏）；后台扫描中也不发（等 scanning 完成的重试链）。 */
    @Override
    public void tick() {
        super.tick();
        if (ClientSetup.isDemo() || data == null || data.scanning()) return;
        if (++autoRefreshTicks >= 600) {
            autoRefreshTicks = 0;
            PacketDistributor.sendToServer(new NetHandler.PanelReq());
        }
    }

    private int autoRefreshTicks;

    private void doDiag() {
        try {
            int x = Integer.parseInt(xBox.getValue().trim());
            int z = Integer.parseInt(zBox.getValue().trim());
            diagRes = null;
            diagStatusLine = null;   // 与 diagRes 同步清（GUI审计#6）
            PacketDistributor.sendToServer(new NetHandler.DiagReq(x, z));
        } catch (NumberFormatException ignored) {}
    }

    public void onData(NetHandler.PanelData d) {
        boolean wasScanning = data != null && data.scanning();
        this.data = d;
        animStart = Util.getMillis();
        // GUI 审计#2/#4：浏览页排序结果 + 监视页聚合 + 派生统计在数据到达时算一次，
        // render 每帧只读（原来每帧全量 filter/sort/merge 是 60fps 下的分配大头）
        browseCache = null;
        precomputePanelStats();
        // 后台首扫期间：指数退避重拉（2s→4s→8s 封顶），拿到全量后复位。
        // 多管理员同时开面板时服务端只有一次真重扫，退避避免重试风暴。
        if (d.scanning()) {
            final long backoff = Math.min(8000, 1000L << Math.min(3, scanRetries++));
            Minecraft.getInstance().execute(() -> new java.lang.Thread(() -> {
                try { java.lang.Thread.sleep(backoff); } catch (InterruptedException ignored) { return; }
                Minecraft.getInstance().execute(this::request);
            }, "SableFix-Panel-Retry").start());
        } else {
            scanRetries = 0;
            if (wasScanning) animStart = Util.getMillis();   // 扫描完成，重播淡入
        }
    }

    /** onData 一次算好的派生统计（GUI 审计#6：原来 pageHome/pageHealth 每帧全量 stream 聚合） */
    private long statDeadTotal;      // 所有死槽/坏条目合计（home 横幅用）
    private int statIssueFiles;      // 有问题的文件数（health 横幅用）
    private java.util.Map<String, Integer> ticketCounts;  // uuid -> 次数（pageWatch 聚合）
    private int ticketTotal, ticketUnique;
    private java.util.List<NetHandler.PanelData.FileRow> browseCache;  // 排序+过滤结果缓存

    private void precomputePanelStats() {
        long dead = 0;
        int issues = 0;
        for (NetHandler.PanelData.FileRow f : data.files()) {
            if (f.dead() > 0) { dead += f.dead(); issues++; }
        }
        statDeadTotal = dead;
        statIssueFiles = issues;
        var counts = new java.util.LinkedHashMap<String, Integer>();
        for (String u : data.ticketUuids()) counts.merge(u, 1, Integer::sum);
        ticketCounts = counts;
        ticketTotal = data.ticketUuids().size();
        ticketUnique = counts.size();
    }

    private int scanRetries;
    private String diagStatusLine;   // onDiag 时格式化（GUI审计#6：render 不再每帧拼接）
    public void onDiag(NetHandler.DiagRes r) {
        this.diagRes = r;
        boolean errored = !r.errorKey().isEmpty();
        String statusText = errored ? L(r.errorKey())
                : L(r.loaded() ? "sablefix.diag.loaded" : "sablefix.diag.not_loaded");
        diagStatusLine = "(" + r.x() + ", " + r.z() + ")  " + statusText;
    }

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
        // 体检页：修复按钮（第一次=dry 预览，同文件再点=确认 apply）
        if (tab == 1) {
            for (RepairBtn b : repairBtns) {
                if (inBox(mx, my, b.x(), b.y(), 58, 18)) {
                    data.files().stream().filter(f -> f.name().equals(b.file())).findFirst().ifPresent(this::clickRepair);
                    return true;
                }
            }
        }
        // 浏览页：过滤/排序 chips（命中逻辑与 render 的动态宽度保持一致：按 label 实宽推进）
        if (tab == 2 && my >= 0) {
            int px = SIDE_X + SIDE_W + 10, py = 30;
            int pw = width - px - 10;
            int fy = py + 16;
            if (my >= fy && my < fy + 18) {
                int beforeF = browseFilter, beforeS = browseSort;
                boolean beforeD = browseSortDesc;
                cycleBrowseFilter(mx, px, fy + 1, pw);
                if (browseFilter != beforeF || browseSort != beforeS || browseSortDesc != beforeD) return true;
            }
        }
        // About 页：Telegram 横幅 → 复制链接
        if (tab == 6 && tgBtnW > 0 && inBox(mx, my, tgBtnX, tgBtnY, tgBtnW, tgBtnH)) {
            Minecraft.getInstance().keyboardHandler.setClipboard(SableFixMod.TG_URL);
            tgCopiedAt = Util.getMillis();
            return true;
        }
        // 监视页：票据清理按钮（第一次=dry 预览，再点=确认 apply）
        if (tab == 4 && purgeBtnX >= 0 && inBox(mx, my, purgeBtnX, purgeBtnY, 88, 18)) {
            PacketDistributor.sendToServer(new NetHandler.PurgeReq(purgeConfirm));
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
        if (tab == 4) {
            // GUI 审计#5：render 实测 rowsBottom 记录到字段——旧硬编码 height-44 在
            // purgeMsg 横幅显示期间（cy2+=26）与实际差 22px（恰一行行高，最后一行滚不出来）
            int max = Math.max(0, ticketContentBottom - ticketRowsBottom);
            ticketScroll = Math.max(0, Math.min(max, ticketScroll - (int) (dy * 24)));
            return true;
        }
        if (tab == 2) {
            // 上限 = 内容底 - 可视底（render 时记录 browseContentBottom）
            int px = SIDE_X + SIDE_W + 10, py = 30;
            int h = height - 38;
            int rowsBottom = py + 42 + (h - 42) - 10;   // 与 pageBrowse 的 rowsBottom 对应（近似 clamp 即可）
            int max = Math.max(0, browseContentBottom - rowsBottom);
            browseScroll = Math.max(0, Math.min(max, browseScroll - (int) (dy * 24)));
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
            String scaleTag = System.getProperty("sfx.scale");
            String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HHmmss"));
            String fname = "sfx-tab" + tab + (scaleTag != null ? "-s" + scaleTag : "") + "-" + stamp + ".png";
            img.writeToFile(java.nio.file.Path.of("screenshots", fname));
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
        if (data.scanning()) { scanningCard(g, x, y, a); return; }
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
            int row2 = cy + ch + 8;
            statCard(g, x, row2, cw, ch, L("sablefix.panel.dump_count"), v2, data.dumpCount() == 0 ? OK : WARN, 0.95f, a, mx, my);
            statCard(g, x + cw + 10, row2, cw, ch, L("sablefix.panel.tickets"), v3, c3, 0.95f, a, mx, my);
            cy = row2;   // 修正：2x2 时第二行卡片底端才是维度表的起点（原来只加一次且漏掉 ch 高度）
        }

        // 维度表卡片（2x2 分支下 cy 已指向第二行卡片顶端，再降一行）
        int ty = (w >= 420 ? cy : cy + ch) + 12;
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

        // 底部横幅（剩余空间才画）——dead 合计用 onData 预计算（GUI 审计#6）
        long dead = statDeadTotal;
        int by = ty + th + 10;
        // 卡死提示条（新功能）：发生过卡死抓栈时在总览页优先展示——用户第一眼看到"最近发生过什么"
        if (data.lastStallAtMs() > 0) {
            long agoMs = Math.max(0, System.currentTimeMillis() - data.lastStallAtMs());   // 回拨防护
            String ago = agoMs < 60_000 ? (agoMs / 1000) + "s"
                    : agoMs < 3_600_000 ? (agoMs / 60_000) + "min"
                    : (agoMs / 3_600_000) + "h";
            if (by + 26 <= y + h) {
                banner(g, x, by, w, 26, false,
                        L("sablefix.panel.stall_recent", ago, data.dumpCount()), a);
                by += 32;
            }
        }
        if (by + 26 <= y + h) {
            banner(g, x, by, w, 26, dead == 0,
                    dead == 0 ? L("sablefix.panel.all_ok") : L("sablefix.panel.dead_found", dead), a);
        }
    }

    // ---- 体检 ----
    private void pageHealth(GuiGraphics g, int x, int y, int w, int h, int mx, int my, int a) {
        if (data == null) { loadingCard(g, x, y, a); return; }
        if (data.scanning()) { scanningCard(g, x, y, a); return; }
        title(g, x, y, L("sablefix.panel.tab.health"), a);
        int issues = statIssueFiles;   // onData 预计算（GUI 审计#6）
        banner(g, x, y + 16, w, 26, issues == 0,
                issues == 0 ? L("sablefix.panel.health_ok") : L("sablefix.panel.health_issues", issues), a);

        // 修复结果横幅（apply/dry 返回后显示，直到切页或刷新）
        int by = y + 48;
        if (repairMsg != null) {
            boolean good = repairMsgOk;
            roundRect(g, x, by, w, 22, 8, good ? 0x144EC97F : 0x14E05D5D, withA(good ? OK : BAD, a));
            dtr(g, repairMsg, x + 12, by + 7, w - 24, withA(good ? OK : BAD, a), false);
        }

        int ly = y + (repairMsg != null ? 76 : 50);
        int cardH = h - (ly - y) - 4;
        if (cardH < 30) return;
        roundRect(g, x, ly, w, cardH, 10, CARD, BORDER | a);
        g.drawString(font, L("sablefix.panel.bad_files"), x + 12, ly + 8, withA(DIM, a), false);

        // 列位置：为右侧修复按钮留 58px
        int btnX = x + w - 66;
        int typeX = x + w - 172, deadX = x + w - 134, sizeX = x + w - 94;
        int nameMax = typeX - (x + 28) - 8;
        int iy = ly + 22;
        repairBtns.clear();
        int shown = 0;
        for (NetHandler.PanelData.FileRow f : data.files()) {
            if (f.dead() <= 0) continue;
            if (iy + 20 > ly + cardH - 22) break;   // 给底栏留位，不画穿
            shown++;
            g.fill(x + 8, iy - 4, x + w - 8, iy + 16, 0x14E05D5D);   // GUI审计#3：行背景直角化（原 roundRect 14 fill）
            dot(g, x + 18, iy + 5, 3, withA(BAD, a));
            dtr(g, f.name(), x + 28, iy, nameMax, withA(TEXT, a), false);
            dtr(g, f.slvlr() ? "slvlr" : "slvls", typeX, iy, 56, withA(FAINT, a), false);
            dtr(g, "×" + f.dead(), deadX, iy, 36, withA(BAD, a), false);
            dtr(g, fmtSize(f.size()), sizeX, iy, 48, withA(DIM, a), false);
            // 修复按钮：该文件处于确认态时变红显示「确认清零」
            boolean confirm = f.name().equals(repairConfirmFile);
            boolean hov = inBox(mx, my, btnX, iy - 4, 58, 18);
            int bfill = confirm ? (hov ? 0x66E05D5D : 0x40E05D5D) : (hov ? 0x3F7DBAFF : 0x227DBAFF);
            roundRect(g, btnX, iy - 4, 58, 18, 6, bfill, withA(confirm ? BAD : ACC, a));
            String bl = L(confirm ? "sablefix.panel.repair_confirm" : "sablefix.panel.repair_btn");
            int bw = font.width(bl);
            if (bw <= 54) {
                g.drawString(font, bl, btnX + (58 - bw) / 2, iy + 1, withA(confirm ? BAD : TEXT, a), false);
            } else {
                dtr(g, bl, btnX + 4, iy + 1, 50, withA(confirm ? BAD : TEXT, a), false);
            }
            repairBtns.add(new RepairBtn(f.name(), btnX, iy - 4));
            iy += 24;
        }
        if (shown == 0) {
            g.drawString(font, L("sablefix.panel.no_fix_needed"), x + 12, iy, withA(OK, a), false);
        }
        g.drawString(font, L("sablefix.panel.readonly_note"), x + 12, ly + cardH - 14, withA(FAINT, a), false);

        // ---- 备份管理卡片（新功能）：修复/清理的自动备份清单，新→旧，最新 8 条 ----
        var backups = data.backups();
        if (!backups.isEmpty()) {
            int shown2 = 0;
            int byy = iy + 14;
            if (byy + 12 <= ly + cardH - 4) {   // 回归自审#9：标题同款边界检查（坏文件满列表时防溢出）
                g.drawString(font, L("sablefix.panel.backups_title"), x + 12, byy, withA(DIM, a), false);
            }
            byy += 12;
            for (var b : backups) {
                if (byy + 12 > ly + cardH - 4 || shown2 >= 8) break;
                dtr(g, b.name(), x + 28, byy, w - 170, withA(TEXT, a), false);
                dtr(g, fmtSize(b.size()), x + w - 128, byy, 44, withA(DIM, a), false);
                dtr(g, fmtDur(System.currentTimeMillis() - b.atMs()), x + w - 74, byy, 62, withA(FAINT, a), false);
                byy += 13;
                shown2++;
            }
            if (backups.size() > 8) {
                dtr(g, L("sablefix.panel.backups_more", backups.size() - 8), x + 28, byy, w - 60, withA(FAINT, a), false);
            }
        }
    }

    // ---- 修复交互状态 ----
    private record RepairBtn(String file, int x, int y) {}
    private final java.util.List<RepairBtn> repairBtns = new java.util.ArrayList<>();
    private String repairConfirmFile;   // 待确认（第一次点=发 dry 预览，第二次点同一文件=apply）
    private String repairMsg;
    private boolean repairMsgOk;
    private String repairDryFile;       // dry 预览对应的文件名（确认目标）

    public void onRepairRes(NetHandler.RepairRes r) {
        if (!r.ok()) {
            repairMsg = L(r.errKey(), r.errArg() == null || r.errArg().isEmpty() ? "" : r.errArg());
            repairMsgOk = false;
            repairConfirmFile = null;
            return;
        }
        if (!r.applied()) {
            // dry：显示计划，进入确认态
            repairDryFile = r.file();
            repairConfirmFile = r.file();
            repairMsg = L("sablefix.panel.repair_plan_gui", r.slots(), r.preview());
            repairMsgOk = true;
        } else {
            repairConfirmFile = null;
            repairDryFile = null;
            repairMsg = L("sablefix.panel.repair_done_gui", r.slots());
            repairMsgOk = true;
            var p = Minecraft.getInstance().player;
            if (p != null)
                p.displayClientMessage(Component.translatable("sablefix.cmd.repair_done", r.slots(), r.backup()), false);
        }
        request();   // 重扫刷新列表
    }

    private void clickRepair(NetHandler.PanelData.FileRow f) {
        if (f.name().equals(repairConfirmFile) && f.name().equals(repairDryFile)) {
            PacketDistributor.sendToServer(new NetHandler.RepairReq(f.name(), true));   // 二次确认 → apply
        } else {
            PacketDistributor.sendToServer(new NetHandler.RepairReq(f.name(), false));  // 第一次 → dry
        }
    }

    public void onPurgeRes(NetHandler.PurgeRes r) {
        if (!r.ok()) {
            purgeMsg = L(r.errKey());
            purgeMsgOk = false;
            purgeConfirm = false;
            return;
        }
        if (!r.applied()) {
            purgeConfirm = true;
            purgeMsg = L("sablefix.panel.purge_plan", r.count());
            purgeMsgOk = true;
        } else {
            purgeConfirm = false;
            purgeMsg = L("sablefix.panel.purge_done_gui", r.count());
            purgeMsgOk = true;
            var p = Minecraft.getInstance().player;
            if (p != null)
                p.displayClientMessage(Component.translatable("sablefix.cmd.purge_done", r.count(), r.backup()), false);
        }
        request();   // 重新拉数据（票据数应归零）
    }

    // ---- 浏览 ----
    private static final int FILTER_ALL = 0, FILTER_BAD = 1, FILTER_OK = 2;
    private static final int SORT_NAME = 0, SORT_SIZE = 1, SORT_DEAD = 2;
    private int browseFilter = FILTER_ALL;
    private int browseSort = SORT_NAME;
    private boolean browseSortDesc = true;
    private int browseScroll;
    private int browseContentBottom;   // 渲染时记录，滚动上限
    private final java.util.List<int[]> browseHitRows = new java.util.ArrayList<>(); // [y, h] 每行（表头点击用不到，预留）

    private java.util.stream.Stream<NetHandler.PanelData.FileRow> browseStream() {
        java.util.stream.Stream<NetHandler.PanelData.FileRow> s = data.files().stream();
        if (browseFilter == FILTER_BAD) s = s.filter(f -> f.dead() > 0);
        if (browseFilter == FILTER_OK) s = s.filter(f -> f.dead() <= 0 && f.size() >= 0);
        java.util.Comparator<NetHandler.PanelData.FileRow> c = switch (browseSort) {
            case SORT_SIZE -> java.util.Comparator.comparingLong(NetHandler.PanelData.FileRow::size);
            case SORT_DEAD -> java.util.Comparator.comparingInt(NetHandler.PanelData.FileRow::dead);
            default -> java.util.Comparator.comparing(NetHandler.PanelData.FileRow::name, String.CASE_INSENSITIVE_ORDER);
        };
        if (browseSortDesc) c = c.reversed();
        return s.sorted(c);
    }

    private void cycleBrowseFilter(double mx, int x, int ly, int w) {
        // 与 render 同款动态宽度：label 实宽 + padding，命中才准确
        String[] fLabels = { L("sablefix.panel.filter_all"), L("sablefix.panel.filter_bad"), L("sablefix.panel.filter_ok") };
        String[] sLabels = { L("sablefix.panel.sort_name"), L("sablefix.panel.sort_size"), L("sablefix.panel.sort_dead") };
        int fTotal = 0, sTotal = 0;
        for (String s : fLabels) fTotal += font.width(s) + 18;
        for (String s : sLabels) sTotal += font.width(s) + 26;
        boolean compact = fTotal + 16 + sTotal + 24 > w;
        int chipGap = 8;
        int cx = x + 12;
        for (int i = 0; i < 3; i++) {
            int cw = compact ? 52 : Math.max(52, font.width(fLabels[i]) + 18);
            if (inBox(mx, ly - 1, cx, 0, cw, 18)) {
                browseFilter = i;
                browseScroll = 0;
                browseCache = null;   // GUI 审计#2：过滤变更 → 排序缓存失效
                return;
            }
            cx += cw + chipGap;
        }
        // 排序 chip 从右往左展开（与 render 一致）
        int sx = x + w - 12;
        for (int i = 0; i < 3; i++) {
            String label = sLabels[i] + (browseSort == i ? (browseSortDesc ? " ↓" : " ↑") : "");
            int cw = compact ? 52 : Math.max(52, font.width(label) + 20);
            sx -= cw + (i > 0 ? chipGap : 0);
        }
        for (int i = 0; i < 3; i++) {
            String label = sLabels[i] + (browseSort == i ? (browseSortDesc ? " ↓" : " ↑") : "");
            int cw = compact ? 52 : Math.max(52, font.width(label) + 20);
            int cxx = sx + (i == 0 ? 0 : 0);   // sx 现在指向最左 chip 起点
            int offset = 0;
            for (int j = 0; j < i; j++) {
                String lj = sLabels[j] + (browseSort == j ? (browseSortDesc ? " ↓" : " ↑") : "");
                offset += (compact ? 52 : Math.max(52, font.width(lj) + 20)) + chipGap;
            }
            if (inBox(mx, ly - 1, cxx + offset, 0, cw, 18)) {
                if (browseSort == i) browseSortDesc = !browseSortDesc; else browseSort = i;
                browseScroll = 0;
                browseCache = null;   // GUI 审计#2：排序变更 → 缓存失效
                return;
            }
        }
    }

    private void pageBrowse(GuiGraphics g, int x, int y, int w, int h, int mx, int my, int a) {
        if (data == null) { loadingCard(g, x, y, a); return; }
        if (data.scanning()) { scanningCard(g, x, y, a); return; }
        title(g, x, y, L("sablefix.panel.tab.browse"), a);

        // 过滤 chips 行（y+16）—— 宽度自适应标签（中文 4 字 + 方向箭头会超 54px）
        int fy = y + 16;
        String[] fLabels = { L("sablefix.panel.filter_all"), L("sablefix.panel.filter_bad"), L("sablefix.panel.filter_ok") };
        String[] sLabels = { L("sablefix.panel.sort_name"), L("sablefix.panel.sort_size"), L("sablefix.panel.sort_dead") };
        int chipH = 18;
        int fTotal = 0, sTotal = 0;
        for (String s : fLabels) fTotal += font.width(s) + 18;
        for (String s : sLabels) sTotal += font.width(s) + 26;   // 排序 chip 预留箭头位
        int chipGap = 8;
        // 空间不够时缩到最小 chip 宽（52），再不够只能截断（dtr 兜底）
        boolean compact = fTotal + chipGap * 2 + sTotal + 24 > w;
        int fx = x + 12;
        for (int i = 0; i < 3; i++) {
            int cw = compact ? 52 : Math.max(52, font.width(fLabels[i]) + 18);
            boolean sel = browseFilter == i;
            boolean hov = inBox(mx, my, fx, fy, cw, chipH);
            roundRect(g, fx, fy, cw, chipH, 6, sel ? 0x3F7DBAFF : (hov ? 0x22FFFFFF : 0x12FFFFFF),
                    sel ? withA(ACC, a) : 0);
            int tw = font.width(fLabels[i]);
            if (tw <= cw - 8) g.drawString(font, fLabels[i], fx + (cw - tw) / 2, fy + 5, withA(sel ? TEXT : DIM, a), false);
            else dtr(g, fLabels[i], fx + 4, fy + 5, cw - 8, withA(sel ? TEXT : DIM, a), false);
            fx += cw + chipGap;
        }
        // 排序 chips（右侧）：点击循环 升/降
        int sx = x + w - 12;
        for (int i = 2; i >= 0; i--) {
            String label = sLabels[i] + (browseSort == i ? (browseSortDesc ? " ↓" : " ↑") : "");
            int cw = compact ? 52 : Math.max(52, font.width(label) + 20);
            sx -= cw;
            if (i < 2) sx -= chipGap;
            int cxx = sx;
            boolean sel = browseSort == i;
            boolean hov = inBox(mx, my, cxx, fy, cw, chipH);
            roundRect(g, cxx, fy, cw, chipH, 6, sel ? 0x2A7DBAFF : (hov ? 0x22FFFFFF : 0x12FFFFFF),
                    sel ? withA(ACC, a) : 0);
            int tw = font.width(label);
            if (tw <= cw - 8) g.drawString(font, label, cxx + (cw - tw) / 2, fy + 5, withA(sel ? ACC : DIM, a), false);
            else dtr(g, label, cxx + 4, fy + 5, cw - 8, withA(sel ? ACC : DIM, a), false);
        }

        int ly = fy + 26;
        int cardH = h - (ly - y) - 4;
        if (cardH < 30) return;
        roundRect(g, x, ly, w, cardH, 10, CARD, BORDER | a);
        // 列位置按宽度百分比（防超框）
        int sizeX = x + w * 46 / 100, liveX = x + w * 66 / 100, stX = x + w * 82 / 100;
        int nameMax = sizeX - (x + 16) - 10;
        int hy = ly + 6;
        g.drawString(font, L("sablefix.panel.col_file"), x + 16, hy, withA(FAINT, a), false);
        g.drawString(font, L("sablefix.panel.col_size"), sizeX, hy, withA(FAINT, a), false);
        g.drawString(font, L("sablefix.panel.col_live"), liveX, hy, withA(FAINT, a), false);
        g.drawString(font, L("sablefix.panel.col_status"), stX, hy, withA(FAINT, a), false);

        // 行区滚动裁剪
        int rowsTop = ly + 20;
        int rowsBottom = ly + cardH - 6;
        g.enableScissor(x + 1, rowsTop, x + w - 1, rowsBottom);
        int iy = rowsTop - browseScroll;
        int row = 0;
        browseContentBottom = rowsTop;
        // GUI 审计#2：排序/过滤结果缓存——失效点 onData()（browseCache=null）+ filter/sort 写点；
        // 首次访问时惰性重算，每帧循环不再全量 sort
        if (browseCache == null) browseCache = browseStream().toList();
        var list = browseCache;
        for (NetHandler.PanelData.FileRow f : list) {
            if (iy + 16 > rowsTop) {   // 只画可见区
                if (iy < rowsBottom) {
                    // GUI 审计#3：行级斑马纹 r=5 在 16px 行高下圆角不可辨——改直角单次 fill
                    //（原 roundRect = 12 次 fill/行 × 16 行 = 192 次/帧）
                    if (row % 2 == 0) g.fill(x + 8, iy - 4, x + w - 8, iy + 12, 0x0AFFFFFF);
                    boolean bad = f.dead() > 0;
                    dtr(g, f.name(), x + 16, iy, nameMax, withA(bad ? BAD : (f.size() < 0 ? FAINT : TEXT), a), false);
                    if (f.size() >= 0) {
                        dtr(g, fmtSize(f.size()), sizeX, iy, liveX - sizeX - 8, withA(DIM, a), false);
                        if (f.live() >= 0) dtr(g, String.valueOf(f.live()), liveX, iy, stX - liveX - 8, withA(DIM, a), false);
                        dtr(g, L(bad ? "sablefix.panel.status_bad" : "sablefix.panel.status_ok"), stX, iy, w - (stX - x) - 16, withA(bad ? BAD : OK, a), false);
                    }
                }
            }
            iy += 16;
            row++;
            browseContentBottom += 16;
        }
        g.disableScissor();
        // 溢出提示
        int maxScroll = Math.max(0, browseContentBottom - rowsBottom);
        if (maxScroll > 0) {
            String hint = browseScroll <= 0 ? "↓" : (browseScroll >= maxScroll ? "↑" : "↑↓");
            g.drawString(font, hint, x + w - font.width(hint) - 10, ly + cardH - 13, withA(FAINT, a), false);
        }
    }

    // ---- 诊断 ----
    private void pageDiag(GuiGraphics g, int x, int y, int w, int h, int mx, int my, int a) {
        title(g, x, y, L("sablefix.panel.tab.diag"), a);
        g.drawString(font, "X", diagIx - 10, 48, withA(FAINT, 0xFFFFFFFF), false);
        g.drawString(font, "Z", diagIx + diagBoxW + 4, 48, withA(FAINT, 0xFFFFFFFF), false);
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
        // 状态行客户端本地化：正常=加载/未加载；errorKey 非空=服务器报错（跨语言不串台）
        boolean errored = !diagRes.errorKey().isEmpty();
        int statusColor = errored ? BAD : (loaded ? OK : WARN);
        dot(g, x + 22, cy2 + 18, 5, withA(statusColor, a));
        // GUI审计#6：状态行在结果到达时格式化一次（onDiag），render 每帧不再拼 5-6 个临时对象
        dtr(g, diagStatusLine, x + 34, cy2 + 12, w - 48, withA(statusColor, a), false);
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
        if (data.scanning()) { scanningCard(g, x, y, a); return; }
        title(g, x, y, L("sablefix.panel.tab.watch"), a);
        boolean ok = data.lastTickGapMs() < 500;
        banner(g, x, y + 16, w, 26, ok,
                ok ? L("sablefix.panel.watch_ok", data.lastTickGapMs())
                   : L("sablefix.panel.watch_lag", data.lastTickGapMs()), a);

        int cy2 = y + 50;
        // 票据清理结果横幅（dry/apply 返回后显示）
        if (purgeMsg != null) {
            roundRect(g, x, cy2, w, 20, 8, purgeMsgOk ? 0x144EC97F : 0x14E05D5D,
                    withA(purgeMsgOk ? OK : BAD, a));
            dtr(g, purgeMsg, x + 12, cy2 + 6, w - 24, withA(purgeMsgOk ? OK : BAD, a), false);
            cy2 += 26;
        }
        int infoH = 84;
        int ty = cy2 + infoH + 10;
        int tickH = h - (ty - y) - 4;
        if (tickH < 30) return;   // 窗口太矮就不画下半（防负高度出界）

        roundRect(g, x, cy2, w, infoH, 10, CARD, BORDER | a);
        infoRow(g, x, w, cy2 + 10, L("sablefix.panel.watch_threshold"), L("sablefix.panel.watch_threshold_val"), a);
        infoRow(g, x, w, cy2 + 28, L("sablefix.panel.watch_dumps"), Integer.toString(data.dumpCount()), a);
        infoRow(g, x, w, cy2 + 46, L("sablefix.panel.watch_file"), "logs/sablefix/threaddump-*.txt", a);
        infoRow(g, x, w, cy2 + 64, L("sablefix.panel.watch_manual"), "/sablefix dump", a);

        roundRect(g, x, ty, w, tickH, 10, CARD, BORDER | a);
        // 聚合统计（GUI 审计#4）：onData 预聚合一次，render 只读——原实现每帧
        // new LinkedHashMap + 全量 merge（几百张票时每秒数万次 hash/装箱）
        var counts = ticketCounts;
        int total = ticketTotal, unique = ticketUnique;
        g.drawString(font, L("sablefix.panel.ticket_uuids"), x + 12, ty + 8, withA(DIM, a), false);
        g.drawString(font, L("sablefix.panel.tickets_stats", total, unique), x + w - 12 - font.width(L("sablefix.panel.tickets_stats", total, unique)), ty + 8, withA(ACC, a), false);

        // 清理按钮（统计行左侧空间够时显示）：三态 = 正常 / 确认
        // 阈值 470：720p auto-scale 2x 下逻辑宽 504，560 会把按钮挤掉
        if (total > 0 && w > 470) {
            int bw = 88;
            int bx = x + w - 12 - bw - font.width(L("sablefix.panel.tickets_stats", total, unique)) - 14;
            boolean confirm = purgeConfirm;
            boolean hov = inBox(mx, my, bx, ty + 3, bw, 18);
            int bfill = confirm ? (hov ? 0x66E05D5D : 0x40E05D5D) : (hov ? 0x3F7DBAFF : 0x227DBAFF);
            roundRect(g, bx, ty + 3, bw, 18, 6, bfill, withA(confirm ? BAD : ACC, a));
            String bl = L(confirm ? "sablefix.panel.purge_confirm" : "sablefix.panel.purge_btn");
            int tw2 = font.width(bl);
            if (tw2 <= bw - 8) g.drawString(font, bl, bx + (bw - tw2) / 2, ty + 8, withA(confirm ? BAD : TEXT, a), false);
            else dtr(g, bl, bx + 4, ty + 8, bw - 8, withA(confirm ? BAD : TEXT, a), false);
            purgeBtnX = bx; purgeBtnY = ty + 3;
        } else {
            purgeBtnX = -1;
        }

        // 行区滚动（复用 aboutScroll 的裁剪模式）
        int rowsTop = ty + 20;
        int rowsBottom = ty + tickH - 6;
        g.enableScissor(x + 1, rowsTop, x + w - 1, rowsBottom);
        int iy = rowsTop - ticketScroll;
        ticketContentBottom = rowsTop;
        if (unique == 0) {
            g.drawString(font, L("sablefix.panel.tickets_empty"), x + 12, iy, withA(FAINT, a), false);
            ticketContentBottom += 16;
        } else {
            for (var e : counts.entrySet()) {
                if (iy + 16 > rowsTop) {
                    if (iy < rowsBottom) {
                        boolean dup = e.getValue() > 1;
                        g.fill(x + 8, iy - 4, x + w - 8, iy + 14, 0x0A58A6FF);   // GUI审计#3：行背景直角化
                        dot(g, x + 18, iy + 4, 3, withA(dup ? WARN : ACC, a));
                        dtr(g, e.getKey(), x + 28, iy, w - 92, withA(TEXT, a), false);
                        String cnt = "×" + e.getValue();
                        g.drawString(font, cnt, x + w - 46, iy, withA(dup ? WARN : FAINT, a), false);
                    }
                }
                iy += 22;
                ticketContentBottom += 22;
            }
        }
        g.disableScissor();
        ticketRowsBottom = rowsBottom;   // 滚动 clamp 用（GUI 审计#5：横幅出现时 render 侧才是真值）
        int maxScroll = Math.max(0, ticketContentBottom - rowsBottom);
        if (maxScroll > 0) {
            String hint = ticketScroll <= 0 ? "↓" : (ticketScroll >= maxScroll ? "↑" : "↑↓");
            g.drawString(font, hint, x + w - font.width(hint) - 10, ty + tickH - 13, withA(FAINT, a), false);
        }
    }

    private int ticketScroll;
    private int ticketContentBottom;
    private int ticketRowsBottom;           // render 实测可视区底端（滚动 clamp 用）
    private int purgeBtnX = -1, purgeBtnY;
    private boolean purgeConfirm;          // dry 预览后的确认态
    private String purgeMsg;               // 横幅消息（复用 watch banner 上方）
    private boolean purgeMsgOk;

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

    /** 后台扫描未完成：脉冲圆点 + 自动重试提示 */
    private void scanningCard(GuiGraphics g, int x, int y, int a) {
        roundRect(g, x, y + 22, 240, 46, 10, CARD, BORDER | a);
        long t = Util.getMillis();
        float phase = (t % 1200) / 1200f;
        int alphaPulse = (int) (140 + 100 * Math.sin(phase * Math.PI));
        dot(g, x + 24, y + 44, 4, (alphaPulse << 24) | (WARN & 0x00FFFFFF));
        g.drawString(font, L("sablefix.panel.scanning"), x + 36, y + 38, withA(DIM, a), false);
        g.drawString(font, L("sablefix.panel.scanning_retry"), x + 16, y + 52, withA(FAINT, a), false);
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
        if (data.scanning()) { scanningCard(g, x, y, a); return; }
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
        int qavail = halfW - 16;
        float qScale = font.width(qtext) * 1.1f <= qavail ? 1.1f : Math.max(0.75f, qavail / (float) font.width(qtext));
        int qw = (int) (font.width(qtext) * qScale);
        g.pose().pushPose();
        g.pose().translate(x + 10 + halfW / 2f - qw / 2f, qy + 14 - 7 * qScale, 0);
        g.pose().scale(qScale, qScale, 1);
        g.drawString(font, qtext, 0, 0, withA(ACC, a), true);
        g.pose().popPose();

        boolean tgHov = inBox(mx, my, x + 20 + halfW, qy, halfW, 28);
        tgBtnX = x + 20 + halfW; tgBtnY = qy; tgBtnW = halfW; tgBtnH = 28;
        roundRect(g, tgBtnX, tgBtnY, tgBtnW, tgBtnH, 8, tgHov ? 0x3358A6FF : 0x2258A6FF, ACC);
        boolean copied = tgCopiedAt > 0 && Util.getMillis() - tgCopiedAt < 2000;
        String ttext = copied ? L("sablefix.panel.about_tg_copied") : L("sablefix.panel.about_tg");
        // 字号自适应：1.1 倍放得下就放大，否则缩到正好（最低 0.75）
        int tavail = tgBtnW - 16;
        float tScale = font.width(ttext) * 1.1f <= tavail ? 1.1f : Math.max(0.75f, tavail / (float) font.width(ttext));
        int tw = (int) (font.width(ttext) * tScale);
        g.pose().pushPose();
        g.pose().translate(tgBtnX + tgBtnW / 2f - tw / 2f, tgBtnY + 14 - 7 * tScale, 0);
        g.pose().scale(tScale, tScale, 1);
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
        if (ms < 0) ms = 0;   // 时钟回拨防护（回归自审#10：负值会打出 "-3-05" 乱串）
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? L("sablefix.panel.dur_h", h, String.format("%02d", m))
                     : L("sablefix.panel.dur_ms", m, String.format("%02d", sec));
    }
}
