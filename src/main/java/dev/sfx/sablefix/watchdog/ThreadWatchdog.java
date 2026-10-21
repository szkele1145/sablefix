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

    /** 十轮审计#57：看门狗会话代号。stop() 只是把 running 置 false 并 interrupt，
     *  若旧看门狗线程**正卡在 dump() 里**（写盘耗时），它仍会把这次抓栈写完：
     *  ① DiagReport.latestDump() 按 **mtime 最新**挑栈文件 → 旧存档的卡死现场会被当成
     *     「最新」→ 诊断报告里的线程栈是**别的存档**的（排查被误导，与 #28「残缺报告比没有更糟」同族）；
     *  ② DUMPS / lastStallAtMs / lastDumpNanos / ModLog 全部污染新会话。
     *  旧存档的卡死现场对**新存档**没有诊断价值（进程状态都不同），且会挤掉新会话的真实现场
     *  → 过期会话的抓栈**整个不做**（连文件都不写）。 */
    private static final java.util.concurrent.atomic.AtomicLong SESSION =
            new java.util.concurrent.atomic.AtomicLong();
    /** 看门狗线程绑定的会话代号（null = 主线程/当前会话，如 /sablefix dump 手动触发）。 */
    private static final ThreadLocal<Long> BOUND_SESSION = new ThreadLocal<>();

    /** 本次调用是否仍属当前会话：未绑定（主线程）→ 属当前；已绑定但代号过期 → 否。 */
    private static boolean sessionAllowed() {
        Long bound = BOUND_SESSION.get();
        return bound == null || bound == SESSION.get();
    }

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
        // 十轮审计#57：推进会话代号并绑定本线程——旧看门狗线程的抓栈从此被判定为过期
        final long session = SESSION.incrementAndGet();
        thread = new Thread(() -> {
            BOUND_SESSION.set(session);
            try {
                loop();
            } finally {
                BOUND_SESSION.remove();
            }
        }, "SableFix-Watchdog");
        thread.setDaemon(true);
        thread.start();
        ModLogHolder.started();
        SableFixMod.LOGGER.info("主线程监视器已启动（卡死 {}s 抓栈）", ALERT_NANOS / 1_000_000_000L);
    }

    public static synchronized void stop() {
        running = false;
        // 十轮审计#57：推进会话代号——旧看门狗线程的循环条件据此判定过期并退出。
        // 只靠 running=false 不够：旧线程若正卡在 dump() 写盘里，interrupt 被吞，
        // 循环回到 while 时 running 已被下一会话 start() 置回 true → **旧线程复活**。
        SESSION.incrementAndGet();
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
        // 十轮审计#57：循环条件用**会话代号**而不是 static 的 running。
        // 原实现 `while (running)`：stop() 置 false + interrupt + thread=null，而下一会话
        // start() 又置 true → **旧线程的循环复活**（interrupt 只在 sleep 处生效；若它当时正
        // 卡在 dump() 写盘里，interrupt 被吞掉）→ 两个看门狗线程同时跑。
        // 且 lastDumpNanos 是 static 共享 → 旧线程抢先置位 5 分钟冷却 → 新会话的真看门狗
        // **跳过抓栈** → 卡死现场丢失（本模组的目标场景）。
        while (sessionAllowed()) {
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

    /** 抓一份全线程栈；stallNanos 为 -1 表示手动触发。手动抓栈后重置冷却，避免自动重复抓栈。
     *  返回写入的 dump 路径；失败返回 null（十轮审计#29：原返回 void → 命令层无法区分成功/失败，
     *  磁盘满时仍然回显「抓栈成功」，而文件已被删除）。 */
    public static Path dump(long stallNanos) {
        // 十轮审计#57：过期会话的看门狗抓栈**整个不做**——旧存档的卡死现场对新存档
        // 没有诊断价值（进程状态都不同），写下去还会挤掉新会话的真实现场
        // （DiagReport.latestDump 按 mtime 挑「最新」）。
        if (!sessionAllowed()) return null;
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
            // 十轮审计#49：碰撞处理改为**独占创建**（CREATE_NEW），已存在就换名重试。
            // 原实现用「先 Files.exists 探测、再 newBufferedWriter（默认 CREATE+TRUNCATE）」：
            // 探测与创建之间有竞态（另一次抓栈可能同时创建同名文件）→ 后者**截断覆盖**前者，
            // 丢掉先写的那份栈证据；且后缀循环上限 100 用尽后，i=100 退出循环时 out 仍
            // 可能已存在 → 照样覆盖。与 DiagReport 的 #40 同构（那边已改用 CREATE_NEW 占位）。
            // 栈证据是**唯一一份**卡死现场，绝不能被覆盖。
            PrintWriter w = null;
            for (int i = 0; i < 100; i++) {
                Path cand = (i == 0) ? dir.resolve("threaddump-" + stamp + ".txt")
                        : dir.resolve("threaddump-" + stamp + "-" + i + ".txt");
                try {
                    w = new PrintWriter(Files.newBufferedWriter(cand,
                            StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW,
                            java.nio.file.StandardOpenOption.WRITE));
                    out = cand;
                    break;
                } catch (java.nio.file.FileAlreadyExistsException e) {
                    // 同名已存在（极罕见：同毫秒多次抓栈）→ 换下一个后缀
                }
            }
            if (w == null) {
                throw new IOException("无法创建线程栈文件（前 100 个同名都已被占用）");
            }
            try (PrintWriter pw = w) {
                pw.printf("主线程已 %.1f 秒没有 tick%n", stallNanos / 1_000_000_000.0);
                pw.printf("抓取时间: %s%n%n", LocalDateTime.now());
                // Thread.getAllStackTraces() 在 JDK 20+ 已过时（一致性/活性问题）——
                // 改用 ThreadMXBean.dumpAllThreads（官方推荐的等价 API，且含锁信息能力）
                java.lang.management.ThreadMXBean mx = java.lang.management.ManagementFactory.getThreadMXBean();
                java.lang.management.ThreadInfo[] infos = mx.dumpAllThreads(true, true);
                if (infos == null) {
                    // 理论不应为 null（JMX 实现返回空数组而非 null），但栈证据是卡死现场的
                    //  唯一来源——为 null 时写一行说明并保留文件，别让整个抓栈白跑。
                    pw.println("(线程信息不可用: dumpAllThreads 返回 null)");
                }
                if (infos != null) for (java.lang.management.ThreadInfo ti : infos) {
                    pw.printf("\"%s\" prio=%d id=%d %s%n", ti.getThreadName(),
                            ti.getPriority(), ti.getThreadId(), ti.getThreadState());
                    for (StackTraceElement ste : ti.getStackTrace()) {
                        pw.printf("\tat %s%n", ste);
                    }
                    pw.println();
                }
            }
            DUMPS.incrementAndGet();
            lastDumpNanos = System.nanoTime();   // 手动抓栈也进冷却，防自动线程紧接着重复抓
            lastStallAtMs = System.currentTimeMillis();   // 最近卡死时刻（面板提示条）
            pruneOldDumps(dir);
            ModLogHolder.dumped(stallNanos, out);
            SableFixMod.LOGGER.warn("主线程已卡 {} 秒！线程栈已写入 {}", stallNanos / 1_000_000_000.0, out);
            return out;
        } catch (IOException | RuntimeException ex) {
            // 十轮审计#28：写入失败（磁盘满/权限）会留下一个**残缺的** threaddump-*.txt：
            //  ① 它占用 50 份保留配额（pruneOldDumps 按文件数）→ 挤掉真正的有效栈；
            //  ② DiagReport.latestDump() 按 mtime 选最新 → 可能选中这个残缺文件
            //  → 诊断报告里的「线程栈」是空的，比没有更糟。失败即删除半截文件。
            // 十轮审计#49：补 RuntimeException —— 写栈过程中若抛非受检异常（如 OOM 之外的
            //  意外 NPE），原来会直接冒泡出 dump()：文件已建但只写了一半 → 半截栈留在盘上
            //  （且占保留配额、还可能被 latestDump 选中）。看门狗线程绝不能因它死掉。
            try { if (out != null) Files.deleteIfExists(out); } catch (IOException ignored) {}
            // 八轮审计#2：LOGGER 走控制台；同步进 ModLog 环形缓冲（面板日志页可见），复活死键 watch.write_fail
            SableFixMod.LOGGER.error("写线程栈失败", ex);
            dev.sfx.sablefix.log.ModLog.errorT("sablefix.watch.write_fail");
            return null;
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
            // 十轮审计#50：取不到修改时间的文件**不能当"最旧"**——原实现 catch 后返回 0L，
            // 于是它排在最前 → **第一个被删**。而「读不到 mtime」（文件正被写/被占用/权限不足）
            // 并不代表它旧，恰恰可能是**刚写的那一份**（唯一一份卡死现场）→ 被误删。
            // 改为：读不到 mtime 就**跳过该文件**——既不参与排序、也不参与删除计数
            // （配额语义变成「保留 50 份**可识别的**栈」）。读不到 mtime 通常是**瞬时**状态
            // （文件正被写），下一轮就能读到 → 自动归队，不会长期累积。
            var files = stream.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("threaddump-") && n.endsWith(".txt");
                    }).filter(p -> {
                        try { Files.getLastModifiedTime((Path) p); return true; }
                        catch (IOException e) { return false; }
                    }).sorted(java.util.Comparator.comparingLong(p -> {
                        try { return Files.getLastModifiedTime((Path) p).toMillis(); }
                        catch (IOException e) { return 0L; }   // 排序期间再读失败：仍按 0 处理
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
