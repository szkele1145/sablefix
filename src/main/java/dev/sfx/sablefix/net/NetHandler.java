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
     *  v2: 1.3.1（DiagRes.errorKey 客户端本地化 + 发送侧截断语义确立）
     *  v3: 1.4.0（负 VarInt clamp、日志截断方向修正——接收语义变化）
     *  v4: 1.5.0（PanelData 增 lastStallAtMs + backups 字段——载荷格式变更）
     *  v5: 1.5.2（lastStallAtMs → lastStallAgoMs、BackupRow.atMs → ageMs：服务端算好相对时长，
     *      绕 NTP 失步面板服的时钟偏差——载荷语义变更）
     *  v6: 日志行结构化（LogRow：key+args 客户端按玩家语言渲染；服务端 Language 只装 en_us，
     *      服务端预渲染会把中文服的面板日志变成英文——载荷格式变更） */
    public static final String PROTOCOL_VERSION = "6";

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

    /** S2C：面板数据。文件行 + 票据 + 看门狗状态 + 维度统计 + 运行日志。scanning=true 表示后台扫描未完成，客户端稍后重试。
     *  lastStallAgoMs：距最近一次卡死抓栈的毫秒数（服务端算好——三轮审计#4/#5：客户端时钟对服务器
     *  epoch 的偏移在 NTP 失步的面板服上显示错乱；改下发相对时长）。-1=未发生过。
     *  backups：backups/sablefix 下的备份清单（备份管理页用；最多 64 条，超出只保留最新——防帧超限）。
     *  BackupRow.ageMs 同理由服务端算好。 */
    public record PanelData(
            List<FileRow> files,
            int ticketCount, List<String> ticketUuids,
            long lastTickGapMs, int dumpCount, long uptimeMs,
            List<DimRow> dims,
            List<LogRow> logLines, boolean scanning,
            long lastStallAgoMs,
            List<BackupRow> backups) implements CustomPacketPayload {

        public record FileRow(String name, boolean slvlr, long size, int live, int dead) {}
        public record DimRow(String name, int entities, int blockEntities, int chunks) {}
        /** 备份行：文件名（含时间戳后缀）+ 字节数 + 距今毫秒（服务端算好，绕时钟偏差） */
        public record BackupRow(String name, long size, long ageMs) {}
        /** 日志行（九轮审计#1）：key 非空=可翻译（客户端按玩家语言渲染，args 逐个下发）；
         *  key 空=纯文本 msg 已定型。level 仅用于染色，照原样透传。 */
        public record LogRow(String level, String msg, String key, List<String> args) {}

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
                (b, p) -> { b.writeUtf(utf8Trunc(p.file(), 128), 128); b.writeBoolean(p.apply); },
                b -> new RepairReq(b.readUtf(128), b.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** S2C：修复结果。ok=false 时 errKey 为 lang key（errArg 可空）；dry 时 slots=死槽数、preview=前 20 个槽号。
     *  十轮审计#2：所有写侧 writeUtf 一律过 utf8Trunc——backup 是**绝对路径**（面板服深路径轻易
     *  超 256B）、errArg 是「文件: 异常消息」，超限即 EncoderException 断连（修复成功的回包
     *  断连会让玩家误以为修复失败）。 */
    public record RepairRes(boolean ok, boolean applied, String file, int slots, String preview,
                            String backup, String errKey, String errArg) implements CustomPacketPayload {
        public static final Type<RepairRes> TYPE = new Type<>(id("repair_res"));
        public static final StreamCodec<FriendlyByteBuf, RepairRes> CODEC = StreamCodec.of(
                (b, p) -> { b.writeBoolean(p.ok); b.writeBoolean(p.applied); b.writeUtf(utf8Trunc(p.file, 128), 128);
                        b.writeVarInt(p.slots); b.writeUtf(utf8Trunc(p.preview, 256), 256); b.writeUtf(utf8Trunc(p.backup, 256), 256);
                        b.writeUtf(utf8Trunc(p.errKey, 64), 64); b.writeUtf(utf8Trunc(p.errArg, 128), 128); },
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
                        b.writeUtf(utf8Trunc(p.backup, 256), 256); b.writeUtf(utf8Trunc(p.errKey, 64), 64); },
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
                        b.writeUtf(utf8Trunc(p.errorKey, 64), 64); b.writeVarInt(p.entities); b.writeVarInt(p.blockEntities); b.writeUtf(utf8Trunc(p.dim, 128), 128); },
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
        List<PanelData.LogRow> logs = p.logLines.size() > maxLogs
                ? p.logLines.subList(p.logLines.size() - maxLogs, p.logLines.size())
                : p.logLines;

        f.writeVarInt(files.size());
        for (PanelData.FileRow r : files) {
            // 十轮审计#2：文件名可能超 128B（Sable 子区域名含区域标题，NTFS 单名上限 255 字符），
            // 备份名 = 原名 + 时间戳后缀更容易超 192B——写侧必须截断，否则 EncoderException 断连。
            f.writeUtf(utf8Trunc(r.name(), 128), 128); f.writeBoolean(r.slvlr());
            f.writeVarLong(r.size()); f.writeVarInt(r.live()); f.writeVarInt(r.dead());
        }
        f.writeVarInt(p.ticketCount());
        f.writeVarInt(uuids.size());
        for (String u : uuids) f.writeUtf(utf8Trunc(u, 64), 64);
        f.writeVarLong(p.lastTickGapMs());
        f.writeVarInt(p.dumpCount());
        f.writeVarLong(p.uptimeMs());
        // 六审#6：dims 写侧补截断——原读侧 clamp(256) 而写侧无截断，>256 维度的病态环境
        // 下读循环少读条目导致后续字段错位。写读对齐。
        List<PanelData.DimRow> dims = p.dims.size() > MAX_DIMS
                ? p.dims.subList(0, MAX_DIMS) : p.dims;
        f.writeVarInt(dims.size());
        for (PanelData.DimRow d : dims) {
            f.writeUtf(utf8Trunc(d.name(), 128), 128); f.writeVarInt(d.entities());
            f.writeVarInt(d.blockEntities()); f.writeVarInt(d.chunks());
        }
        f.writeVarInt(logs.size());
        for (PanelData.LogRow r : logs) writeLogRow(f, r);
        f.writeBoolean(p.scanning());
        f.writeVarLong(p.lastStallAgoMs);
        // 备份清单：最多 64 条（buildScan 侧已截断），防帧超限
        List<PanelData.BackupRow> backups = p.backups.size() > 64
                ? p.backups.subList(0, 64) : p.backups;
        f.writeVarInt(backups.size());
        for (PanelData.BackupRow b : backups) {
            f.writeUtf(utf8Trunc(b.name(), 192), 192); f.writeVarLong(b.size()); f.writeVarLong(b.ageMs());
        }
    }

    private static PanelData readPanel(FriendlyByteBuf f) {
        // 模糊审计：readVarInt 可为负 → new ArrayList<>(负) 抛 IAE 踢客户端。四处 clamp 到 ≥0。
        // 六审#1：readUtf 补显式上限与写侧逐字对齐（纵深防御——恶意/损坏包不依赖 32767 默认值）
        int n = clampCount(f.readVarInt(), MAX_FILES);
        List<PanelData.FileRow> files = new ArrayList<>(Math.min(n, 1024));
        for (int i = 0; i < n; i++) {
            files.add(new PanelData.FileRow(f.readUtf(128), f.readBoolean(), f.readVarLong(), f.readVarInt(), f.readVarInt()));
        }
        int tc = clampCount(f.readVarInt(), 65535);
        int un = clampCount(f.readVarInt(), MAX_UUIDS);
        List<String> uuids = new ArrayList<>(Math.min(un, 256));
        for (int i = 0; i < un; i++) uuids.add(f.readUtf(64));
        long gap = f.readVarLong();
        int dumps = f.readVarInt();
        long up = f.readVarLong();
        int dn = clampCount(f.readVarInt(), MAX_DIMS);
        List<PanelData.DimRow> dims = new ArrayList<>(Math.min(dn, 16));
        for (int i = 0; i < dn; i++) {
            dims.add(new PanelData.DimRow(f.readUtf(128), f.readVarInt(), f.readVarInt(), f.readVarInt()));
        }
        int ln = clampCount(f.readVarInt(), MAX_LOGS);
        List<PanelData.LogRow> logs = new ArrayList<>(Math.min(ln, 200));
        for (int i = 0; i < ln; i++) logs.add(readLogRow(f));
        boolean scanning = f.readBoolean();
        long stallAgo = f.readVarLong();
        int bn = clampCount(f.readVarInt(), 64);
        List<PanelData.BackupRow> backups = new ArrayList<>(bn);
        for (int i = 0; i < bn; i++) {
            backups.add(new PanelData.BackupRow(f.readUtf(192), f.readVarLong(), f.readVarLong()));
        }
        return new PanelData(files, tc, uuids, gap, dumps, up, dims, logs, scanning, stallAgo, backups);
    }

    /** VarInt 长度防御：负值按 0 处理（恶意/损坏包不踢客户端） */
    private static int clampCount(int raw, int cap) {
        return Math.max(0, Math.min(raw, cap));
    }

    /** 日志行编码（九轮审计#1）：
     *  level(≤16B) | key(≤128B，空串=纯文本) | msg(≤384B，key 非空时为时间戳前缀) | args(逐个 ≤128B，最多 8 个)
     *  args 统一转字符串下发——客户端按 %s 占位渲染，数字类型差异对显示无影响。 */
    private static final int MAX_LOG_ARGS = 8;

    private static void writeLogRow(FriendlyByteBuf f, PanelData.LogRow r) {
        f.writeUtf(utf8Trunc(r.level() == null ? "" : r.level(), 16), 16);
        String key = r.key() == null ? "" : r.key();
        f.writeUtf(utf8Trunc(key, 128), 128);
        f.writeUtf(utf8Trunc(r.msg() == null ? "" : r.msg(), 384), 384);
        List<String> args = r.args() == null ? List.of() : r.args();
        int n = Math.min(args.size(), MAX_LOG_ARGS);
        f.writeVarInt(n);
        for (int i = 0; i < n; i++) f.writeUtf(utf8Trunc(args.get(i), 128), 128);
    }

    private static PanelData.LogRow readLogRow(FriendlyByteBuf f) {
        String level = f.readUtf(16);
        String key = f.readUtf(128);
        String msg = f.readUtf(384);
        int an = clampCount(f.readVarInt(), MAX_LOG_ARGS);
        List<String> args = new ArrayList<>(Math.min(an, MAX_LOG_ARGS));
        for (int i = 0; i < an; i++) args.add(f.readUtf(128));
        return new PanelData.LogRow(level, msg, key.isEmpty() ? null : key, args);
    }

    /** 临时探针入口（十轮审计#1）：验证后删除。 */
    static String utf8TruncForTest(String s, int maxBytes) { return utf8Trunc(s, maxBytes); }

    /** 七轮审计#3：writeUtf(s, n) 的 n 是 UTF-8 字节上限，超限抛 EncoderException → NeoForge 直接断连。
     *  logLines 无字节上游约束（损伤摘要行拼 3 个最长 128B 文件名、ModLog 含绝对备份路径），
     *  深目录服务器开面板即被踢。按字节安全截断（不切多字节字符中间，尾缀 …）。
     *  注意：384B 截断与 readUtf(512) 读侧上限无关——读侧 clamp 到字符，写侧才是保护。
     *  十轮审计#1：尾缀 … 本身占 3 UTF-8 字节——原实现只留 1 字节（cut = maxBytes - 1），
     *  截断结果最坏 127+3=130B，仍超 writeUtf(128) 上限 → EncoderException 断连。
     *  截断越界本身没被七轮审计#3 修掉。给后缀留足 4 字节。 */
    private static String utf8Trunc(String s, int maxBytes) {
        if (s == null) return "";
        if (maxBytes <= 0) return "";
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (b.length <= maxBytes) return s;
        // 十轮审计#14：maxBytes < 4 时 cut = maxBytes - 4 为负 → new String(b, 0, 负数) 抛
        // StringIndexOutOfBoundsException。当前所有调用点 maxBytes ≥ 16（安全），但这是通用
        // 工具——留个明确的最小值边界，别让下一个人加个 writeUtf(utf8Trunc(x, 2)) 就崩服。
        if (maxBytes <= 4) {
            // 只放得下尾缀（3B）甚至放不下 → 退化为按字符截断，保证字节数 ≤ maxBytes
            int chars = Math.max(0, maxBytes - 3);
            String t = s.length() <= chars ? s : s.substring(0, chars);
            // substring 会劈开代理对（emoji = 2 个 char）→ 末位是高代理时去掉半个。
            // 否则 getBytes(UTF_8) 会把落单代理替换成 '?'，字符串往返不一致。
            if (!t.isEmpty() && Character.isHighSurrogate(t.charAt(t.length() - 1))) {
                t = t.substring(0, t.length() - 1);
            }
            byte[] tb = t.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return tb.length <= maxBytes ? t : "";
        }
        int cut = maxBytes - 4;   // 给尾缀 …（3B）留位 + 1B 余量
        while (cut > 0 && (b[cut] & 0xC0) == 0x80) cut--;   // 回退到 UTF-8 序列头
        return new String(b, 0, cut, java.nio.charset.StandardCharsets.UTF_8) + "…";
    }
}
