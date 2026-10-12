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

    /** 协议版本：**任何**增删/改通道负载格式时必须 +1。两端版本号不一致会在握手期被
     *  NeoForge 拒绝并给出清晰提示，而不是运行期才"网络通道抽风"。
     *  v1: 1.0.0–1.3.0（panel/diag + repair + purge，期间变更未 bump 是历史教训）
     *  v2: 1.3.1 起（DiagRes.errorKey 客户端本地化 + 发送侧截断语义确立） */
    public static final String PROTOCOL_VERSION = "2";

    public static void register(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        PayloadRegistrar reg = event.registrar(PROTOCOL_VERSION);
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
        reg.playToServer(PurgeReq.TYPE, PurgeReq.CODEC, (p, ctx) ->
                ctx.enqueueWork(() -> ServerHandlers.onPurgeReq(p, ctx)));
        reg.playToClient(PurgeRes.TYPE, PurgeRes.CODEC, (p, ctx) ->
                ctx.enqueueWork(() -> ClientBridge.onPurgeRes(p)));
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

    /** C2S：票据清理请求。apply=false 为 dry 预览（只回条数），true = 备份+清空。 */
    public record PurgeReq(boolean apply) implements CustomPacketPayload {
        public static final Type<PurgeReq> TYPE = new Type<>(id("purge_req"));
        public static final StreamCodec<FriendlyByteBuf, PurgeReq> CODEC = StreamCodec.of(
                (b, p) -> b.writeBoolean(p.apply),
                b -> new PurgeReq(b.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** S2C：票据清理结果。ok=false 时 errKey 为 lang key。 */
    public record PurgeRes(boolean ok, boolean applied, int count, String backup, String errKey) implements CustomPacketPayload {
        public static final Type<PurgeRes> TYPE = new Type<>(id("purge_res"));
        public static final StreamCodec<FriendlyByteBuf, PurgeRes> CODEC = StreamCodec.of(
                (b, p) -> { b.writeBoolean(p.ok); b.writeBoolean(p.applied); b.writeVarInt(p.count);
                        b.writeUtf(p.backup, 256); b.writeUtf(p.errKey, 64); },
                b -> new PurgeRes(b.readBoolean(), b.readBoolean(), b.readVarInt(),
                        b.readUtf(256), b.readUtf(64)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** S2C：区块诊断结果。errorKey 非空=查询失败（客户端本地化渲染，避免服务器 locale 泄漏到客户端）。 */
    public record DiagRes(int x, int z, boolean loaded, String errorKey,
                          int entities, int blockEntities, String dim) implements CustomPacketPayload {
        public static final Type<DiagRes> TYPE = new Type<>(id("diag_res"));
        public static final StreamCodec<FriendlyByteBuf, DiagRes> CODEC = StreamCodec.of(
                (b, p) -> { b.writeVarInt(p.x); b.writeVarInt(p.z); b.writeBoolean(p.loaded);
                        b.writeUtf(p.errorKey, 64); b.writeVarInt(p.entities); b.writeVarInt(p.blockEntities); b.writeUtf(p.dim, 128); },
                b -> new DiagRes(b.readVarInt(), b.readVarInt(), b.readBoolean(), b.readUtf(64),
                        b.readVarInt(), b.readVarInt(), b.readUtf(128)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    // ---- 序列化 ----
    private static final int MAX_FILES = 4096;
    private static final int MAX_UUIDS = 8192;
    private static final int MAX_DIMS = 256;
    private static final int MAX_LOGS = 400;

    private static void writePanel(FriendlyByteBuf f, PanelData p) {
        // 发送侧硬截断（防帧超限：超大存档 files×name 最坏 300KB+、logs 200KB → 总量逼近 1MB 断线阈值。
        // 抽风的根因：NeoForge 对超限 play 载荷直接断开连接。截断优先保命，面板显示"N 项已省略"提示行。）
        int maxFiles = 512, maxUuids = 512, maxLogs = 150;
        List<PanelData.FileRow> files = p.files.size() > maxFiles ? p.files.subList(0, maxFiles) : p.files;
        List<String> uuids = p.ticketUuids.size() > maxUuids ? p.ticketUuids.subList(0, maxUuids) : p.ticketUuids;
        // logs 截断方向修正：ModLog 快照"最新在后"，取头部=丢最新留最旧（排障最需要的恰是最新）。
        // 修正为保留尾部（最新）maxLogs 条。
        List<String> logs = p.logLines.size() > maxLogs
                ? p.logLines.subList(p.logLines.size() - maxLogs, p.logLines.size())
                : p.logLines;

        f.writeVarInt(files.size());
        for (PanelData.FileRow r : files) {
            f.writeUtf(r.name(), 128); f.writeBoolean(r.slvlr());
            f.writeVarLong(r.size()); f.writeVarInt(r.live()); f.writeVarInt(r.dead());
        }
        f.writeVarInt(p.ticketCount());
        f.writeVarInt(uuids.size());
        for (String u : uuids) f.writeUtf(u, 64);
        f.writeVarLong(p.lastTickGapMs());
        f.writeVarInt(p.dumpCount());
        f.writeVarLong(p.uptimeMs());
        f.writeVarInt(p.dims.size());
        for (PanelData.DimRow d : p.dims) {
            f.writeUtf(d.name(), 128); f.writeVarInt(d.entities());
            f.writeVarInt(d.blockEntities()); f.writeVarInt(d.chunks());
        }
        f.writeVarInt(logs.size());
        for (String line : logs) f.writeUtf(line, 384);
        f.writeBoolean(p.scanning());
    }

    private static PanelData readPanel(FriendlyByteBuf f) {
        // 模糊审计：readVarInt 可为负 → new ArrayList<>(负) 抛 IAE 踢客户端。四处 clamp 到 ≥0。
        int n = clampCount(f.readVarInt(), MAX_FILES);
        List<PanelData.FileRow> files = new ArrayList<>(Math.min(n, 1024));
        for (int i = 0; i < n; i++) {
            files.add(new PanelData.FileRow(f.readUtf(), f.readBoolean(), f.readVarLong(), f.readVarInt(), f.readVarInt()));
        }
        int tc = f.readVarInt();
        int un = clampCount(f.readVarInt(), MAX_UUIDS);
        List<String> uuids = new ArrayList<>(Math.min(un, 256));
        for (int i = 0; i < un; i++) uuids.add(f.readUtf());
        long gap = f.readVarLong();
        int dumps = f.readVarInt();
        long up = f.readVarLong();
        int dn = clampCount(f.readVarInt(), MAX_DIMS);
        List<PanelData.DimRow> dims = new ArrayList<>(Math.min(dn, 16));
        for (int i = 0; i < dn; i++) {
            dims.add(new PanelData.DimRow(f.readUtf(), f.readVarInt(), f.readVarInt(), f.readVarInt()));
        }
        int ln = clampCount(f.readVarInt(), MAX_LOGS);
        List<String> logs = new ArrayList<>(Math.min(ln, 200));
        for (int i = 0; i < ln; i++) logs.add(f.readUtf(512));
        boolean scanning = f.readBoolean();
        return new PanelData(files, tc, uuids, gap, dumps, up, dims, logs, scanning);
    }

    /** VarInt 长度防御：负值按 0 处理（恶意/损坏包不踢客户端） */
    private static int clampCount(int raw, int cap) {
        return Math.max(0, Math.min(raw, cap));
    }
}
