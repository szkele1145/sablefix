package dev.sfx.sablefix.watchdog;

import dev.sfx.sablefix.SableFixMod;
import dev.sfx.sablefix.scan.DamageRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 1.3.0 ①层·第一阶段（纯观察，零注入——见 COMPATIBILITY.md 风险预案）：
 * 独立守护线程每 30s 轮询各维度 loaded chunk holder 的 saveSyncFuture 完成状态。
 * 某区块的保存 future 持续未完成超过阈值（默认 30s，即跨过一个完整轮询周期）→ 记入损伤登记簿 + WARN。
 *
 * 为什么可行：ChunkHolder#getSaveSyncFuture / isReadyForSaving 是公开 API（javap 已核对），
 * 我们只读不写，与 c2me / chunktreefix 的 mixin 零交互面。
 * 为什么用独立线程而不是事件：保存 pipeline 内部没有"任务卡住"的回调，卡住恰恰是没有回调——只能旁路观测。
 *
 * 第二阶段（跳过）另行评审：需要证明"跳过一个区块的保存"在 Sable+c2me 组合下可安全实施。
 */
public final class SaveObserver {
    private static final long POLL_MILLIS = 30_000L;
    /** 十轮审计#25：登记阈值必须 >= 一个完整轮询周期。原值 20s < POLL_MILLIS(30s)
     *  → 任何「第一轮发现 pending」的条目，第二轮（30s 后）stalledMs 必然 >= 20s → **一轮就登记**，
     *  阈值形同虚设（实际语义变成「只要某轮看到 pending 就登记」）。取 30s = 至少跨过
     *  一个完整轮询周期仍 pending 才登记，语义与数值一致。 */
    private static final long PENDING_THRESHOLD_MS = POLL_MILLIS;
    /** 每维度每轮遍历实体的总上限（十轮审计#24）——原上限只统计 holder 非空者，
     *  未加载 chunk 的实体不计数 → 上限失效 → 实体量大时每 30s 全量遍历（O(N)）。 */
    private static final int MAX_ENTITY_VISITS = 4096;

    /** sinceNanos=首次观察到 pending 的时刻；lastSeenNanos=最近一轮仍 pending 的时刻（审计#5 泄漏修复） */
    private record Track(long sinceNanos, boolean registered, long lastSeenNanos) {}

    /** 维度名@chunkX,chunkZ -> 追踪状态 */
    private static final Map<String, Track> PENDING = new ConcurrentHashMap<>();
    private static volatile boolean running;
    private static Thread thread;
    private static volatile MinecraftServer server;

    /** 十轮审计#58：观察者会话代号（与 ThreadWatchdog#57 同构）。
     *  原实现用 static `running` 做循环条件：stop() 置 false + interrupt()，但旧线程若正卡在
     *  scanLevel()（遍历维度实体，实体量大时可达秒级）里，interrupt 被吞；下一会话 start()
     *  把 running 置回 true → **旧线程复活**，两个观察者线程同时轮询。后果：
     *  ① static `server` 已被换成新会话的 → 旧线程用**新存档的维度**去扫；
     *  ② static `PENDING` 共享 → 新旧两套「维度@x,z」键混在一起；
     *  ③ 旧线程的 dropTracking → **清掉新会话刚登记的 SAVE_STALLED**（真损伤被抹掉，
     *     与 #55 DamageRegistry.clear 抹掉真损伤同族）；
     *  ④ 旧线程的 register → 用**新存档的维度名**登记**旧存档**的卡顿。
     *  改为：循环条件用代号判定；过期线程既不扫描也不登记/清登记。 */
    private static final java.util.concurrent.atomic.AtomicLong SESSION =
            new java.util.concurrent.atomic.AtomicLong();
    /** 观察者线程绑定的会话代号（null = 主线程/当前会话）。 */
    private static final ThreadLocal<Long> BOUND_SESSION = new ThreadLocal<>();

    /** 本次调用是否仍属当前会话：未绑定（主线程）→ 属当前；已绑定但代号过期 → 否。 */
    private static boolean sessionAllowed() {
        Long bound = BOUND_SESSION.get();
        return bound == null || bound == SESSION.get();
    }

    private SaveObserver() {}

    public static synchronized void start(MinecraftServer srv) {
        if (thread != null) return;
        server = srv;
        running = true;
        // 十轮审计#58：推进会话代号并绑定本线程——旧观察者线程的扫描/登记从此判定为过期
        final long session = SESSION.incrementAndGet();
        thread = new Thread(() -> {
            BOUND_SESSION.set(session);
            try {
                loop();
            } finally {
                BOUND_SESSION.remove();
            }
        }, "SableFix-SaveObserver");
        thread.setDaemon(true);
        thread.start();
        SableFixMod.LOGGER.info("保存观察者启动（每 30s 轮询保存 future，跨过一轮仍卡住才登记）");
    }

