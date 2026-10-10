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
                }
                for (SlvlsIssue i : r.slvls()) {
                    dev.sfx.sablefix.log.ModLog.warn(i.file().getFileName() + "：" + i.badEntries() + " 个坏条目");
                }
                dev.sfx.sablefix.log.ModLog.warn("子层级体检完成：共 " + dead + " 死指针 / " + bad + " 坏条目，用面板「存档体检」页查看");
                SableFixMod.LOGGER.warn("子层级体检：{} 个文件共 {} 个死指针 / {} 个坏条目，用 /sablefix check 查看",
                        r.slvlr().size() + r.slvls().size(), dead, bad);
            }
        } catch (Exception e) {
            dev.sfx.sablefix.log.ModLog.error("子层级体检失败：" + e.getMessage());
            SableFixMod.LOGGER.error("子层级体检失败（不影响服务器运行）", e);
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
        ByteBuffer head = readHead(f);
        int dead = 0, live = 0;
        for (int i = 0; i < 1024; i++) {
            long ptr = Integer.toUnsignedLong(head.getInt(i * 4));
            if (ptr == 0) continue;
            if (ptr < 4096 || ptr + 5 > size) {
                dead++;
                continue;
            }
            // 读记录头验证长度
            ByteBuffer rec = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
            try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ)) {
                if (ch.read(rec, ptr) != 4) { dead++; continue; }
            }
            long recLen = Integer.toUnsignedLong(rec.getInt(0));
            if (recLen < 1 || ptr + 4 + recLen > size) {
                dead++;
            } else {
                live++;
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

    private static ByteBuffer readHead(Path f) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(4096).order(ByteOrder.BIG_ENDIAN);
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ)) {
            while (buf.hasRemaining() && ch.read(buf) > 0) {}
        }
        return buf;
    }
}
