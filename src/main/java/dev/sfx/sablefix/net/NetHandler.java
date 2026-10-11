package dev.sfx.sablefix.net;

import dev.sfx.sablefix.SableFixMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;

/** 全部网络包定义 + 注册。C2S=客户端发服务端，S2C=服务端发客户端。 */
public final class NetHandler {

    public static void register(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        PayloadRegistrar reg = event.registrar("1");
        reg.playToServer(PanelReq.TYPE, PanelReq.CODEC, (p, ctx) ->
                ctx.enqueueWork(() -> ServerHandlers.onPanelReq(ctx)));
        reg.playToClient(PanelData.TYPE, PanelData.CODEC, (p, ctx) ->
                ctx.enqueueWork(() -> ClientBridge.onPanelData(p)));
        reg.playToServer(DiagReq.TYPE, DiagReq.CODEC, (p, ctx) ->
                ctx.enqueueWork(() -> ServerHandlers.onDiagReq(p, ctx)));
        reg.playToClient(DiagRes.TYPE, DiagRes.CODEC, (p, ctx) ->
                ctx.enqueueWork(() -> ClientBridge.onDiagRes(p)));
        reg.playToServer(RepairReq.TYPE, RepairReq.CODEC, (p, ctx) ->
                ctx.enqueueWork(() -> ServerHandlers.onRepairReq(p, ctx)));
        reg.playToClient(RepairRes.TYPE, RepairRes.CODEC, (p, ctx) ->
                ctx.enqueueWork(() -> ClientBridge.onRepairRes(p)));
        SableFixMod.LOGGER.info("网络通道已注册");
    }