    public static synchronized void stop() {
        running = false;
        // 十轮审计#58：推进会话代号——旧观察者线程的循环条件据此判定过期并退出。
        // 只靠 running=false 不够：旧线程若正卡在 scanLevel() 里，interrupt 被吞，
        // 循环回到 while 时 running 已被下一会话 start() 置回 true → **旧线程复活**。
        SESSION.incrementAndGet();
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
        // 十轮审计#12：原本只 PENDING.clear()（丢追踪）——已登记的 SAVE_STALLED 会留在
        // 登记簿里，停服时 flush(3s) 把它落盘 → 下次开服继续误报（保存卡顿是**瞬时**状态，
        //  停服瞬间一切 pending 都会随存档落盘而消失，不该跨重启保留）。
        java.util.List<String> drop = new java.util.ArrayList<>(PENDING.keySet());
        for (String k : drop) dropTracking(k);
        PENDING.clear();
    }

    private static void loop() {
        // 十轮审计#58：循环条件用**会话代号**而不是 static 的 running（详见 SESSION 注释）
        while (sessionAllowed()) {
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.nanoTime();
            MinecraftServer srv = server;
            if (srv == null) continue;
            for (ServerLevel level : srv.getAllLevels()) {
                try {
                    scanLevel(level, now);
                } catch (Exception e) {
                    // 观察绝不影响保存：任何异常吞掉记日志
                    SableFixMod.LOGGER.debug("save observer scan error on {}", level.dimension().location(), e);
                }
            }
            // 已恢复的 pending 清除追踪（审计#5：改按 lastSeen 超时清理——旧逻辑只清
            // registered=true 条目，首次 pending 后恢复且不再被 checkHolder 覆盖的条目永久滞留）
            // 十轮审计#12：这里原本只丢追踪不登记——超时条目若已登记过，DamageRegistry 里的
            // SAVE_STALLED 永远留着（每 5 分钟误报 WARN + 跨重启持久化 + 玩家永久提示）。
            // 收集后再统一 dropTracking，避免在 removeIf 谓词里动 map。
            java.util.List<String> expired = new java.util.ArrayList<>();
            for (java.util.Map.Entry<String, Track> en : PENDING.entrySet()) {
                if (now - en.getValue().lastSeenNanos() > POLL_MILLIS * 4) expired.add(en.getKey());
            }
            for (String k : expired) dropTracking(k);
        }
    }

    /** 丢弃追踪并同步清登记（十轮审计#12）。
     *
     *  <p>为什么必须成对：PENDING 是"正在追踪"的状态，DamageRegistry 是"已登记的损伤"。
     *  两者分开删除时，任何一条"只删 PENDING 不删登记"的路径都会留下一条**永远无法清除**
     *  的登记（SAVE_STALLED 的登记名是稳定的维度@x,z，clear 必须精确匹配）→
     *  每 5 分钟永久误报 WARN、跨重启持久化、玩家进服永久被提示。
     *  历史上正是这样出了三处漏清：!hasWork() 短路、lastSeen 超时清理、以及恢复路径
     *  （恢复路径的登记名还混入了毫秒数，匹配不上，已在上一个提交改正）。 */
    private static void dropTracking(String key) {
        // 十轮审计#58：过期会话的观察者不得丢追踪——PENDING 是**共享 map**，
        // 旧会话的键（维度@x,z）与新会话可能**同名**（同维度同坐标）→ 旧线程会把
        // 新会话正在追踪的条目删掉（连带把新会话刚登记的 SAVE_STALLED 清掉）。
        // DamageRegistry.clear 侧已有 #55 的会话守卫，这里补的是 PENDING 侧。
        if (!sessionAllowed()) return;
        Track t = PENDING.remove(key);
        if (t != null && t.registered()) {
            dev.sfx.sablefix.scan.DamageRegistry.clear(
                    dev.sfx.sablefix.scan.DamageRegistry.Kind.SAVE_STALLED, key);
        }
    }

