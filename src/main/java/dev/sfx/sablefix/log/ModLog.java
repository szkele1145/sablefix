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
 *
 * 九轮审计#1：条目分两种形态——
 *  - 纯文本（msg 已定型，如文件名、路径、异常信息）
 *  - 可翻译（key + args，渲染时机在**客户端**，按玩家语言解析）
 * 服务端没有语言概念（服务端 Language 只装 en_us），所以服务端一律不解析翻译——
 * 否则中文服的面板日志会显示英文。翻译由客户端在渲染时完成。
 */
public final class ModLog {

    /**
     * @param time  时间戳文本（HH:mm:ss.SSS）
     * @param level INFO/WARN/ERROR
     * @param msg   已定型文本（可翻译条目时为空）
     * @param key   翻译键（可翻译条目时非空；否则为空）
     * @param args  翻译参数（可为 null）
     */
    public record Entry(String time, String level, String msg, String key, List<Object> args) {
        public boolean translatable() { return key != null && !key.isEmpty(); }
    }

    private static final int CAP = 200;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final Deque<Entry> BUF = new ArrayDeque<>();

    private ModLog() {}

    public static void info(String msg) { add("INFO", msg, null, null); }
    public static void warn(String msg) { add("WARN", msg, null, null); }
    public static void error(String msg) { add("ERROR", msg, null, null); }

    /** 可翻译日志：文本在客户端按玩家语言渲染（服务端不解析——服务端只有 en_us）。 */
    public static void infoT(String key, Object... args) { add("INFO", "", key, args); }
    public static void warnT(String key, Object... args) { add("WARN", "", key, args); }
    public static void errorT(String key, Object... args) { add("ERROR", "", key, args); }

    private static void add(String level, String msg, String key, Object[] args) {
        // 七轮审计#4：1.6.2 声称"LOGGER 输出移出 synchronized 区"，但 add() 只在
        // info/warn/error 里调用，而这三个方法整体是 synchronized 的——日志输出
        // 其实一直在类锁内（持锁做文件 I/O，主线程 tick 与后台扫描线程争锁）。
        // 改为只把环形缓冲的读改写放进锁内，时间戳也在锁内取以保持与入队顺序一致，
        // 输出移到锁外，兑现原注释的意图。
        // 九轮审计#1：List.of 对 null 元素直接 NPE——而 e.getMessage() 在无 message 的
        // 异常上就是 null（如 scan.sub_fail），会把整个日志记录炸掉。改用 Arrays.asList（允许 null）。
        // 十轮审计#4：参数在此入口统一净化（null→空串，非法类型→toString）——存进缓冲的
        // args 全部是合法类型，服务端控制台渲染 / DiagReport 渲染 / 客户端下发三条路径同时受益。
        Object[] safeArgs = sanitizeArgs(args);
        List<Object> argList = (safeArgs.length == 0) ? null : java.util.Arrays.asList(safeArgs);
        synchronized (ModLog.class) {
            if (BUF.size() >= CAP) BUF.removeFirst();
            BUF.addLast(new Entry(LocalDateTime.now().format(FMT), level, msg, key, argList));
        }
        // 控制台输出用服务端可得的文本：可翻译条目在服务端渲染为 en_us（LanguageHook 只装 en_us）。
        String out = (key != null && !key.isEmpty())
                ? net.minecraft.network.chat.Component.translatable(key, safeArgs).getString()
                : msg;
        final String fMsg = "[log] " + out;
        switch (level) {
            case "WARN" -> SableFixMod.LOGGER.warn(fMsg);
            case "ERROR" -> SableFixMod.LOGGER.error(fMsg);
            default -> SableFixMod.LOGGER.info(fMsg);
        }
    }

    /** 翻译参数净化（十轮审计#4）：null → 空串，非法类型 → toString()。 */
    private static Object[] sanitizeArgs(Object[] args) {
        if (args == null || args.length == 0) return new Object[0];
        Object[] safe = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a == null) safe[i] = "";
            else if (a instanceof net.minecraft.network.chat.Component || a instanceof Number
                    || a instanceof Boolean || a instanceof String) safe[i] = a;
            else safe[i] = a.toString();
        }
        return safe;
    }

    /** 快照（最新在后），供网络下发 */
    public static synchronized List<Entry> snapshot() {
        return new ArrayList<>(BUF);
    }
}
