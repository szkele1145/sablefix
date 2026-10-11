package dev.sfx.sablefix.client;

import com.mojang.brigadier.CommandDispatcher;
import dev.sfx.sablefix.net.ClientBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * 客户端侧入口：只在客户端加载。
 * /sfxpanel 打开面板；网络回包桥接到当前打开的界面。
 */
@EventBusSubscriber(modid = "sablefix", value = net.neoforged.api.distmarker.Dist.CLIENT)
public final class ClientSetup {

    private static SfxPanelScreen openScreen;

    private ClientSetup() {}

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        // 演示模式（截图/录屏）：等标题界面就绪 → 打开面板 → 面板自轮播 7 页截图 → 自动退出。
        // 开关二选一：JVM 系统属性 -Dsfx.demo=true 或环境变量 SFX_DEMO=1
        if (!isDemo()) return;
        Minecraft mc = Minecraft.getInstance();
        if (demoPhase == 0) {
            // 阶段0：等标题界面出现（更早 setScreen 会被资源加载界面覆盖）
            if (mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen) demoPhase = 1;
            return;
        }
        if (demoPhase == 1) {
            demoTicks++;
            if (demoTicks == 40) {
                // demo 下强制 1x：auto scale(3x) 会让面板逻辑宽只剩 291，按钮/卡片全被挤没
                mc.options.guiScale().set(1);
                mc.setScreen(new SfxPanelScreen());
                demoPhase = 2;
            }
        }
        // 阶段2：SfxPanelScreen.demoAutoCycle 负责轮播与 stop()
    }

    private static int demoPhase;
    private static int demoTicks;

    /** 演示模式开关：系统属性 sfx.demo 或环境变量 SFX_DEMO。 */
    static boolean isDemo() {
        return Boolean.getBoolean("sfx.demo") || "1".equals(System.getenv("SFX_DEMO"));
    }

    @SubscribeEvent
    public static void onClientCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("sfxpanel").executes(c -> {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                openScreen = new SfxPanelScreen();
                mc.setScreen(openScreen);
            });
            return 1;
        }));
        // 把回包桥接进界面
        ClientBridge.setPanelSink(data -> {
            SfxPanelScreen s = openScreen;
            if (s != null) s.onData(data);
        });
        ClientBridge.setDiagSink(res -> {
            SfxPanelScreen s = openScreen;
            if (s != null) s.onDiag(res);
        });
        ClientBridge.setRepairSink(res -> {
            SfxPanelScreen s = openScreen;
            if (s != null) s.onRepairRes(res);
        });
        ClientBridge.setPurgeSink(res -> {
            SfxPanelScreen s = openScreen;
            if (s != null) s.onPurgeRes(res);
        });
    }

    /** 面板关闭时由 SfxPanelScreen 调用：释放静态引用，避免持有最后一帧数据无法回收。 */
    static void clearOpenScreen(SfxPanelScreen s) {
        if (openScreen == s) openScreen = null;
    }
}
