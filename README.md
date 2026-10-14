# Sable Fix Core

Sable 存档自愈与诊断核心（Minecraft 1.21.1 / NeoForge 21.1.x，单 jar 双端）。

## 它解决什么

**定位先说清楚：诊断优先，修复为辅。** 死锁的根因通常在别的模组的代码里（本例：两个 mod 同时 patch `ChunkMap#saveAllChunks`），任何模组都无法从外部修好这种冲突——而且没有证据的话，你连「是不是哪两个模组在打架」都无从判断。前 20 秒抓的就是日志（全线程栈写盘），它本身就是破案证据：「该摘哪个 mod」的结论直接从栈里读。摘掉后连跑几天没再卡，靠的正是这份栈。

以「建设世界」服务器 2026-10-10 事故为原型：

- **`save-all` 卡死 60 秒被看门狗杀服** —— 根因是多个模组同时 patch `ChunkMap#saveAllChunks`
  （实测凶手：`chunktreefix 1.1.2` / chunksavefixport，与 c2me 区块系统重写撞车）。
  本 mod 的看门狗在卡死 **20 秒**时就把全线程栈写盘，抢在原版 60 秒杀服前保住现场。
- **子层级（.slvlr / .slvls）指针损坏** —— 启动时自动全量体检，死指针逐文件报告。
- **force-load 票据异常** —— 读得出条数和每个 uuid，票据文件损坏立刻发现。

## 功能

| 页签 | 内容 |
|---|---|
| 总览 | 运行时间 / tick 间隔 / 抓栈次数 / 票据数 / 各维度实体与加载区块数（30 秒自动刷新） |
| 存档体检 | 问题文件清单（死指针/坏条目）+ 每行一键「修复」（dry 预览 → 确认清零，自动备份） |
| 子层级浏览器 | 全部 .slvlr/.slvls 文件的大小、有效记录数；过滤（全部/异常/健康）+ 排序 + 滚轮翻页 |
| 区块诊断 | 输入世界坐标，查询区块加载状态 / 实体数 / 方块实体数 |
| 死锁监视 | tick 间隔、抓栈阈值、票据 UUID 聚合统计（重复持有高亮）+ 一键「清理票据」 |
| 运行日志 | 被动巡检全过程（启动体检/修复/清理的结果），可回溯 |
| 关于 | 版本、定位说明、命令速查、反馈渠道（QQ/TG 点击复制） |

## 使用

服务端与客户端都装同一个 jar：

- **服务端**：启动时自动体检（日志里看 `[SableFix]`），`/sablefix` 命令族（OP 2 级）：
  - `/sablefix check` 全量体检 · `/sablefix list` 问题文件 · `/sablefix tickets` 票据明细 · `/sablefix dump` 立刻抓全线程栈
  - `/sablefix repair <file> dry|apply` 修复单个子层级文件 · `repair-all dry|apply` 批量修复
  - `/sablefix tickets-purge dry|apply` 清空 force-load 票据
  - `/sablefix diag <x y z>` 区块诊断（加载状态/实体数/方块实体数）
  - `/sablefix fix` **一键修复**：屏蔽 Sable 写回 → 修复全部 → 踢全体玩家 → 停服（⚠ 会踢人并停止服务器，需面板自动重启）
  - `/sablefix unmask` 兜底解锁：fix 被中断后清除残留只读标记
  - `/sablefix report` 一键诊断报告（损伤+日志+线程栈打包成单文件，发群求助用）
- **客户端**：游戏内输入 `/sfxpanel` 打开图形面板（需服务端也装本 mod）
  - 存档体检页每个问题文件旁有「修复」按钮（dry 预览 → 确认清零）
  - 死锁监视页有「清理票据」按钮

所有修复操作**先自动备份**到 `<世界>/backups/sablefix/`，只清指针表/票据列表，绝不碰数据区。

抓栈文件：`logs/sablefix/threaddump-*.txt`

## 排障教训（写给未来的自己）

1. `save-all` 卡死时线程栈全是 park/wait、没有线程在干活 = **任务提交了但 future 永不完成**
   —— 往「谁 patch 了 saveAllChunks」方向查，不是往存档数据损坏方向查。
2. 数据手术（票据增删、指针修复）对这类死锁**完全无效**，别浪费时间。
3. 启动日志里的 mixin 冲突警告（`@Redirect conflict. Skipping ...`）是重要线索。
4. **运行时修复会被 Sable 周期写回覆盖**（内存索引定期落盘）——修了重启又出现的死循环，
   根治方法是 `/sablefix fix`（屏蔽写回后修复再停服）。

## 构建

```bash
gradle build   # 产物在 build/libs/，需 JDK 21
```

## License

MIT

## 反馈

- QQ 群：1129543042（中文）
- Telegram：@maodie_mods（international；链接也可在模组内「关于」页点击复制）
