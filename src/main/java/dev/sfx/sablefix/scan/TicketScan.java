package dev.sfx.sablefix.scan;

import dev.sfx.sablefix.SableFixMod;
import net.minecraft.server.MinecraftServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/**
 * B：force-load 票据文件体检（只读）。
 * 文件 = gzip 包着的大端 NBT：root compound -> data compound -> tickets List。
 * 这里只做最保守的事：解得开、读得出条数和 uuid 列表，别的不动。
 *
 * v1.1 修复：NBT List-of-int-array（如 UUID 列表直接存为 TAG_List<TAG_Int_Array>）
 * 旧解析器只处理 List-of-compound，元素为 int 数组时整表被当原始字节跳过 → 计数漏报。
 */
public final class TicketScan {

    private TicketScan() {}

    public record TicketInfo(int count, List<UUID> uuids, long fileSize) {}

    public static void startupScan(MinecraftServer server) {
        try {
            Path p = ticketFile(server);
            if (!Files.exists(p)) {
                dev.sfx.sablefix.log.ModLog.infoT("sablefix.scan.tickets_absent");
                SableFixMod.LOGGER.info("票据体检：文件不存在（视为 0 条，正常）");
                // 十轮审计#48：文件不存在时也要**清登记**——原实现直接 return，两类残留无人清：
                //  1) 上轮文件损坏 → 登记 "<corrupted ticket file>" → 管理员删掉坏文件 → 本条永久残留；
                //  2) 上轮 150 条 → 登记 "<文件名>" → 文件被删 → 本条永久残留。
                // 两者都会导致每 5 分钟误报 WARN + 玩家进服永久提示。
                // 「文件不存在」= 0 条 = 健康，与解析成功但计数回落是同一情形（L49-51 的 else 分支）。
                DamageRegistry.clear(DamageRegistry.Kind.TICKETS_STUCK, CORRUPTED_TICKET_FILE);
                DamageRegistry.clear(DamageRegistry.Kind.TICKETS_STUCK, p.getFileName().toString());
                return;
            }
            TicketInfo info = read(p);
            // 十轮审计#10：闭环清登记——上次扫描失败（文件损坏）登记了
            // "<corrupted ticket file>"，本次解析成功即说明文件已恢复（管理员手动替换 /
            // 工具修复）。原实现无清除方 → 该登记永久残留，每 5 分钟误报 WARN + 玩家永久提示。
            // 与 STALE_READONLY 的"健康时清登记"同一范式。
            DamageRegistry.clear(DamageRegistry.Kind.TICKETS_STUCK, CORRUPTED_TICKET_FILE);
            dev.sfx.sablefix.log.ModLog.infoT("sablefix.scan.tickets_ok", info.count(), info.fileSize());
            SableFixMod.LOGGER.info("票据体检：{} 条票据，文件 {} 字节，gzip/NBT 可正常解析", info.count(), info.fileSize());
            if (info.count() >= 100) {
                DamageRegistry.register(DamageRegistry.Kind.TICKETS_STUCK, p.getFileName().toString(), info.count());
            } else {
                // 条数回落到阈值以下（已清理）→ 清掉具体文件名的登记
                DamageRegistry.clear(DamageRegistry.Kind.TICKETS_STUCK, p.getFileName().toString());
            }
        } catch (Exception e) {
            dev.sfx.sablefix.log.ModLog.errorT("sablefix.scan.tickets_fail", e.getMessage());
            SableFixMod.LOGGER.error("票据体检失败：票据文件可能损坏，用 /sablefix tickets 查看", e);
            DamageRegistry.register(DamageRegistry.Kind.TICKETS_STUCK, CORRUPTED_TICKET_FILE, 1);
        }
    }

    /** 票据文件损坏时的登记名（唯一权威定义，十轮审计#10）。 */
    public static final String CORRUPTED_TICKET_FILE = "<corrupted ticket file>";

    public static Path ticketFile(MinecraftServer server) {
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("data").resolve("sable_sub_level_force_load_tickets.dat");
    }

    /** 解压后裸 NBT 大小上限：正常票据文件远小于此；超限按损坏处理（gzip 炸弹/异常膨胀防护）。 */
    private static final int MAX_RAW_BYTES = 64 * 1024 * 1024;

