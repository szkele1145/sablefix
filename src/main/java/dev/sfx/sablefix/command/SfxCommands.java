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
                .then(Commands.literal("dump").executes(c -> dump(c.getSource()))));
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
        src.sendSuccess(() -> Component.literal("§7/sfxpanel §f— ").append(tr("sablefix.cmd.help_panel")), false);
        src.sendSuccess(() -> tr("sablefix.cmd.help_passive"), false);
        src.sendSuccess(() -> tr("sablefix.cmd.help_qq"), false);
        return 1;
    }

    private static int check(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        try {
            Path dir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("sublevels");
            SubLevelScan.Report r = SubLevelScan.scan(dir);
            long dead = r.slvlr().stream().mapToLong(SubLevelScan.SlvlIssue::deadPointers).sum();
            long bad = r.slvls().stream().mapToLong(SubLevelScan.SlvlsIssue::badEntries).sum();
            src.sendSuccess(() -> tr("sablefix.cmd.check_sub",
                    r.scannedSlvlr(), dead, r.scannedSlvls(), bad), false);
            if (!r.slvlr().isEmpty() || !r.slvls().isEmpty()) {
                src.sendSuccess(() -> tr("sablefix.cmd.check_list_hint"), false);
            }
            Path tf = TicketScan.ticketFile(server);
            if (!java.nio.file.Files.exists(tf)) {
                src.sendSuccess(() -> tr("sablefix.cmd.check_tickets_absent"), false);
            } else {
                TicketScan.TicketInfo t = TicketScan.read(tf);
                src.sendSuccess(() -> tr("sablefix.cmd.check_tickets_ok", t.count()), false);
            }
        } catch (Exception e) {
            src.sendFailure(tr("sablefix.cmd.check_fail", e.getMessage()));
            SableFixMod.LOGGER.error("check 失败", e);
        }
        return 0;
    }

    private static int list(CommandSourceStack src) {
        try {
            Path dir = src.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("sublevels");
            SubLevelScan.Report r = SubLevelScan.scan(dir);
            if (r.slvlr().isEmpty() && r.slvls().isEmpty()) {
                src.sendSuccess(() -> tr("sablefix.cmd.list_empty"), false);
                return 0;
            }
            for (SubLevelScan.SlvlIssue i : r.slvlr()) {
                src.sendSuccess(() -> tr("sablefix.cmd.list_slvlr",
                        i.file().getFileName(), i.deadPointers(), i.livePointers(), i.size()), false);
            }
            for (SubLevelScan.SlvlsIssue i : r.slvls()) {
                src.sendSuccess(() -> tr("sablefix.cmd.list_slvls",
                        i.file().getFileName(), i.badEntries(), i.size()), false);
            }
        } catch (Exception e) {
            src.sendFailure(tr("sablefix.cmd.list_fail", e.getMessage()));
        }
        return 0;
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

    private static int dump(CommandSourceStack src) {
        ThreadWatchdog.dump(-1L);
        src.sendSuccess(() -> tr("sablefix.cmd.dump_ok"), false);
        return 0;
    }
}
