package dev.sfx.sablefix.command;

import com.mojang.brigadier.CommandDispatcher;
import dev.sfx.sablefix.SableFixMod;
import dev.sfx.sablefix.scan.SubLevelScan;
import dev.sfx.sablefix.scan.TicketScan;
import dev.sfx.sablefix.watchdog.ThreadWatchdog;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.nio.file.Path;

/**
 * B/C/D/E 的命令入口。
 * /sablefix check   —— 全量体检子层级 + 票据
 * /sablefix list    —— 子层级问题文件清单（C 浏览器的最小版）
 * /sablefix tickets —— 票据明细
 * /sablefix dump    —— 立刻抓一份全线程栈（E 手动版）
 * 全部只读，无副作用，权限 2 级以上。
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
                .then(Commands.literal("dump").executes(c -> dump(c.getSource()))));
    }

    private static int help(CommandSourceStack src) {
        src.sendSuccess(() -> Component.literal("§6=== Sable Fix Core 帮助 ==="), false);
        src.sendSuccess(() -> Component.literal("§7/sablefix check §f— 全量体检：子层级指针 + 票据文件（只读）"), false);
        src.sendSuccess(() -> Component.literal("§7/sablefix list §f— 列出有死指针/坏条目的文件"), false);
        src.sendSuccess(() -> Component.literal("§7/sablefix tickets §f— 查看 force-load 票据明细"), false);
        src.sendSuccess(() -> Component.literal("§7/sablefix dump §f— 立刻抓一份全线程栈到 logs/sablefix/"), false);
        src.sendSuccess(() -> Component.literal("§7客户端 /sfxpanel §f— 打开图形面板（需装同一个 jar）"), false);
        src.sendSuccess(() -> Component.literal("§8被动功能：启动自动体检 · 卡死 20 秒自动抓栈 · 运行日志"), false);
        src.sendSuccess(() -> Component.literal("§8问题反馈Q群 1129543042"), false);
        return 1;
    }

    private static int check(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        try {
            Path dir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("sublevels");
            SubLevelScan.Report r = SubLevelScan.scan(dir);
            long dead = r.slvlr().stream().mapToLong(SubLevelScan.SlvlIssue::deadPointers).sum();
            long bad = r.slvls().stream().mapToLong(SubLevelScan.SlvlsIssue::badEntries).sum();
            src.sendSuccess(() -> Component.literal(String.format(
                    "子层级体检: .slvlr %d 个(死指针 %d) .slvls %d 个(坏条目 %d)",
                    r.scannedSlvlr(), dead, r.scannedSlvls(), bad)), false);
            if (!r.slvlr().isEmpty() || !r.slvls().isEmpty()) {
                src.sendSuccess(() -> Component.literal("用 /sablefix list 看具体文件"), false);
            }
            TicketScan.TicketInfo t = TicketScan.read(TicketScan.ticketFile(server));
            src.sendSuccess(() -> Component.literal("票据: " + t.count() + " 条, 解析正常"), false);
        } catch (Exception e) {
            src.sendFailure(Component.literal("体检出错: " + e.getMessage()));
            SableFixMod.LOGGER.error("check 失败", e);
        }
        return 0;
    }

    private static int list(CommandSourceStack src) {
        try {
            Path dir = src.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("sublevels");
            SubLevelScan.Report r = SubLevelScan.scan(dir);
            if (r.slvlr().isEmpty() && r.slvls().isEmpty()) {
                src.sendSuccess(() -> Component.literal("没有发现问题文件"), false);
                return 0;
            }
            for (SubLevelScan.SlvlIssue i : r.slvlr()) {
                src.sendSuccess(() -> Component.literal(String.format("%s: 死指针 %d / 有效 %d, %d 字节",
                        i.file().getFileName(), i.deadPointers(), i.livePointers(), i.size())), false);
            }
            for (SubLevelScan.SlvlsIssue i : r.slvls()) {
                src.sendSuccess(() -> Component.literal(String.format("%s: 坏条目 %d, %d 字节",
                        i.file().getFileName(), i.badEntries(), i.size())), false);
            }
        } catch (Exception e) {
            src.sendFailure(Component.literal("出错: " + e.getMessage()));
        }
        return 0;
    }

    private static int tickets(CommandSourceStack src) {
        try {
            TicketScan.TicketInfo t = TicketScan.read(TicketScan.ticketFile(src.getServer()));
            src.sendSuccess(() -> Component.literal("票据共 " + t.count() + " 条, 文件 " + t.fileSize() + " 字节"), false);
            t.uuids().forEach(u -> src.sendSuccess(() -> Component.literal("  " + u), false));
        } catch (Exception e) {
            src.sendFailure(Component.literal("票据读取失败: " + e.getMessage()));
        }
        return 0;
    }

    private static int dump(CommandSourceStack src) {
        ThreadWatchdog.dump(-1L);
        src.sendSuccess(() -> Component.literal("已抓全线程栈，见 logs/sablefix/"), false);
        return 0;
    }
}
