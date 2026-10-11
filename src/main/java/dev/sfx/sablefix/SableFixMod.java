package dev.sfx.sablefix;

import dev.sfx.sablefix.command.SfxCommands;
import dev.sfx.sablefix.net.NetHandler;
import dev.sfx.sablefix.scan.SubLevelScan;
import dev.sfx.sablefix.scan.TicketScan;
import dev.sfx.sablefix.watchdog.ThreadWatchdog;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(SableFixMod.MODID)
public class SableFixMod {
    public static final String MODID = "sablefix";
    public static final String VERSION = "1.6.0";
    public static final String TG_URL = "https://t.me/maodie_mods";
    public static final String QQ_GROUP = "1129543042";
    public static final Logger LOGGER = LoggerFactory.getLogger("SableFix");
    /** 进程启动时间（面板显示运行时长用） */
    public static final long START_TIME = System.currentTimeMillis();

    public SableFixMod(IEventBus modBus) {
        // mod bus：网络包注册
        modBus.addListener(NetHandler::register);
        // game bus：服务器生命周期 / tick / 命令
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(SfxCommands::onRegisterCommands);
    }

    private void onServerStarted(ServerStartedEvent event) {
        ThreadWatchdog.start();
        // 启动体检只读不改，问题文件写日志；修复要玩家显式执行
        SubLevelScan.startupScan(event.getServer());
        TicketScan.startupScan(event.getServer());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        ThreadWatchdog.stop();
    }

    private void onTick(ServerTickEvent.Post event) {
        ThreadWatchdog.onTick();
    }
}
