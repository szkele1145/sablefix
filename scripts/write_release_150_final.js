// 1.5.0 最终 changelog（含 fix 命令 + 命名规范）
const fs = require('fs');
const changelog = [
  '## v1.5.0 — One-Click Fix & New Features 🎁',
  '',
  '- 🔧 **`/sablefix fix` — one-click repair**: masks Sable\'s write-back (read-only) → repairs everything → kicks all players → stops the server for restart. **Root-cures the loop where runtime repairs got overwritten by Sable\'s periodic index write-back** (the "repaired but it came back after every restart" experience). Run it, wait for the server to come back, done.',
  '  - ⚠ Note: `fix` **stops** the server — your panel must auto-restart it (or you start it manually). Most panels (Pterodactyl etc.) do this by default.',
  '- 📋 **`/sablefix report`**: everything needed for group-chat help (damage ledger, 200 mod-log lines, latest thread-stack digest, runtime info) in one txt under `logs/sablefix/`.',
  '- ⚠ **Stall banner**: panel overview shows "Recent stall: 5min ago (2 dumps)" when the watchdog has captured stacks.',
  '- 💾 **Backup management**: the Health page lists the auto-backups made before each repair/purge (latest 8, name+size+age).',
  '- 📦 **New file naming**: `sablefix-<version>-<mc-version>-<loader>.jar` (e.g. `sablefix-1.5.0-1.21.1-neoforge.jar`) — consistent with community conventions.',
  '- 🧹 Thread dumps via ThreadMXBean (deprecated API replaced); clock-rollback guards; readable timestamps in reports.',
  '- 🔁 All repair guidance now states clearly: **repairs during runtime may be overwritten by Sable** — use `/sablefix fix` instead.',
  '',
  '🌐 **Protocol v4** — both sides must update to 1.5.0 together (mismatched versions rejected at handshake with a clear message).',
  '',
  '## 中文',
  '',
  '- 🔧 **`/sablefix fix` 一键修复**：屏蔽 Sable 写回 → 修复全部 → 踢全体玩家 → 停服等待重启。**根治"运行时修复被 Sable 周期写回覆盖"**（即"修了重启也还有"的死循环）。执行后等服务器拉起即可。',
  '  - ⚠ 注意：fix 会**停止服务器**——需要面板自动重启（或手动拉起）。大多数面板（Pterodactyl 等）默认行为即是如此。',
  '- 📋 **`/sablefix report` 一键诊断报告**：求助发群全部信息打包单个 txt。',
  '- ⚠ **卡死提示条**：发生过卡死时总览页显示"最近卡死：5 分钟前（累计 2 次）"。',
  '- 💾 **备份管理**：体检页展示修复/清理前的自动备份（最新 8 份）。',
  '- 📦 **文件命名规范**：`sablefix-1.5.0-1.21.1-neoforge.jar`（模组名-版本-游戏版本-加载器）。',
  '- 🧹 ThreadMXBean 现代化、时钟回拨防护、报告时间戳可读化。',
  '- 🔁 所有修复提示明确：**运行时修复会被 Sable 覆盖——请用 `/sablefix fix`**。',
  '',
  '🌐 **协议 v4** —— 双端必须同升 1.5.0。',
].join('\n');
const data = {
  name: 'Sable Fix Core 1.5.0',
  version_number: '1.5.0',
  changelog,
  dependencies: [{ project_id: 'T9PomCSv', dependency_type: 'optional' }],
  game_versions: ['1.21.1'],
  loaders: ['neoforge'],
  featured: true,
  release_channel: 'release',
  project_id: 'rPkYnI7j',
  file_parts: ['sablefix-1.5.0-1.21.1-neoforge.jar'],
};
fs.writeFileSync('F:/vibe coding工程/sfx/proj/mrdata.json', JSON.stringify(data));
fs.writeFileSync('F:/vibe coding工程/sfx/proj/ghrel.json', JSON.stringify({ tag_name: 'v1.5.0', target_commitish: 'main', name: 'Sable Fix Core 1.5.0', body: changelog }));
console.log('1.5.0 final payloads written');
