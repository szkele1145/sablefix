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
 * 生命周期：maskAll()（fix 开始）→ withWritable()（逐文件修复）→ unmaskAll()（踢人后、halt 前）。
 * 任何异常路径都会 unmaskAll——若进程被强杀没来得及恢复，文件残留只读时 Sable 会
 * 在日志里明确报"无法写子层级"，管理员一眼可见（fail-loud 优于静默覆盖）。
 */
public final class SableWriteMask {

    private SableWriteMask() {}

    private static final List<Path> MASKED = new ArrayList<>();

    /** fix 全流程守卫：运行中拒绝再次进入（对抗审计#1：双 fix 交错 = MASKED 竞态 + halt 杀 daemon 线程 = 残留只读） */
    private static final java.util.concurrent.atomic.AtomicBoolean FIX_RUNNING = new java.util.concurrent.atomic.AtomicBoolean();

    /** 尝试进入 fix 流程；false=已在运行中（调用方拒绝）。finally 里 release()。 */
    public static boolean tryBeginFix() { return FIX_RUNNING.compareAndSet(false, true); }
    public static void endFix() { FIX_RUNNING.set(false); }

    /** 屏蔽全部：sublevels 下所有 .slvlr/.slvls 设只读（原本只读的不记——unmask 不能动用户的）。
     *  对抗审计#1：不做 MASKED.clear()——重复 fix 的交错下 clear 会扔掉上一轮清单导致文件残留只读。
     *  增量添加；已只读（无论谁设的）不入清单。幂等。 */
    public static synchronized void maskAll(net.minecraft.server.MinecraftServer server) {
        Path sublevels = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("sublevels");
        if (!Files.isDirectory(sublevels)) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(sublevels, "*.{slvlr,slvls}")) {
            for (Path p : ds) {
                try {
                    if (!(Boolean) Files.getAttribute(p, "dos:readonly")) {
                        Files.setAttribute(p, "dos:readonly", true);
                        MASKED.add(p);
                    }
                } catch (IOException e) {
                    SableFixMod.LOGGER.warn("mask: {} 设置只读失败（继续）", p.getFileName());
                }
            }
        } catch (IOException e) {
            SableFixMod.LOGGER.warn("mask: 扫描 sublevels 失败（无屏蔽）", e);
        }
        SableFixMod.LOGGER.warn("mask: {} 个子层级文件已只读（Sable 写回被屏蔽）", MASKED.size());
    }

    /** 恢复全部：mask 期间设只读的文件恢复可写。halt 前必调。 */
    public static synchronized void unmaskAll() {
        for (Path p : MASKED) {
            try {
                Files.setAttribute(p, "dos:readonly", false);
            } catch (IOException e) {
                SableFixMod.LOGGER.error("unmask: {} 恢复可写失败——请手动取消只读！Sable 将无法保存子层级！", p.getFileName());
            }
        }
        SableFixMod.LOGGER.warn("unmask: {} 个文件已恢复可写", MASKED.size());
        MASKED.clear();
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
