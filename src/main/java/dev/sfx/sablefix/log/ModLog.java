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
        switch (level) {
            case "WARN" -> SableFixMod.LOGGER.warn("[log] {}", msg);
            case "ERROR" -> SableFixMod.LOGGER.error("[log] {}", msg);
            default -> SableFixMod.LOGGER.info("[log] {}", msg);
        }
    }

    /** 快照（最新在后），供网络下发 */
    public static synchronized List<Entry> snapshot() {
        return new ArrayList<>(BUF);
    }
}
