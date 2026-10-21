package dev.sfx.sablefix.scan;

import dev.sfx.sablefix.SableFixMod;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 子层级修复：把死指针/坏条目槽位清零（最小侵入——不重建数据，只让加载器跳过坏槽）。
 * 安全模型：
 *  - dry  永不写盘，只报告将要清零的槽号；
 *  - apply 先把原文件完整备份到 backups/sablefix/<name>.<时间戳>.bak，再执行；
 *  - 只清指针表（前 4096 字节）里的槽位为 0，绝不触碰数据区；
 *  - 文件被占用（服务器运行中 Sable 可能持有句柄）时抛 IOException，命令层给出"建议停服后执行"。
 */
public final class SubLevelRepair {

    private SubLevelRepair() {}

    /** 一份修复计划：要清零的槽号列表（0-based，指针表内序号）。 */
    public record RepairPlan(Path file, boolean slvlr, List<Integer> slots, long size) {
        public boolean isEmpty() { return slots.isEmpty(); }
    }

    /** 修复结果。 */
    public record RepairResult(Path file, Path backup, int cleared) {}

    // ---------- 计划 ----------

    /** 扫描并生成修复计划；文件健康时返回空槽列表。 */
    public static RepairPlan plan(Path f) throws IOException {
        String name = f.getFileName().toString();
        if (name.endsWith(".slvlr")) return planSlvlr(f);
        if (name.endsWith(".slvls")) return planSlvls(f);
        throw new IOException("unsupported file type: " + name);
    }

