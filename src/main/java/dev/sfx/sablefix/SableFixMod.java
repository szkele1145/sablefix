package dev.sfx.sablefix;

import dev.sfx.sablefix.command.SfxCommands;
import dev.sfx.sablefix.net.NetHandler;
import dev.sfx.sablefix.scan.DamageRegistry;
import dev.sfx.sablefix.scan.SubLevelScan;
import dev.sfx.sablefix.scan.TicketScan;
import dev.sfx.sablefix.watchdog.SaveObserver;
import dev.sfx.sablefix.watchdog.ThreadWatchdog;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(SableFixMod.MODID)
public class SableFixMod {
    public static final String MODID = "sablefix";
    public static final String VERSION = "1.5.0";
    public static final String TG_URL = "https://t.me/maodie_mods";
    public static final String QQ_GROUP = "1129543042";
    public static final Logger LOGGER = LoggerFactory.getLogger("SableFix");
    /** 进程启动时间（面板显示运行时长用） */
    public static final long START_TIME = System.currentTimeMillis();

    public SableFixMod(IEventBus modBus) {
        // mod bus：网络包注册
        modBus.addListener(NetHandler::register);
        // game bus：服务器生命周期 / tick / 命令 / 玩家进出（损伤提醒）
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(this::onPlayerJoin);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLeave);
        NeoForge.EVENT_BUS.addListener(SfxCommands::onRegisterCommands);
    }

    private void onServerStarted(ServerStartedEvent event) {
        ThreadWatchdog.start();
        SaveObserver.start(event.getServer());
        // 损伤登记簿：锚定/载入持久化清单（重启不丢），体检结果随后登记。
        // init 必须同步先行（登记簿路径锚定+载入），后续 register 才有落点
        DamageRegistry.init(dev.sfx.sablefix.net.ServerHandlers.worldDir(event.getServer()).resolve("data"));
        // 启动体检后台化：600 文件大存档同步扫描阻塞开服 ~3.5s（v2 基准折算）。
        // 与面板扫描共用 SCAN_POOL（天然互斥）；DamageRegistry/ModLog 均 synchronized，线程安全已核实
        dev.sfx.sablefix.net.ServerHandlers.runStartupScanAsync(event.getServer());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        ThreadWatchdog.stop();
        SaveObserver.stop();
    }

    private void onTick(ServerTickEvent.Post event) {
        ThreadWatchdog.onTick();
        DamageRegistry.periodicWarn();
    }

    private void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        // 可用性审计#1+#6：OP 收逐条详细（文件名+数量+修复指引）；
        // 普通玩家只发一条汇总（原实现逐条重发同一句安慰文案=N 条刷屏，且 player_notice
        // 的 lang key 无 %s 占位——传 2 参被静默丢弃，双重问题一并修）
        boolean admin = sp.hasPermissions(2);
        var pending = DamageRegistry.onPlayerJoin(sp.getUUID());
        if (admin) {
            for (var e : pending) {
                sp.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "sablefix.damage.admin_notice", e.file, e.count));
            }
        } else if (!pending.isEmpty()) {
            sp.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "sablefix.damage.player_notice"));
        }
    }

    private void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        DamageRegistry.onPlayerLeave(event.getEntity().getUUID());
    }
}
