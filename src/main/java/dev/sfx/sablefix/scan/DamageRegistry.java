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

    public enum Kind {
        /** .slvlr/.slvls 死指针/坏条目 */
        SUBLVL_DEAD,
        /** force-load 票据异常堆积 */
        TICKETS_STUCK,
        /** 保存管线卡住（SaveObserver：区块保存 future 长时间未完成） */
        SAVE_STALLED
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
        if (changed) saveLater(new LinkedHashMap<>(DAMAGE));
        return isNew;
    }

    /** 问题消失（如玩家已用 repair 修复）时移除，下次 WARN 不再出现。写盘同样移到锁外（审计#3）。 */
    public static synchronized void clear(Kind kind, String file) {
        if (DAMAGE.remove(kind.name() + ":" + file) != null) saveLater(new LinkedHashMap<>(DAMAGE));
    }

    /** 锚定存储路径 + 载入已有登记（重启后损伤清单不丢）。storeDir = <世界>/data（由调用方传入，Registry 不依赖 MC 类型）。 */
    public static synchronized void init(Path dataDir) {
        storePath = dataDir.resolve("sablefix_damage.json");
        DAMAGE.clear();
        if (Files.exists(storePath)) {
            try {
                Map<String, Entry> loaded = GSON.fromJson(Files.readString(storePath),
                        new TypeToken<Map<String, Entry>>() {}.getType());
                if (loaded != null) DAMAGE.putAll(loaded);
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
        SableFixMod.LOGGER.warn("[SableFix][损伤未处置] 共 {} 项：{}——用 /sablefix repair / tickets-purge 处理",
                DAMAGE.size(),
                DAMAGE.values().stream().map(e -> e.file + "×" + e.count)
                        .limit(5).collect(java.util.stream.Collectors.joining(", "))
                        + (DAMAGE.size() > 5 ? " …" : ""));
    }

    /** 玩家进服：返回该玩家未提示过的条目快照（调用方渲染 MC 文案），并标记已提示。 */
    public static synchronized java.util.List<Entry> onPlayerJoin(UUID playerId) {
        java.util.List<Entry> pending = new java.util.ArrayList<>();
        if (DAMAGE.isEmpty()) return pending;
        Map<String, Boolean> seen = NOTIFIED.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
        for (Entry e : DAMAGE.values()) {
            String id = e.kind + ":" + e.file;
            if (seen.putIfAbsent(id, Boolean.TRUE) == null) pending.add(e);
        }
        return pending;
    }

    /** 玩家退出清理（防 NOTIFIED 集合慢性增长）。 */
    public static synchronized void onPlayerLeave(UUID playerId) {
        NOTIFIED.remove(playerId);
    }

    /** 当前损伤快照（面板后续版本用）。 */
    public static synchronized java.util.List<Entry> snapshot() {
        return new java.util.ArrayList<>(DAMAGE.values());
    }

    /** 锁外异步写盘（审计#3）：快照在锁内拷贝好，写盘交给调用方线程继续持锁外执行。
     *  写失败仅记日志（数据仍在内存，下次变更会再试）。 */
    private static void saveLater(Map<String, Entry> snapshot) {
        if (storePath == null) return;
        try {
            Path tmp = storePath.resolveSibling(storePath.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(snapshot));
            Files.move(tmp, storePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            SableFixMod.LOGGER.error("损伤登记簿写盘失败", e);
        }
    }
}
