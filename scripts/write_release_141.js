// 1.4.1 发布 payload
const fs = require('fs');
const changelog = [
  '## v1.4.1 — 扫描修复补丁 🩹',
  '',
  '- 🔥 **修复"永远扫描中"**：扫描失败后的占位缓存时间戳溢出（Long.MIN_VALUE 相减为负 → 永远判定未过期），导致面板永久卡在"扫描中"不再重试——已修，失败冷却结束后立即重新扫描。',
  '- ⚡ **开服扫描结果入缓存**：启动体检扫完直接进面板缓存——打开 GUI 瞬间就有全量数据，不再"开服扫了一遍、开面板又白扫一遍"。',
  '- 📄 **关于页更新至 1.4.1**：修复套件命令一览、5 分钟补抓 + 50 份上限说明、30 秒自动刷新说明。',
  '',
  '协议与 1.4.0 兼容（协议 v3 未变，无需两端同步升级——但建议一起更新）。',
  '',
  '## English',
  '',
  '- 🔥 **Fixed "scanning forever"**: the failure placeholder cache used Long.MIN_VALUE as its timestamp — subtraction overflowed negative so it never expired, freezing the panel on "scanning" forever. Failed scans now properly retry after the 30 s cooldown.',
  '- ⚡ **Startup scan fills the cache**: boot-time check results now go straight into the panel cache — the GUI shows full data instantly instead of rescanning what was just scanned.',
  '- 📄 **About page updated for 1.4.1**.',
  '',
  'Protocol compatible with 1.4.0 (v3 unchanged).',
].join('\n');
const data = {
  name: 'Sable Fix Core 1.4.1',
  version_number: '1.4.1',
  changelog,
  dependencies: [{ project_id: 'T9PomCSv', dependency_type: 'optional' }],
  game_versions: ['1.21.1'],
  loaders: ['neoforge'],
  featured: true,
  release_channel: 'release',
  project_id: 'rPkYnI7j',
  file_parts: ['sablefix-1.4.1.jar'],
};
fs.writeFileSync('F:/vibe coding工程/sfx/proj/mrdata.json', JSON.stringify(data));
fs.writeFileSync('F:/vibe coding工程/sfx/proj/ghrel.json', JSON.stringify({ tag_name: 'v1.4.1', target_commitish: 'main', name: 'Sable Fix Core 1.4.1', body: changelog }));
console.log('1.4.1 payloads written');
