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

    public static void info(String msg) { add("INFO", msg); }
    public static void warn(String msg) { add("WARN", msg); }
    public static void error(String msg) { add("ERROR", msg); }

    private static void add(String level, String msg) {
        // 七轮审计#4：1.6.2 声称"LOGGER 输出移出 synchronized 区"，但 add() 只在
        // info/warn/error 里调用，而这三个方法整体是 synchronized 的——日志输出
        // 其实一直在类锁内（持锁做文件 I/O，主线程 tick 与后台扫描线程争锁）。
        // 改为只把环形缓冲的读改写放进锁内，时间戳也在锁内取以保持与入队顺序一致，
        // 输出移到锁外，兑现原注释的意图。
        synchronized (ModLog.class) {
            if (BUF.size() >= CAP) BUF.removeFirst();
            BUF.addLast(new Entry(LocalDateTime.now().format(FMT), level, msg));
        }
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
