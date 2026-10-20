package dev.sfx.sablefix.scan;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.sfx.sablefix.SableFixMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 损伤登记簿：把体检发现的问题持久化成 <存档>/data/sablefix_damage.json，
 * 支撑三类提醒（全部只读数据，不碰存档内容）：
 *  - 控制台周期 WARN（默认 5 分钟一次，防止"修好了忘拔警告"和"坏了没人知道"两种极端）
 *  - 玩家进服时聊天框提示（每玩家每条目只提示一次，新条目再次提示）
 *  - 面板总览页直接读（后续版本）
 * 线程模型：体检在后台线程调 register()，玩家事件在主线程调 onPlayerJoin()，全部走 synchronized。
 */
public final class DamageRegistry {

    private DamageRegistry() {}

    /** 残留只读条目的登记名（唯一权威定义，八轮审计#1）。
     *  条目键 = kind.name() + ":" + file，注册方（SubLevelScan.warnStaleReadOnly）与清除方
     *  （SubLevelScan / SableWriteMask 兜底 / SableFixMod 启动解锁 / /sablefix unmask）
     *  必须逐字一致，否则 clear 静默失配 → 已解锁仍每 5 分钟 WARN 且跨重启持久化。 */
    public static final String STALE_READONLY_FILE = "<stale read-only files>";

    public enum Kind {
        /** .slvlr/.slvls 死指针/坏条目 */
        SUBLVL_DEAD,
        /** force-load 票据异常堆积 */
        TICKETS_STUCK,
        /** 保存管线卡住（SaveObserver：区块保存 future 长时间未完成） */
        SAVE_STALLED,
        /** fix 中断残留只读（二轮审计#3：正解是 /sablefix unmask 不是 repair——独立 kind 避免误导提示） */
        STALE_READONLY
    }

    public static final class Entry {
        public String kind;        // Kind.name()
        public String file;        // 相对 sublevels 的文件名（票据条目为固定名）
        public int count;          // 死指针数 / 异常票据数
        public long firstSeenMs;   // 系统时钟（跨重启可比）
        public long lastSeenMs;
    }

