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
 * 某区块的保存 future 持续未完成超过阈值（默认 20s）→ 记入损伤登记簿 + WARN。
 *
 * 为什么可行：ChunkHolder#getSaveSyncFuture / isReadyForSaving 是公开 API（javap 已核对），
 * 我们只读不写，与 c2me / chunktreefix 的 mixin 零交互面。
 * 为什么用独立线程而不是事件：保存 pipeline 内部没有"任务卡住"的回调，卡住恰恰是没有回调——只能旁路观测。
 *
 * 第二阶段（跳过）另行评审：需要证明"跳过一个区块的保存"在 Sable+c2me 组合下可安全实施。
 */
public final class SaveObserver {
    private static final long POLL_MILLIS = 30_000L;
    private static final long PENDING_THRESHOLD_MS = 20_000L;

    private record Track(long sinceNanos, boolean registered) {}

    /** 维度名@chunkX,chunkZ -> 追踪状态 */
    private static final Map<String, Track> PENDING = new ConcurrentHashMap<>();
    private static volatile boolean running;
    private static Thread thread;
    private static volatile MinecraftServer server;

    private SaveObserver() {}

    public static synchronized void start(MinecraftServer srv) {
        if (thread != null) return;
        server = srv;
        running = true;
        thread = new Thread(SaveObserver::loop, "SableFix-SaveObserver");
        thread.setDaemon(true);
        thread.start();
        SableFixMod.LOGGER.info("保存观察者启动（每 30s 轮询保存 future，卡住 20s 登记）");
    }

    public static synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
        PENDING.clear();
    }

    private static void loop() {
        while (running) {
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
            MinecraftServer srv = server;
            if (srv == null) continue;
            long now = System.nanoTime();
            for (ServerLevel level : srv.getAllLevels()) {
                try {
                    scanLevel(level, now);
                } catch (Exception e) {
                    // 观察绝不影响保存：任何异常吞掉记日志
                    SableFixMod.LOGGER.debug("save observer scan error on {}", level.dimension().location(), e);
                }
            }
            // 已恢复的 pending 清除登记
            PENDING.entrySet().removeIf(e -> {
                if (now - e.getValue().sinceNanos() > PENDING_THRESHOLD_MS * 2
                        && e.getValue().registered()) {
                    // 恢复超过一个周期未再观察到 pending：从损伤登记簿移除由后续体检确认，这里只清追踪
                    return true;
                }
                return false;
            });
        }
    }

    private static void scanLevel(ServerLevel level, long now) {
        var chunkSource = level.getChunkSource();
        // 官方入口：枚举 visible holders（getVisibleChunkIfPresent 需要已知 pos；
        // 用 hasWork() 全局短路 + 遍历 loaded chunks 的 holder）
        if (!chunkSource.chunkMap.hasWork()) {
            // 无积压：所有 pending 清零
            PENDING.entrySet().removeIf(e -> e.getKey().startsWith(dimName(level) + "@"));
            return;
        }
        int checked = 0;
        // 玩家所在 chunk 必查（最影响体验，不受 64 上限约束）
        for (var p : level.players()) {
            var cp = p.blockPosition();
            ChunkHolder ph = chunkSource.chunkMap.getVisibleChunkIfPresent(new net.minecraft.world.level.ChunkPos(cp).toLong());
            if (ph != null) { checkHolder(level, ph, now); }
        }
        for (var entity : level.getAllEntities()) {
            // 从实体位置取 holder 视角太窄；改用 chunk source 的可见集合——
            // ServerLevel 没有公开枚举全部 holder 的 API，退而求其次：检查实体所在 chunk（覆盖活跃区）
            // 加上玩家所在 chunk。这个覆盖面在死锁场景（save-all 全量卡住）下足够——save-all 卡住时
            // hasWork() 长期为 true，任一活跃 chunk 的 saveSyncFuture 未完成即可确认。
            var pos = entity.blockPosition();
            ChunkHolder holder = chunkSource.chunkMap.getVisibleChunkIfPresent(new net.minecraft.world.level.ChunkPos(pos).toLong());
            if (holder == null) continue;
            checkHolder(level, holder, now);
            if (++checked >= 64) break;   // 每维度每轮最多 64 个，控制开销
        }
    }

    private static void checkHolder(ServerLevel level, ChunkHolder holder, long now) {
        // saveSync 字段可为 null（chunk 尚未经历首次保存）——无保存历史≠卡住，直接跳过
        var saveFuture = holder.getSaveSyncFuture();
        if (saveFuture == null || saveFuture.isDone()) {
            PENDING.remove(dimName(level) + "@" + holder.getPos().x + "," + holder.getPos().z);
            return;
        }
        boolean pending = true;
        String key = dimName(level) + "@" + holder.getPos().x + "," + holder.getPos().z;
        if (pending) {
            PENDING.compute(key, (k, t) -> {
                if (t == null) return new Track(now, false);
                long stalledMs = (now - t.sinceNanos()) / 1_000_000L;
                if (!t.registered() && stalledMs >= PENDING_THRESHOLD_MS) {
                    DamageRegistry.register(DamageRegistry.Kind.SAVE_STALLED,
                            k + " (save pending " + stalledMs + "ms)", 1);
                    SableFixMod.LOGGER.warn("保存 future 卡住 {}：{}ms 未完成（已登记损伤）", k, stalledMs);
                    return new Track(t.sinceNanos(), true);
                }
                return t;
            });
        } else {
            PENDING.remove(key);
        }
    }

    private static String dimName(ServerLevel level) {
        return level.dimension().location().toString();
    }

    /** 防未使用告警的占位引用（UUID 保留给未来 player-specific 观测） */
    @SuppressWarnings("unused")
    private static final Class<UUID> KEEP_IMPORT = UUID.class;
}
