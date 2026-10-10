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

    /** S2C：面板数据。文件行 + 票据 + 看门狗状态 + 维度统计 + 运行日志 */
    public record PanelData(
            List<FileRow> files,
            int ticketCount, List<String> ticketUuids,
            long lastTickGapMs, int dumpCount, long uptimeMs,
            List<DimRow> dims,
            List<String> logLines) implements CustomPacketPayload {

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
    }

    private static PanelData readPanel(FriendlyByteBuf f) {
        int n = f.readVarInt();
        List<PanelData.FileRow> files = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            files.add(new PanelData.FileRow(f.readUtf(), f.readBoolean(), f.readVarLong(), f.readVarInt(), f.readVarInt()));
        }
        int tc = f.readVarInt();
        int un = f.readVarInt();
        List<String> uuids = new ArrayList<>(un);
        for (int i = 0; i < un; i++) uuids.add(f.readUtf());
        long gap = f.readVarLong();
        int dumps = f.readVarInt();
        long up = f.readVarLong();
        int dn = f.readVarInt();
        List<PanelData.DimRow> dims = new ArrayList<>(dn);
        for (int i = 0; i < dn; i++) {
            dims.add(new PanelData.DimRow(f.readUtf(), f.readVarInt(), f.readVarInt(), f.readVarInt()));
        }
        int ln = f.readVarInt();
        List<String> logs = new ArrayList<>(ln);
        for (int i = 0; i < ln; i++) logs.add(f.readUtf(512));
        return new PanelData(files, tc, uuids, gap, dumps, up, dims, logs);
    }
}