    private NetHandler() {}

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(SableFixMod.MODID, path);
    }

    /** C2S：请求全量面板数据 */
    public record PanelReq() implements CustomPacketPayload {
        public static final Type<PanelReq> TYPE = new Type<>(id("panel_req"));
        public static final StreamCodec<FriendlyByteBuf, PanelReq> CODEC = StreamCodec.of(
                (b, p) -> {}, b -> new PanelReq());
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** S2C：面板数据。文件行 + 票据 + 看门狗状态 + 维度统计 + 运行日志。scanning=true 表示后台扫描未完成，客户端稍后重试。 */
    public record PanelData(
            List<FileRow> files,
            int ticketCount, List<String> ticketUuids,
            long lastTickGapMs, int dumpCount, long uptimeMs,
            List<DimRow> dims,
            List<String> logLines, boolean scanning) implements CustomPacketPayload {

        public record FileRow(String name, boolean slvlr, long size, int live, int dead) {}
        public record DimRow(String name, int entities, int blockEntities, int chunks) {}

        public static final Type<PanelData> TYPE = new Type<>(id("panel_data"));
        public static final StreamCodec<FriendlyByteBuf, PanelData> CODEC = StreamCodec.of(NetHandler::writePanel, NetHandler::readPanel);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** C2S：区块诊断请求 */
    public record DiagReq(int x, int z) implements CustomPacketPayload {
        public static final Type<DiagReq> TYPE = new Type<>(id("diag_req"));
        public static final StreamCodec<FriendlyByteBuf, DiagReq> CODEC = StreamCodec.of(
                (b, p) -> { b.writeVarInt(p.x); b.writeVarInt(p.z); },
                b -> new DiagReq(b.readVarInt(), b.readVarInt()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** C2S：修复请求。apply=false 为 dry 预览（只回计划），true = 备份+清零。 */
    public record RepairReq(String file, boolean apply) implements CustomPacketPayload {
        public static final Type<RepairReq> TYPE = new Type<>(id("repair_req"));
        public static final StreamCodec<FriendlyByteBuf, RepairReq> CODEC = StreamCodec.of(
                (b, p) -> { b.writeUtf(p.file(), 128); b.writeBoolean(p.apply); },
                b -> new RepairReq(b.readUtf(128), b.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** S2C：修复结果。ok=false 时 errKey 为 lang key（errArg 可空）；dry 时 slots=死槽数、preview=前 20 个槽号。 */
    public record RepairRes(boolean ok, boolean applied, String file, int slots, String preview,
                            String backup, String errKey, String errArg) implements CustomPacketPayload {
        public static final Type<RepairRes> TYPE = new Type<>(id("repair_res"));
        public static final StreamCodec<FriendlyByteBuf, RepairRes> CODEC = StreamCodec.of(
                (b, p) -> { b.writeBoolean(p.ok); b.writeBoolean(p.applied); b.writeUtf(p.file, 128);
                        b.writeVarInt(p.slots); b.writeUtf(p.preview, 256); b.writeUtf(p.backup, 256);
                        b.writeUtf(p.errKey, 64); b.writeUtf(p.errArg, 128); },
                b -> new RepairRes(b.readBoolean(), b.readBoolean(), b.readUtf(128), b.readVarInt(),
                        b.readUtf(256), b.readUtf(256), b.readUtf(64), b.readUtf(128)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** S2C：区块诊断结果 */
    public record DiagRes(int x, int z, boolean loaded, String status,
                          int entities, int blockEntities, String dim) implements CustomPacketPayload {
        public static final Type<DiagRes> TYPE = new Type<>(id("diag_res"));
        public static final StreamCodec<FriendlyByteBuf, DiagRes> CODEC = StreamCodec.of(
                (b, p) -> { b.writeVarInt(p.x); b.writeVarInt(p.z); b.writeBoolean(p.loaded);
                        b.writeUtf(p.status); b.writeVarInt(p.entities); b.writeVarInt(p.blockEntities); b.writeUtf(p.dim); },
                b -> new DiagRes(b.readVarInt(), b.readVarInt(), b.readBoolean(), b.readUtf(),
                        b.readVarInt(), b.readVarInt(), b.readUtf()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    // ---- 序列化 ----
    private static final int MAX_FILES = 4096;
    private static final int MAX_UUIDS = 8192;
    private static final int MAX_DIMS = 256;
    private static final int MAX_LOGS = 400;

    private static void writePanel(FriendlyByteBuf f, PanelData p) {
        f.writeVarInt(p.files.size());
        for (PanelData.FileRow r : p.files) {
            f.writeUtf(r.name()); f.writeBoolean(r.slvlr());
            f.writeVarLong(r.size()); f.writeVarInt(r.live()); f.writeVarInt(r.dead());
        }
        f.writeVarInt(p.ticketCount());
        f.writeVarInt(p.ticketUuids.size());
        for (String u : p.ticketUuids) f.writeUtf(u);
        f.writeVarLong(p.lastTickGapMs());
        f.writeVarInt(p.dumpCount());
        f.writeVarLong(p.uptimeMs());
        f.writeVarInt(p.dims.size());
        for (PanelData.DimRow d : p.dims) {
            f.writeUtf(d.name()); f.writeVarInt(d.entities());
            f.writeVarInt(d.blockEntities()); f.writeVarInt(d.chunks());
        }
        f.writeVarInt(p.logLines.size());
        for (String line : p.logLines) f.writeUtf(line, 512);
        f.writeBoolean(p.scanning());
    }

    private static PanelData readPanel(FriendlyByteBuf f) {
        int n = Math.min(f.readVarInt(), MAX_FILES);
        List<PanelData.FileRow> files = new ArrayList<>(Math.min(n, 1024));
        for (int i = 0; i < n; i++) {
            files.add(new PanelData.FileRow(f.readUtf(), f.readBoolean(), f.readVarLong(), f.readVarInt(), f.readVarInt()));
        }
        int tc = f.readVarInt();
        int un = Math.min(f.readVarInt(), MAX_UUIDS);
        List<String> uuids = new ArrayList<>(Math.min(un, 256));
        for (int i = 0; i < un; i++) uuids.add(f.readUtf());
        long gap = f.readVarLong();
        int dumps = f.readVarInt();
        long up = f.readVarLong();
        int dn = Math.min(f.readVarInt(), MAX_DIMS);
        List<PanelData.DimRow> dims = new ArrayList<>(Math.min(dn, 16));
        for (int i = 0; i < dn; i++) {
            dims.add(new PanelData.DimRow(f.readUtf(), f.readVarInt(), f.readVarInt(), f.readVarInt()));
        }
        int ln = Math.min(f.readVarInt(), MAX_LOGS);
        List<String> logs = new ArrayList<>(Math.min(ln, 200));
        for (int i = 0; i < ln; i++) logs.add(f.readUtf(512));
        boolean scanning = f.readBoolean();
        return new PanelData(files, tc, uuids, gap, dumps, up, dims, logs, scanning);
    }
}
