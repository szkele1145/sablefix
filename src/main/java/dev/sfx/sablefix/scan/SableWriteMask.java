package dev.sfx.sablefix.scan;

import dev.sfx.sablefix.SableFixMod;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Sable 写回屏蔽（fix 三连的第 1 步）：
 * 运行时修复被 Sable 周期写回覆盖——Sable 内存持有子层级索引并定期落盘。
 *
 * 方案（保底，逆向出 Sable 写回类名前）：
 * sublevels 全部文件设 Windows 只读 → Sable 周期写回打开失败 → 走它自身的异常处理
 * （catch 级兜底，不崩服，内存索引照常服务玩家）。修复每个文件的写入瞬间用
 * withWritable() 单文件解锁/回锁——毫秒窗口 vs Sable 的分钟级周期，碰撞概率可忽略。
 *
 * 生命周期：maskAll()（fix 开始）→ withWritable()（逐文件修复）→ 只读**贯穿 shutdown**
 * → ServerStoppedEvent 解锁（Sable 垂死写回已成定局）→ 新进程启动兜底 unmaskAll(server)。
 *
 * P0 修复（1.5.4）：原先在 halt 前 unmask，实测 /sablefix fix 后面板自动重启仍报同样损坏。
 * 根因：Sable 进程被 halt(false) 触发的停服存档仍持有内存中陈旧的子层级索引，
 * 一旦文件恢复可写就会把这些陈旧索引写回磁盘，覆盖刚清零的槽位。保持只读穿过 shutdown
 * 是唯一能挡住这次写回的时机（Sable 写失败走自身 catch 吞掉，不崩服）。
 *
 * 任何异常路径都会解锁——若进程被强杀没来得及恢复，文件残留只读时 Sable 会在日志里
 * 明确报"无法写子层级"，管理员一眼可见（fail-loud 优于静默覆盖），下次开服自动兜底解锁。
 */
public final class SableWriteMask {

    private SableWriteMask() {}

    private static final List<Path> MASKED = new ArrayList<>();

    /** fix 全流程守卫：运行中拒绝再次进入（对抗审计#1：双 fix 交错 = MASKED 竞态 + halt 杀 daemon 线程 = 残留只读） */
    private static final java.util.concurrent.atomic.AtomicBoolean FIX_RUNNING = new java.util.concurrent.atomic.AtomicBoolean();

    /** 平台探测（二轮审计#6）：Linux 无 dos:readonly—— UnsupportedOperationException 若逃逸会让
     *  fix 第一步就整体失败。启动时探测一次缓存：false=跳过屏蔽步骤（fix 降级为纯修复模式）。 */
    private static volatile Boolean dosSupported;

    /** dos:readonly 是否可用（惰性探测，结果缓存）。 */
    static boolean dosSupported(Path sample) {
        Boolean s = dosSupported;
        if (s != null) return s;
        try {
            if (sample == null || !Files.exists(sample)) {
                // 没样本文件：临时造一个探测
                Path probe = Files.createTempFile("sfx-dos-probe", ".tmp");
                try {
                    Files.setAttribute(probe, "dos:readonly", true);
                    Files.setAttribute(probe, "dos:readonly", false);
                    s = true;
                } finally { Files.deleteIfExists(probe); }
            } else {
                Files.getAttribute(sample, "dos:readonly");
                s = true;
            }
        } catch (UnsupportedOperationException e) {
            s = false;
        } catch (Exception e) {
            s = false;   // IO 故障按不支持处理（fix 降级，宁可不屏蔽也不炸）
        }
        dosSupported = s;
        return s;
    }

    /** 尝试进入 fix 流程；false=已在运行中（调用方拒绝）。 */
    public static boolean tryBeginFix() { return FIX_RUNNING.compareAndSet(false, true); }
    public static void endFix() { FIX_RUNNING.set(false); }

