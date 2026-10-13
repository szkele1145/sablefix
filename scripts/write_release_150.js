// 1.5.0 发布 payload
const fs = require('fs');
const changelog = [
  '## v1.5.0 — New Features 🎁',
  '',
  'Three user-facing features plus a full regression audit of the new code.',
  '',
  '- 📋 **`/sablefix report` — one-click diagnostic report**: everything needed for "asking for help in a group chat" (damage ledger with readable timestamps, last 200 mod logs, latest thread-stack digest, runtime info) packed into a single txt under `logs/sablefix/`. One file instead of four screenshots.',
  '- ⚠ **Stall banner**: if the server ever froze long enough for the watchdog to capture stacks, the panel overview now shows "Recent stall: 5min ago (2 dumps total)" — you see what happened at first glance, linked to the Watch tab.',
  '- 💾 **Backup management**: the Health page now lists the auto-backups created before every repair/purge (latest 8, name + size + age) — see what you repaired, where the backups are, without touching the file system.',
  '- 🧹 **Modernization**: thread dumps now use `ThreadMXBean` (replaces the deprecated `getAllStackTraces`); clock-rollback guards; report timestamps human-readable.',
  '',
  '🌐 **Protocol v4** — both sides must update to 1.5.0 together (mismatched versions are rejected at handshake with a clear message).',
  '',
  '## 中文',
  '',
  '三个面向用户的新功能 + 新代码全量回归审计。',
  '',
  '- 📋 **`/sablefix report` 一键诊断报告**：求助发群需要的全部信息（可读时间戳的损伤清单、最近 200 条模组日志、最新线程栈摘要、运行信息）打成单个 txt（`logs/sablefix/`）。一个文件顶四张截图。',
  '- ⚠ **卡死提示条**：只要服务器发生过卡死抓栈，面板总览页就显示"最近卡死：5 分钟前（累计抓栈 2 次）"——第一眼看到发生过什么。',
  '- 💾 **备份管理**：体检页展示每次修复/清理前自动创建的备份（最新 8 份：文件名+大小+多久前）——修过什么、备份在哪，不用翻文件系统。',
  '- 🧹 **现代化**：线程栈改用 ThreadMXBean（替代已废弃 API）；时钟回拨防护；报告时间戳可读化。',
  '',
  '🌐 **协议 v4** —— 双端必须同升 1.5.0（握手期明确拒绝不匹配版本）。',
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
  file_parts: ['sablefix-1.5.0.jar'],
};
fs.writeFileSync('F:/vibe coding工程/sfx/proj/mrdata.json', JSON.stringify(data));
fs.writeFileSync('F:/vibe coding工程/sfx/proj/ghrel.json', JSON.stringify({ tag_name: 'v1.5.0', target_commitish: 'main', name: 'Sable Fix Core 1.5.0', body: changelog }));
console.log('1.5.0 payloads written');
