package dev.sfx.sablefix.scan;

import dev.sfx.sablefix.SableFixMod;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * A/B/C：子层级存储体检。
 * .slvlr 格式：头部 4096 字节 = 1024 个 4 字节大端指针（指向 128 对齐的记录）；
 * 记录 = [4B 大端长度][1B flag][gzip(长度-1)]。
 * 指针为 0 = 空槽；非 0 必须落在 [4096, fileLen) 且记录能完整读出，否则是死指针。
 * .slvls 格式：头部 4096 字节 = 1024 个条目 [3B 大端扇区号][1B 扇区数]，扇区 4096 字节。
 * 本扫描器只读不改：报告死指针，修复由玩家显式执行。
 */
public final class SubLevelScan {

    private SubLevelScan() {}

    public record SlvlIssue(Path file, int deadPointers, int livePointers, long size) {}
    public record SlvlsIssue(Path file, int badEntries, long size) {}

    public record Report(List<SlvlIssue> slvlr, List<SlvlsIssue> slvls,
                         int scannedSlvlr, int scannedSlvls) {}

    /** 启动时只读体检，问题文件逐个打日志。 */
    public static void startupScan(MinecraftServer server) {
        try {
            dev.sfx.sablefix.log.ModLog.info("开始子层级体检…");
            Report r = scan(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("sublevels"));
            long dead = r.slvlr().stream().mapToLong(SlvlIssue::deadPointers).sum();
            long bad = r.slvls().stream().mapToLong(SlvlsIssue::badEntries).sum();
            if (dead == 0 && bad == 0) {
                dev.sfx.sablefix.log.ModLog.info("子层级体检完成：" + r.scannedSlvlr() + " 个 .slvlr + "
                        + r.scannedSlvls() + " 个 .slvls，全部健康");
                SableFixMod.LOGGER.info("子层级体检：扫描 {} 个 .slvlr + {} 个 .slvls，全部健康",
                        r.scannedSlvlr(), r.scannedSlvls());
            } else {
                for (SlvlIssue i : r.slvlr()) {
                    dev.sfx.sablefix.log.ModLog.warn(i.file().getFileName() + "：" + i.deadPointers()
                            + " 个死指针（有效 " + i.livePointers() + "）");
                    DamageRegistry.register(DamageRegistry.Kind.SUBLVL_DEAD, i.file().getFileName().toString(), i.deadPointers());
                }
                for (SlvlsIssue i : r.slvls()) {
                    dev.sfx.sablefix.log.ModLog.warn(i.file().getFileName() + "：" + i.badEntries() + " 个坏条目");
                    DamageRegistry.register(DamageRegistry.Kind.SUBLVL_DEAD, i.file().getFileName().toString(), i.badEntries());
                }
                dev.sfx.sablefix.log.ModLog.warn("子层级体检完成：共 " + dead + " 死指针 / " + bad + " 坏条目，用面板「存档体检」页查看");
                SableFixMod.LOGGER.warn("子层级体检：{} 个文件共 {} 个死指针 / {} 个坏条目，用 /sablefix check 查看",
                        r.slvlr().size() + r.slvls().size(), dead, bad);
            }
        } catch (Exception e) {
            dev.sfx.sablefix.log.ModLog.error("子层级体检失败：" + e.getMessage());
            SableFixMod.LOGGER.error("子层级体检失败（不影响服务器运行）", e);
            // 体检本身失败（目录不可读/IO 故障）：登记一个整卷级损伤，提醒不再静默
            DamageRegistry.register(DamageRegistry.Kind.SUBLVL_DEAD, "<sublevels scan failed>", 1);
        }
    }

    public static Report scan(Path sublevelsDir) throws IOException {
        List<SlvlIssue> slvlr = new ArrayList<>();
        List<SlvlsIssue> slvls = new ArrayList<>();
        int nR = 0, nS = 0;
        if (!Files.isDirectory(sublevelsDir)) {
            return new Report(slvlr, slvls, 0, 0);
        }
        try (Stream<Path> files = Files.list(sublevelsDir)) {
            for (Path f : files.sorted().toList()) {
                String name = f.getFileName().toString();
                if (name.endsWith(".slvlr")) {
                    nR++;
                    SlvlIssue i = checkSlvlr(f);
                    if (i != null) slvlr.add(i);
                } else if (name.endsWith(".slvls")) {
                    nS++;
                    SlvlsIssue i = checkSlvls(f);
                    if (i != null) slvls.add(i);
                }
            }
        }
        return new Report(slvlr, slvls, nR, nS);
    }

