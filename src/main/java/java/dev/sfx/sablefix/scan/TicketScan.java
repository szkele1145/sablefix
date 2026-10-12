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
                dev.sfx.sablefix.log.ModLog.info("票据体检：文件不存在（视为 0 条，正常）");
                SableFixMod.LOGGER.info("票据体检：文件不存在（视为 0 条，正常）");
                return;
            }
            TicketInfo info = read(p);
            dev.sfx.sablefix.log.ModLog.info("票据体检完成：" + info.count() + " 条 force-load 票据，gzip/NBT 解析正常（"
                    + info.fileSize() + " 字节）");
            SableFixMod.LOGGER.info("票据体检：{} 条票据，文件 {} 字节，gzip/NBT 可正常解析", info.count(), info.fileSize());
            // 异常堆积登记（阈值 100 条：正常服务器极少超过个位数；幽灵加载点案例是 2533 条）
            if (info.count() >= 100) {
                DamageRegistry.register(DamageRegistry.Kind.TICKETS_STUCK, p.getFileName().toString(), info.count());
            }
        } catch (Exception e) {
            dev.sfx.sablefix.log.ModLog.error("票据体检失败：票据文件可能损坏 — " + e.getMessage());
            SableFixMod.LOGGER.error("票据体检失败：票据文件可能损坏，用 /sablefix tickets 查看", e);
            // 票据文件本身损坏（解不开）：登记，提示可 tickets-purge 重建
            DamageRegistry.register(DamageRegistry.Kind.TICKETS_STUCK, "<corrupted ticket file>", 1);
        }
    }

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

    /** 返回 tickets 条数；遇到 uuid 形状的 int 数组就收集。递归下降足够小文件用。 */
    private static int parse(byte[] b, List<UUID> uuids) {
        Counter c = new Counter();
        try {
            readPayload(b, new Pos(), b.length, c, uuids, 0, true);
        } catch (RuntimeException ignored) {
            // 截断/损坏（越界、负 NBT 长度等）按已解析到的部分处理
        }
        return c.count;
    }

    private static final class Counter { int count; }

    /** 跳过/解析一个 payload。end = payload 结束位置（exclusive）。root 层不消费 tag 头。 */
    private static void readPayload(byte[] b, Pos p, int end, Counter counter, List<UUID> uuids, int depth, boolean root) {
        if (depth > 12) return;
        while (p.v < end) {
            int tag = b[p.v] & 0xFF;
            p.v++;
            if (tag == 0) return;                       // TAG_End
            int nameLen = ((b[p.v] & 0xFF) << 8) | (b[p.v + 1] & 0xFF);
            p.v += 2 + nameLen;                          // 跳过名字
            switch (tag) {
                case 10 -> readPayload(b, p, end, counter, uuids, depth + 1, false); // compound 自行消费到 End
                case 9 -> {
                    int elemTag = b[p.v] & 0xFF; p.v++;
                    int n = readLen(b, p);
                    if (elemTag == 10) {
                        // 元素 compound 里有 End tag，递归吃掉即可
                        for (int i = 0; i < n; i++) {
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
                                p.v += (long) len * 4;
                            }
                        }
                    } else {
                        int size = payloadSize(elemTag);
                        p.v += (long) size * n;
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
                        p.v += (long) n * 4;
                    }
                }
                case 12 -> { int n = readLen(b, p); p.v += (long) n * 8; }
                case 8  -> { int n = ((b[p.v] & 0xFF) << 8) | (b[p.v + 1] & 0xFF); p.v += 2 + n; }
                case 7  -> { int n = readLen(b, p); p.v += n; }
                default -> p.v += payloadSize(tag);
            }
        }
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
