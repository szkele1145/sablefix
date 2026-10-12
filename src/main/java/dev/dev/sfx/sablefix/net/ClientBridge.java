package dev.sfx.sablefix.net;

import java.util.function.Consumer;

/**
 * 客户端桥接：服务端网络应答先进这里，再转发给已注册的界面消费者。
 * 本类不含任何客户端类型，保证在服务端加载也不会炸。
 */
public final class ClientBridge {

    private static volatile Consumer<NetHandler.PanelData> panelSink;
    private static volatile Consumer<NetHandler.DiagRes> diagSink;
    private static volatile Consumer<NetHandler.RepairRes> repairSink;
    private static volatile Consumer<NetHandler.PurgeRes> purgeSink;

    private ClientBridge() {}

    public static void setPanelSink(Consumer<NetHandler.PanelData> sink) { panelSink = sink; }
    public static void setDiagSink(Consumer<NetHandler.DiagRes> sink) { diagSink = sink; }
    public static void setRepairSink(Consumer<NetHandler.RepairRes> sink) { repairSink = sink; }
    public static void setPurgeSink(Consumer<NetHandler.PurgeRes> sink) { purgeSink = sink; }

    public static void onPanelData(NetHandler.PanelData data) {
        Consumer<NetHandler.PanelData> s = panelSink;
        if (s != null) s.accept(data);
    }

    public static void onDiagRes(NetHandler.DiagRes res) {
        Consumer<NetHandler.DiagRes> s = diagSink;
        if (s != null) s.accept(res);
    }

    public static void onRepairRes(NetHandler.RepairRes res) {
        Consumer<NetHandler.RepairRes> s = repairSink;
        if (s != null) s.accept(res);
    }

    public static void onPurgeRes(NetHandler.PurgeRes res) {
        Consumer<NetHandler.PurgeRes> s = purgeSink;
        if (s != null) s.accept(res);
    }
}
