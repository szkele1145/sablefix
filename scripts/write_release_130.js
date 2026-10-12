// 预写 1.3.0 发布 payload（发布时跑本脚本生成最新版）
const fs = require('fs');
const changelog = [
  '## v1.3.0 — Watch & Ledger 👁️',
  '',
  '- 👁️ **SaveObserver** — passive watchdog on the save pipeline: per-chunk save futures polled every 30 s (public API only, zero mixin injection); a chunk pending >20 s is registered as damage before the 60 s vanilla kill',
  '- 📒 **Damage ledger** — persistent `<world>/data/sablefix_damage.json`: scan findings survive restarts; console WARN every 5 min; players get a notice on join (OPs get file+count+repair hint, players get reassurance)',
  '- 🔁 **Closed loop** — successful repair/purge clears its ledger entry automatically (WARN and join-notice stop)',
  '- 🛡️ **Hardening** — repair re-verifies plan before write (concurrent-write guard); scan pool is a dedicated daemon thread; 30s failure cooldown on corrupt saves',
  '- 🧹 **i18n** — last hardcoded strings moved to lang keys (156→163); DiagRes localized on client (no server-locale leakage)',
  '- ➕ `/sablefix diag <x y z>` command (GUI parity)',
  '',
  '## 中文',
  '- 👁️ **保存观察者** — 纯观察 watchdog：30 秒轮询各区块保存 future（只用公开 API，零 mixin 注入），挂起超 20 秒登记为损伤，抢在 60 秒杀服前留证',
  '- 📒 **损伤台账** — 持久化清单：体检发现跨重启保留；控制台每 5 分钟提醒；玩家进服差异化提示（OP 详细/玩家安抚）',
  '- 🔁 **闭环** — 修复/清理成功自动移除登记，提醒随之停止',
  '- 🛡️ 加固：修复写回前重扫防并发、扫描专用守护线程、坏存档 30s 失败冷却',
  '- ➕ `/sablefix diag` 命令补齐（与 GUI 同源）',
].join('\n');
const data = {
  name: 'Sable Fix Core 1.3.0',
  version_number: '1.3.0',
  changelog,
  dependencies: [{ project_id: 'T9PomCSv', dependency_type: 'optional' }],
  game_versions: ['1.21.1'],
  loaders: ['neoforge'],
  featured: true,
  release_channel: 'release',
  project_id: 'rPkYnI7j',
  file_parts: ['sablefix-1.3.0.jar'],
};
fs.writeFileSync('F:/vibe coding工程/sfx/proj/mrdata.json', JSON.stringify(data));
fs.writeFileSync('F:/vibe coding工程/sfx/proj/ghrel.json', JSON.stringify({ tag_name: 'v1.3.0', target_commitish: 'main', name: 'Sable Fix Core 1.3.0', body: changelog }));
console.log('1.3.0 payloads prewritten');