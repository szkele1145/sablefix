package dev.sfx.sablefix.scan;

import dev.sfx.sablefix.SableFixMod;
import dev.sfx.sablefix.log.ModLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
        Path dir = reportDir();
        Files.createDirectories(dir);
        // 十轮审计#27：文件名原本是秒级精度 → 同一秒内生成两份报告会**互相覆盖**
        // （与备份覆盖同族）。改用毫秒 + 碰撞后缀。
        String base = "report-" + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        Path out = dir.resolve(base + ".txt");
        for (int i = 1; Files.exists(out) && i < 100; i++) {
            out = dir.resolve(base + "-" + i + ".txt");
        }
        // 十轮审计#40：报告是**唯一一份**「发给维护者的求助材料」——原来直接写最终文件，
        //  写到一半被杀/断电会留下**残缺的报告**（日志段只写了一半，看不出关键日志在不在）
        //  → 拿残缺报告排查会被**误导**。且 Files.writeString 是 CREATE+TRUNCATE_EXISTING
        //  → 目标已存在会截断覆盖（碰撞后缀循环用尽 100 次上限后就会撞上）。
        //
        //  写 tmp → 原子改名。为什么不是「move(tmp, out, ATOMIC_MOVE)」就完事：
        //  探针实测 ATOMIC_MOVE 会**忽略其它选项**，目标已存在时**静默替换**（Windows 上是
        //  MoveFileEx 的替换语义）→ 仍然会覆盖旧报告。故先用 CREATE_NEW **独占占位**目标名
        //  （已存在即抛 FileAlreadyExistsException → 绝不覆盖别人的报告），占位成功后才
        //  用 REPLACE_EXISTING+ATOMIC_MOVE 把 tmp 原子换上去（此时替换的是自己的空占位）。
        Path tmp = dir.resolve(out.getFileName() + ".tmp");
        boolean placed = false;     // 是否**我们**占位成功（只有占位成功的才允许删 out）
        try {
            Files.createFile(out);                       // CREATE_NEW：独占占位，已存在即抛
            placed = true;
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // 失败兜底：删掉半截 tmp，不让残缺内容占位（用户会误当成一份报告）
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            // 只删**我们自己**建的空占位。若 createFile 就失败（同名文件是别人的），
            // 绝不能删它 —— 否则会毁掉一份已有报告。
            if (placed) {
                try { Files.deleteIfExists(out); } catch (IOException ignored) {}
            }
            throw e;
        }
        ModLog.info("诊断报告已生成：" + out.getFileName());
        return out;
    }

    /** 报告目录：<user.dir>/logs/sablefix。user.dir 不可用时降级为相对路径。
     *  十轮审计#27：原先 write() 有 try/catch 兜底、latestDump() 却没有 →
     *  user.dir 异常时 latestDump() 抛 InvalidPathException（RuntimeException）→
     *  整个 report 命令失败 → 兜底形同虚设。现在两处共用同一解析。 */
    private static Path reportDir() {
        try {
            return Path.of(System.getProperty("user.dir"), "logs", "sablefix");
        } catch (Exception e) {
            try {
                return Path.of("logs", "sablefix");
            } catch (Exception e2) {
                return Path.of(".").resolve("logs").resolve("sablefix");
            }
        }
    }

    private static Path latestDump() {
        Path dir = reportDir();
        Path best = null;
        long bestTime = Long.MIN_VALUE;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "threaddump-*.txt")) {
            for (Path p : ds) {
                // 十轮审计#27：取修改时间可能因「文件在遍历途中被删/改名」抛 IOException
                //  （watchdog 补抓会写新 dump）→ 原实现会让整个 report 命令失败。
                // 单个文件取不到时间就跳过它，不影响其它候选。
                long t;
                try {
                    t = Files.getLastModifiedTime(p).toMillis();
                } catch (IOException ignored) {
                    continue;
                }
                if (t > bestTime) { bestTime = t; best = p; }
            }
        } catch (IOException ignored) {}
        return best;
    }
}
