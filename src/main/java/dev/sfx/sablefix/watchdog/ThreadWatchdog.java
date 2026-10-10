package dev.sfx.sablefix.watchdog;

import dev.sfx.sablefix.SableFixMod;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * E：主线程卡死监视器。
 * 原版看门狗 60 秒直接杀服，且只留下被 park 的那一层栈。
 * 这里在卡死 20 秒时就把全部线程栈写盘，每 15 秒再补一份，
 * 保证下一次 save-all 卡死时能拿到"谁该完成任务却没干"的现场。
 */
public final class ThreadWatchdog {
    private static final long ALERT_NANOS = 20_000_000_000L;
    private static final long REDUMP_NANOS = 15_000_000_000L;
    private static final long POLL_MILLIS = 5_000L;

    private static volatile boolean running;
    private static volatile long lastTickNanos = System.nanoTime();
    private static volatile long lastGapMs;
    private static volatile long lastDumpNanos;
    private static volatile int dumpCount;
    private static Thread thread;

    private ThreadWatchdog() {}

    public static synchronized void start() {
        if (thread != null) return;
        running = true;
        lastTickNanos = System.nanoTime();
        lastDumpNanos = 0;
        thread = new Thread(ThreadWatchdog::loop, "SableFix-Watchdog");
        thread.setDaemon(true);
        thread.start();
        dev.sfx.sablefix.log.ModLog.info("主线程监视器启动：卡死 " + ALERT_NANOS / 1_000_000_000L
                + " 秒自动抓全线程栈（原版看门狗 60 秒杀服）");
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
    /** 本服累计抓栈次数 */
    public static int dumpCount() { return dumpCount; }

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
            dump(stall);
        }
    }

    /** 抓一份全线程栈；stallNanos 为 -1 表示手动触发。 */
    public static void dump(long stallNanos) {
        try {
            Path dir = Path.of("logs", "sablefix");
            Files.createDirectories(dir);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path out = dir.resolve("threaddump-" + stamp + ".txt");
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
                w.printf("主线程已 %.1f 秒没有 tick%n", stallNanos / 1_000_000_000.0);
                w.printf("抓取时间: %s%n%n", LocalDateTime.now());
                for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                    Thread t = e.getKey();
                    w.printf("\"%s\" %s prio=%d id=%d %s%n", t.getName(),
                            t.isDaemon() ? "daemon" : "", t.getPriority(), t.getId(), t.getState());
                    for (StackTraceElement ste : e.getValue()) {
                        w.printf("\tat %s%n", ste);
                    }
                    w.println();
                }
            }
            dumpCount++;
            dev.sfx.sablefix.log.ModLog.error("主线程已卡 " + stallNanos / 1_000_000_000.0
                    + " 秒！全线程栈已写入 " + out);
            SableFixMod.LOGGER.warn("主线程已卡 {} 秒！线程栈已写入 {}", stallNanos / 1_000_000_000.0, out);
        } catch (IOException ex) {
            SableFixMod.LOGGER.error("写线程栈失败", ex);
        }
    }
}
