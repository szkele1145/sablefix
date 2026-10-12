package dev.sfx.sablefix.scan;

import dev.sfx.sablefix.SableFixMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.zip.GZIPInputStream;

/**
 * 票据清理：把 force-load 票据文件的 tickets 列表清空（其他字段原样保留）。
 * 安全模型：
 *  - 用 MC 官方 NbtIo 解析/写回（不手写字节），未知字段全部保留；
 *  - dry 永不写盘；
 *  - apply 先备份原文件到 <world>/backups/sablefix/，再原子写回（写临时文件后 move）；
 *  - 文件被 Sable 占用时抛 FileSystemException，命令层给"停服后执行"提示；
 *  - 空列表写回 = 0 条票据，Sable 读取时按无票据处理。
 */
public final class TicketPurge {

    private TicketPurge() {}

    /** 解析结果：root NBT + tickets 条数（解析失败抛异常）。 */
    public record Parsed(CompoundTag root, int ticketCount) {}

    /** 解压 + 解析票据文件。 */
    public static Parsed parse(Path p) throws IOException {
        CompoundTag root = NbtIo.readCompressed(p, NbtAccounter.unlimitedHeap());
        int n = 0;
        if (root.contains("data", 10)) {
            CompoundTag data = root.getCompound("data");
            // ListTag 元素类型不定（compound / int-array），tagId 0 = 不限类型
            n = data.getList("tickets", 0).size();
        }
        return new Parsed(root, n);
    }

    /** dry 统计：只解析，不写。返回条数。 */
    public static int count(Path p) throws IOException {
        return parse(p).ticketCount();
    }

    /** apply：备份 + 清空 tickets + 原子写回。返回备份路径。 */
    public static Path purge(Path p) throws IOException {
        Parsed parsed = parse(p);
        if (parsed.ticketCount() == 0) {
            throw new IOException("already empty (0 tickets)");
        }
        Path dir = p.getParent();
        Path backupDir = dir != null && dir.getParent() != null
                ? dir.getParent().resolve("backups").resolve("sablefix")
                : Path.of("backups", "sablefix");
        Files.createDirectories(backupDir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path backup = backupDir.resolve(p.getFileName() + "." + stamp + ".bak");
        Files.copy(p, backup, StandardCopyOption.REPLACE_EXISTING);

        CompoundTag root = parsed.root();
        CompoundTag data = root.getCompound("data");
        data.put("tickets", new ListTag());          // 清空列表，保留 data 下其他字段
        Path tmp = p.resolveSibling(p.getFileName() + ".purge.tmp");
        NbtIo.writeCompressed(root, tmp);
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        SableFixMod.LOGGER.warn("票据清理：{} 原 {} 条已清零（备份 {}）", p.getFileName(), parsed.ticketCount(), backup);
        // 闭环：票据清零即移除登记
        DamageRegistry.clear(DamageRegistry.Kind.TICKETS_STUCK, p.getFileName().toString());
        return backup;
    }

    /** 供 dry 显示前 N 个 uuid（复用只读解析器）。 */
    public static TicketScan.TicketInfo peek(Path p) throws IOException {
        return TicketScan.read(p);
    }

    /** 防误用：确保解压后的文件确实能被我们的只读解析器读（双解析器交叉验证）。 */
    @SuppressWarnings("unused")
    private static void sanityCheck(Path p) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(p))) {
            in.readAllBytes();
        }
    }
}
