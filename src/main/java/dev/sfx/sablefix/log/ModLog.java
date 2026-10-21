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
     * @param args  翻译参数（**永不为 null**——无参条目是空列表；十轮审计#15）
     */
    public record Entry(String time, String level, String msg, String key, List<Object> args) {
        public boolean translatable() { return key != null && !key.isEmpty(); }
    }

    private static final int CAP = 200;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final Deque<Entry> BUF = new ArrayDeque<>();

    /** 十轮审计#56：日志会话代号。启动体检跑在**后台线程**（SCAN_POOL 是 static 单线程池，
     *  跨服务器实例存活）：上一个存档排入的体检任务可能在本会话启动后才跑完 →
     *  它会把**旧存档**的扫描记录（旧文件名、旧路径、旧损伤数）写进新会话的日志页，
     *  管理员照着去查当前存档里**根本不存在**的文件（#32 清缓冲只解决「已写入的旧日志」，
     *  解决不了「旧任务新写入的日志」）。
     *  做法与 DamageRegistry#55 同构：后台线程绑定自己所属会话，过期写入直接忽略。 */
    private static final java.util.concurrent.atomic.AtomicLong SESSION =
            new java.util.concurrent.atomic.AtomicLong();
    /** 后台线程绑定的会话代号（null = 主线程/当前会话）。 */
    private static final ThreadLocal<Long> BOUND_SESSION = new ThreadLocal<>();

    /** 当前会话代号（入队时捕获）。 */
    public static long session() {
        return SESSION.get();
    }

    /** 绑定当前线程到指定会话（后台体检线程用；必须配套 unbindSession）。 */
    public static void bindSession(long captured) {
        BOUND_SESSION.set(captured);
    }

    /** 解绑当前线程（后台线程结束时必须调用——线程池复用线程，不解绑会串会话）。 */
    public static void unbindSession() {
        BOUND_SESSION.remove();
    }

    /** 本次写入是否仍属当前会话：未绑定（主线程）→ 属当前；已绑定但代号过期 → 否。 */
    private static boolean sessionAllowed() {
        Long bound = BOUND_SESSION.get();
        return bound == null || bound == SESSION.get();
    }

    private ModLog() {}

    public static void info(String msg) { add("INFO", msg, null, null); }
    public static void warn(String msg) { add("WARN", msg, null, null); }
    public static void error(String msg) { add("ERROR", msg, null, null); }

    /** 可翻译日志：文本在客户端按玩家语言渲染（服务端不解析——服务端只有 en_us）。 */
    public static void infoT(String key, Object... args) { add("INFO", "", key, args); }
    public static void warnT(String key, Object... args) { add("WARN", "", key, args); }
    public static void errorT(String key, Object... args) { add("ERROR", "", key, args); }

    private static void add(String level, String msg, String key, Object[] args) {
        // 十轮审计#56：过期会话的后台体检（别的存档）→ 丢弃这条日志，不污染本会话的日志页
        if (!sessionAllowed()) return;
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
        // 十轮审计#15：无参可翻译条目（如 infoT("sablefix.scan.sub_start")）原先存 null，
        // 消费方 ServerHandlers.buildScan 直接 e.args().stream() → NPE → **每次开面板必现**
        // （启动体检必然写入一条无参条目）。契约改为「args 永不为 null」，无参即空列表。
        // 用 List.of 而非 Arrays.asList：不可变（asList 支持 set()，会把缓冲里的条目改掉）。
        // List.of 不接受 null 元素——但 sanitizeArgs 已把 null 转成 ""，此处安全。
        List<Object> argList = List.of(safeArgs);
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

    /** 清空环形缓冲（十轮审计#32）：BUF 是 static，跨服务器实例存活。
     *  同一 JVM 重开服务器（面板服换存档、单人世界重开）时，日志页会混着**上一个存档**的
     *  扫描/修复记录（含旧文件名、旧路径、旧损伤数）——管理员会照着这些记录去查当前存档里
     *  根本不存在的文件。与 SCAN_CACHE / SCAN_RUNNING 同族：会话状态随服务器实例重置。
     *  注意：只清缓冲，不影响已打印到控制台的历史日志（那是 LOGGER 的职责）。 */
    public static synchronized void clearForNewSession() {
        BUF.clear();
        // 十轮审计#56：推进会话代号——上一个存档排入/正在跑的后台体检，它的日志写入
        // 从此被判定为过期（sessionAllowed）→ 不再往本会话的日志页写旧存档的记录。
        SESSION.incrementAndGet();
    }
}