    public static TicketInfo read(Path p) throws IOException {
        byte[] raw;
        try (InputStream in = new GZIPInputStream(Files.newInputStream(p))) {
            // 64KB 缓冲循环读 + 累计计数：解压超 64MB 直接判损坏，防 gzip 炸弹打爆内存
            ByteArrayOutputStream buf = new ByteArrayOutputStream(64 * 1024);
            byte[] chunk = new byte[64 * 1024];
            long total = 0;
            int r;
            while ((r = in.read(chunk)) != -1) {
                total += r;
                if (total > MAX_RAW_BYTES) {
                    throw new IOException("票据文件解压后超过 64MB（疑似 gzip 炸弹或损坏）");
                }
                buf.write(chunk, 0, r);
            }
            raw = buf.toByteArray();
        }
        // 最小 NBT 解析：只挑 tickets 列表里的 uuid（tag 0x0b, 长度 16）
        List<UUID> uuids = new ArrayList<>();
        int count = parse(raw, uuids);
        return new TicketInfo(count, uuids, Files.size(p));
    }

    /** 返回 tickets 条数；遇到 uuid 形状的 int 数组就收集。递归下降足够小文件用。
     *  十轮审计#37：条数改为**按 data.tickets 列表长度**计（与 TicketPurge 用的官方
     *  NbtIo 解析器同源同语义）。原实现用「uuid 形状的 int 数组个数」作条数：
     *  - tickets 元素若是 compound 形态 → 自研解析器数出 0，官方解析器数出 N
     *    → 面板显示 0 条、dry 预览说 0 条，而 purge 实际清掉 N 条（显示与实际不符）；
     *  - tickets 之外还有别的 uuid 形状数组 → 自研解析器多计。
     *  破坏性操作（purge）与显示（scan/dry）必须用**同一个**数字来源。 */
    private static int parse(byte[] b, List<UUID> uuids) {
        Counter c = new Counter();
        boolean ok = true;
        try {
            readPayload(b, new Pos(), b.length, c, uuids, 0, true);
        } catch (RuntimeException ignored) {
            // 截断/损坏（越界、负 NBT 长度等）按已解析到的部分处理
            ok = false;
        }
        // 十轮审计#37：优先用 data.tickets 列表长度（与 TicketPurge 的官方解析器同源）。
        // 解析中断（损坏）时列表长度不可信 → 回退启发式计数，并在调用处按"损坏"提示。
        if (ok && c.ticketsCount >= 0) return c.ticketsCount;
        return c.count;
    }

    private static final class Counter { int count; int ticketsCount = -1; }