    /** damageId -> entry（LinkedHashMap 保持发现顺序，日志可读） */
    private static final Map<String, Entry> DAMAGE = new LinkedHashMap<>();
    /** 每玩家已提示过的 damageId（不持久化：重启后每玩家会再被提示一次——损伤仍在时这是合理行为） */
    private static final Map<UUID, Map<String, Boolean>> NOTIFIED = new ConcurrentHashMap<>();
    /** 写盘单线程：register/clear 内只拍快照并提交，永不在 synchronized 块内做同步 IO（审计#3 闭环）。 */
    private static final ExecutorService WRITE_POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sablefix-damage-writer");
        t.setDaemon(true);
        return t;
    });
    private static long lastPeriodicWarnMs;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile Path storePath;   // ServerStarted 时锚定

    /** 体检/票据扫描发现损伤时登记（任意线程）。返回 true=新增条目（新问题），false=已知。
     *  审计#3：锁内只做数据变更+拷贝快照，save() 磁盘写移到锁外——原实现在 synchronized
     *  临界区内做 GSON+ATOMIC_MOVE（1-50ms），与主线程每 tick 的 periodicWarn 争锁 →
     *  偶发单 tick 阻塞（隐形掉 tps 源）。改后"最后写赢"，可接受。 */
    public static synchronized boolean register(Kind kind, String file, int count) {
        String id = kind.name() + ":" + file;
        long now = System.currentTimeMillis();
        Entry e = DAMAGE.get(id);
        boolean isNew = e == null;
        if (e == null) {
            e = new Entry();
            e.kind = kind.name();
            e.file = file;
            e.firstSeenMs = now;
            DAMAGE.put(id, e);
        }
        // 内容无变化（已知条目、数量相同）不写盘：启动体检每次都跑，避免无意义磁盘写
        boolean changed = isNew || e.count != count;
        e.count = count;
        e.lastSeenMs = now;
        if (changed) {
            Map<String, Entry> snap = snapshotLocked();   // 锁内深拷贝：写线程只读私有副本
            // 十轮审计#8：路径与快照同批捕获（锁内），写线程不再读执行时的静态 storePath。
            final Path target = storePath;
            WRITE_POOL.execute(() -> writeSnapshotSync(snap, target));
        }
        return isNew;
    }

    /** 问题消失（如玩家已用 repair 修复）时移除，下次 WARN 不再出现。写盘同样移到锁外（审计#3）。 */
    public static synchronized void clear(Kind kind, String file) {
        if (DAMAGE.remove(kind.name() + ":" + file) != null) {
            Map<String, Entry> snap = snapshotLocked();   // 同样锁内深拷贝
            final Path target = storePath;                 // 十轮审计#8：路径随快照捕获
            WRITE_POOL.execute(() -> writeSnapshotSync(snap, target));
        }
    }

    /** 调用方必须已持有 DamageRegistry 类锁。深拷贝成写线程可独占读取的普通 Map：
     *  DAMAGE 是普通 LinkedHashMap，锁外 new LinkedHashMap<>(DAMAGE) 会与主线程
     *  的 periodicWarn/onPlayerJoin 并发 → CME 或半更新状态；浅拷贝还会让 Entry
     *  字段（count/lastSeenMs）被活对象持续改写，写出撕裂数据。（1.6.1 修 1.6.0 回归） */
    private static Map<String, Entry> snapshotLocked() {
        Map<String, Entry> snap = new LinkedHashMap<>(DAMAGE.size() * 2);
        for (Map.Entry<String, Entry> en : DAMAGE.entrySet()) {
            Entry src = en.getValue();
            Entry copy = new Entry();
            copy.kind = src.kind;
            copy.file = src.file;
            copy.count = src.count;              // int 读在锁内完成，避免撕裂
            copy.firstSeenMs = src.firstSeenMs;
            copy.lastSeenMs = src.lastSeenMs;
            snap.put(en.getKey(), copy);
        }
        return snap;
    }

    /** 停服收尾：阻塞等已提交的写盘任务全部落盘（barrier：单线程 FIFO，空任务完成即代表
     *  此前所有快照都写完了），避免守护线程随 JVM 退出把队列里的登记丢掉。
     *  synchronized = flush 期间不再有新任务入队，barrier 才真正覆盖"此前全部"。
     *  刻意不调 WRITE_POOL.shutdown()：单人世界/面板会在同一 JVM 内重开服务器，
     *  shutdown 后 register 会抛 RejectedExecutionException 反而更糟。（1.6.1） */
    public static synchronized void flush(long timeoutMs) {
        if (storePath == null) return;
        try {
            WRITE_POOL.submit(() -> { }).get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            SableFixMod.LOGGER.warn("损伤登记簿收尾写盘等待失败（内存数据仍在，下次变更会重试）", e);
        }
    }

    /** 锚定存储路径 + 载入已有登记（重启后损伤清单不丢）。storeDir = <世界>/data（由调用方传入，Registry 不依赖 MC 类型）。
     *  十轮审计#8：载入前先同步排空写队列（与 flush 同构的 barrier）。单人世界/面板在同一
     *  JVM 重开服务器时，上一轮 flush(3s) 若超时，队列里残留的旧快照任务会在本轮 init
     *  载入之后才执行——用**旧数据覆盖**磁盘，把刚载入/刚 register 的登记回滚掉。载入前
     *  排空队列，保证读到的就是最后写入的。无超时：启动路径多等几个写任务是安全的
     *  （flush 超时说明磁盘本来就慢，这里的等待通常也是毫秒级）。 */
    public static synchronized void init(Path dataDir) {
        try {
            WRITE_POOL.submit(() -> { }).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            SableFixMod.LOGGER.warn("损伤登记簿载入前等待旧写任务超时（旧快照可能晚于载入落盘）", e);
        }
        storePath = dataDir.resolve("sablefix_damage.json");
        DAMAGE.clear();
        // 十轮审计#26：NOTIFIED 是 static → 跨服务器实例存活。同一 JVM 重开服务器
        // （单人世界/面板服换存档是常态）时，上一轮玩家的「已提示」记录还在 →
        // 同一个玩家（同一 UUID）在新服务器进服时**不会再收到损伤提示**
        // （onPlayerJoin 只对未见过的 damageId 提示）→ 管理员看不到损伤。
        // 提示状态属于**会话内**状态，随服务器实例重置。
        NOTIFIED.clear();
        if (Files.exists(storePath)) {
            try {
                Map<String, Entry> loaded = GSON.fromJson(Files.readString(storePath),
                        new TypeToken<Map<String, Entry>>() {}.getType());
                if (loaded != null) {
                    DAMAGE.putAll(loaded);
                    // 十轮审计#9：清理旧格式的 SAVE_STALLED 登记——旧版把「已 pending 毫秒数」
                    // 拼进登记名，导致恢复时 clear 永远失配 → 登记永久残留（每 5 分钟误报 WARN
                    // + 跨重启持久化 + 玩家进服永久提示）。新格式登记名是稳定的
                    // 「维度@x,z」，不含 " (save pending " 后缀；带后缀的一律是旧格式残留，
                    // 且保存卡顿是**瞬时**状态（恢复即应清），载入时直接丢弃。
                    int stale = 0;
                    for (java.util.Iterator<Map.Entry<String, Entry>> it = DAMAGE.entrySet().iterator(); it.hasNext(); ) {
                        Map.Entry<String, Entry> en = it.next();
                        if (Kind.SAVE_STALLED.name().equals(en.getValue().kind)
                                && en.getKey().contains(" (save pending ")) {
                            it.remove();
                            stale++;
                        }
                    }
                    if (stale > 0) {
                        SableFixMod.LOGGER.info("损伤登记簿清理：{} 条旧格式保存卡顿登记（保存卡顿为瞬时状态，不跨重启保留）", stale);
                    }
                }
                SableFixMod.LOGGER.info("损伤登记簿载入：{} 条未处置损伤", DAMAGE.size());
            } catch (Exception e) {
                SableFixMod.LOGGER.error("损伤登记簿解析失败（将重建）", e);
            }
        }
        lastPeriodicWarnMs = System.currentTimeMillis();   // 启动即算一次，避免 5 分钟内重复刷
    }

    /** 周期 WARN：服务器 tick 里调（内部自己节流，默认 5 分钟）。有损伤才说话。 */
    public static synchronized void periodicWarn() {
        if (DAMAGE.isEmpty() || storePath == null) return;
        long now = System.currentTimeMillis();
        if (now - lastPeriodicWarnMs < dev.sfx.sablefix.watchdog.ThreadWatchdog.WARN_PERIOD_MS) return;
        lastPeriodicWarnMs = now;
        // 单行汇总（条目多时控制台不刷屏）；明细已由体检日志给过，这里只提醒"还有多少没修"
        // 1.6.2：按 kind 分级汇总并给出对应处置命令——原实现只打 "file×count"，
        // STALE_READONLY 会显示成 "<stale read-only files>×1"，看不出该用 unmask 而非 repair。
        Map<String, int[]> byKind = new LinkedHashMap<>();
        for (Entry e : DAMAGE.values()) {
            int[] agg = byKind.computeIfAbsent(e.kind, k -> new int[2]);
            agg[0] += e.count;
            agg[1]++;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, int[]> en : byKind.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(kindLabel(en.getKey())).append('×').append(en.getValue()[1]);
        }
        SableFixMod.LOGGER.warn("[SableFix][损伤未处置] 共 {} 项：{}——{}",
                DAMAGE.size(), sb, recoveryHint(byKind));
    }

    /** 控制台/聊天可读的损伤类型名（避免把内部 kind 枚举名直接糊给用户）。1.6.2 起命令侧共用。 */
    public static String kindLabel(String kind) {
        return switch (kind) {
            case "SUBLVL_DEAD" -> "子层级死指针";
            case "TICKETS_STUCK" -> "票据堆积";
            case "SAVE_STALLED" -> "保存卡顿";
            case "STALE_READONLY" -> "残留只读";
            default -> kind;
        };
    }

    /** 按实际存在的损伤类型拼处置指引（调用方已持锁）。 */
    private static String recoveryHint(Map<String, int[]> byKind) {
        StringBuilder sb = new StringBuilder();
        if (byKind.containsKey("STALE_READONLY")) sb.append("/sablefix unmask；");
        if (byKind.containsKey("TICKETS_STUCK")) sb.append("/sablefix tickets-purge apply；");
        if (byKind.containsKey("SUBLVL_DEAD")) sb.append("/sablefix repair-all apply 或 /sablefix fix；");
        if (sb.length() == 0) sb.append("见 /sablefix check");
        else sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    /** 玩家进服：返回该玩家未提示过的条目快照（调用方渲染 MC 文案），并标记已提示。
     *  返回**深拷贝**（1.6.2）：调用方在锁外读 e.count 渲染文案，直接放活引用会与
     *  后台扫描线程的 register() 并发改写 count → 提示里出现半更新的数量（与
     *  snapshot() 同源的 1.6.0 回归，此处一并收口）。 */
    public static synchronized java.util.List<Entry> onPlayerJoin(UUID playerId) {
        java.util.List<Entry> pending = new java.util.ArrayList<>();
        if (DAMAGE.isEmpty()) return pending;
        Map<String, Boolean> seen = NOTIFIED.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
        for (Entry e : DAMAGE.values()) {
            String id = e.kind + ":" + e.file;
            if (seen.putIfAbsent(id, Boolean.TRUE) == null) {
                Entry copy = new Entry();
                copy.kind = e.kind;
                copy.file = e.file;
                copy.count = e.count;
                copy.firstSeenMs = e.firstSeenMs;
                copy.lastSeenMs = e.lastSeenMs;
                pending.add(copy);
            }
        }
        return pending;
    }

    /** 玩家退出清理（防 NOTIFIED 集合慢性增长）。 */
    public static synchronized void onPlayerLeave(UUID playerId) {
        NOTIFIED.remove(playerId);
    }

    /** 当前损伤快照（面板/DiagReport 在后台线程读，必须是深拷贝——Entry 可变，
     *  浅拷贝会让调用方读到被 register 并发改写的 count/lastSeenMs）。 */
    public static synchronized java.util.List<Entry> snapshot() {
        return new java.util.ArrayList<>(snapshotLocked().values());
    }

    /** 写盘前已由调用方在类锁内拍好快照（审计#3），本方法在 WRITE_POOL 线程里执行
     *  同步原子写盘（tmp 文件 + ATOMIC_MOVE 替换，不丢已登记的损伤）——异步的是
     *  调度（WRITE_POOL.execute），写盘本身是同步的，故不叫 saveLater。
     *  写失败仅记日志（数据仍在内存，下次变更会再试）。
     *  十轮审计#8：目标路径随快照一起捕获，不读执行时的静态 storePath——面板服切存档时
     *  storePath 改指新存档，残留旧任务若读执行时字段，会把上一存档的登记写进新存档文件。 */
    private static void writeSnapshotSync(Map<String, Entry> snapshot) {
        writeSnapshotSync(snapshot, storePath);
    }

    private static void writeSnapshotSync(Map<String, Entry> snapshot, Path target) {
        if (target == null) return;
        try {
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(snapshot));
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            SableFixMod.LOGGER.error("损伤登记簿写盘失败", e);
        }
    }
}
