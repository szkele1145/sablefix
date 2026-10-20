package dev.sfx.sablefix.scan;

import dev.sfx.sablefix.SableFixMod;
import dev.sfx.sablefix.log.ModLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 一键诊断报告导出（新功能）：把"求助发群里"需要的全部信息打成单个 txt 文件，
 * 放在 logs/sablefix/ 下并给出路径——不用再让用户翻四个地方截图。
 * 内容：服务器基本信息 + 损伤登记簿 + 最近 200 条模组日志 + 最新一份线程 dump 的摘要。
 * 纯只读聚合（不动存档、不动备份），IO 全量走 SCAN_POOL（调用方保证）。
 */
public final class DiagReport {

    private DiagReport() {}

    /** 生成报告并返回写入路径。异步线程调用（磁盘 IO：读 dump 目录 + 写一个 txt）。 */
    public static Path write() throws IOException {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("=== Sable Fix Core 诊断报告 ===\n");
        sb.append("生成时间: ").append(LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append('\n');
        sb.append("版本: ").append(SableFixMod.VERSION).append("  协议: ")
                .append(dev.sfx.sablefix.net.NetHandler.PROTOCOL_VERSION).append('\n');
        sb.append("运行时长(ms): ").append(System.currentTimeMillis() - SableFixMod.START_TIME).append('\n');
        sb.append("NeoForge: ").append(net.neoforged.fml.loading.FMLLoader.versionInfo().neoForgeVersion()).append('\n');
        sb.append('\n');

        // 损伤登记簿
        List<DamageRegistry.Entry> dmg = DamageRegistry.snapshot();
        sb.append("--- 损伤登记簿 (").append(dmg.size()).append(" 项) ---\n");
        DateTimeFormatter tsFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");
        for (var e : dmg) {
            sb.append("  ").append(e.kind).append(" ×").append(e.count).append("  ")
                    .append(e.file).append("  (首次 ")
                    .append(java.time.Instant.ofEpochMilli(e.firstSeenMs)
                            .atZone(java.time.ZoneId.systemDefault()).format(tsFmt))
                    .append(", 最近 ")
                    .append(java.time.Instant.ofEpochMilli(e.lastSeenMs)
                            .atZone(java.time.ZoneId.systemDefault()).format(tsFmt))
                    .append(")\n");
        }
        sb.append('\n');

        // 模组日志（最近 200 条）。九轮审计#1：可翻译条目在报告里渲染为服务端语言（en_us）——
        // 报告是发给维护者/群里的纯文本文件，与玩家客户端语言无关。
        List<ModLog.Entry> logs = ModLog.snapshot();
        sb.append("--- 模组日志 (最近 ").append(logs.size()).append(" 条) ---\n");
        for (var e : logs) {
            sb.append("  ").append(e.time()).append(" [").append(e.level()).append("] ");
            if (e.translatable()) {
                sb.append(net.minecraft.network.chat.Component.translatable(e.key(),
                        e.args() == null ? new Object[0] : e.args().toArray()).getString());
            } else {
                sb.append(e.msg());
            }
            sb.append('\n');
        }
        sb.append('\n');

        // 最新一份线程 dump 摘要（前 80 行——死锁证据的关键面：线程名+栈顶）
        // 三轮审计#1：dump 正被 watchdog 补抓写入时 readAllLines 可能读到半写内容甚至抛 IO——
        // 单独 try-catch（失败写"读取失败"继续生成报告，不让整个 report 命令失败）
        Path latest = latestDump();
        if (latest != null) {
            sb.append("--- 最新线程栈 (").append(latest.getFileName()).append("，前 80 行) ---\n");
            try {
                List<String> lines = Files.readAllLines(latest, StandardCharsets.UTF_8);
                for (int i = 0; i < Math.min(80, lines.size()); i++) {
                    sb.append("  ").append(lines.get(i)).append('\n');
                }
                if (lines.size() > 80) sb.append("  …（共 ").append(lines.size()).append(" 行，完整文件见同目录）\n");
            } catch (Exception e) {
                sb.append("  [读取失败（文件可能正被写入）: ").append(e.getMessage()).append("]\n");
            }
        } else {
            sb.append("--- 线程栈：无（服务器未发生过卡死抓栈）---\n");
        }

        // 写入 logs/sablefix/（三轮审计#1：user.dir 解析兜底——面板服工作目录可能异常）
        Path dir;
        try {
            dir = Path.of(System.getProperty("user.dir"), "logs", "sablefix");
        } catch (Exception e) {
            dir = Path.of("logs", "sablefix");
        }
        Files.createDirectories(dir);
        Path out = dir.resolve("report-" + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt");
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        ModLog.info("诊断报告已生成：" + out.getFileName());
        return out;
    }

    private static Path latestDump() {
        Path dir = Path.of(System.getProperty("user.dir"), "logs", "sablefix");
        Path best = null;
        long bestTime = Long.MIN_VALUE;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "threaddump-*.txt")) {
            for (Path p : ds) {
                long t = Files.getLastModifiedTime(p).toMillis();
                if (t > bestTime) { bestTime = t; best = p; }
            }
        } catch (IOException ignored) {}
        return best;
    }
}