    /** 跳过/解析一个 payload。end = payload 结束位置（exclusive）。root 层不消费 tag 头。
     *  十轮审计#37：compound 的字段名不再直接丢弃——需要认出 data.tickets 才能按列表长度计条数。 */
    private static void readPayload(byte[] b, Pos p, int end, Counter counter, List<UUID> uuids, int depth, boolean root) {
        // 模糊审计洞1：原 depth>12 静默 return 不消耗任何字节——外层 for(n=0x7FFFFFFF) 循环
        // 变成 21 亿次空调用（等价死循环，数十字节 gzip 即可让启动体检挂死）。
        // 改抛异常：外层 catch(RuntimeException) 按损坏处理。
        if (depth > 12) throw new IllegalStateException("NBT depth > 12");
        // 十轮审计#37：当前 compound 名（"data" / "tickets" 靠它匹配）
        String curName = root ? "" : null;
        while (p.v < end) {
            int tag = b[p.v] & 0xFF;
            p.v++;
            if (tag == 0) return;                       // TAG_End
            int nameLen = ((b[p.v] & 0xFF) << 8) | (b[p.v + 1] & 0xFF);
            p.v += 2 + nameLen;                          // 跳过名字
            // 十轮审计#37：解析字段名（NBT 名字是 UTF-8；票据文件里是 ASCII）。
            // 名字起始 = p.v - nameLen；nameLen 异常大时 p.v 可能越过 end → 提取会越界 → 置 null。
            String fieldName = null;
            int nameStart = p.v - nameLen;
            if (nameLen > 0 && nameStart >= 0 && nameLen <= b.length && nameStart + nameLen <= b.length) {
                try {
                    fieldName = new String(b, nameStart, nameLen, java.nio.charset.StandardCharsets.US_ASCII);
                } catch (RuntimeException ignored) {}
            }
            if (fieldName != null) curName = fieldName;
            switch (tag) {
                case 10 -> readPayload(b, p, end, counter, uuids, depth + 1, false); // compound 自行消费到 End
                case 9 -> {
                    int elemTag = b[p.v] & 0xFF; p.v++;
                    int n = readLen(b, p);
                    // 十轮审计#37：data.tickets 列表 → 条数按**列表长度**计（与官方解析器同源）
                    if ("tickets".equals(curName) && counter.ticketsCount < 0) {
                        counter.ticketsCount = n;
                    }
                    if (elemTag == 10) {
                        // 元素 compound 里有 End tag，递归吃掉即可
                        // 十轮审计#5：n 不可信（最大 2^31-1），数据耗尽后递归空转——
                        // 9 字节损坏文件即可让解析跑 5.6 秒（探针实测 21 亿次空调用），
                        // 而 /sablefix tickets 与 /sablefix check 在**主线程**调 read → 卡服。
                        // 兄弟分支（elemTag==11）已有 p.v+4<=b.length 守卫，这里补同类守卫。
                        for (int i = 0; i < n && p.v < end; i++) {
                            readPayload(b, p, end, counter, uuids, depth + 1, true);
                        }
                    } else if (elemTag == 11) {
                        // List<int[]>：每个元素是长度前缀 int 数组（老版解析器整表当原始字节跳过 → 漏计）
                        for (int i = 0; i < n && p.v + 4 <= b.length; i++) {
                            int len = readLen(b, p);
                            if (len == 4) { p.v += 16; }                       // DataVersion 之类
                            else if (len == 16 && p.v + 16 <= b.length) {      // uuid 形状
                                uuids.add(new UUID(readLong(b, p), readLong(b, p)));
                                counter.count++;
                            } else {
                                advanceChecked(p, (long) len * 4, b.length);   // 回归审计：上一轮漏改的裸 +=
                            }
                        }
                    } else {
                        int size = payloadSize(elemTag);
                        advanceChecked(p, (long) size * n, b.length);
                    }
                }
                case 11 -> {
                    int n = readLen(b, p);
                    if (n == 4) {  // DataVersion 之类，跳过
                        p.v += 16;
                    } else if (n == 16) {
                        // 疑似 uuid
                        if (p.v + 16 <= b.length) {
                            uuids.add(new UUID(readLong(b, p), readLong(b, p)));
                            counter.count++;
                        }
                        p.v += 16;
                    } else {
                        advanceChecked(p, (long) n * 4, b.length);
                    }
                }
                case 12 -> { int n = readLen(b, p); advanceChecked(p, (long) n * 8, b.length); }
                case 8  -> { int n = ((b[p.v] & 0xFF) << 8) | (b[p.v + 1] & 0xFF); p.v += 2 + n; }
                case 7  -> { int n = readLen(b, p); advanceChecked(p, n, b.length); }
                default -> p.v += payloadSize(tag);
            }
        }
    }

    /** 模糊审计洞2：复合赋值 += 隐式窄化回 int——n*4 溢出时 p.v 回绕到数组内错误位置，
     *  任意 16 字节被收成 UUID → 误登记 → 诱导玩家 purge 掉正常票据。
     *  改显式 long 累加 + 越界即断（按截断/损坏处理）。 */
    private static void advanceChecked(Pos p, long delta, int len) {
        long next = (long) p.v + delta;
        if (next >= len) { p.v = len; return; }   // 越界钉到末尾：外层 while(p.v<end) 自然退出
        p.v = (int) next;
    }

    private static int payloadSize(int tag) {
        return switch (tag) {
            case 1 -> 1; case 2 -> 2; case 3, 5 -> 4; case 4, 6 -> 8;
            default -> 0;
        };
    }

    private static int readInt(byte[] b, Pos p) {
        int v = ((b[p.v] & 0xFF) << 24) | ((b[p.v + 1] & 0xFF) << 16) | ((b[p.v + 2] & 0xFF) << 8) | (b[p.v + 3] & 0xFF);
        p.v += 4;
        return v;
    }

    /** 读 4 字节大端有符号 int 作 NBT 长度/数量字段：负值即损坏数据，立即抛出，防回绕死循环。 */
    private static int readLen(byte[] b, Pos p) {
        int v = readInt(b, p);
        if (v < 0) throw new IllegalStateException("negative NBT length");
        return v;
    }

    private static long readLong(byte[] b, Pos p) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (b[p.v + i] & 0xFFL);
        p.v += 8;
        return v;
    }

    private static final class Pos { int v; }
}