    private static void scanLevel(ServerLevel level, long now) {
        var chunkSource = level.getChunkSource();
        // 官方入口：枚举 visible holders（getVisibleChunkIfPresent 需要已知 pos；
        // 用 hasWork() 全局短路 + 遍历 loaded chunks 的 holder）
        if (!chunkSource.chunkMap.hasWork()) {
            // 无积压：所有 pending 清零
            // 十轮审计#12：原本只丢追踪不登记 → 已登记的 SAVE_STALLED 永久残留
            // （hasWork() 查的是光照/卸载/poi/排序器/票据，**不含 holder 的 save future**，
            //  所以这条短路可以在 save future 仍 pending 时触发——MC 1.21.1 ChunkMap.hasWork
            //  源码已核对）。改走 dropTracking：丢追踪与清登记成对。
            java.util.List<String> drop = new java.util.ArrayList<>();
            for (String k : PENDING.keySet()) {
                if (k.startsWith(dimName(level) + "@")) drop.add(k);
            }
            for (String k : drop) dropTracking(k);
            return;
        }
        int checked = 0;
        // 玩家所在 chunk 必查（最影响体验，不受 64 上限约束）
        for (var p : level.players()) {
            var cp = p.blockPosition();
            ChunkHolder ph = chunkSource.chunkMap.getVisibleChunkIfPresent(new net.minecraft.world.level.ChunkPos(cp).toLong());
            if (ph != null) { checkHolder(level, ph, now); }
        }
        // 十轮审计#24：实体遍历的 64 上限原先只统计「holder 非空」的个数——
        // 实体所在的 chunk 若未加载（holder==null）就 continue，计数不增 → 上限形同虚设，
        // 实体量大的服务器每 30s 仍要遍历**全部**实体（getAllEntities 遍历 byId.values()，
        //  O(N)）。改为按**总遍历数**封顶。
        // 另：用 holder 去重集合——同一 chunk 的多个实体原本会重复 checkHolder
        //  （重复 compute 同一 key，白白浪费）。去重后每次轮询每个 chunk 最多查一次。
        java.util.Set<Long> seen = new java.util.HashSet<>();
        int visited = 0;
        for (var entity : level.getAllEntities()) {
            if (++visited > MAX_ENTITY_VISITS) break;   // 总遍历上限（防 O(N) 遍历）
            var pos = entity.blockPosition();
            long posLong = new net.minecraft.world.level.ChunkPos(pos).toLong();
            if (!seen.add(posLong)) continue;             // 本轮已查过该 chunk
            ChunkHolder holder = chunkSource.chunkMap.getVisibleChunkIfPresent(posLong);
            if (holder == null) continue;
            checkHolder(level, holder, now);
            if (++checked >= 64) break;   // 每维度每轮最多 64 个已确认 holder，控制开销
        }
    }

    private static void checkHolder(ServerLevel level, ChunkHolder holder, long now) {
        // 十轮审计#58：过期会话的观察者不得写 PENDING——它是**共享 map**，且键是
        // 「维度@x,z」（新旧会话可能同名）→ 旧线程的 compute 会**覆盖**新会话的 Track
        // （新会话的 registered 标志丢失 → 它的 SAVE_STALLED 登记再也清不掉）。
        if (!sessionAllowed()) return;
        // saveSync 字段可为 null（chunk 尚未经历首次保存）——无保存历史≠卡住，直接跳过
        var saveFuture = holder.getSaveSyncFuture();
        String key = dimName(level) + "@" + holder.getPos().x + "," + holder.getPos().z;
        if (saveFuture == null || saveFuture.isDone()) {
            // 三轮审计#3：已登记的卡顿恢复时同步清登记——原实现只清追踪，
            // DamageRegistry 的 SAVE_STALLED 无人清 → periodicWarn 每 5 分钟永久误报
            // 且持久化跨重启（登记的 file 字段就是 key+毫秒数）
            // 十轮审计#9：登记名必须是**稳定**的 key。原实现把「已 pending 毫秒数」拼进
            // 登记名，而清除时用的是**当前**算出的毫秒数——两者必然不同（至少差一个轮询
            // 周期 30s），clear 永远静默失配 → 登记永久残留（每 5 分钟误报 WARN + 跨重启
            // 持久化 + 玩家进服永久提示）。且每次超时都会以新毫秒数再登记一条 →
            // 登记簿里堆积无数条同源记录。改为登记名 = 稳定 key，耗时写进日志。
            // 十轮审计#12：恢复路径也统一走 dropTracking（丢追踪 + 清登记成对）。
            dropTracking(key);
            return;
        }
        PENDING.compute(key, (k, t) -> {
            if (t == null) return new Track(now, false, now);
            long stalledMs = (now - t.sinceNanos()) / 1_000_000L;
            if (!t.registered() && stalledMs >= PENDING_THRESHOLD_MS) {
                // 十轮审计#9：登记名用稳定 key（不含毫秒数）——保证恢复时能精确清除。
                DamageRegistry.register(DamageRegistry.Kind.SAVE_STALLED, k, 1);
                SableFixMod.LOGGER.warn("保存 future 卡住 {}：{}ms 未完成（已登记损伤）", k, stalledMs);
                return new Track(t.sinceNanos(), true, now);
            }
            return new Track(t.sinceNanos(), t.registered(), now);   // 审计#5：每轮刷新 lastSeen
        });
    }

    private static String dimName(ServerLevel level) {
        return level.dimension().location().toString();
    }

    /** 防未使用告警的占位引用（UUID 保留给未来 player-specific 观测） */
    @SuppressWarnings("unused")
    private static final Class<UUID> KEEP_IMPORT = UUID.class;
}