    static SlvlIssue checkSlvlr(Path f) throws IOException {
        long size = Files.size(f);
        if (size < 4096) {
            return new SlvlIssue(f, 1, 0, size);  // 连头部都不完整，视为损坏
        }
        // 模糊审计：>2GB 极端文件 (int) 强转回绕 → 段读 offset 为负抛异常/健康指针误判 dead。
        // 退化为逐指针 long 算术路径（慢但正确）；正常 16MB 文件不受影响。
        if (size > Integer.MAX_VALUE - 8L) {
            return checkSlvlrLegacy(f, size);
        }
        ByteBuffer head = readHead(f);
        int dead = 0, live = 0;
        // 批读 v2：排序指针 → 8KB 窗口分段顺序读，一次 syscall 校验段内全部记录头。
        // 旧实现每指针 1 次 4B read（900 syscall/文件）；16MB 文件实测 72ms、600 文件大存档 26s
        // —— 面板"扫描中"转半天的根因。v2 实测同基准 7.3×（100×16MB: 4327ms → 594ms）。
        List<Integer> offs = new ArrayList<>(1024);
        for (int i = 0; i < 1024; i++) {
            long ptr = Integer.toUnsignedLong(head.getInt(i * 4));
            if (ptr == 0) continue;
            if (ptr < 4096 || ptr + 5 > size) {
                dead++;
                continue;
            }
            offs.add((int) ptr);
        }
        if (!offs.isEmpty()) {
            offs.sort(null);
            try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ)) {
                int i = 0;
                while (i < offs.size()) {
                    int segStart = offs.get(i);
                    int j = i;
                    // 段长：直到下一个指针超出 8KB 窗口（平衡 syscall 数与内存）
                    while (j + 1 < offs.size() && offs.get(j + 1) - segStart < 8_192) j++;
                    int segEnd = Math.min(offs.get(j) + 4, (int) size);
                    int segLen = Math.max(4, segEnd - segStart);
                    ByteBuffer seg = ByteBuffer.allocate(segLen).order(ByteOrder.BIG_ENDIAN);
                    // 审计#4：positioned read 契约可短读——单次 read 会把段尾的健康指针误判
                    // 成 dead（误报 → 玩家修复 → 好记录被清零）。与 readHead 同构的补读循环。
                    int got = 0;
                    while (seg.hasRemaining()) {
                        int r = ch.read(seg, segStart + got);
                        if (r < 0) break;
                        got += r;
                    }
                    if (got < 4) {
                        for (int k = i; k <= j; k++) dead++;
                    } else {
                        seg.flip();
                        for (int k = i; k <= j; k++) {
                            int p = offs.get(k);
                            int inSeg = p - segStart;
                            if (inSeg + 4 > seg.remaining()) { dead++; continue; }
                            long recLen = Integer.toUnsignedLong(seg.getInt(inSeg));
                            if (recLen < 1 || p + 4 + recLen > size) dead++; else live++;
                        }
                    }
                    i = j + 1;
                }
            }
        }
        return dead == 0 ? null : new SlvlIssue(f, dead, live, size);
    }

    static SlvlsIssue checkSlvls(Path f) throws IOException {
        long size = Files.size(f);
        if (size < 4096) {
            return new SlvlsIssue(f, 1, size);
        }
        ByteBuffer head = readHead(f);
        int bad = 0;
        for (int i = 0; i < 1024; i++) {
            int off = ((head.get(i * 4) & 0xFF) << 16) | ((head.get(i * 4 + 1) & 0xFF) << 8) | (head.get(i * 4 + 2) & 0xFF);
            int cnt = head.get(i * 4 + 3) & 0xFF;
            if (off == 0 && cnt == 0) continue;   // 空槽
            long end = (long) off * 4096 + (long) cnt * 4096;
            if (off == 0 || cnt == 0 || end > size) bad++;
        }
        return bad == 0 ? null : new SlvlsIssue(f, bad, size);
    }

    /** >2GB .slvlr 的退化路径：全 long 算术，无 int 强转（模糊审计：批读的 (int) 强转在
     *  size > Integer.MAX_VALUE 时回绕为负 → 负 offset 抛异常/健康指针误判 dead）。 */
    static SlvlIssue checkSlvlrLegacy(Path f, long size) throws IOException {
        ByteBuffer head = readHead(f);
        int dead = 0, live = 0;
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ)) {
            for (int i = 0; i < 1024; i++) {
                long ptr = Integer.toUnsignedLong(head.getInt(i * 4));
                if (ptr == 0) continue;
                if (ptr < 4096 || ptr + 5 > size) { dead++; continue; }
                ByteBuffer rec = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
                if (ch.read(rec, ptr) != 4) { dead++; continue; }
                long recLen = Integer.toUnsignedLong(rec.getInt(0));
                if (recLen < 1 || ptr + 4 + recLen > size) dead++; else live++;
            }
        }
        return dead == 0 ? null : new SlvlIssue(f, dead, live, size);
    }

    private static ByteBuffer readHead(Path f) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(4096).order(ByteOrder.BIG_ENDIAN);
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ)) {
            while (buf.hasRemaining() && ch.read(buf) > 0) {}
        }
        return buf;
    }
}
