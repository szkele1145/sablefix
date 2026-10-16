package dev.sfx.sablefix.log;

import dev.sfx.sablefix.SableFixMod;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 模组内部日志：被动功能（启动体检 / 票据扫描 / 看门狗）的处理过程与结果。
 * 只存内存（环形 200 条），面板拉取数据时随 PanelData 下发。
 */
public final class ModLog {

    public record Entry(String time, String level, String msg) {}

    private static final int CAP = 200;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final Deque<Entry> BUF = new ArrayDeque<>();

    private ModLog() {}

    public static synchronized void info(String msg) { add("INFO", msg); }
    public static synchronized void warn(String msg) { add("WARN", msg); }
    public static synchronized void error(String msg) { add("ERROR", msg); }

    private static void add(String level, String msg) {
        Entry e = new Entry(LocalDateTime.now().format(FMT), level, msg);
        if (BUF.size() >= CAP) BUF.removeFirst();
        BUF.addLast(e);
        // 1.6.2：LOGGER 输出移出 synchronized 区（审计残余 nit 收口）。
        // SableFixMod.LOGGER 本身线程安全，但把同步输出留在类锁内会让
        // 主线程 tick 与后台扫描线程争锁；仅日志顺序可能与入队顺序
        // 微调，对内存环形缓冲无影响。
        final String fMsg = "[log] " + msg;
        switch (level) {
            case "WARN" -> SableFixMod.LOGGER.warn(fMsg);
            case "ERROR" -> SableFixMod.LOGGER.error(fMsg);
            default -> SableFixMod.LOGGER.info(fMsg);
        }
    }

    /** 快照（最新在后），供网络下发 */
    public static synchronized List<Entry> snapshot() {
        return new ArrayList<>(BUF);
    }
}
