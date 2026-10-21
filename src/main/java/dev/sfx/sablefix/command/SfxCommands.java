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
                .then(Commands.literal("backup")
                        .then(Commands.literal("list").executes(c -> backupList(c.getSource())))
                        .then(Commands.literal("del")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .executes(c -> backupDel(c.getSource(),
                                                StringArgumentType.getString(c, "name")))))
                        .then(Commands.literal("purge").executes(c -> backupPurge(c.getSource()))))
                .then(Commands.literal("diag")
                        .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                .executes(c -> diag(c.getSource(), net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(c, "pos")))))
                .then(Commands.literal("report").executes(c -> report(c.getSource())))
                .then(Commands.literal("fix").executes(c -> fix(c.getSource())))
                .then(Commands.literal("unmask").executes(c -> unmask(c.getSource()))));
    }

    /** /sablefix unmask：残留只读兜底（fix 被外部强杀后的救命命令）——扫 sublevels 恢复一切只读。 */
    private static int unmask(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        int n = dev.sfx.sablefix.scan.SableWriteMask.unmaskAll(server);
        // 五审#5：闭环——unmask 修完清登记（否则 STALE_READONLY 每 5 分钟 WARN 永远报、跨重启持久化）
        if (n >= 0) {
            dev.sfx.sablefix.scan.DamageRegistry.clear(
                    dev.sfx.sablefix.scan.DamageRegistry.Kind.STALE_READONLY,
                    dev.sfx.sablefix.scan.DamageRegistry.STALE_READONLY_FILE);
        }
        final int fN = n;
        src.sendSuccess(() -> tr("sablefix.cmd.unmask_done", fN), false);
        return 1;
    }

    /**
     * 一键全自动修复（用户设计）：屏蔽 Sable 写回 → repair-all → 踢全体玩家 → 自动重启。
     * 运行时修复被 Sable 周期写回覆盖的问题（"修了重启也还有"的根治方案）。
     * 编排全部异步（scan+plan 不卡主线程），踢人/重启回主线程执行。
     *
     * 屏蔽方案（务实折中，逆向出 Sable 类名前的保底）：
     * 问题文件全部只读（挡 Sable 周期写回——其写回失败被自身 catch 吞掉不崩服）；
     * 修复每个文件的写入瞬间由 SableWriteMask 单文件解锁/回锁（毫秒窗口，Sable 写回是分钟级周期，碰撞概率可忽略）；
     * 修完 → 踢人 → halt 重启。**刻意不在 halt 前 unmask**（P0 修复 1.5.4）：
     * halt(false) 的停服存档阶段，Sable 仍持有内存中陈旧的子层级索引，文件一旦恢复可写
     * 就会被它写回、覆盖刚清零的槽位——这正是"fix 后自动重启仍报损坏"的根因。
     * 只读改由 ServerStoppedEvent（存档已全部落盘）解锁，并由新进程启动时兜底扫描解锁。
     */
    private static int fix(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        // 对抗审计#1：重复执行守卫——双 fix 交错会导致 MASKED 竞态 + halt 杀 daemon 线程 = 残留只读
        if (!dev.sfx.sablefix.scan.SableWriteMask.tryBeginFix()) {
            src.sendFailure(tr("sablefix.cmd.fix_busy"));
            return 0;
        }
        src.sendSuccess(() -> tr("sablefix.cmd.fix_started"), false);
        dev.sfx.sablefix.net.ServerHandlers.runAsync(() -> {
            try {
                // 1. 屏蔽：sublevels 全部只读（Sable 周期写回撞上只读会走自身异常处理，不影响服运行）。
                //    二轮审计#6：Linux 无 dos:readonly → maskAll 返回 false，降级为纯修复模式
                boolean masked = dev.sfx.sablefix.scan.SableWriteMask.maskAll(server);
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
                final boolean fMasked = masked;
                SableFixMod.LOGGER.warn("fix: {} ok, {} failed, {} slots cleared, masked={}", ok, fail, cleared, masked);
                // 3+4. 回主线程终局：踢全体 + unmask + halt。
                //    二轮审计#1：endFix 移到这里（halt 之前）——守卫覆盖 halt 投递窗口，
                //    第一个终局执行时 FIX_RUNNING 仍为 true，第二个 fix 进不来 → 无交错。
                //    若终局 lambda 没执行到（halt 前进程死）——反正进程都没了，守卫无意义。
                server.execute(() -> {
                    src.sendSuccess(() -> tr("sablefix.cmd.fix_done", fOk, fFail, fCleared, fMasked), false);
                    // 十轮审计#21：**必须遍历快照**。MC 源码实证（1.21.1）：
                    //   ServerGamePacketListenerImpl.onDisconnect → removePlayerFromWorld
                    //     → PlayerList.remove → this.players.remove(p)   ← 改的是 ArrayList 本身
                    //   而 getPlayers() 返回的正是这个 ArrayList（playersView 才是 unmodifiable 视图，
                    //   但 getPlayers() 返回的是 players）。
                    //   原代码直接 for-each 遍历 → 踢第一个玩家即抛 ConcurrentModificationException
                    //   → endFix() 不执行（fix 永久 busy）+ server.halt(false) 不执行（**服不重启**）
                    //   → 而此时 sublevels 已被设只读（Sable 写回被挡）→ 子层级进度存不下来。
                    //   快照遍历 + 每玩家独立 try/catch + 终局 finally，三重兜底。
                    try {
                        for (ServerPlayer p : new ArrayList<>(server.getPlayerList().getPlayers())) {
                            try {
                                p.connection.disconnect(tr("sablefix.cmd.fix_kick"));
                            } catch (Exception e) {
                                SableFixMod.LOGGER.warn("fix: 踢出 {} 失败（继续踢其余玩家）", p.getName().getString(), e);
                            }
                        }
                    } catch (Exception e) {
                        SableFixMod.LOGGER.error("fix: 踢人阶段异常（仍会停服重启）", e);
                    } finally {
                        // P0 修复（1.5.4）：此处**不再** unmaskAll！保持只读穿过 shutdown，
                        // 挡住 Sable 垂死写回覆盖刚清零的槽位；解锁交给 ServerStoppedEvent
                        // （存档落盘后）+ 新进程启动时的目录兜底解锁。
                        dev.sfx.sablefix.scan.SableWriteMask.endFix();
                        server.halt(false);
                    }
                });
            } catch (Exception e) {
                SableFixMod.LOGGER.error("fix 失败（不重启，解除屏蔽）", e);
                dev.sfx.sablefix.scan.SableWriteMask.unmaskAll(server);
                server.execute(() -> src.sendFailure(tr("sablefix.cmd.fix_fail", e.getMessage())));
                dev.sfx.sablefix.scan.SableWriteMask.endFix();
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
                    // 十轮审计#22：原先用 tr(...).getString() 拼字符串 → **服务端 getString() 永远返回英文**
                    // （服务端 Language 只有 en_us）→ 中文客户端看到的是 "dead slot(s)" 而不是 "个死槽"。
                    // 改为整行 translatable → 客户端本地化 ✅（Component 对象在客户端渲染时才解析）。
                    src.sendSuccess(() -> tr("sablefix.cmd.repair_all_file_line",
                            p.file().getFileName().toString(), p.slots().size()), false);
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
                    // 二轮审计#4：包 withWritable——与 fix 并发（SCAN_POOL 串行但时序上可能在 fix 屏蔽期间）
                    // 时不再因只读全军 AccessDenied；was=false 时等价直调（不回锁，幂等）
                    SubLevelRepair.RepairResult res = dev.sfx.sablefix.scan.SableWriteMask.withWritable(p.file(),
                            () -> { try { return SubLevelRepair.apply(p); } catch (IOException e) { throw new RuntimeException(e); } });
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
            // 十轮审计#44：dir 必须**也**归一化——原来只归一化 target、拿未归一化的 dir 去比：
            //   dir 含 ".." 时（管理员用 --world-path "C:\server\world\..\world" 启动即可出现），
            //   target 归一化后变成 "...\world\sublevels\x" 而 dir 仍是 "...\world\..\world\sublevels"
            //   → startsWith 恒为 false → **所有修复请求都被判越界** → 修复功能完全不可用。
            // 两侧都归一化后再比，路径穿越防护不受影响（探针实测 C:..\..\evil.txt 与 C:\abs.txt 仍被拦）。
            dir = dir.normalize();
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

    /**
     * 十轮审计#4：统一净化翻译参数。
     *  TranslatableContents 的构造器在**非 production 环境**会校验参数类型，只接受
     *  Component / Number / Boolean / String——传 Path 之类会直接抛 IllegalArgumentException
     *  （开发环境必崩）。null 参数同样不合法（生产环境渲染成 "null"，开发环境抛异常）。
     *  这里把所有非合法参数统一转成 String，null 转空串——一处净化，全调用点受益。
     */
    private static Component tr(String key, Object... args) {
        if (args == null || args.length == 0) return Component.translatable(key);
        Object[] safe = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a == null) safe[i] = "";
            else if (a instanceof Component || a instanceof Number || a instanceof Boolean || a instanceof String) safe[i] = a;
            else safe[i] = a.toString();
        }
        return Component.translatable(key, safe);
    }

    /** 毫秒→可读时长（几秒前/几分钟前/几小时前/几天前）。服务端已减掉 now，此处只做展示。 */
    private static String fmtAge(long ageMs) {
        if (ageMs < 0) ageMs = 0;
        long s = ageMs / 1000;
        if (s < 60) return s + "秒前";
        long m = s / 60;
        if (m < 60) return m + "分钟前";
        long h = m / 60;
        if (h < 24) return h + "小时前";
        return h / 24 + "天前";
    }

    private static int help(CommandSourceStack src) {
        src.sendSuccess(() -> tr("sablefix.cmd.help_title"), false);
        src.sendSuccess(() -> Component.literal("§7/sablefix check §f— ").append(tr("sablefix.cmd.help_check")), false);
        src.sendSuccess(() -> Component.literal("§7/sablefix list §f— ").append(tr("sablefix.cmd.help_list")), false);
        src.sendSuccess(() -> Component.literal("§7/sablefix tickets §f— ").append(tr("sablefix.cmd.help_tickets")), false);
        src.sendSuccess(() -> Component.literal("§7/sablefix dump §f— ").append(tr("sablefix.cmd.help_dump")), false);
        src.sendSuccess(() -> Component.literal("§7/sablefix report §f— ").append(tr("sablefix.cmd.help_report")), false);
        src.sendSuccess(() -> Component.literal("§7/sablefix fix §f— ").append(tr("sablefix.cmd.help_fix")), false);
        src.sendSuccess(() -> Component.literal("§7/sablefix unmask §f— ").append(tr("sablefix.cmd.help_unmask")), false);
        src.sendSuccess(() -> Component.literal("§7/sablefix repair <file> dry|apply §f— ").append(tr("sablefix.cmd.help_repair")), false);
        src.sendSuccess(() -> Component.literal("§7/sablefix repair-all dry|apply §f— ").append(tr("sablefix.cmd.help_repair_all")), false);
        src.sendSuccess(() -> Component.literal("§7/sablefix tickets-purge dry|apply §f— ").append(tr("sablefix.cmd.help_purge")), false);
        src.sendSuccess(() -> Component.literal("§7/sablefix diag <x y z> §f— ").append(tr("sablefix.cmd.help_diag")), false);
        src.sendSuccess(() -> Component.literal("§7/sfxpanel §f— ").append(tr("sablefix.cmd.help_panel")), false);
        src.sendSuccess(() -> Component.literal("§7/sablefix backup list|del <name>|purge §f— ")
                .append(tr("sablefix.cmd.help_backup")), false);
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
                // 1.6.2：先报"已登记损伤"（持久化登记簿，跨重启保留）再报本次实扫结果。
                // 实机反馈：fix 后自动重启仍报损坏时，用户无法一眼看出是哪一类损伤——
                // 登记簿里明明有 SUBLVL_DEAD / STALE_READONLY，check 却只打扫描计数。
                java.util.List<dev.sfx.sablefix.scan.DamageRegistry.Entry> dmg =
                        dev.sfx.sablefix.scan.DamageRegistry.snapshot();
                Path dir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("sublevels");
                SubLevelScan.Report r = SubLevelScan.scan(dir);
                long dead = r.slvlr().stream().mapToLong(SubLevelScan.SlvlIssue::deadPointers).sum();
                long bad = r.slvls().stream().mapToLong(SubLevelScan.SlvlsIssue::badEntries).sum();
                // 按 kind 汇总（保持登记顺序）：SUBLVL_DEAD×3(2 文件) 这类一行看完
                java.util.LinkedHashMap<String, int[]> byKind = new java.util.LinkedHashMap<>();
                for (var e : dmg) {
                    int[] agg = byKind.computeIfAbsent(e.kind, k -> new int[2]);
                    agg[0] += e.count;
                    agg[1]++;
                }
                StringBuilder sb = new StringBuilder();
                for (var en : byKind.entrySet()) {
                    if (sb.length() > 0) sb.append(" §7| §f");
                    sb.append(dev.sfx.sablefix.scan.DamageRegistry.kindLabel(en.getKey()))
                            .append(" ×").append(en.getValue()[0])
                            .append("§7(").append(en.getValue()[1]).append(" 项)§f");
                }
                final String kindSummary = sb.toString();
                server.execute(() -> {
                    src.sendSuccess(() -> tr("sablefix.cmd.check_sub",
                            r.scannedSlvlr(), dead, r.scannedSlvls(), bad), false);
                    if (kindSummary.isEmpty()) {
                        src.sendSuccess(() -> tr("sablefix.cmd.check_damage_zero"), false);
                    } else {
                        src.sendSuccess(() -> tr("sablefix.cmd.check_damage",
                                dmg.size(), kindSummary), false);
                        // 分类型处置指引：STALE_READONLY 是 unmask（repair 无效），
                        // TICKETS_STUCK 是 tickets-purge，其余走 repair（1.6.2）
                        boolean hasRepairable = byKind.containsKey("SUBLVL_DEAD");
                        boolean hasStale = byKind.containsKey("STALE_READONLY");
                        boolean hasTickets = byKind.containsKey("TICKETS_STUCK");
                        if (hasStale) src.sendSuccess(() -> tr("sablefix.cmd.check_hint_stale"), false);
                        if (hasTickets) src.sendSuccess(() -> tr("sablefix.cmd.check_hint_tickets"), false);
                        if (hasRepairable) src.sendSuccess(() -> tr("sablefix.cmd.check_hint_repair"), false);
                        if (byKind.containsKey("SAVE_STALLED") && !hasRepairable && !hasStale && !hasTickets) {
                            src.sendSuccess(() -> tr("sablefix.cmd.check_hint_stalled"), false);
                        }
                    }
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
                    // 十轮审计#45：列表**必须限条数**——每条 sendSuccess 都在主线程做
                    // 序列化+网络广播，600 个损坏文件 = 600 条消息 → tick 被阻塞、
                    // 客户端被刷爆（票据 uuid 同理，64MB 上限内可达百万条）。
                    // 与 panel.backups_more 同思路：列前 N 条，剩余只报数量。
                    final int LIMIT = 20;
                    int shown = 0;
                    int hidden = 0;
                    for (SubLevelScan.SlvlIssue i : r.slvlr()) {
                        if (shown >= LIMIT) { hidden++; continue; }
                        shown++;
                        // 十轮审计#4：参数必须是 String/Number/Boolean/Component——传 Path 会在
                        // 非 production 环境下被 TranslatableContents 构造器直接拒（IllegalArgumentException）。
                        src.sendSuccess(() -> tr("sablefix.cmd.list_slvlr",
                                i.file().getFileName().toString(), i.deadPointers(), i.livePointers(), i.size()), false);
                    }
                    for (SubLevelScan.SlvlsIssue i : r.slvls()) {
                        if (shown >= LIMIT) { hidden++; continue; }
                        shown++;
                        src.sendSuccess(() -> tr("sablefix.cmd.list_slvls",
                                i.file().getFileName().toString(), i.badEntries(), i.size()), false);
                    }
                    if (hidden > 0) {
                        final int h = hidden;
                        src.sendSuccess(() -> tr("sablefix.cmd.list_more", h), false);
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
            // 十轮审计#45：uuid 列表**必须限条数**——票据文件解压上限 64MB，每条票据
            // 16 字节 → 理论可达百万级；forEach 每条一次 sendSuccess（主线程序列化+广播）
            // → **tick 卡死 + 客户端被刷爆**。列前 N 条，其余只报数量。
            final int LIMIT = 20;
            List<java.util.UUID> uuids = t.uuids();
            int shown = Math.min(LIMIT, uuids.size());
            for (int i = 0; i < shown; i++) {
                final String u = uuids.get(i).toString();
                src.sendSuccess(() -> Component.literal("  " + u), false);
            }
            // 十轮审计#53：省略数必须按**权威条数**算，不能按 uuids.size()。
            // 自研解析器的 uuids 可能与权威 count 不一致（票据元素是 compound 形态时一条都
            // 收集不到，见 TicketScan#37），原实现会出现两种看不懂的输出：
            //  ① 「30000 条」但**一条 uuid 都不列、也不提示省略**（uuids 为空 → hidden=0）；
            //  ② 「30000 条」+ 列 20 条 +「另有 480 条未列出」→ 20+480≠30000，数字对不上。
            int hidden = Math.max(0, t.count() - shown);
            if (hidden > 0) {
                final int h = hidden;
                src.sendSuccess(() -> tr("sablefix.cmd.tickets_more", h), false);
            }
            // 明细一条都收集不到但权威条数 > 0 → 明确说明「明细不可解析」，别让用户以为命令坏了
            if (t.count() > 0 && uuids.isEmpty()) {
                src.sendSuccess(() -> tr("sablefix.cmd.tickets_detail_unavailable"), false);
            }
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
            // 十轮审计#22：status 改为 Component（不 .getString()）——服务端 getString() 永远返回英文
            // （服务端 Language 只有 en_us），拼进 translatable 参数后客户端也不会再翻译它
            // → 中文客户端看到的是 "loaded" 而不是 "已加载"。Component 作为 translatable 参数
            // 会在**客户端渲染时递归解析** → 本地化正确 ✅（MC 标准做法）。
            net.minecraft.network.chat.Component status = loaded
                    ? tr("sablefix.diag.loaded") : tr("sablefix.diag.not_loaded");
            final int fe = entities, fbe = blockEntities;
            final String fDim = level.dimension().location().toString();
            final int fCx = cx, fCz = cz;
            src.sendSuccess(() -> tr("sablefix.cmd.diag_header", "(" + fCx * 16 + ", " + fCz * 16 + ")", status), false);
            src.sendSuccess(() -> tr("sablefix.cmd.diag_dim", fDim), false);
            src.sendSuccess(() -> tr("sablefix.cmd.diag_entities", fe), false);
            src.sendSuccess(() -> tr("sablefix.cmd.diag_be", fbe), false);
            return 1;
        } catch (Exception e) {
            src.sendFailure(tr("sablefix.cmd.diag_fail", e.getMessage()));
            SableFixMod.LOGGER.error("diag 命令失败", e);
        }
        return 0;
    }

    private static int dump(CommandSourceStack src) {
        // 十轮审计#11：手动抓栈必须与自动路径同构。
        //  ① dump() 只捕获 IOException——SecurityException 等未受检异常会从命令 handler
        //     逸出（命令包装层兜住不崩服，但提示不可控）。
        //  ② dumpAllThreads(true, true) 带锁信息，需要 touching 每个持锁线程——在主线程
        //     同步执行可能阻塞在与卡死现场相关的锁上（正是抓栈想诊断的场景）。
        //     移到后台线程：主线程只投递，完成后回主线程给提示。
        final MinecraftServer server = src.getServer();
        dev.sfx.sablefix.net.ServerHandlers.runAsync(() -> {
            Path out = null;
            try {
                out = ThreadWatchdog.dump(-1L);
            } catch (Exception e) {
                SableFixMod.LOGGER.error("手动抓栈失败（与自动路径同构的兜底）", e);
            }
            // 十轮审计#29：dump 现在返回写入路径；失败时为 null（半截文件已被删）。
            //  原先无论成败都回显 dump_ok——磁盘满时玩家以为抓到了，实际什么都没有。
            final boolean ok = out != null;
            final String fileName = ok ? out.getFileName().toString() : "";
            server.execute(() -> {
                if (ok) src.sendSuccess(() -> tr("sablefix.cmd.dump_ok", fileName), false);
                else src.sendFailure(tr("sablefix.cmd.dump_fail"));
            });
        });
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
     * 备份列表：回显 backups/sablefix/ 下的 .bak 文件（按时间新→旧，最多 8 行），
     * 目录不存在或为空时提示。IO 很廉价，直接主线程执行。
     */
    private static int backupList(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        List<dev.sfx.sablefix.net.NetHandler.PanelData.BackupRow> rows =
                dev.sfx.sablefix.net.ServerHandlers.listBackups(server);
        Path backupDir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("backups").resolve("sablefix");
        src.sendSuccess(() -> tr("sablefix.cmd.backup_dir", backupDir.toString()), false);
        if (rows.isEmpty()) {
            src.sendSuccess(() -> tr("sablefix.cmd.backup_none"), false);
            return 1;
        }
        src.sendSuccess(() -> tr("sablefix.cmd.backup_count", rows.size()), false);
        int shown = Math.min(rows.size(), 8);
        for (int i = 0; i < shown; i++) {
            dev.sfx.sablefix.net.NetHandler.PanelData.BackupRow b = rows.get(i);
            src.sendSuccess(() -> tr("sablefix.cmd.backup_row", b.name(), dev.sfx.sablefix.client.ui.Format.fmtSize(b.size()),
                    fmtAge(b.ageMs())), false);
        }
        if (rows.size() > shown) {
            src.sendSuccess(() -> tr("sablefix.panel.backups_more", rows.size() - shown), false);
        }
        return 1;
    }

    /**
     * 删除单个备份：仅允许纯文件名（内部已防路径遍历），删除后回显结果。
     */
    private static int backupDel(CommandSourceStack src, String name) {
        try {
            dev.sfx.sablefix.net.ServerHandlers.deleteBackup(src.getServer(), name);
            src.sendSuccess(() -> tr("sablefix.cmd.backup_del_done", name), false);
            return 1;
        } catch (java.nio.file.NoSuchFileException e) {
            src.sendFailure(tr("sablefix.cmd.backup_none"));   // 六轮审计#4：硬编码中文替换为双语键
            return 0;
        } catch (java.nio.file.FileSystemException e) {
            src.sendFailure(tr("sablefix.cmd.backup_del_locked", name));
            SableFixMod.LOGGER.warn("backup-del locked: {}", name, e);
            return 0;
        } catch (Exception e) {
            src.sendFailure(tr("sablefix.cmd.backup_del_fail", e.getMessage()));
            SableFixMod.LOGGER.error("backup-del failed: {}", name, e);
            return 0;
        }
    }

    /**
     * 清空全部备份：返回删除数量并回显。
     */
    private static int backupPurge(CommandSourceStack src) {
        try {
            int n = dev.sfx.sablefix.net.ServerHandlers.purgeBackups(src.getServer());
            src.sendSuccess(() -> tr("sablefix.cmd.backup_purge_done", n), false);
            return 1;
        } catch (java.nio.file.FileSystemException e) {
            src.sendFailure(tr("sablefix.cmd.backup_purge_locked"));
            SableFixMod.LOGGER.warn("backup-purge locked", e);
            return 0;
        } catch (Exception e) {
            src.sendFailure(tr("sablefix.cmd.backup_purge_fail", e.getMessage()));
            SableFixMod.LOGGER.error("backup-purge failed", e);
            return 0;
        }
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
