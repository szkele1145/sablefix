// 生成 1.2.0 发布 payload（Modrinth version + GitHub release body）
// 用法：node write_release_120.js  → 产出 mrdata.json + ghrel.json
const fs = require('fs');

const changelog = [
  '## v1.2.0 — Repair Suite 🛠️',
  '',
  'The "repair" in "Sable Fix Core" is now real: sublevel repair, ticket purge, all read-only→explicit-confirm.',
  '',
  '### 🛠️ Sublevel repair',
  '- `/sablefix repair <file> dry|apply` — preview dead slots, then clear them (pointer table only, data area never touched; auto-backup to `<world>/backups/sablefix/`)',
  '- `/sablefix repair-all dry|apply` — batch fix every problem file; one locked file does not abort the rest',
  '- 🖥️ GUI: per-file **Fix** button in Save Check tab (dry preview → confirm), ticket **Purge** button in Deadlock Watch tab',
  '- Tab-completion lists only problem files; case-insensitive file matching; clear "file locked → stop server" hints',
  '',
  '### 🎫 Ticket purge',
  '- `/sablefix tickets-purge dry|apply` — clears the force-load ticket list via official NbtIo (all other NBT fields kept), atomic write + backup',
  '- Per-UUID aggregation with duplicate highlighting (one UUID holding many tickets = classic stuck-chunk signal)',
  '',
  '### ⚡ Performance & robustness',
  '- **Zero main-thread IO**: panel scans (first + refresh) run on a background pool; panel shows a pulsing "scanning" card and auto-refreshes',
  '- Panel packet permission gate (op level 2) + list length caps (anti-OOM hardening)',
  '- Thread-dump & backup paths resolve against JVM `user.dir` (correct when launched via wrappers that change CWD)',
  '- Watchdog dump counter made atomic; manual dump resets the auto-dump cooldown',
  '- TicketScan NBT: List-of-int-array UUID lists now counted (was silently skipped)',
  '',
  '### 🌐 i18n & UI',
  '- Full bilingual UI (en_us / zh_cn, 154 keys), language-audit tooling',
  '- UI modularized (Theme / Widgets / Format); layout verified at GUI scale 1x/2x/3x (逻辑宽 291–1144) via automated screenshot pipeline',
  '- Version discipline: this is a batched release (see DEV_STATUS.md)',
  '',
  '## 中文',
  '',
  '"修复模组"名副其实了：子层级修复 + 票据清理，全程只读→显式确认。',
  '',
  '- 🛠️ **子层级修复**：`dry` 预览死槽 / `apply` 只清指针表（数据区一个字节不碰）+ 自动备份；支持批量 repair-all；GUI 一键修复；Tab 补全只列问题文件；大小写容错',
  '- 🎫 **票据清理**：官方 NbtIo 读写，其他字段原样保留；按 UUID 聚合统计，重复持有琥珀色高亮',
  '- ⚡ **性能**：面板扫描全后台（首扫也是），主线程零 IO；网络包权限校验 + 长度上限；路径 CWD 解耦；看门狗计数竞态修复',
  '- 🌐 **双语**：en_us/zh_cn 全量 154 key；UI 模块化；三档 GUI scale 布局穷举验证',
].join('\n');

const data = {
  name: 'Sable Fix Core 1.2.0',
  version_number: '1.2.0',
  changelog,
  dependencies: [{ project_id: 'T9PomCSv', dependency_type: 'optional' }],
  game_versions: ['1.21.1'],
  loaders: ['neoforge'],
  featured: true,
  release_channel: 'release',
  project_id: 'rPkYnI7j',
  file_parts: ['sablefix-1.2.0.jar'],
};
fs.writeFileSync('F:/vibe coding工程/sfx/proj/mrdata.json', JSON.stringify(data));
fs.writeFileSync('F:/vibe coding工程/sfx/proj/ghrel.json', JSON.stringify({ tag_name: 'v1.2.0', target_commitish: 'main', name: 'Sable Fix Core 1.2.0', body: changelog }));
console.log('1.2.0 release payloads written (mrdata.json + ghrel.json)');
