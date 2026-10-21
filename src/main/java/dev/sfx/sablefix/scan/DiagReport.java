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

    /**
     * 生成报告并返回写入路径。异步线程调用（磁盘 IO：读 dump 目录 + 写一个 txt）。
     *
     * @param server 当前服务器实例；用于在报告里标明**这是哪个世界**（十轮审计#62）。
     *               传 null 时报告不含世界信息（不崩，但维护者无法定位是哪个存档）。
     */
    public static Path write(net.minecraft.server.MinecraftServer server) throws IOException {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("=== Sable Fix Core 诊断报告 ===\n");
        sb.append("生成时间: ").append(LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append('\n');
        sb.append("版本: ").append(SableFixMod.VERSION).append("  协议: ")
                .append(dev.sfx.sablefix.net.NetHandler.PROTOCOL_VERSION).append('\n');
        // 十轮审计#62：报告必须能回答「这是**哪个世界**的报告」——
        // 面板服多存档时，所有存档的报告都落在同一个 <运行目录>/logs/sablefix/ 里，
        // 文件名只带时间戳 → 维护者拿到一份报告**不知道是哪个存档** → 无法定位问题，
        // 而报告的唯一用途就是求助。头部标明世界名与存档目录。
        String worldName = null;
        String worldDir = null;
        if (server != null) {
            try {
                worldName = server.getWorldData().getLevelName();
            } catch (Throwable ignored) {}
            try {
                worldDir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                        .toAbsolutePath().toString();
            } catch (Throwable ignored) {}
        }
        sb.append("世界名称: ").append(worldName == null ? "(未知)" : worldName).append('\n');
        sb.append("存档目录: ").append(worldDir == null ? "(未知)" : worldDir).append('\n');
        // 十轮审计#60：用**服务器实例**运行时长。原先用 START_TIME（JVM 启动时刻）→
        //  面板服换存档时 JVM 常驻 → 报告写「运行时长 86400000ms」而服务器只开了 5 分钟
        //  → 维护者据此判断「跑了一天都没卡死」→ **误判**。
        sb.append("运行时长(ms): ").append(dev.sfx.sablefix.SableFixMod.serverUptimeMs()).append('\n');
        sb.append("进程启动时间: ").append(new java.util.Date(dev.sfx.sablefix.SableFixMod.START_TIME))
                .append("  (面板服换存档时 JVM 常驻，与上面的运行时长不同)\n");
        sb.append("NeoForge: ").append(net.neoforged.fml.loading.FMLLoader.versionInfo().neoForgeVersion()).append('\n');
        if (server != null) {
            try {
                sb.append("在线玩家数: ").append(server.getPlayerCount()).append("  维度数: ");
                // MC 1.21.1：MinecraftServer#getAllLevels() 返回 Iterable<ServerLevel>（不是 Collection），
                //  没有 size() → 用流式计数（源码 MinecraftServer.java 已核对）。
                int dims = 0;
                for (net.minecraft.server.level.ServerLevel ignored2 : server.getAllLevels()) dims++;
                sb.append(dims).append('\n');
            } catch (Throwable ignored) {}
        }
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
        //
        // 十轮审计#61：栈文件写在 <运行目录>/logs/sablefix/，**不按存档分目录** →
        // 面板服换存档时**上一个存档的卡死现场仍在同目录**。原实现按 mtime 挑「最新」→
        //  报告会把**别的存档的现场**当成本次的贴出来 → 维护者据此排查**方向全错**
        //  （本次进程的状态与上一个存档毫不相干）。
        //  现在：优先只认**本次服务器启动之后**写的栈；本次没有卡死就**明说没有**，
        //  并把旧栈单独标注为「上次运行的」，不与本次混为一谈。
        long serverStart = dev.sfx.sablefix.SableFixMod.serverStartTimeMs();
        Path latest = latestDump(serverStart);
        if (latest != null) {
            sb.append("--- 最新线程栈 (").append(latest.getFileName()).append("，前 80 行) ---\n");
            sb.append("  [本次运行内抓取的现场]\n");
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
            Path stale = latestDump(0L);   // 0 = 不设下界，取目录里最新的一份（旧的）
            sb.append("--- 线程栈：本次运行没有卡死抓栈 ---\n");
            if (stale != null) {
                sb.append("  [注意] 以下是**之前某次运行**留下的栈（抓取时间早于本次服务器启动 ")
                        .append(serverStart).append("），")
                        .append("与本次进程状态无关，仅供参考，不要据此判断本次问题：\n");
                try {
                    long t = Files.getLastModifiedTime(stale).toMillis();
                    sb.append("  文件: ").append(stale.getFileName())
                            .append("  修改时间: ").append(new java.util.Date(t)).append('\n');
                    List<String> lines = Files.readAllLines(stale, StandardCharsets.UTF_8);
                    for (int i = 0; i < Math.min(80, lines.size()); i++) {
                        sb.append("  ").append(lines.get(i)).append('\n');
                    }
                    if (lines.size() > 80) sb.append("  …（共 ").append(lines.size()).append(" 行，完整文件见同目录）\n");
                } catch (Exception e) {
                    sb.append("  [读取失败: ").append(e.getMessage()).append("]\n");
                }
            } else {
                sb.append("  （服务器未发生过卡死抓栈）\n");
            }
        }

        // 写入 logs/sablefix/（三轮审计#1：user.dir 解析兜底——面板服工作目录可能异常）
        Path dir = reportDir();
        Files.createDirectories(dir);
        // 十轮审计#27：文件名原本是秒级精度 → 同一秒内生成两份报告会**互相覆盖**
        // （与备份覆盖同族）。改用毫秒 + 碰撞后缀。
        // 十轮审计#62：文件名带上**世界名**——面板服多存档时所有报告落在同一目录，
        // 只有时间戳 → 维护者分不清哪份是哪个世界的（报告头已标世界名，文件名同步更好找）。
        String base = "report-" + safeFilePart(worldName) + "-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
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

    /**
     * 把世界名净化成可安全用作文件名的一段。
     *
     * <p>只剔除 Windows 文件名里**真正非法**的字符（{@code < > : " / \ | ? *} 与控制字符），
     * **保留非 ASCII 字符**（中文等）——世界名用中文是常态，若一律替换成下划线，
     * 所有中文世界名都会退化成同一个兜底名，多存档时**依然分不清**（#62 的目的落空）。
     * 另：Windows 不允许文件名以点或空格结尾，一并处理。
     */
    private static String safeFilePart(String name) {
        if (name == null || name.isEmpty()) return "world";
        StringBuilder sb = new StringBuilder(Math.min(name.length(), 24));
        for (int i = 0; i < name.length() && sb.length() < 24; i++) {
            char c = name.charAt(i);
            boolean illegal = c < 0x20
                    || c == '<' || c == '>' || c == ':' || c == '"'
                    || c == '/' || c == '\\' || c == '|' || c == '?' || c == '*';
            sb.append(illegal ? '_' : c);
        }
        // 去掉尾部的点与空格（Windows 不允许）
        while (sb.length() > 0) {
            char last = sb.charAt(sb.length() - 1);
            if (last == '.' || last == ' ') sb.setLength(sb.length() - 1);
            else break;
        }
        String s = sb.toString();
        // 全被替换成下划线（名字全是非法字符）→ 用兜底名
        return s.replace("_", "").isEmpty() ? "world" : s;
    }

    /**
     * 取目录里修改时间最新的一份线程栈。
     *
     * @param notBeforeMs 只接受修改时间 ≥ 该值的文件；传 0 表示不设下界。
     *                     十轮审计#61：用于把「本次服务器运行内写的栈」与「上次运行留下的栈」分开。
     */
    private static Path latestDump(long notBeforeMs) {
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
                // 十轮审计#61：下界过滤——早于本次服务器启动的栈不属于本次运行
                if (t < notBeforeMs) continue;
                if (t > bestTime) { bestTime = t; best = p; }
            }
        } catch (IOException ignored) {}
        return best;
    }
}
