# Sable Fix Core · 开发状态

## 当前版本线
- **Modrinth/GitHub 最新已发布**：1.2.0（Repair Suite）
- **main 分支攒批中（1.3.0 候选）**：

| # | 内容 | 状态 |
|---|---|---|
| 1 | SaveObserver：保存 future 纯观察 watchdog（30s 轮询、玩家 chunk 必查、卡 20s 登记、零 mixin 注入） | ✅ 已实现+基线验证 |
| 2 | DamageRegistry：持久损伤台账（JSON 落盘/重启载入/周期 WARN 5min/玩家进服差异化提示/repair-purge 闭环清除/去噪写盘） | ✅ 已实现+端到端验证 |
| 3 | repair apply 并发写防护（写回前重扫校验，槽位漂移即中止） | ✅ |
| 4 | 诊断失败回包（GUI 不再永久"等待查询"） | ✅ |
| 5 | `/sablefix diag <pos>` 命令（文档承诺过但缺失） | ✅ |
| 6 | 面板损伤摘要前置到运行日志页（零协议变更） | ✅ |
| 7 | 双语硬编码清零（诊断状态/面板失败/健康汇总行） | ✅ |
| 8 | DiagRes 客户端本地化（errorKey 透传，服务器 locale 不串台） | ✅ |
| 9 | 兼容性地图 COMPATIBILITY.md（零 mixin 审计 + 1.3.0 风险预案 + 测试清单） | ✅ |
| 10 | 发布工具链（version_upload.js multipart、write_release_120 双语 payload） | ✅ |

## 版本纪律（持续有效）
- 攒批发版；单个小修不发版
- **1.3.0 发布条件**：SaveObserver 在真实 Sable+c2me 环境观察一周无异常 + 跳过机制设计评审通过
- 发布动作：bump_version → build → Modrinth POST version（version_upload.js）→ GitHub release（ghrel.json + jar）

## 1.3.0 发布前必做
- [ ] SaveObserver 观察期数据（真实 Sable+c2me 服务器 ≥1 周）
- [ ] 跳过机制设计评审（观察→跳过的动作边界）
- [ ] COMPATIBILITY.md 测试清单跑完（4 项组合测试）

## 攒批池（低优先）
- [ ] 面板数据压缩（触发条件：真实用户触达 418KB 包体）
- [ ] repair 进度持久化（重启后继续提示未修复项——DamageRegistry 已覆盖大半）