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
- [ ] **跳过机制架构决策（阻塞项，字节码审计结论）**：ChunkHolder 只暴露 saveSync future 的**只读**访问，无公开的"发起/取消保存"入口——从外部 cancel 会把异常注入完成链，可能污染 MC 保存状态机。安全跳过**必须 mixin 注入 ChunkMap#saveAllChunks 循环**，与"零注入"兼容性原则正面冲突。**候选方案**：a) 注入但限定在 Sable 存档路径（sublevels 文件写入点，绕开 ChunkMap 主循环）b) 观察者检测到卡住后**通知管理员手动处理**（现状已是）并持续登记 c) 深入 c2me 源码找它暴露的调度钩子。评审后定案，宁缺毋滥
- [ ] COMPATIBILITY.md 测试清单跑完（4 项组合测试）

## 攒批池（低优先）
- [ ] 面板数据压缩（触发条件：真实用户触达 418KB 包体）
- [ ] repair 进度持久化（重启后继续提示未修复项——DamageRegistry 已覆盖大半）

## 工程迁移（2026-10-12）：D:\sfx → F:\vibe coding工程\sfx

- **构建环境三件套已就位**：完整 JDK = `F:\gradleuser\jdks\eclipse_adoptium-21-amd64-windows.2`（foojay 下载，含 javac）；
  gradle = `F:\gradlehome`（junction→gradle-8.10.2）；项目访问 = `F:\sfxlink`（junction→proj，规避 worker classpath 中文/空格路径 bug）
- **构建命令**：`$env:JAVA_HOME='F:\sfx_jdk'（JRE，仅 launcher 用也 OK，因 NFRT 现走 ECJ）；cd F:\sfxlink; F:\gradlehome\bin\gradle.bat --no-daemon build -g F:\gradleuser`
  —— 或直接用 foojay JDK 当 JAVA_HOME（推荐）：`$env:JAVA_HOME=(Get-ChildItem F:\gradleuser\jdks -Directory | ? Name -match eclipse).FullName`
- **脚本路径**：scripts/*.js 已批量改指 F:/vibe coding工程/sfx/proj
- **凭据**：.ghtoken/.mrtoken 已在新位置 F:\vibe coding工程\sfx\
- 被占用的 java.exe 已补拷（占用源=旧路径孤儿 gradle 进程，已清理）
> ⚠ **历史文档**：本文档停更于 v1.2.0 时期。当前版本 1.5.x 的新命令（fix/unmask/report）、
> 协议演进（v4/v5）、DamageRegistry 新 kind（STALE_READONLY）以 README.md 与发布页 changelog 为准。