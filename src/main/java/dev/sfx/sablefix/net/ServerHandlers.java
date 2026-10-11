package dev.sfx.sablefix.net;

import dev.sfx.sablefix.SableFixMod;
import dev.sfx.sablefix.scan.SubLevelScan;
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

    private record CachedScan(SubLevelScan.Report report, List<String> ticketUuids,
                              int ticketCount, long at) {}

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
            if (cached == null) {
                cached = buildScan(sublevels, server);
                SCAN_CACHE.set(cached);
            } else if (System.currentTimeMillis() - cached.at() > CACHE_TTL_MS
                    && SCAN_RUNNING.compareAndSet(0, 1)) {
                // 过期：立即回旧数据 + 后台重扫（扫描纯文件 IO，不碰 MC API，线程安全）
                CompletableFuture.runAsync(() -> {
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
                files.add(new NetHandler.PanelData.FileRow("（其余 " + healthy + " 个文件全部健康）", true, -1, -1, 0));
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
            for (var e : logSnap) logLines.add(e.time() + " [" + e.level() + "] " + e.msg());

            NetHandler.PanelData data = new NetHandler.PanelData(
                    files, ticketCount, uuids,
                    ThreadWatchdog.lastTickGapMs(), ThreadWatchdog.dumpCount(), uptime, dims,
                    logLines);
            ctx.reply(data);
        } catch (Exception e) {
            SableFixMod.LOGGER.error("面板数据生成失败", e);
            ctx.player().displayClientMessage(Component.literal("[SableFix] 面板数据生成失败: " + e.getMessage()), false);
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
        return new CachedScan(report, uuids, ticketCount, System.currentTimeMillis());
    }

    public static void onDiagReq(NetHandler.DiagReq req, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        if (!player.hasPermissions(2)) {
            player.displayClientMessage(Component.translatable("sablefix.perm.denied"), false);
            return;
        }
        try {
            ServerLevel level = player.serverLevel();
            int cx = req.x() >> 4, cz = req.z() >> 4;
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            boolean loaded = chunk != null;
            int entities = 0, blockEntities = 0;
            String status = loaded ? "FULL 已加载" : "未加载";
            if (loaded) {
                AABB box = new AABB(cx * 16.0, level.getMinBuildHeight(), cz * 16.0,
                        cx * 16.0 + 16, level.getMaxBuildHeight(), cz * 16.0 + 16);
                entities = level.getEntities(EntityTypeTest.forClass(Entity.class), box, e -> true).size();
                blockEntities = chunk.getBlockEntities().size();
            }
            ctx.reply(new NetHandler.DiagRes(req.x(), req.z(), loaded, status, entities, blockEntities,
                    level.dimension().location().toString()));
        } catch (Exception e) {
            SableFixMod.LOGGER.error("诊断失败", e);
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