    /** /sablefix unmask：残留只读兜底解锁（对抗审计#8——fix 被外部强杀后管理员的救命命令）。
     *  不依赖 MASKED 清单（进程被杀清单就丢了）：直接扫 sublevels 恢复一切只读的 slvlr/slvls。
     *  二轮审计#2：整体 synchronized——与主线程终局 unmask / SCAN_POOL 的 withWritable 互斥，
     *  消除全扫 vs 回锁竞态导致的残留只读。返回解锁文件数。 */
    public static synchronized int unmaskAll(net.minecraft.server.MinecraftServer server) {
        int n = unmaskAll();   // 清单内（若同进程）先恢复
        try {
            Path sublevels = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("sublevels");
            if (Files.isDirectory(sublevels)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(sublevels, "*.{slvlr,slvls}")) {
                    for (Path p : ds) {
                        try {
                            if ((Boolean) Files.getAttribute(p, "dos:readonly")) {
                                Files.setAttribute(p, "dos:readonly", false);
                                n++;
                            }
                        } catch (IOException | UnsupportedOperationException ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}
        return n;
    }

    /** 屏蔽全部：sublevels 下所有 .slvlr/.slvls 设只读（原本只读的不记——unmask 不能动用户的）。
     *  对抗审计#1：不做 MASKED.clear()——重复 fix 的交错下 clear 会扔掉上一轮清单导致文件残留只读。
     *  增量添加；已只读（无论谁设的）不入清单。幂等。
     *  二轮审计#6：Linux 无 dos 支持 → 返回 false，fix 降级为纯修复模式（跳过屏蔽，修复照跑）。 */
    public static synchronized boolean maskAll(net.minecraft.server.MinecraftServer server) {
        Path sublevels = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("sublevels");
        if (!Files.isDirectory(sublevels)) return true;
        // 七轮审计#2：原先前置开一次目录流取"样本文件"给 dosSupported，再开第二次流做屏蔽——两次 open。
        // 合并为一次：首个文件兼作探测样本（必须用目标卷上的真实文件探测，不能用系统 temp——
        // sublevels 可能位于 FAT32/网络盘，与 temp 卷的支持性不同），同流继续完成屏蔽。
        int masked = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(sublevels, "*.{slvlr,slvls}")) {
            for (Path p : ds) {
                if (!dosSupported(p)) {
                    SableFixMod.LOGGER.warn("mask: 本平台无 dos:readonly（Linux/macOS）——跳过写回屏蔽，fix 降级为纯修复模式");
                    // 探测失败即中止：此时尚未改动任何文件（dosSupported 先于 setAttribute）
                    return false;
                }
                try {
                    if (!(Boolean) Files.getAttribute(p, "dos:readonly")) {
                        Files.setAttribute(p, "dos:readonly", true);
                        MASKED.add(p);
                        masked++;
                    }
                } catch (IOException e) {
                    SableFixMod.LOGGER.warn("mask: {} 设置只读失败（继续）", p.getFileName());
                }
            }
        } catch (IOException e) {
            SableFixMod.LOGGER.warn("mask: 扫描 sublevels 失败（无屏蔽）", e);
        }
        // 空目录：dosSupported 从未被调用 → 没有文件需要屏蔽，视为成功
        SableFixMod.LOGGER.warn("mask: {} 个子层级文件已只读（Sable 写回被屏蔽）", masked);
        return true;
    }

    /** 恢复全部：mask 期间设只读的文件恢复可写（清单驱动）。P0 修复后不再在 halt 前调用——
     *  改由 ServerStoppedEvent（存档落盘后）解锁，新进程启动时另有目录扫描兜底。返回恢复数。 */
    public static synchronized int unmaskAll() {
        int n = 0;
        for (Path p : MASKED) {
            try {
                Files.setAttribute(p, "dos:readonly", false);
                n++;
            } catch (IOException e) {
                SableFixMod.LOGGER.error("unmask: {} 恢复可写失败——请手动取消只读！Sable 将无法保存子层级！", p.getFileName());
            }
        }
        SableFixMod.LOGGER.warn("unmask: {} 个文件已恢复可写", n);
        MASKED.clear();
        return n;
    }

    /** 修复写入的毫秒窗口：单文件临时解锁 → 执行写入 → 回锁。
     *  调用方在 callable 里做实际的 apply（备份+清零）。 */
    public static synchronized <T> T withWritable(Path file, Callable<T> writeAction) throws Exception {
        boolean was = false;
        try {
            was = (Boolean) Files.getAttribute(file, "dos:readonly");
        } catch (IOException ignored) {}
        if (was) Files.setAttribute(file, "dos:readonly", false);
        try {
            return writeAction.call();
        } finally {
            if (was) {
                try { Files.setAttribute(file, "dos:readonly", true); } catch (IOException ignored) {}
            }
        }
    }
}
