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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 服务端网络包处理：全部在主线程执行，扫描结果回发。 */
public final class ServerHandlers {

    private ServerHandlers() {}

    public static void onPanelReq(IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        try {
            MinecraftServer server = player.getServer();
            Path sublevels = worldDir(server).resolve("sublevels");

            SubLevelScan.Report r = SubLevelScan.scan(sublevels);
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

            int ticketCount = -1;
            List<String> uuids = List.of();
            try {
                TicketScan.TicketInfo t = TicketScan.read(TicketScan.ticketFile(server));
                ticketCount = t.count();
                uuids = t.uuids().stream().map(Object::toString).toList();
            } catch (Exception e) {
                SableFixMod.LOGGER.warn("面板读票据失败", e);
            }

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

    public static void onDiagReq(NetHandler.DiagReq req, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
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
