package dev.sfx.sablefix.watchdog;

import dev.sfx.sablefix.SableFixMod;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * E：主线程卡死监视器。
 * 原版看门狗 60 秒直接杀服，且只留下被 park 的那一层栈。
 * 这里在卡死 20 秒时就把全部线程栈写盘，每 5 分钟再补一份，
 * 保证下一次 save-all 卡死时能拿到"谁该完成任务却没干"的现场。
 *
 * 线程模型：onTick 由主线程调用；loop 由守护线程调用；
 * 计数器用 Atomic；lastTickNanos volatile（单写多读）。
 */
public final class ThreadWatchdog {
    private static final long ALERT_NANOS = 20_000_000_000L;
    /** 补抓间隔 5 分钟（用户反馈：卡死期间每 15s 一份会把内存/磁盘打爆——
     *  一份全线程栈 50-500KB，卡 1 小时 = 240 份 ≈ 100MB。改 300s：同场景 12 份，
     *  且 50 份保留上限下足够覆盖 10 小时的卡死现场）。 */
    private static final long REDUMP_NANOS = 300_000_000_000L;
    private static final long POLL_MILLIS = 5_000L;
    /** 损伤登记簿的周期提醒间隔（DamageRegistry.periodicWarn 使用） */
    public static final long WARN_PERIOD_MS = 5 * 60_000L;

    private static volatile boolean running;
    private static volatile long lastTickNanos = System.nanoTime();
    private static volatile long lastGapMs;
    private static volatile long lastDumpNanos;
    /** 最近一次卡死抓栈的时刻（epoch millis；0=本次运行未发生过）——面板提示条用 */
    private static volatile long lastStallAtMs;
    private static final AtomicInteger DUMPS = new AtomicInteger();
    private static Thread thread;

    private ThreadWatchdog() {}

    public static synchronized void start() {
        if (thread != null) return;
        running = true;
        lastTickNanos = System.nanoTime();
        lastDumpNanos = 0;
        // 十轮审计#18：会话状态一并重置。原实现重置了 lastTickNanos/lastDumpNanos，
        // 却漏了 lastStallAtMs 与 DUMPS——两者是 **static** 字段，跨服务器实例存活。
        // 同一 JVM 重开服务器（面板服换存档/重启是常见操作）时：
        //  - lastStallAtMs 仍是**上一个服务器**的卡死时刻 → 面板显示「上次卡死 N 分钟前」
        //    会指向别的存档的故障（跨存档误导性诊断信息）；
        //  - DUMPS 是 AtomicInteger → 抓栈次数跨服累积，新服显示一个虚高的数字。
        // 面板消费方：ServerHandlers.lastStallAtMs()/dumpCount() → PanelData。
        lastStallAtMs = 0L;
        DUMPS.set(0);
        thread = new Thread(ThreadWatchdog::loop, "SableFix-Watchdog");
        thread.setDaemon(true);
        thread.start();
        ModLogHolder.started();
        SableFixMod.LOGGER.info("主线程监视器已启动（卡死 {}s 抓栈）", ALERT_NANOS / 1_000_000_000L);
    }

    public static synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    public static void onTick() {
        long now = System.nanoTime();
        lastGapMs = (now - lastTickNanos) / 1_000_000L;
        lastTickNanos = now;
    }

    /** 距上一次 tick 的毫秒数（面板显示用） */
    public static long lastTickGapMs() { return lastGapMs; }
    /** 本服累计抓栈次数（原子读，供看门狗线程与主线程并发访问） */
    public static int dumpCount() { return DUMPS.get(); }
    /** 最近一次卡死抓栈的 epoch millis（0=本次运行未卡死过）。面板显示"上次卡死 N 分钟前"。 */
    public static long lastStallAtMs() { return lastStallAtMs; }

    private static void loop() {
        while (running) {
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
            long stall = System.nanoTime() - lastTickNanos;
            if (stall < ALERT_NANOS) continue;
            if (System.nanoTime() - lastDumpNanos < REDUMP_NANOS && lastDumpNanos != 0) continue;
            lastDumpNanos = System.nanoTime();
            // 七轮审计#6：dump() 只捕获 IOException——SecurityException、dumpAllThreads 的
            // UnsupportedOperationException 等未受检异常会从 loop() 逸出、杀死看门狗线程，
            // 此后整个服务器生命周期内再无卡死抓栈（恰是本模组的目标场景）。补外层捕获；
            // lastDumpNanos 已在 dump 前置位，失败同样进 5 分钟冷却，不会引发异常风暴。
            try {
                dump(stall);
            } catch (Exception e) {
                SableFixMod.LOGGER.error("看门狗抓栈异常（线程存活，继续监视）", e);
            }
        }
    }

