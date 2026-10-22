package dev.sfx.sablefix.net;

import dev.sfx.sablefix.SableFixMod;
import dev.sfx.sablefix.scan.SubLevelRepair;
import dev.sfx.sablefix.scan.SubLevelScan;
import dev.sfx.sablefix.scan.TicketPurge;
import dev.sfx.sablefix.scan.TicketScan;
import dev.sfx.sablefix.watchdog.ThreadWatchdog;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** 服务端网络包处理：全部在主线程执行，扫描结果回发。 */
public final class ServerHandlers {

    private ServerHandlers() {}

    /** 文件体检缓存：面板打开很频繁，589 个文件不该每次都在主线程重扫。
     *  首次请求同步扫一次；之后 TTL 内直接用缓存，过期由后台线程重扫。 */
    private static final long CACHE_TTL_MS = 60_000L;
    private static final AtomicReference<CachedScan> SCAN_CACHE = new AtomicReference<>();
    private static final AtomicLong SCAN_RUNNING = new AtomicLong();
    /** 扫描专用单线程守护池：不占公共 ForkJoinPool（大存档扫描不饿饿其他并行流任务），守护线程随服务器退出自动消亡 */
    private static final java.util.concurrent.ExecutorService SCAN_POOL =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "SableFix-Scan");
                t.setDaemon(true);
                return t;
            });
    /** 失败冷却：首扫/重扫抛异常后，30s 内不再重试（防止坏存档导致无限失败循环），给用户真实错误而非永久 scanning */
    private static final long SCAN_FAILURE_COOLDOWN_MS = 30_000L;
    // 九轮审计#P0：初始值 0L 表示“从未失败，冷却已过期”。若用 Long.MIN_VALUE，
    // System.currentTimeMillis() - Long.MIN_VALUE 会 long 溢出为负，使 coolingDown
    // 永远为 true → 面板永远返回 SCAN_FAILED/COLD_SCAN 空报告，永不重试真扫描。
    private static volatile long lastScanFailureAt = 0L;

    /** 十轮审计#31：扫描会话代号。SCAN_POOL 是 static 单线程池，跨服务器实例存活——
     *  上一个存档排入/正在运行的扫描任务会把**旧存档的报告**写回静态 SCAN_CACHE，
     *  把 resetScanCache() 刚清干净的缓存重新污染（面板显示别的存档的文件名、损伤数），
     *  并且它 finally 里的 SCAN_RUNNING.set(0) 会**提前释放新会话的闸门**。
     *  每次开服自增；任务捕获入队时的代号，写缓存前核对，不一致就丢弃结果。 */
    private static final java.util.concurrent.atomic.AtomicLong SCAN_EPOCH =
            new java.util.concurrent.atomic.AtomicLong();

    /** 通用异步任务入口：命令的磁盘扫描走 SCAN_POOL（同池与启动体检/面板扫描天然互斥串行）。 */
    public static void runAsync(Runnable task) {
        SCAN_POOL.execute(() -> {
            try {
                task.run();
            } catch (Exception e) {
                SableFixMod.LOGGER.warn("async task failed", e);
            }
        });
    }

    /** 服务器启动时清空扫描缓存（十轮审计#18）。
     *
     *  <p>为什么必须清：SCAN_CACHE 是 static 字段，跨服务器实例存活。同一 JVM 重开服务器
     *  （面板服换存档、单人世界重开——MC 1.21.1 的常态）时，缓存里还是**上一个存档**的报告。
     *  启动扫描是异步的（大存档 ~3.5s），这期间管理员打开面板会看到**别的存档**的文件名、
     *  损伤数、票据数——甚至照着它去修当前存档里**不存在的文件**。
     *  与 ThreadWatchdog 的会话状态重置同族：静态状态必须随服务器实例重置。 */
    public static void resetScanCache() {
        SCAN_CACHE.set(null);
        lastScanFailureAt = 0L;   // 失败冷却同样属于上一个会话
        // 十轮审计#30：SCAN_RUNNING 也必须复位。三个扫描路径在 finally 里 set(0)，
        // 正常流程不会泄漏——但进程在扫描中途被强杀（崩溃/System.exit）、或 halt 时
        // 守护线程被直接掐断，finally 就不执行，SCAN_RUNNING 停在 1。
        // 它又是 static → 下一个服务器实例的所有扫描入口都卡在
        // `!SCAN_RUNNING.compareAndSet(0, 1)` 上直接返回：首次扫描永远不跑、
        // 面板数据永久为空、且**没有任何路径能复位**（只能重启 JVM）。
        // 服务器实例启动即代表上一个会话彻底结束，此时复位是无条件的正确答案。
        SCAN_RUNNING.set(0);
        // 十轮审计#31：推进会话代号——上一个存档排入/正在跑的扫描任务写缓存前会核对它，
        // 不一致就丢弃结果（见 SCAN_EPOCH 注释），不会污染本会话的缓存。
        SCAN_EPOCH.incrementAndGet();
    }

    /** 当前会话代号（扫描任务入队时捕获，写缓存前核对）。 */
    private static long scanEpoch() {
        return SCAN_EPOCH.get();
    }

    /** 命令 tab 补全用：从面板缓存读问题文件名（无缓存/扫描中返回空——面板一开即预热）。
     *  原实现每次按键同步全量 scan（大存档 3.5s/键）。 */
    public static java.util.List<String> cachedProblemFileNames() {
        CachedScan c = SCAN_CACHE.get();
        if (c == null || c == COLD_SCAN || c == SCAN_FAILED) return List.of();
        var names = new ArrayList<String>(c.report().slvlr().size() + c.report().slvls().size());
        for (SubLevelScan.SlvlIssue i : c.report().slvlr()) names.add(i.file().getFileName().toString());
        for (SubLevelScan.SlvlsIssue i : c.report().slvls()) names.add(i.file().getFileName().toString());
        return names;
    }

    /** 补全缓存预热（可用性：纯命令流用户没开过面板时 tab 拿不到建议）。
     *  幂等：已有缓存或已在扫描则跳过；异步不阻塞输入，下一次按键命中。 */
    public static void warmScanCache(MinecraftServer server) {
        if (SCAN_CACHE.get() != null || !SCAN_RUNNING.compareAndSet(0, 1)) return;
        Path sublevels = worldDir(server).resolve("sublevels");
        final long epoch = scanEpoch();   // 十轮审计#31：入队即定代号
        SCAN_POOL.execute(() -> {
            try {
                CachedScan fresh = buildScan(sublevels, server);
                // 代号变了 = 已经换过服务器实例，这份结果是别的存档的 → 丢弃
                if (scanEpoch() != epoch) return;
                SCAN_CACHE.set(fresh);
            } catch (Exception e) {
                // 十轮审计#17：与首扫/重扫路径一致地记失败时刻（冷却判断用）
                if (scanEpoch() == epoch) lastScanFailureAt = System.currentTimeMillis();
                SableFixMod.LOGGER.warn("补全预热扫描失败", e);
            } finally {
                // 只在仍属本会话时释放闸门：过期任务若贸然 set(0)，会提前放行新会话正在跑的扫描
                if (scanEpoch() == epoch) SCAN_RUNNING.set(0);
            }
        });
    }

    /** 启动体检后台化（审计#1）：原在 ServerStarted 主线程同步跑，大存档阻塞开服 ~3.5s。
     *  与面板扫描共用 SCAN_POOL 天然互斥；体检结果进 DamageRegistry/ModLog（均 synchronized）。
     *  修复：结果同时填 SCAN_CACHE——开服扫的那次成果对面板可见（原实现扫完即丢，
     *  面板打开还要整个重扫一遍，用户看到的是"扫描中"白等）。 */
    public static void runStartupScanAsync(MinecraftServer server) {
        // 十轮审计#55：代号必须**入队时**捕获（在主线程、init() 之后），不能在任务体里捕获——
        // 任务体里捕获时，旧存档的任务可能已在新会话 init() 之后才开始跑 → 会拿到**新**代号
        // → 守卫失效。onServerStarted 里 init() 先于本调用，故此处拿到的就是本会话代号。
        final long session = dev.sfx.sablefix.scan.DamageRegistry.session();
        // 十轮审计#56：日志缓冲同构 —— 旧存档的体检日志不得写进新会话的日志页
        final long logSession = dev.sfx.sablefix.log.ModLog.session();
        SCAN_POOL.execute(() -> {
            // 把本线程绑定到捕获的会话。体检里的 register/clear 会核对绑定代号是否仍有效
            // → 换存档后旧体检的写入被忽略，不会用别的存档的损伤污染本会话的登记簿，
            // 也不会抹掉本会话刚登记的真损伤。
            // 必须 finally 解绑：SCAN_POOL 是**复用**线程，不解绑会让下一次任务带着旧代号。
            dev.sfx.sablefix.scan.DamageRegistry.bindSession(session);
            dev.sfx.sablefix.log.ModLog.bindSession(logSession);
            try {
            try {
                SubLevelScan.startupScan(server);
            } catch (Exception e) {
                SableFixMod.LOGGER.warn("启动子层级体检任务异常", e);
            }
            try {
                TicketScan.startupScan(server);
            } catch (Exception e) {
                SableFixMod.LOGGER.warn("启动票据体检任务异常", e);
            }
            // 启动扫描结果直接入缓存：面板打开瞬间就有全量数据（60s TTL 内免重扫）
            if (SCAN_RUNNING.compareAndSet(0, 1)) {
                // 十轮审计#54：补 epoch 检查（#31 的设计在**这条路径上被漏掉**）。
                // SCAN_POOL 是 static 单线程池、跨会话存活：上一个存档排入的启动扫描
                // 可能在本会话启动后才跑完 → 它会把**别的存档**的报告写进 SCAN_CACHE，
                // 而且 finally 里的 SCAN_RUNNING.set(0) 会**提前放行**本会话正在跑的扫描闸门。
                // 与 warmScanCache / 面板重扫两处一致：入队即定代号，写缓存前核对。
                final long epoch = scanEpoch();
                try {
                    Path sublevels = worldDir(server).resolve("sublevels");
                    CachedScan fresh = buildScan(sublevels, server);
                    if (scanEpoch() != epoch) return;   // 换过存档 → 丢弃本次结果
                    SCAN_CACHE.set(fresh);
                } catch (Exception e) {
                    // 十轮审计#17：与首扫/重扫/预热路径一致地记失败时刻（冷却判断用）
                    if (scanEpoch() == epoch) lastScanFailureAt = System.currentTimeMillis();
                    SableFixMod.LOGGER.warn("启动扫描入缓存失败（面板打开时会重扫）", e);
                } finally {
                    if (scanEpoch() == epoch) SCAN_RUNNING.set(0);
                }
            }
            } finally {
                dev.sfx.sablefix.scan.DamageRegistry.unbindSession();
                dev.sfx.sablefix.log.ModLog.unbindSession();
            }
        });
    }

    private record CachedScan(SubLevelScan.Report report, List<String> ticketUuids,
                              int ticketCount, long at,
                              List<NetHandler.PanelData.BackupRow> backups) {}

    /** 首扫未完成时回给面板的占位报告（客户端据 scanning 标志显示"扫描中"并自动重试）。 */
    private static final CachedScan COLD_SCAN = new CachedScan(
            new SubLevelScan.Report(List.of(), List.of(), 0, 0), List.of(), 0, 0L, List.of());

    /** 首扫失败冷却期的占位（ticketCount=-2；客户端据 scanning=false + 空 files 显示冷却提示，服务器日志有真实错误）。 */
    private static final CachedScan SCAN_FAILED = new CachedScan(
            new SubLevelScan.Report(List.of(), List.of(), 0, 0), List.of(), -2, 0L, List.of());

    public static void onPanelReq(IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        // 与 /sablefix 命令同级权限：文件名/票据/日志属诊断信息，不对普通玩家开放
        if (!player.hasPermissions(2)) {
            player.displayClientMessage(Component.translatable("sablefix.perm.denied"), false);
            return;
        }
        try {
            MinecraftServer server = player.getServer();
            Path sublevels = worldDir(server).resolve("sublevels");

            CachedScan cached = SCAN_CACHE.get();
            // 修复：SCAN_FAILED.at=Long.MIN_VALUE 使"过期重扫"分支的
            // now - at 溢出为负 → 永远不过期 → 冷却结束后依然永远回 FAILED 空报告，
            // 客户端无限退避（用户报告"一直扫描中"的根因）。
            // 冷却结束后应允许立即重试真扫描。
            boolean failedCold = cached == SCAN_FAILED;
            boolean coolingDown = System.currentTimeMillis() - lastScanFailureAt < SCAN_FAILURE_COOLDOWN_MS;
            if (cached == null || (failedCold && !coolingDown)) {
                if (!coolingDown && SCAN_RUNNING.compareAndSet(0, 1)) {
                    final long epoch = scanEpoch();   // 十轮审计#31：入队即定代号
                    SCAN_POOL.execute(() -> {
                        try {
                            CachedScan fresh = buildScan(sublevels, server);
                            if (scanEpoch() != epoch) return;   // 换过存档 → 丢弃本次结果
                            SCAN_CACHE.set(fresh);
                        } catch (Exception e) {
                            if (scanEpoch() == epoch) lastScanFailureAt = System.currentTimeMillis();
                            SableFixMod.LOGGER.warn("后台首扫失败（30s 冷却）", e);
                        } finally {
                            if (scanEpoch() == epoch) SCAN_RUNNING.set(0);
                        }
                    });
                }
                cached = coolingDown ? SCAN_FAILED : COLD_SCAN;
            } else if (!failedCold && System.currentTimeMillis() - cached.at() > CACHE_TTL_MS
                    && SCAN_RUNNING.compareAndSet(0, 1)) {
                // 过期：立即回旧数据 + 后台重扫（扫描纯文件 IO，不碰 MC API，线程安全）
                final long epoch = scanEpoch();   // 十轮审计#31：入队即定代号
                SCAN_POOL.execute(() -> {
                    try {
                        CachedScan fresh = buildScan(sublevels, server);
                        if (scanEpoch() != epoch) return;   // 换过存档 → 丢弃本次结果
                        SCAN_CACHE.set(fresh);
                    } catch (Exception e) {
                        // 十轮审计#17：失败后必须推进 TTL 起点，否则缓存的 at 停在过去 →
                        // 下一个面板请求又判定"已过期" → 再次重扫 → **无限重扫循环**
                        // （每次 3.5s CPU，大存档持续烧 CPU；L47 注释里的失败冷却只覆盖首扫）。
                        // 旧数据此时仍然有效（扫描失败多是文件被占用等临时问题）→ 保留旧报告，
                        // 只把 TTL 起点重置到现在（60s 内不再重试），并记失败时刻供冷却判断。
                        // 十轮审计#31：跨会话的失败不该写本会话的冷却/缓存。
                        if (scanEpoch() == epoch) {
                            lastScanFailureAt = System.currentTimeMillis();
                            CachedScan old = SCAN_CACHE.get();
                            if (old != null) {
                                SCAN_CACHE.set(new CachedScan(old.report(), old.ticketUuids(),
                                        old.ticketCount(), System.currentTimeMillis(), old.backups()));
                            }
                        }
                        SableFixMod.LOGGER.warn("后台重扫失败（沿用旧缓存，60s 后重试）", e);
                    } finally {
                        if (scanEpoch() == epoch) SCAN_RUNNING.set(0);
                    }
                });
            }
            SubLevelScan.Report r = cached.report();

            List<NetHandler.PanelData.FileRow> files = new ArrayList<>();
            for (SubLevelScan.SlvlIssue i : r.slvlr()) {
                files.add(new NetHandler.PanelData.FileRow(i.file().getFileName().toString(), true,
                        i.size(), i.livePointers(), i.deadPointers()));
            }
            for (SubLevelScan.SlvlsIssue i : r.slvls()) {
                files.add(new NetHandler.PanelData.FileRow(i.file().getFileName().toString(), false,
                        i.size(), -1, i.badEntries()));
            }
            int healthy = r.scannedSlvlr() + r.scannedSlvls() - r.slvlr().size() - r.slvls().size();
            if (healthy > 0) {
                // 十轮审计#23：汇总行**不再在服务端预渲染**（服务端 Language 只有 en_us，
                // .getString() 永远返回英文 → 中文客户端看到 "remaining N files all healthy"）。
                // 改为：name 置空、live 传健康数 → 客户端用本地语言渲染 browse_healthy。
                //  字段格式未变（FileRow 结构不动），但**语义变了** → 按既定原则 bump
                //  PROTOCOL_VERSION（6→7）：旧客户端连新服会被 NeoForge 握手期明确拒绝，
                //  而不是静默显示一行空白。
                // 六审#3：汇总行置顶（原 add 尾部——files 超 512 截断保头时"全部健康"提示被静默切掉）
                files.add(0, new NetHandler.PanelData.FileRow("", true, -1, healthy, 0));
            }

            int ticketCount = cached.ticketCount();
            List<String> uuids = cached.ticketUuids();

            List<NetHandler.PanelData.DimRow> dims = new ArrayList<>();
            for (ServerLevel level : server.getAllLevels()) {
                dims.add(new NetHandler.PanelData.DimRow(
                        level.dimension().location().toString(),
                        countEntities(level),
                        -1,
                        level.getChunkSource().getLoadedChunksCount()));
            }

            // 十轮审计#60：用**服务器实例**运行时长，而不是 JVM 运行时长。
            // 面板服换存档时 JVM 常驻 → 原来显示「已运行 24 小时」而服务器只开了 5 分钟。
            long uptime = SableFixMod.serverUptimeMs();

            var logSnap = dev.sfx.sablefix.log.ModLog.snapshot();
            var logLines = new java.util.ArrayList<NetHandler.PanelData.LogRow>(logSnap.size() + 1);
            // 损伤登记簿摘要前置：OP 开面板即见当前损伤状态（不用翻控制台）
            var dmg = dev.sfx.sablefix.scan.DamageRegistry.snapshot();
            if (!dmg.isEmpty()) {
                logLines.add(new NetHandler.PanelData.LogRow("WARN",
                        java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS")),
                        null, java.util.List.of(),
                        "未处置损伤 " + dmg.size() + " 项："
                                + dmg.stream().map(e -> e.file + "×" + e.count).limit(3)
                                        .collect(java.util.stream.Collectors.joining(", "))
                                + (dmg.size() > 3 ? " …" : "")));
            }
            // 九轮审计#1：可翻译条目下发 key+args，客户端按玩家语言渲染（服务端只装 en_us，
            // 服务端预渲染会把中文服面板日志变成英文）；纯文本条目原样透传。
            for (var e : logSnap) {
                if (e.translatable()) {
                    // 十轮审计#15：args 曾可能为 null（无参可翻译条目）→ e.args().stream() NPE
                    // → 整个面板请求失败（启动体检必写一条无参条目 → 每次开面板必现）。
                    // ModLog 侧已改为「args 永不为 null」，这里再兜一层（纵深防御）。
                    var ea = e.args();
                    logLines.add(new NetHandler.PanelData.LogRow(e.level(), e.time(), e.key(),
                            ea == null ? java.util.List.of() : ea.stream().map(String::valueOf).toList(), ""));
                } else {
                    // 十轮审计#33：msg 统一为时间戳，正文走 body（原先两者拼在 msg 里，
                    // 客户端靠找 ']' 猜切分点 → 正文含 ']' 时错位，且两类行渲染形态不一致）
                    logLines.add(new NetHandler.PanelData.LogRow(e.level(), e.time(),
                            null, java.util.List.of(), e.msg()));
                }
            }

            // 三轮审计#4/#5：服务端算好相对时长下发（lastStallAgoMs），绕 NTP 失步的客户端时钟
            long stallAt = ThreadWatchdog.lastStallAtMs();
            long stallAgo = stallAt == 0 ? -1 : Math.max(0, System.currentTimeMillis() - stallAt);
            NetHandler.PanelData data = new NetHandler.PanelData(
                    files, files.size(),
                    ticketCount, uuids,
                    ThreadWatchdog.lastTickGapMs(), ThreadWatchdog.dumpCount(), uptime, dims,
                    logLines, logLines.size(), cached == COLD_SCAN || cached == SCAN_FAILED,
                    stallAgo,
                    cached.backups(),
                    // 十轮审计#73：备份**总数**单独下发（列表在下发侧截断到 8 条）。
                    // 占位缓存（COLD/FAILED）的 backups 为空 → 总数 0。
                    cached.backups().size());
            ctx.reply(data);
        } catch (Exception e) {
            SableFixMod.LOGGER.error("面板数据生成失败", e);
            // 十轮审计#4：null 参数在非 production 环境被 TranslatableContents 构造器拒绝
            ctx.player().displayClientMessage(Component.translatable("sablefix.panel.fail",
                    e.getMessage() == null ? "" : e.getMessage()), false);
        }
    }

    /** 一次完整文件体检（子层级 + 票据），可在线程池调用：只做文件 IO，不碰 MC 运行时状态。 */
    private static CachedScan buildScan(Path sublevels, MinecraftServer server) throws Exception {
        SubLevelScan.Report report = SubLevelScan.scan(sublevels);
        int ticketCount = -1;
        List<String> uuids = List.of();
        try {
            Path tf = TicketScan.ticketFile(server);
            if (Files.exists(tf)) {
                TicketScan.TicketInfo t = TicketScan.read(tf);
                ticketCount = t.count();
                uuids = t.uuids().stream().map(Object::toString).toList();
            } else {
                ticketCount = 0;   // 文件不存在 = 0 条，不是错误
            }
        } catch (Exception e) {
            SableFixMod.LOGGER.warn("面板读票据失败", e);
        }
        // 备份清单（备份管理页）：backups/sablefix 下的 .bak 文件，按时间倒序取前 64
        List<NetHandler.PanelData.BackupRow> backups = listBackups(server);
        return new CachedScan(report, uuids, ticketCount, System.currentTimeMillis(), backups);
    }

    /** 收集 backups/sablefix 备份清单（修复/清理的自动备份）。目录读取廉价（<几十条文件名），
     *  随扫描缓存一起走——缓存 TTL 内多次开面板零重复 IO。失败静默空清单（备份页显示"无备份"）。 */
    /** 对外：面板读取备份清单（缓存 TTL 60s，删除后下次打开面板/请求面板数据即刷新）。 */
    public static List<NetHandler.PanelData.BackupRow> listBackups(MinecraftServer server) {
        try {
            Path world = worldDir(server);
            Path backupDir = world.resolve("backups").resolve("sablefix");
            if (!Files.isDirectory(backupDir)) return List.of();
            long now = System.currentTimeMillis();
            List<NetHandler.PanelData.BackupRow> out = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(backupDir, "*.bak")) {
                for (Path p : ds) {
                    try {
                        long at = Files.getLastModifiedTime(p).toMillis();
                        // 三轮审计#4：ageMs 服务端算好（客户端时钟 NTP 失步不再显示错乱）
                        out.add(new NetHandler.PanelData.BackupRow(
                                p.getFileName().toString(),
                                Files.size(p),
                                Math.max(0, now - at)));
                    } catch (IOException ignored) {}
                }
            }
            out.sort((a, b) -> Long.compare(a.ageMs(), b.ageMs()));   // 新→旧（age 小=新）
            // 十轮审计#73：不再在此截断到 64——截断值会让「共 N 个备份」（命令）与
            // 「还有 N 条」（面板）都拿不到真实总数（真实 200 份时两边都只显示 64）。
            // 全量返回：命令侧显示真实总数；面板侧的下发截断在 NetHandler 写侧
            // （MAX_BACKUPS_SENT=8，客户端只画 8 条）+ backupTotal 字段给真实总数。
            // 目录里几百个文件名的 list 是廉价 IO（微秒级），全量无性能问题。
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 删除单个备份文件（仅允许 backups/sablefix 下的纯文件名，防路径遍历），不存在抛 NoSuchFileException。 */
    public static void deleteBackup(MinecraftServer server, String name) throws IOException {
        // 十轮审计#20：原实现只黑名单 '/' 与 '\\' → 盘符写法可逃逸。
        // 十轮审计#44 **更正**该注释的结论（探针实测，勿凭直觉）：
        //   Path.resolve("C:server.properties") 在 Windows 上**不会**返回 "C:server.properties"，
        //   JDK 在 resolve 时**丢弃盘符前缀** → 结果是 <base>\server.properties（不逃逸）。
        //   真正会逃逸的是 "C:..\..\evil.txt"（→ <base>\..\..\evil.txt）和 "C:\abs.txt"
        //   （→ 绝对路径），两者都被 startsWith 白名单拦住 ✅。
        //   另加 ':' 黑名单（盘符与 NTFS 备用数据流 x::$DATA）、"."/".." 显式拒绝。
        if (name == null || name.isEmpty()
                || name.contains("/") || name.contains("\\") || name.contains(":")
                || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("非法备份文件名: " + name);
        }
        Path backupDir = worldDir(server).resolve("backups").resolve("sablefix");
        // 十轮审计#44：dir 也要**归一化**（同 onRepairReq）——只归一化 target、拿未归一化的
        // dir 去比 → dir 含 ".." 时（--world-path 带相对段即可出现）startsWith 恒 false
        // → **所有备份都删不掉**。两侧都归一化后再比，穿越防护不受影响。
        backupDir = backupDir.normalize();
        Path target = backupDir.resolve(name).normalize();
        if (!target.startsWith(backupDir) || !Files.isRegularFile(target)) {
            throw new java.nio.file.NoSuchFileException(target.toString());
        }
        Files.delete(target);
        SableFixMod.LOGGER.info("backup deleted: {}", target);
    }

    /** 清空 backups/sablefix 下全部 .bak，返回删除数量。
     *  十轮审计#43：单个文件删不掉（被占用/权限）**不能中断整批**——原实现直接
     *  Files.delete(p)，一个被占用的备份就让整批中断，后面的备份**一个都没删**，
     *  而命令回报失败 → 用户重试又撞同一个被占用的文件 → 后面永远清不掉。
     *  （与十轮审计#35「一个坏文件不能中断整批」同族。）
     *  现在：单个失败记日志并继续；**全部**都失败才抛（让用户知道确实有问题）。 */
    public static int purgeBackups(MinecraftServer server) throws IOException {
        Path backupDir = worldDir(server).resolve("backups").resolve("sablefix");
        if (!Files.isDirectory(backupDir)) return 0;
        int n = 0;
        int failed = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(backupDir, "*.bak")) {
            for (Path p : ds) {
                try {
                    Files.delete(p);
                    n++;
                } catch (IOException e) {
                    // 被占用/权限不足：跳过它，继续删其余的
                    failed++;
                    SableFixMod.LOGGER.warn("backup purge: {} 删除失败（被占用或权限不足，继续其余）",
                            p.getFileName(), e);
                }
            }
        }
        SableFixMod.LOGGER.info("backup purge: {} file(s) deleted, {} failed", n, failed);
        // 一个都没删成且确实有文件 → 抛出去（命令层给「被占用」提示）
        if (n == 0 && failed > 0) {
            throw new java.nio.file.FileSystemException(backupDir.toString(), null,
                    failed + " 个备份文件被占用或权限不足，未能删除");
        }
        return n;
    }

    /** GUI 修复请求：dry 预览 / apply 备份+清零。全部主线程执行（文件小、耗时毫秒级）。 */
    public static void onRepairReq(NetHandler.RepairReq req, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        if (!player.hasPermissions(2)) {
            ctx.reply(new NetHandler.RepairRes(false, req.apply(), req.file(), 0, "", "",
                    "sablefix.perm.denied", ""));
            return;
        }
        try {
            MinecraftServer server = player.getServer();
            Path dir = worldDir(server).resolve("sublevels");
            // 十轮审计#44：dir 也要**归一化**（与 SfxCommands.repair 同构）。只归一化 target、
            // 拿未归一化的 dir 去比 → dir 含 ".." 时（--world-path 带相对段即可出现）
            // startsWith 恒 false → **所有面板修复请求都被判越界** → 修复功能不可用。
            dir = dir.normalize();
            Path target = dir.resolve(req.file()).normalize();
            if (!target.startsWith(dir) || !Files.exists(target)) {
                ctx.reply(new NetHandler.RepairRes(false, req.apply(), req.file(), 0, "", "",
                        "sablefix.cmd.repair_no_file", req.file()));
                return;
            }
            SubLevelRepair.RepairPlan plan = SubLevelRepair.plan(target);
            if (plan.isEmpty()) {
                ctx.reply(new NetHandler.RepairRes(true, false, req.file(), 0, "", "",
                        "sablefix.cmd.repair_nothing", req.file()));
                return;
            }
            if (!req.apply()) {
                // 三轮审计#2：preview 超 256 时 writeUtf(256) 抛异常断连；且警示文案按服务器
                // locale 渲染违反 errorKey 客户端本地化原则。改为：note 走 errKey（客户端本地化），
                // preview 纯槽位列表服务端硬截 240 保安全。
                String preview = plan.slots().stream().map(String::valueOf)
                        .limit(20).collect(java.util.stream.Collectors.joining(", "))
                        + (plan.slots().size() > 20 ? " …" : "");
                if (preview.length() > 240) preview = preview.substring(0, 240) + "…";
                ctx.reply(new NetHandler.RepairRes(true, false, req.file(), plan.slots().size(),
                        preview, "", "sablefix.panel.repair_note_live", ""));
                return;
            }
            SubLevelRepair.RepairResult res = SubLevelRepair.apply(plan);
            SCAN_CACHE.set(null);   // 修复后缓存作废，下次面板请求重扫
            // DamageRegistry 闭环：GUI 修复成功同步清登记（否则 5min WARN 继续报已修复项）
            String rf = target.getFileName().toString();
            if (res.cleared() > 0) dev.sfx.sablefix.scan.DamageRegistry.clear(
                    dev.sfx.sablefix.scan.DamageRegistry.Kind.SUBLVL_DEAD, rf);
            ctx.reply(new NetHandler.RepairRes(true, true, req.file(), res.cleared(), "",
                    res.backup().toString(), "", ""));
            dev.sfx.sablefix.log.ModLog.warn("面板修复：" + req.file() + " 清零 " + res.cleared()
                    + " 槽（备份 " + res.backup() + "）");
        } catch (java.nio.file.FileSystemException e) {
            ctx.reply(new NetHandler.RepairRes(false, true, req.file(), 0, "", "",
                    "sablefix.cmd.repair_locked", req.file()));
        } catch (java.io.FileNotFoundException e) {
            // Windows"可读共享"句柄：备份(copy)成功但 raf("rw") 抛 FileNotFoundException——
            // 兼容性审计：原走通用 fail，玩家看到 repair_fail 而非明确的"停服后操作"提示
            ctx.reply(new NetHandler.RepairRes(false, true, req.file(), 0, "", "",
                    "sablefix.cmd.repair_locked", req.file()));
        } catch (Exception e) {
            SableFixMod.LOGGER.error("panel repair failed: {}", req.file(), e);
            ctx.reply(new NetHandler.RepairRes(false, req.apply(), req.file(), 0, "", "",
                    "sablefix.cmd.repair_fail", req.file() + ": " + e.getMessage()));
        }
    }

    /** GUI 票据清理：dry 预览条数 / apply 备份+清空。主线程执行（单文件 NBT 读写，毫秒级）。 */
    public static void onPurgeReq(NetHandler.PurgeReq req, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        if (!player.hasPermissions(2)) {
            ctx.reply(new NetHandler.PurgeRes(false, req.apply(), 0, "", "sablefix.perm.denied"));
            return;
        }
        try {
            MinecraftServer server = player.getServer();
            Path tf = TicketScan.ticketFile(server);
            if (!Files.exists(tf)) {
                ctx.reply(new NetHandler.PurgeRes(true, false, 0, "", "sablefix.cmd.tickets_none"));
                return;
            }
            int n = TicketPurge.count(tf);
            if (n == 0) {
                ctx.reply(new NetHandler.PurgeRes(true, false, 0, "", "sablefix.cmd.purge_zero"));
                return;
            }
            if (!req.apply()) {
                ctx.reply(new NetHandler.PurgeRes(true, false, n, "", ""));
                return;
            }
            Path backup = TicketPurge.purge(tf);
            SCAN_CACHE.set(null);   // 票据数据已变，作废面板缓存
            // DamageRegistry 闭环：GUI 清理成功同步清登记
            dev.sfx.sablefix.scan.DamageRegistry.clear(
                    dev.sfx.sablefix.scan.DamageRegistry.Kind.TICKETS_STUCK,
                    tf.getFileName().toString());
            ctx.reply(new NetHandler.PurgeRes(true, true, n, backup.toString(), ""));
            dev.sfx.sablefix.log.ModLog.warn("面板票据清理：原 " + n + " 条已清空（备份 " + backup + "）");
        } catch (java.nio.file.FileSystemException e) {
            ctx.reply(new NetHandler.PurgeRes(false, true, 0, "", "sablefix.cmd.purge_locked"));
        } catch (java.io.FileNotFoundException e) {
            // 兼容性审计：Windows 可读共享句柄下的占用——locked 语义
            ctx.reply(new NetHandler.PurgeRes(false, true, 0, "", "sablefix.cmd.purge_locked"));
        } catch (Exception e) {
            SableFixMod.LOGGER.error("panel purge failed", e);
            ctx.reply(new NetHandler.PurgeRes(false, req.apply(), 0, "", "sablefix.cmd.purge_fail"));
        }
    }

    public static void onDiagReq(NetHandler.DiagReq req, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        if (!player.hasPermissions(2)) {
            // 与 repair/purge 统一：走 errorKey 回包，客户端本地化（服务器 locale 不串台）
            ctx.reply(new NetHandler.DiagRes(req.x(), req.z(), false, "sablefix.perm.denied",
                    0, 0, player.serverLevel().dimension().location().toString()));
            return;
        }
        try {
            ServerLevel level = player.serverLevel();
            int cx = req.x() >> 4, cz = req.z() >> 4;
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            boolean loaded = chunk != null;
            int entities = 0, blockEntities = 0;
            // status 由客户端按 loaded 本地化（服务器 locale ≠ 客户端语言时不串台）
            if (loaded) {
                AABB box = new AABB(cx * 16.0, level.getMinBuildHeight(), cz * 16.0,
                        cx * 16.0 + 16, level.getMaxBuildHeight(), cz * 16.0 + 16);
                entities = level.getEntities(EntityTypeTest.forClass(Entity.class), box, e -> true).size();
                blockEntities = chunk.getBlockEntities().size();
            }
            ctx.reply(new NetHandler.DiagRes(req.x(), req.z(), loaded, "", entities, blockEntities,
                    level.dimension().location().toString()));
        } catch (Exception e) {
            SableFixMod.LOGGER.error("诊断失败", e);
            // 失败也要回包：客户端 diagRes==null 只会一直显示"等待查询"，永远不知道出错了
            ctx.reply(new NetHandler.DiagRes(req.x(), req.z(), false,
                    "sablefix.diag.error",
                    0, 0, player.serverLevel().dimension().location().toString()));
        }
    }

    private static int countEntities(ServerLevel level) {
        int[] n = {0};
        level.getEntities().getAll().forEach(e -> n[0]++);
        return n[0];
    }

    public static Path worldDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT);
    }
}
