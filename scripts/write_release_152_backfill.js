// 1.5.2 补传 payload（与 GitHub 发布内容一致）
const fs = require('fs');
const changelog = [
  '## v1.5.2 — Audit Round 3 🧹',
  '',
  'Five fixes from the third adversarial audit round (covering previously untouched corners).',
  '',
  '- 🔕 **SAVE_STALLED false-warning eliminated**: a recovered save-stall now clears its damage-ledger entry on recovery — previously it warned every 5 minutes forever, persisted across restarts.',
  '- 🔌 **Repair preview overflow fixed**: previews longer than 256 chars would *disconnect* the client (writeUtf throws, not truncates); now hard-capped at 240. The runtime-repair warning is now client-side localized (no more server-locale leakage).',
  '- 📄 **/sablefix report hardened**: reading a dump mid-write (watchdog re-capture moment) no longer fails the whole report; odd working directories fall back gracefully.',
  '- 🕐 **Clock-drift proofing (protocol v5)**: stall banner and backup ages are now computed on the *server* and sent as relative durations — panels with unsynced NTP no longer show "3h ago" for a 5-minute-old backup.',
  '',
  '🌐 **Protocol v5** — both sides must update to 1.5.2+ together.',
  '',
  '## 中文',
  '',
  '第三轮对抗审计（扫此前未覆盖的角落）5 项修复：',
  '',
  '- 🔕 **SAVE_STALLED 误报根治**：卡顿恢复后自动清登记——原来每 5 分钟永久误报且跨重启。',
  '- 🔌 **修复预览溢出修复**：超 256 字符会直接断连（writeUtf 是抛异常不是截断）；现 240 硬截。运行时修复警示改客户端本地化（不再服务器语言串台）。',
  '- 📄 **report 加固**：dump 正被补抓写入时不再让整个报告失败；异常工作目录优雅回退。',
  '- 🕐 **时钟偏差免疫（协议 v5）**：卡死横幅与备份年龄改由服务端算好相对时长下发——NTP 失步的面板服不再显示错乱。',
  '',
  '🌐 **协议 v5** —— 双端必须同升 1.5.2+。',
].join('\n');
const data = {
  name: 'Sable Fix Core 1.5.2', version_number: '1.5.2', changelog,
  dependencies: [{ project_id: 'T9PomCSv', dependency_type: 'optional' }],
  game_versions: ['1.21.1'], loaders: ['neoforge'], featured: false, release_channel: 'release',
  project_id: 'rPkYnI7j', file_parts: ['sablefix-1.5.2-1.21.1-neoforge.jar'],
};
fs.writeFileSync('F:/vibe coding工程/sfx/proj/mrdata_152.json', JSON.stringify(data));
console.log('1.5.2 payload written (mrdata_152)');