    /** 抓一份全线程栈；stallNanos 为 -1 表示手动触发。手动抓栈后重置冷却，避免自动重复抓栈。 */
    public static void dump(long stallNanos) {
        Path out = null;   // 十轮审计#28：提到 try 外——写入失败时需要删掉半截文件
        try {
            // 锚定游戏运行目录（服务器 CWD 可能有差异，用 MinecraftServer 运行目录兜底）
            Path dir = resolveDumpDir();
            Files.createDirectories(dir);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
            // 十轮审计#28：毫秒精度仍有碰撞可能（手动抓栈与自动抓栈同一毫秒），
            //  而 newBufferedWriter 默认 CREATE+TRUNCATE → 同名会**截断覆盖**，
            //  丢掉先写的那份栈证据。加碰撞后缀，绝不覆盖已有 dump。
            out = dir.resolve("threaddump-" + stamp + ".txt");
            for (int i = 1; Files.exists(out) && i < 100; i++) {
                out = dir.resolve("threaddump-" + stamp + "-" + i + ".txt");
            }
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
                w.printf("主线程已 %.1f 秒没有 tick%n", stallNanos / 1_000_000_000.0);
                w.printf("抓取时间: %s%n%n", LocalDateTime.now());
                // Thread.getAllStackTraces() 在 JDK 20+ 已过时（一致性/活性问题）——
                // 改用 ThreadMXBean.dumpAllThreads（官方推荐的等价 API，且含锁信息能力）
                java.lang.management.ThreadMXBean mx = java.lang.management.ManagementFactory.getThreadMXBean();
                java.lang.management.ThreadInfo[] infos = mx.dumpAllThreads(true, true);
                if (infos == null) {
                    // 理论不应为 null（JMX 实现返回空数组而非 null），但栈证据是卡死现场的
                    //  唯一来源——为 null 时写一行说明并保留文件，别让整个抓栈白跑。
                    w.println("(线程信息不可用: dumpAllThreads 返回 null)");
                }
                if (infos != null) for (java.lang.management.ThreadInfo ti : infos) {
                    w.printf("\"%s\" prio=%d id=%d %s%n", ti.getThreadName(),
                            ti.getPriority(), ti.getThreadId(), ti.getThreadState());
                    for (StackTraceElement ste : ti.getStackTrace()) {
                        w.printf("\tat %s%n", ste);
                    }
                    w.println();
                }
            }
            DUMPS.incrementAndGet();
            lastDumpNanos = System.nanoTime();   // 手动抓栈也进冷却，防自动线程紧接着重复抓
            lastStallAtMs = System.currentTimeMillis();   // 最近卡死时刻（面板提示条）
            pruneOldDumps(dir);
            ModLogHolder.dumped(stallNanos, out);
            SableFixMod.LOGGER.warn("主线程已卡 {} 秒！线程栈已写入 {}", stallNanos / 1_000_000_000.0, out);
        } catch (IOException ex) {
            // 十轮审计#28：写入失败（磁盘满/权限）会留下一个**残缺的** threaddump-*.txt：
            //  ① 它占用 50 份保留配额（pruneOldDumps 按文件数）→ 挤掉真正的有效栈；
            //  ② DiagReport.latestDump() 按 mtime 选最新 → 可能选中这个残缺文件
            //  → 诊断报告里的「线程栈」是空的，比没有更糟。失败即删除半截文件。
            try { if (out != null) Files.deleteIfExists(out); } catch (IOException ignored) {}
            // 八轮审计#2：LOGGER 走控制台；同步进 ModLog 环形缓冲（面板日志页可见），复活死键 watch.write_fail
            SableFixMod.LOGGER.error("写线程栈失败", ex);
            dev.sfx.sablefix.log.ModLog.errorT("sablefix.watch.write_fail");
        }
    }

    /** 锚定日志目录：<游戏运行目录>/logs/sablefix。优先 JVM user.dir（服务器启动目录），失败退回 CWD。 */
    private static Path resolveDumpDir() {
        try {
            return Path.of(System.getProperty("user.dir"), "logs", "sablefix");
        } catch (Exception e) {
            return Path.of("logs", "sablefix");
        }
    }

    /** 审计#6：dump 文件保留上限——反复卡死的服务器（本模组目标场景）月积数百 MB。
     *  每次成功 dump 后按修改时间保留最近 50 份，删更老的；失败仅 debug 日志不影响抓栈。 */
    private static final int MAX_DUMPS_KEPT = 50;

    private static void pruneOldDumps(Path dir) {
        try (var stream = Files.list(dir)) {
            var files = stream.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("threaddump-") && n.endsWith(".txt");
                    }).sorted(java.util.Comparator.comparingLong(p -> {
                        try { return Files.getLastModifiedTime((Path) p).toMillis(); }
                        catch (IOException e) { return 0L; }
                    }).reversed())
                    .toList();
            for (int i = MAX_DUMPS_KEPT; i < files.size(); i++) {
                try { Files.delete(files.get(i)); } catch (IOException ignored) {}
            }
        } catch (Exception e) {
            SableFixMod.LOGGER.debug("旧 dump 清理失败（不影响抓栈）", e);
        }
    }

    /** 锚定日志目录（供 ModLog 面板显示与命令提示复用） */
    public static String dumpDirName() {
        return "logs/sablefix/";
    }

    /** 把 ModLog 依赖收进内部 holder，便于隔离与测试 */
    private static final class ModLogHolder {
        static void started() {
            dev.sfx.sablefix.log.ModLog.infoT("sablefix.watch.started", ALERT_NANOS / 1_000_000_000L);
        }
        static void dumped(long stallNanos, Path out) {
            dev.sfx.sablefix.log.ModLog.errorT("sablefix.watch.dumped", stallNanos / 1_000_000_000.0, out);
        }
    }
}
