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
    private static volatile long lastScanFailureAt = Long.MIN_VALUE;

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
        SCAN_POOL.execute(() -> {
            try {
                SCAN_CACHE.set(buildScan(sublevels, server));
            } catch (Exception e) {
                SableFixMod.LOGGER.warn("补全预热扫描失败", e);
            } finally {
                SCAN_RUNNING.set(0);
            }
        });
    }

    /** 启动体检后台化（审计#1）：原在 ServerStarted 主线程同步跑，大存档阻塞开服 ~3.5s。
     *  与面板扫描共用 SCAN_POOL 天然互斥；体检结果进 DamageRegistry/ModLog（均 synchronized）。
     *  修复：结果同时填 SCAN_CACHE——开服扫的那次成果对面板可见（原实现扫完即丢，
     *  面板打开还要整个重扫一遍，用户看到的是"扫描中"白等）。 */
    public static void runStartupScanAsync(MinecraftServer server) {
        SCAN_POOL.execute(() -> {
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
                try {
                    Path sublevels = worldDir(server).resolve("sublevels");
                    SCAN_CACHE.set(buildScan(sublevels, server));
                } catch (Exception e) {
                    SableFixMod.LOGGER.warn("启动扫描入缓存失败（面板打开时会重扫）", e);
                } finally {
                    SCAN_RUNNING.set(0);
                }
            }
        });
    }

    private record CachedScan(SubLevelScan.Report report, List<String> ticketUuids,
                              int ticketCount, long at,
                              List<NetHandler.PanelData.BackupRow> backups) {}

    /** 首扫未完成时回给面板的占位报告（客户端据 scanning 标志显示"扫描中"并自动重试）。 */
    private static final CachedScan COLD_SCAN = new CachedScan(
            new SubLevelScan.Report(List.of(), List.of(), 0, 0), List.of(), 0, Long.MIN_VALUE, List.of());

    /** 首扫失败冷却期的占位（ticketCount=-2；客户端据 scanning=false + 空 files 显示冷却提示，服务器日志有真实错误）。 */
    private static final CachedScan SCAN_FAILED = new CachedScan(
            new SubLevelScan.Report(List.of(), List.of(), 0, 0), List.of(), -2, Long.MIN_VALUE, List.of());

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
                    SCAN_POOL.execute(() -> {
                        try {
                            SCAN_CACHE.set(buildScan(sublevels, server));
                        } catch (Exception e) {
                            lastScanFailureAt = System.currentTimeMillis();
                            SableFixMod.LOGGER.warn("后台首扫失败（30s 冷却）", e);
                        } finally {
                            SCAN_RUNNING.set(0);
                        }
                    });
                }
                cached = coolingDown ? SCAN_FAILED : COLD_SCAN;
            } else if (!failedCold && System.currentTimeMillis() - cached.at() > CACHE_TTL_MS
                    && SCAN_RUNNING.compareAndSet(0, 1)) {
                // 过期：立即回旧数据 + 后台重扫（扫描纯文件 IO，不碰 MC API，线程安全）
                SCAN_POOL.execute(() -> {
                    try {
                        SCAN_CACHE.set(buildScan(sublevels, server));
                    } catch (Exception e) {
                        SableFixMod.LOGGER.warn("后台重扫失败（沿用旧缓存）", e);
                    } finally {
                        SCAN_RUNNING.set(0);
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
                // 复用 GUI 的 browse_healthy key：服务端按服务器 locale 渲染，客户端英文界面也能读懂。
                // 六审#3：汇总行置顶（原 add 尾部——files 超 512 截断保头时"全部健康"提示被静默切掉）
                files.add(0, new NetHandler.PanelData.FileRow(
                        net.minecraft.network.chat.Component.translatable("sablefix.panel.browse_healthy", healthy).getString(),
                        true, -1, -1, 0));
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

            long uptime = System.currentTimeMillis() - SableFixMod.START_TIME;

            var logSnap = dev.sfx.sablefix.log.ModLog.snapshot();
            var logLines = new java.util.ArrayList<String>(logSnap.size());
            // 损伤登记簿摘要前置：OP 开面板即见当前损伤状态（不用翻控制台）
            var dmg = dev.sfx.sablefix.scan.DamageRegistry.snapshot();
            if (!dmg.isEmpty()) {
                logLines.add(java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
                        + " [WARN] 未处置损伤 " + dmg.size() + " 项："
                        + dmg.stream().map(e -> e.file + "×" + e.count).limit(3)
                                .collect(java.util.stream.Collectors.joining(", "))
                        + (dmg.size() > 3 ? " …" : ""));
            }
            for (var e : logSnap) logLines.add(e.time() + " [" + e.level() + "] " + e.msg());

            // 三轮审计#4/#5：服务端算好相对时长下发（lastStallAgoMs），绕 NTP 失步的客户端时钟
            long stallAt = ThreadWatchdog.lastStallAtMs();
            long stallAgo = stallAt == 0 ? -1 : Math.max(0, System.currentTimeMillis() - stallAt);
            NetHandler.PanelData data = new NetHandler.PanelData(
                    files, ticketCount, uuids,
                    ThreadWatchdog.lastTickGapMs(), ThreadWatchdog.dumpCount(), uptime, dims,
                    logLines, cached == COLD_SCAN || cached == SCAN_FAILED,
                    stallAgo,
                    cached.backups());
            ctx.reply(data);
        } catch (Exception e) {
            SableFixMod.LOGGER.error("面板数据生成失败", e);
            ctx.player().displayClientMessage(Component.translatable("sablefix.panel.fail", e.getMessage()), false);
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
    private static List<NetHandler.PanelData.BackupRow> listBackups(MinecraftServer server) {
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
            return out.size() > 64 ? out.subList(0, 64) : out;
        } catch (Exception e) {
            return List.of();
        }
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
