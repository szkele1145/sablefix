package dev.sfx.sablefix.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.sfx.sablefix.SableFixMod;
import dev.sfx.sablefix.scan.SubLevelRepair;
import dev.sfx.sablefix.scan.SubLevelScan;
import dev.sfx.sablefix.scan.TicketPurge;
import dev.sfx.sablefix.scan.TicketScan;
import dev.sfx.sablefix.watchdog.ThreadWatchdog;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * B/C/D/E 的命令入口。
 * /sablefix check   —— 全量体检子层级 + 票据
 * /sablefix list    —— 子层级问题文件清单（C 浏览器的最小版）
 * /sablefix tickets —— 票据明细
 * /sablefix dump    —— 立刻抓一份全线程栈（E 手动版）
 * 全部只读，无副作用，权限 2 级以上。
 * 文案走 lang key（zh_cn/en_us）。
 */
public final class SfxCommands {

    private SfxCommands() {}

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("sablefix")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("help").executes(c -> help(c.getSource())))
                .then(Commands.literal("check").executes(c -> check(c.getSource())))
                .then(Commands.literal("list").executes(c -> list(c.getSource())))
                .then(Commands.literal("tickets").executes(c -> tickets(c.getSource())))
                .then(Commands.literal("dump").executes(c -> dump(c.getSource())))
                .then(Commands.literal("repair")
                        .then(Commands.argument("file", StringArgumentType.word())
                                .suggests((c, b) -> suggestProblemFiles(c.getSource(), b))
                                .then(Commands.literal("dry").executes(c -> repair(c.getSource(),
                                        StringArgumentType.getString(c, "file"), false)))
                                .then(Commands.literal("apply").executes(c -> repair(c.getSource(),
                                        StringArgumentType.getString(c, "file"), true)))))
                .then(Commands.literal("repair-all")
                        .then(Commands.literal("dry").executes(c -> repairAll(c.getSource(), false)))
                        .then(Commands.literal("apply").executes(c -> repairAll(c.getSource(), true))))
                .then(Commands.literal("tickets-purge")
                        .then(Commands.literal("dry").executes(c -> ticketsPurge(c.getSource(), false)))
                        .then(Commands.literal("apply").executes(c -> ticketsPurge(c.getSource(), true))))
                .then(Commands.literal("diag")
                        .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                .executes(c -> diag(c.getSource(), net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(c, "pos")))))
                .then(Commands.literal("report").executes(c -> report(c.getSource())))
                .then(Commands.literal("fix").executes(c -> fix(c.getSource()))));
    }

    /**
     * 一键全自动修复（用户设计）：屏蔽 Sable 写回 → repair-all → 踢全体玩家 → 自动重启。
     * 运行时修复被 Sable 周期写回覆盖的问题（"修了重启也还有"的根治方案）。
     * 编排全部异步（scan+plan 不卡主线程），踢人/重启回主线程执行。
     *
     * 屏蔽方案（务实折中，逆向出 Sable 类名前的保底）：
     * 问题文件全部只读（挡 Sable 周期写回——其写回失败被自身 catch 吞掉不崩服）；
     * 修复每个文件的写入瞬间由 SableWriteMask 单文件解锁/回锁（毫秒窗口，Sable 写回是分钟级周期，碰撞概率可忽略）；
     * 修完 → 踢人 → halt 重启（重启前 unmask 保证 Sable 新进程能写）。
     */
    private static int fix(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        src.sendSuccess(() -> tr("sablefix.cmd.fix_started"), false);
        dev.sfx.sablefix.net.ServerHandlers.runAsync(() -> {
            try {
                // 1. 屏蔽：sublevels 全部只读（Sable 周期写回撞上只读会走自身异常处理，不影响服运行）
                dev.sfx.sablefix.scan.SableWriteMask.maskAll(server);
                // 2. 全量修复（逐文件：解锁 → 备份+清零 → 回锁）
                Path dir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("sublevels");
                SubLevelScan.Report r = SubLevelScan.scan(dir);
                List<SubLevelRepair.RepairPlan> plans = new ArrayList<>();
                for (SubLevelScan.SlvlIssue i : r.slvlr()) {
                    try { plans.add(SubLevelRepair.plan(i.file())); } catch (Exception ignored) {}
                }
                for (SubLevelScan.SlvlsIssue i : r.slvls()) {
                    try { plans.add(SubLevelRepair.plan(i.file())); } catch (Exception ignored) {}
                }
                plans.removeIf(SubLevelRepair.RepairPlan::isEmpty);
                int ok = 0, fail = 0, cleared = 0;
                for (SubLevelRepair.RepairPlan p : plans) {
                    try {
                        SubLevelRepair.RepairResult res = dev.sfx.sablefix.scan.SableWriteMask.withWritable(p.file(),
                                () -> { try { return SubLevelRepair.apply(p); } catch (IOException e) { throw new RuntimeException(e); } });
                        ok++; cleared += res.cleared();
                    } catch (Exception e) {
                        fail++;
                        SableFixMod.LOGGER.warn("fix: {} failed: {}", p.file().getFileName(), e.getMessage());
                    }
                }
                final int fOk = ok, fFail = fail, fCleared = cleared;
                SableFixMod.LOGGER.warn("fix: {} ok, {} failed, {} slots cleared", ok, fail, cleared);
                server.execute(() -> src.sendSuccess(() -> tr("sablefix.cmd.fix_done", fOk, fFail, fCleared), false));
                // 3+4. 回主线程：踢全体 + 自动重启（重启前 unmask——halt 后由 finally 兜底也行，
                //      但主线程先 unmask 更稳：玩家被踢时文件已恢复可写）
                server.execute(() -> {
                    for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                        p.connection.disconnect(tr("sablefix.cmd.fix_kick"));
                    }
                    dev.sfx.sablefix.scan.SableWriteMask.unmaskAll();
                    server.halt(false);
                });
            } catch (Exception e) {
                SableFixMod.LOGGER.error("fix 失败（不重启，解除屏蔽）", e);
                dev.sfx.sablefix.scan.SableWriteMask.unmaskAll();
                server.execute(() -> src.sendFailure(tr("sablefix.cmd.fix_fail", e.getMessage())));
            }
        });
        return 1;
    }
    /**
     * 批量修复：dry = 汇总所有问题文件的死槽计划（不写盘）；
     * apply = 逐文件备份 + 清零。单个文件失败（被占用/IO 错）不中断整体，最后汇总成功/失败数。
     */
    private static int repairAll(CommandSourceStack src, boolean doApply) {
        MinecraftServer server = src.getServer();
        // scan+plan 段异步（600 文件 ~3.5s 不卡主线程；apply 是用户显式操作、毫秒级/文件留主线程）。
        // 注：server.execute 仅作调度跳板（命令已在主线程），真正目的是进 runAsync 的扫描池。
        dev.sfx.sablefix.net.ServerHandlers.runAsync(() -> {
        try {
            Path dir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("sublevels");
            SubLevelScan.Report r = SubLevelScan.scan(dir);
            List<SubLevelRepair.RepairPlan> plans = new ArrayList<>();
            for (SubLevelScan.SlvlIssue i : r.slvlr()) {
                try { plans.add(SubLevelRepair.plan(i.file())); } catch (Exception ignored) {}
            }
            for (SubLevelScan.SlvlsIssue i : r.slvls()) {
                try { plans.add(SubLevelRepair.plan(i.file())); } catch (Exception ignored) {}
            }
            plans.removeIf(SubLevelRepair.RepairPlan::isEmpty);
            if (plans.isEmpty()) {
                server.execute(() -> src.sendSuccess(() -> tr("sablefix.cmd.repair_all_nothing"), false));
                return;
            }
            long totalSlots = plans.stream().mapToLong(p -> p.slots().size()).sum();
            server.execute(() -> {
                src.sendSuccess(() -> tr("sablefix.cmd.repair_all_plan", plans.size(), totalSlots), false);
                for (SubLevelRepair.RepairPlan p : plans) {
                    src.sendSuccess(() -> Component.literal("  " + p.file().getFileName()
                            + " → " + p.slots().size() + " " + tr("sablefix.cmd.repair_slots_word").getString()), false);
                }
                if (!doApply) {
                    src.sendSuccess(() -> tr("sablefix.cmd.repair_all_dry_hint"), false);
                }
            });
            if (!doApply) return;
            int okFiles = 0, failFiles = 0, cleared = 0;
            Path lastBackup = null;
            final int planTotal = plans.size();
            int idx = 0;
            for (SubLevelRepair.RepairPlan p : plans) {
                idx++;
                final String fname = p.file().getFileName().toString();
                final int no = idx;
                try {
                    SubLevelRepair.RepairResult res = SubLevelRepair.apply(p);
                    okFiles++;
                    cleared += res.cleared();
                    lastBackup = res.backup();
                    // 逐文件进度：批量时玩家能看到在动，不像卡死
                    server.execute(() -> src.sendSuccess(() -> tr("sablefix.cmd.repair_all_progress", no, planTotal, fname, res.cleared()), false));
                } catch (Exception e) {
                    failFiles++;
                    SableFixMod.LOGGER.warn("repair-all: {} failed: {}", fname, e.getMessage());
                    server.execute(() -> src.sendFailure(tr("sablefix.cmd.repair_all_progress_fail", no, planTotal, fname)));
                }
            }
            final int fOk = okFiles, fFail = failFiles, fCleared = cleared;
            final Path fBackup = lastBackup;
            server.execute(() -> {
                src.sendSuccess(() -> tr("sablefix.cmd.repair_all_done", fOk, fFail, fCleared), false);
                if (fBackup != null) src.sendSuccess(() -> tr("sablefix.cmd.repair_backup_in", fBackup.getParent().toString()), false);
                src.sendSuccess(() -> tr("sablefix.cmd.repair_restart_hint"), false);
            });
            SableFixMod.LOGGER.warn("repair-all: {} ok, {} failed, {} slots cleared", okFiles, failFiles, cleared);
        } catch (Exception e) {
            server.execute(() -> src.sendFailure(tr("sablefix.cmd.repair_fail", "all", e.getMessage())));
            SableFixMod.LOGGER.error("repair-all failed", e);
        }
        });
        return 1;
    }

    /** 补全：只建议有问题的文件名（裸名，不含路径）。
     *  审计：原实现每次按键同步跑全量 scan（大存档 3.5s/键——打字必卡）。
     *  改为只读 SCAN_CACHE（面板维护的缓存，含问题文件清单），未预热时返回空建议（面板一开即预热）。 */
    private static CompletableFuture<Suggestions> suggestProblemFiles(CommandSourceStack src, SuggestionsBuilder b) {
        // 可用性审计#3：纯命令流管理员没开过面板 → SCAN_CACHE 为空 → tab 永远无建议。
        // 缓存为空时异步触发一次扫描（runAsync 幂等），本次先返回已能给出的（可能空）建议，
        // 下一次按键即命中缓存——不打断输入。
        try {
            String prefix = b.getRemainingLowerCase();
            var names = dev.sfx.sablefix.net.ServerHandlers.cachedProblemFileNames();
            if (names.isEmpty()) {
                dev.sfx.sablefix.net.ServerHandlers.warmScanCache(src.getServer());
            }
            for (String name : names) {
                if (name.toLowerCase().startsWith(prefix)) b.suggest(name);
            }
        } catch (Exception ignored) {}
        return b.buildFuture();
    }

    /**
     * 修复入口。dry=true 只预览；apply = 备份 + 清零死槽。
     * 提示：服务器运行中 Sable 可能持有存档句柄；apply 前建议停服（写失败会明确报错）。
     */
    private static int repair(CommandSourceStack src, String fileName, boolean doApply) {
        try {
            Path dir = src.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("sublevels");
            Path target = dir.resolve(fileName).normalize();
            if (!target.startsWith(dir) || !java.nio.file.Files.exists(target)) {
                // 大小写容错：遍历目录找忽略大小写的同名文件（玩家手输大小写经常不准）
                // 修正：DirectoryStream 用 iterator() 每次返回新迭代器——旧代码
                // ds.iterator().hasNext() + ds.iterator().next() 拿的是两个不同迭代器，
                // 且只 next 一次（每次循环重新判断 hasNext → 死循环风险/漏文件）。正确写法用增强 for。
                Path found = null;
                try (java.nio.file.DirectoryStream<Path> ds = java.nio.file.Files.newDirectoryStream(dir)) {
                    for (Path cand : ds) {
                        if (cand.getFileName().toString().equalsIgnoreCase(fileName)) { found = cand; break; }
                    }
                } catch (Exception ignored) {}
                if (found == null || !found.normalize().startsWith(dir)) {
                    src.sendFailure(tr("sablefix.cmd.repair_no_file", fileName));
                    return 0;
                }
                target = found;
            }
            SubLevelRepair.RepairPlan plan = SubLevelRepair.plan(target);
            if (plan.isEmpty()) {
                src.sendSuccess(() -> tr("sablefix.cmd.repair_nothing", fileName), false);
                return 0;
            }
            src.sendSuccess(() -> tr("sablefix.cmd.repair_plan", fileName, plan.slots().size()), false);
            src.sendSuccess(() -> Component.literal("  " + plan.slots().stream().map(String::valueOf)
                    .limit(20).collect(java.util.stream.Collectors.joining(", "))
                    + (plan.slots().size() > 20 ? " …" : "")), false);
            if (!doApply) {
                src.sendSuccess(() -> tr("sablefix.cmd.repair_dry_hint"), false);
                return 1;
            }
            SubLevelRepair.RepairResult res = SubLevelRepair.apply(plan);
            src.sendSuccess(() -> tr("sablefix.cmd.repair_done", res.cleared(), res.backup().toString()), false);
            src.sendSuccess(() -> tr("sablefix.cmd.repair_restart_hint"), false);
            return 1;
        } catch (java.nio.file.FileSystemException e) {
            src.sendFailure(tr("sablefix.cmd.repair_locked", fileName));
            SableFixMod.LOGGER.warn("repair: file locked {}", fileName, e);
        } catch (java.io.FileNotFoundException e) {
            // 兼容性审计：Windows 可读共享句柄下 raf("rw") 抛 FNFE——同 locked 语义
            src.sendFailure(tr("sablefix.cmd.repair_locked", fileName));
            SableFixMod.LOGGER.warn("repair: file in use {}", fileName, e);
        } catch (Exception e) {
            src.sendFailure(tr("sablefix.cmd.repair_fail", fileName, e.getMessage()));
            SableFixMod.LOGGER.error("repair failed: {}", fileName, e);
        }
        return 0;
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable(key, args);
    }

    private static int help(CommandSourceStack src) {
        src.sendSuccess(() -> tr("sablefix.cmd.help_title"), false);
        src.sendSuccess(() -> Component.literal("§7/sablefix check §f— ").append(tr("sablefix.cmd.help_check")), false);
        src.sendSuccess(() -> Component.literal("§7/sablefix list §f— ").append(tr("sablefix.cmd.help_list")), false);
        src.sendSuccess(() -> Component.literal("§7/sablefix tickets §f— ").append(tr("sablefix.cmd.help_tickets")), false);
        src.sendSuccess(() -> Component.literal("§7/sablefix dump §f— ").append(tr("sablefix.cmd.help_dump")), false);
        src.sendSuccess(() -> Component.literal("§7/sablefix report §f— ").append(tr("sablefix.cmd.help_report")), false);
        src.sendSuccess(() -> Component.literal("§7/sablefix fix §f— ").append(tr("sablefix.cmd.help_fix")), false);
        src.sendSuccess(() -> Component.literal("§7/sablefix repair <file> dry|apply §f— ").append(tr("sablefix.cmd.help_repair")), false);
        src.sendSuccess(() -> Component.literal("§7/sablefix repair-all dry|apply §f— ").append(tr("sablefix.cmd.help_repair_all")), false);
        src.sendSuccess(() -> Component.literal("§7/sablefix tickets-purge dry|apply §f— ").append(tr("sablefix.cmd.help_purge")), false);
        src.sendSuccess(() -> Component.literal("§7/sablefix diag <x y z> §f— ").append(tr("sablefix.cmd.help_diag")), false);
        src.sendSuccess(() -> Component.literal("§7/sfxpanel §f— ").append(tr("sablefix.cmd.help_panel")), false);
        src.sendSuccess(() -> tr("sablefix.cmd.help_passive"), false);
        src.sendSuccess(() -> tr("sablefix.cmd.help_qq"), false);
        return 1;
    }

    private static int check(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        // 大存档 scan ~3.5s（600 文件基准折算）不能阻塞主线程 tick——丢扫描池，
        // 结果回主线程再发消息（sendSuccess 必须主线程）。命令本身立即返回。
        server.execute(() -> dev.sfx.sablefix.net.ServerHandlers.runAsync(() -> {
            try {
                Path dir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("sublevels");
                SubLevelScan.Report r = SubLevelScan.scan(dir);
                long dead = r.slvlr().stream().mapToLong(SubLevelScan.SlvlIssue::deadPointers).sum();
                long bad = r.slvls().stream().mapToLong(SubLevelScan.SlvlsIssue::badEntries).sum();
                server.execute(() -> {
                    src.sendSuccess(() -> tr("sablefix.cmd.check_sub",
                            r.scannedSlvlr(), dead, r.scannedSlvls(), bad), false);
                    if (!r.slvlr().isEmpty() || !r.slvls().isEmpty()) {
                        src.sendSuccess(() -> tr("sablefix.cmd.check_list_hint"), false);
                    }
                });
                Path tf = TicketScan.ticketFile(server);
                if (!java.nio.file.Files.exists(tf)) {
                    server.execute(() -> src.sendSuccess(() -> tr("sablefix.cmd.check_tickets_absent"), false));
                } else {
                    TicketScan.TicketInfo t = TicketScan.read(tf);
                    server.execute(() -> src.sendSuccess(() -> tr("sablefix.cmd.check_tickets_ok", t.count()), false));
                }
            } catch (Exception e) {
                server.execute(() -> src.sendFailure(tr("sablefix.cmd.check_fail", e.getMessage())));
                SableFixMod.LOGGER.error("check 失败", e);
            }
        }));
        return 1;   // 已受理（异步），反馈稍后到
    }

    private static int list(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        server.execute(() -> dev.sfx.sablefix.net.ServerHandlers.runAsync(() -> {
            try {
                Path dir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("sublevels");
                SubLevelScan.Report r = SubLevelScan.scan(dir);
                server.execute(() -> {
                    if (r.slvlr().isEmpty() && r.slvls().isEmpty()) {
                        src.sendSuccess(() -> tr("sablefix.cmd.list_empty"), false);
                        return;
                    }
                    for (SubLevelScan.SlvlIssue i : r.slvlr()) {
                        src.sendSuccess(() -> tr("sablefix.cmd.list_slvlr",
                                i.file().getFileName(), i.deadPointers(), i.livePointers(), i.size()), false);
                    }
                    for (SubLevelScan.SlvlsIssue i : r.slvls()) {
                        src.sendSuccess(() -> tr("sablefix.cmd.list_slvls",
                                i.file().getFileName(), i.badEntries(), i.size()), false);
                    }
                });
            } catch (Exception e) {
                server.execute(() -> src.sendFailure(tr("sablefix.cmd.list_fail", e.getMessage())));
            }
        }));
        return 1;
    }

    private static int tickets(CommandSourceStack src) {
        Path tf = TicketScan.ticketFile(src.getServer());
        if (!java.nio.file.Files.exists(tf)) {
            src.sendSuccess(() -> tr("sablefix.cmd.tickets_none"), false);
            return 0;
        }
        try {
            TicketScan.TicketInfo t = TicketScan.read(tf);
            src.sendSuccess(() -> tr("sablefix.cmd.tickets_ok", t.count(), t.fileSize()), false);
            t.uuids().forEach(u -> src.sendSuccess(() -> Component.literal("  " + u), false));
        } catch (Exception e) {
            src.sendFailure(tr("sablefix.cmd.tickets_fail", e.getMessage()));
        }
        return 0;
    }

    /** /sablefix diag <pos>：区块加载状态/实体数/方块实体数（与 GUI 诊断页同源逻辑，只读）。 */
    private static int diag(CommandSourceStack src, net.minecraft.core.BlockPos pos) {
        try {
            net.minecraft.server.level.ServerLevel level = src.getLevel();
            int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
            net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            boolean loaded = chunk != null;
            int entities = 0, blockEntities = 0;
            if (loaded) {
                net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                        cx * 16.0, level.getMinBuildHeight(), cz * 16.0,
                        cx * 16.0 + 16, level.getMaxBuildHeight(), cz * 16.0 + 16);
                entities = level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(net.minecraft.world.entity.Entity.class), box, e -> true).size();
                blockEntities = chunk.getBlockEntities().size();
            }
            String status = loaded ? tr("sablefix.diag.loaded").getString() : tr("sablefix.diag.not_loaded").getString();
            final int fe = entities, fbe = blockEntities;
            final String fDim = level.dimension().location().toString();
            final int fCx = cx, fCz = cz;
            src.sendSuccess(() -> tr("sablefix.cmd.diag_header", "(" + fCx * 16 + ", " + fCz * 16 + ")", status), false);
            src.sendSuccess(() -> tr("sablefix.cmd.diag_dim", fDim), false);
            src.sendSuccess(() -> tr("sablefix.cmd.diag_entities", fe), false);
            src.sendSuccess(() -> tr("sablefix.cmd.diag_be", fbe), false);
            return 1;
        } catch (Exception e) {
            src.sendFailure(tr("sablefix.cmd.list_fail", e.getMessage()));
            SableFixMod.LOGGER.error("diag 命令失败", e);
        }
        return 0;
    }

    private static int dump(CommandSourceStack src) {
        ThreadWatchdog.dump(-1L);
        src.sendSuccess(() -> tr("sablefix.cmd.dump_ok"), false);
        return 0;
    }

    /**
     * 票据清理：dry = 只报当前条数；apply = 备份 + 清空 tickets 列表（其他字段原样保留）。
     * 动的是 data/ 下的正式存档文件，所以 apply 提示比 repair 更重（建议停服）。
     */
    private static int ticketsPurge(CommandSourceStack src, boolean doApply) {
        Path tf = TicketScan.ticketFile(src.getServer());
        try {
            if (!java.nio.file.Files.exists(tf)) {
                src.sendSuccess(() -> tr("sablefix.cmd.tickets_none"), false);
                return 0;
            }
            int n = TicketPurge.count(tf);
            if (n == 0) {
                src.sendSuccess(() -> tr("sablefix.cmd.purge_zero"), false);
                return 0;
            }
            if (!doApply) {
                src.sendSuccess(() -> tr("sablefix.cmd.purge_plan", n), false);
                src.sendSuccess(() -> tr("sablefix.cmd.purge_dry_hint"), false);
                return 1;
            }
            Path backup = TicketPurge.purge(tf);
            src.sendSuccess(() -> tr("sablefix.cmd.purge_done", n, backup.toString()), false);
            src.sendSuccess(() -> tr("sablefix.cmd.purge_restart_hint"), false);
            return 1;
        } catch (java.nio.file.FileSystemException e) {
            src.sendFailure(tr("sablefix.cmd.purge_locked", tf.getFileName().toString()));
            SableFixMod.LOGGER.warn("tickets-purge: file locked", e);
        } catch (java.io.FileNotFoundException e) {
            // 兼容性审计：同 repair——Windows 可读共享句柄下的占用语义
            src.sendFailure(tr("sablefix.cmd.purge_locked", tf.getFileName().toString()));
            SableFixMod.LOGGER.warn("tickets-purge: file in use", e);
        } catch (Exception e) {
            src.sendFailure(tr("sablefix.cmd.purge_fail", e.getMessage()));
            SableFixMod.LOGGER.error("tickets-purge failed", e);
        }
        return 0;
    }

    /**
     * 新功能：一键诊断报告。全量信息（损伤登记簿/模组日志/最新线程栈摘要/运行信息）
     * 打成单个 txt 到 logs/sablefix/——"求助发群里"不用翻四个地方截图。
     * 磁盘 IO 走 SCAN_POOL（dump 读取 + 报告写入），结果回主线程回显路径。
     */
    private static int report(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        dev.sfx.sablefix.net.ServerHandlers.runAsync(() -> {
            try {
                Path out = dev.sfx.sablefix.scan.DiagReport.write();
                server.execute(() -> {
                    src.sendSuccess(() -> tr("sablefix.cmd.report_done", out.toString()), false);
                    src.sendSuccess(() -> tr("sablefix.cmd.report_hint"), false);
                });
            } catch (Exception e) {
                SableFixMod.LOGGER.error("report 生成失败", e);
                server.execute(() -> src.sendFailure(tr("sablefix.cmd.report_fail", e.getMessage())));
            }
        });
        return 1;
    }
}
