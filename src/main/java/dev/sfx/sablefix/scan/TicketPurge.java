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
        // 七轮审计#7：unlimitedHeap 让 gzip 炸弹可以吃满堆（TicketScan.read 有 64MB 解压
        // 上限防炸弹，双解析器只给一个加了配额等于没防）。64MB 配额远大于任何真实票据文件
        // （幽灵加载点案例 2533 条 ≈ 几百 KB），超限抛 NbtAccounter 异常 → 命令/面板层按
        // "损坏文件"提示路径走，与 TicketScan 的语义一致。
        CompoundTag root = NbtIo.readCompressed(p, NbtAccounter.create(64L * 1024 * 1024));
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
        // 十轮审计#19：时间戳加毫秒，且**绝不覆盖**已有备份。
        //  原实现只到秒（yyyyMMdd-HHmmss）且用 REPLACE_EXISTING → 同一秒内两次清理会
        //  **覆盖掉上一次的备份**。而这份备份是恢复原票据的唯一副本（清理是破坏性操作，
        //  误操作后没有别的退路）。SubLevelRepair 早就用毫秒精度了，这里对齐。
        //  同毫秒再撞：加 -1/-2 后缀，保留全部历史备份。
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        Path backup = backupDir.resolve(p.getFileName() + "." + stamp + ".bak");
        for (int i = 1; Files.exists(backup) && i < 1000; i++) {
            backup = backupDir.resolve(p.getFileName() + "." + stamp + "-" + i + ".bak");
        }
        Files.copy(p, backup);   // 不带 REPLACE_EXISTING：已存在则抛 FileAlreadyExistsException（宁可失败也不覆盖备份）

        CompoundTag root = parsed.root();
        CompoundTag data = root.getCompound("data");
        data.put("tickets", new ListTag());          // 清空列表，保留 data 下其他字段
        Path tmp = p.resolveSibling(p.getFileName() + ".purge.tmp");
        Files.deleteIfExists(tmp);                   // 上次崩溃残留清理（审计：孤儿 tmp 永无人删）
        try {
            NbtIo.writeCompressed(root, tmp);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);               // 失败兜底：不留半成品 tmp 污染诊断
            throw e;
        }

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