    private static RepairPlan planSlvlr(Path f) throws IOException {
        long size = Files.size(f);
        if (size < 4096) {
            // 回归审计：check 记 1 死指针但 plan 返回空计划 → dry 显示"无需修复"→
            // 登记永不 clear → WARN/进服提示永久循环。改为明确抛"不可修复"，
            // dry 与 apply 一致走 repair_fail 提示路径（不再静默空计划）。
            throw new IOException("header truncated (<4096B), file not repairable: " + f.getFileName());
        }
        List<Integer> slots = new ArrayList<>();
        // 七轮审计#5：head 与段读合并到同一个 channel（原先两次 open，600 文件 = 1200 次）
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ)) {
            ByteBuffer head = readHead(ch);
            if (!headComplete(head)) {
                // 十轮审计#7：头部读不满 = 不可修复（未读到的槽会被当空槽跳过 → 死槽漏报）。
                throw new IOException("header incomplete (short read), file not repairable: " + f.getFileName());
            }
            for (int i = 0; i < 1024; i++) {
                long ptr = Integer.toUnsignedLong(head.getInt(i * 4));
                if (ptr == 0) continue;
                if (ptr < 4096 || ptr + 5 > size) { slots.add(i); continue; }
                ByteBuffer rec = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
                // 十轮审计#36：positioned read 契约可短读（返回 0..4）——单次 read 读不满时
                // rec 里是 allocate 的 0 → recLen=0 → 误判为死指针 → 计划里多出这个槽 →
                // apply 会把**本来完好的记录清零**（误报 → 破坏性清零）。
                // SubLevelScan 的批量路径早有补读循环（审计#4），此处补齐同构写法。
                // 循环条件带 r > 0：read 允许返回 0（网络存储），否则 hasRemaining 恒真 → 死循环。
                int got = 0, r;
                while (rec.hasRemaining() && (r = ch.read(rec, ptr + got)) > 0) {
                    got += r;
                }
                if (got < 4) { slots.add(i); continue; }
                long recLen = Integer.toUnsignedLong(rec.getInt(0));
                if (recLen < 1 || ptr + 4 + recLen > size) slots.add(i);
            }
        }
        return new RepairPlan(f, true, slots, size);
    }

    private static RepairPlan planSlvls(Path f) throws IOException {
        long size = Files.size(f);
        if (size < 4096) {
            // 回归审计：与 planSlvlr 同因——截断文件 check 记坏但 plan 静默空 → 提示死循环
            throw new IOException("header truncated (<4096B), file not repairable: " + f.getFileName());
        }
        // 七轮审计#5：readHead 现在需要 channel（与 planSlvlr 合并 open 的同一改法）
        ByteBuffer head;
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ)) {
            head = readHead(ch);
            if (!headComplete(head)) {
                // 十轮审计#7：同上，头部读不满 = 不可修复。
                throw new IOException("header incomplete (short read), file not repairable: " + f.getFileName());
            }
        }
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < 1024; i++) {
            int off = ((head.get(i * 4) & 0xFF) << 16) | ((head.get(i * 4 + 1) & 0xFF) << 8) | (head.get(i * 4 + 2) & 0xFF);
            int cnt = head.get(i * 4 + 3) & 0xFF;
            if (off == 0 && cnt == 0) continue;
            long end = (long) off * 4096 + (long) cnt * 4096;
            if (off == 0 || cnt == 0 || end > size) slots.add(i);
        }
        return new RepairPlan(f, false, slots, size);
    }

    // ---------- 执行 ----------

    /** 备份 + 清零计划中的槽位。备份目录：…/world/backups/sablefix/（锚定存档，不依赖 CWD）。 */
    public static RepairResult apply(RepairPlan plan) throws IOException {
        if (plan.slvlr() && plan.size() < 4096) throw new IOException("header truncated, not repairable");
        if (!plan.slvlr() && plan.size() < 4096) throw new IOException("header truncated, not repairable");
        Path dir = plan.file().getParent();                 // …/world/sublevels
        Path backupDir = dir != null && dir.getParent() != null
                ? dir.getParent().resolve("backups").resolve("sablefix")  // …/world/backups/sablefix（锚定存档，不依赖 CWD）
                : Path.of("backups", "sablefix");
        Files.createDirectories(backupDir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));   // 含毫秒：同秒多次修复不互相覆盖
        Path backup = backupDir.resolve(plan.file().getFileName() + "." + stamp + ".bak");
        // 十轮审计#19：绝不覆盖已有备份（与 TicketPurge 同构）。清零是破坏性操作，
        //  备份是恢复原文件的唯一副本——宁可因同名失败，也不静默覆盖掉上一份。
        //  同毫秒再撞：加 -1/-2 后缀，保留全部历史备份。
        for (int i = 1; Files.exists(backup) && i < 1000; i++) {
            backup = backupDir.resolve(plan.file().getFileName() + "." + stamp + "-" + i + ".bak");
        }
        // 十轮审计#38：备份**必须落盘**才能算备份。Files.copy 只写进 page cache 就返回，
        //  数据可能还在 OS 缓存里——此刻断电/强制重启 → 备份文件存在但内容是空的或半截，
        //  而原文件已被清零 → **数据不可恢复**，管理员却以为"有备份"。
        // 手写 copy + force(true)（元数据+内容一起落盘）。探针实测 1MB 文件仅多花 ~1ms。
        copyAndSync(plan.file(), backup);

        // 写回前重扫：备份与写回之间文件若被并发修改（Sable 运行时），死槽位号可能已变——按计划盲写会破坏新数据。
        // 保守策略：重扫结果与计划不一致就放弃本次修复（备份保留，报错给上层）。
        RepairPlan rechecked = plan(plan.file());
        if (!rechecked.slots().equals(plan.slots())) {
            throw new IOException("file changed since plan (concurrent write?) — repair aborted, backup kept: " + backup);
        }

        int cleared = 0;
        try (RandomAccessFile raf = new RandomAccessFile(plan.file().toFile(), "rw")) {
            for (int slot : plan.slots()) {
                long off = (long) slot * 4;
                if (plan.slvlr()) {
                    raf.seek(off);
                    raf.writeInt(0);            // 大端 0 = 空槽
                } else {
                    raf.seek(off);
                    raf.write(new byte[]{0, 0, 0, 0});  // 3B 扇区号 + 1B 扇区数 = 空槽
                }
                cleared++;
            }
        }
        SableFixMod.LOGGER.warn("子层级修复：{} 清零 {} 个槽位（备份 {}）", plan.file().getFileName(), cleared, backup);
        // 闭环：修复成功即从损伤登记簿移除（周期 WARN 停止、玩家进服不再提示）
        String fname = plan.file().getFileName().toString();
        DamageRegistry.clear(DamageRegistry.Kind.SUBLVL_DEAD, fname);
        return new RepairResult(plan.file(), backup, cleared);
    }

    /** 复制并**强制落盘**（十轮审计#38）。
     *  保持 Files.copy 的语义：不带 REPLACE_EXISTING → 目标已存在则抛 FileAlreadyExistsException
     *  （备份绝不覆盖）。额外做 force(true)：内容与元数据都到盘，才算"备份成功"。 */
    private static void copyAndSync(Path src, Path dst) throws IOException {
        try (FileChannel in = FileChannel.open(src, StandardOpenOption.READ);
             FileChannel out = FileChannel.open(dst, StandardOpenOption.WRITE,
                     StandardOpenOption.CREATE_NEW)) {   // CREATE_NEW = 已存在则抛 FileAlreadyExistsException
            long pos = 0, size = in.size();
            ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
            while (pos < size) {
                buf.clear();
                int r = in.read(buf, pos);
                if (r < 0) break;
                if (r == 0) continue;      // 允许短读/返回 0（网络存储），继续读
                buf.flip();
                while (buf.hasRemaining()) out.write(buf);
                pos += r;
            }
            out.force(true);              // 落盘：内容 + 元数据
        }
    }

    /** 读取 4KB 文件头。七轮审计#5：改为在调用方已打开的 channel 上读（合并 open）。
     *  读完后 position 停在 4096，后续绝对读（ch.read(buf, pos)）不受影响。
     *  十轮审计#7：读不满时缓冲区余下部分为 0，绝对 getInt 不受 position 限制 →
     *  未读到的槽会被当**空槽**跳过 → 死槽位漏报（损坏没被修复，修复计划不完整）。
     *  与 SubLevelScan.checkSlvlr 的 headComplete 同构：读不满 = 不可修复，fail loud。 */
    private static ByteBuffer readHead(FileChannel ch) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(4096).order(ByteOrder.BIG_ENDIAN);
        while (buf.hasRemaining() && ch.read(buf) > 0) {}
        return buf;
    }

    /** 头部是否读满 4096 字节。 */
    private static boolean headComplete(ByteBuffer head) {
        return head.position() >= 4096;
    }
}
