# 兼容性地图（v1.2.0 · 1.3.0 保存期跳过攻关前置）

## 我们自己的兼容性现状（v1.2.0 审计结论）

| 风险面 | 现状 | 结论 |
|---|---|---|
| Mixin | **零声明、零注入**（无 mixins.json、无 spongepowered 依赖） | 与 c2me / chunktreefix / Sable / 任何 mixin 模组**结构上不可能冲突** |
| MC 内部 API | 全部为稳定官方面：`MinecraftServer` `ServerLevel` `LevelChunk#getBlockEntities` `NbtIo` `AABB` `CommandSourceStack` `Screen/EditBox`（客户端） | 无 obfuscated 内部调用，MC 小版本升级低风险 |
| `ChunkMap#saveAllChunks` | **代码零接触**（仅 README 叙述中提及事故根因） | 1.3.0 ①层攻关将首次触碰——见下文风险预案 |
| 网络通道 | 全部 `sablefix:` 命名空间（panel/diag/repair/purge 共 8 个） | 冲突概率≈0（命名空间隔离） |
| 命令 | `/sablefix` `/sfxpanel` 自有命名 | 与常见管理模组（如 LuckPerms/Essentials）无碰撞 |
| 文件 | 只读 `sublevels/` 与 `data/sable_*.dat`；写 `data/sablefix_damage.json`、`logs/sablefix/`、`backups/sablefix/` | 与 vanilla/Sable 文件零交集（备份/登记簿均为自有文件） |
| 事件 | ServerStarted/Stopping、ServerTick.Post、PlayerLoggedIn/Out（低频、无优先级抢占） | 与 tick 密集型模组无竞争 |

## 1.3.0 ①层（保存期跳过死块）的接触点与风险预案

计划接触点：`ChunkMap#saveAllChunks` 的提交-完成循环（观察 future 完成状态，跳过 pending 超 20s 的区块写入）。

| 风险 | 概率 | 预案 |
|---|---|---|
| 与 c2me 的异步保存链冲突 | 高 | c2me 重写了整个保存调度——**不做 mixin 注入**，改为独立观察线程轮询 future 状态（与 20s 抓栈同一模式），超时标记 + 跳过由我们的线程完成 |
| 与 chunktreefix 的 @Redirect 冲突 | 高（若它 @Redirect 同方法） | 同上，观察者模式零注入；冲突自检：启动时若检测到它的 mixin 已 apply（日志特征 `@Redirect conflict`），面板「总览」页亮黄条提示 |
| 跳过导致区块永久不保存 | 中 | 跳过的区块写入损伤登记簿（DamageRegistry），下次保存重试；连续 3 次失败才建议人工修复 |
| 跳过引发的数据丢失 | 低 | 与现状对比：现状=60s 后整服被杀，全部未保存数据丢失；跳过=只丢那 1 个区块 |

## 兼容性测试清单（1.3.0 开发期必跑）

- [ ] Sable 1.x + c2me 0.2 + chunktreefix 1.1.2 组合：启动无 mixin 冲突警告
- [ ] 同组合 + 人工构造死锁：观察线程正常标记、正常跳过、正常 WARN
- [ ] 摘掉 chunktreefix：跳过逻辑不再触发（损伤登记簿不新增）
- [ ] 仅 vanilla（无 Sable）：模组静默（sublevels 目录不存在时不报错不注册）

> ⚠ **历史文档**：本文档停更于 v1.2.0 时期。当前版本 1.5.x 的新命令（fix/unmask/report）、
> 协议演进（v4/v5）、DamageRegistry 新 kind（STALE_READONLY）以 README.md 与发布页 changelog 为准。