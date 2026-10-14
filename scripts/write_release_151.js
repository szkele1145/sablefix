// 1.5.1 发布 payload（文件形式——绕 shell 转义）
const fs = require('fs');
const changelog = [
  '## v1.5.1 — Fix Command Hardening 🛡️',
  '',
  'Eight fixes from two adversarial audit rounds on the new /sablefix fix command.',
  '',
  '- 🔁 **Re-entry guard with correct lifetime**: FIX_RUNNING now released inside the final kick-unmask-halt lambda, closing the delivery-window race where a second fix could orphan read-only files.',
  '- 🔒 **unmaskAll fully synchronized**: scan-based unlock and per-file re-lock can no longer interleave into stale read-only files.',
  '- 🐧 **Linux graceful degrade**: platforms without dos:readonly now skip the masking step (pure-repair mode) instead of failing the whole command — previously /sablefix fix crashed on its very first step on Linux.',
  '- 🏷️ **STALE_READONLY is its own damage kind** with dedicated guidance (run /sablefix unmask) — no longer misdirected to repair.',
  '- 🤝 **repair-all during fix**: repairs now unlock/relock per file, no more AccessDenied storm when both run together.',
  '- 📢 fix completion shows mask status; stop-server wording; /sablefix unmask listed in help.',
  '',
  '## 中文',
  '',
  '/sablefix fix 经两轮对抗审计，修 8 项：',
  '',
  '- 🔁 重入守卫生命周期修正（终局 lambda 内释放——关闭 halt 投递窗口的双 fix 竞态）',
  '- 🔒 unmaskAll 全程加锁（全扫与单文件回锁不再交错出残留只读）',
  '- 🐧 Linux 优雅降级（无 dos:readonly 平台跳过屏蔽、纯修复模式——原来 fix 在 Linux 第一步即失败）',
  '- 🏷️ STALE_READONLY 独立损伤类型 + 正确指引（用 unmask 而非误导的 repair）',
  '- 🤝 fix 屏蔽期间跑 repair-all 不再 AccessDenied 全军覆没',
  '- 📢 完成提示含屏蔽状态；停服语义文案；help 收录 unmask',
].join('\n');
const data = {
  name: 'Sable Fix Core 1.5.1', version_number: '1.5.1', changelog,
  dependencies: [{ project_id: 'T9PomCSv', dependency_type: 'optional' }],
  game_versions: ['1.21.1'], loaders: ['neoforge'], featured: false, release_channel: 'release',
  project_id: 'rPkYnI7j', file_parts: ['sablefix-1.5.1-1.21.1-neoforge.jar'],
};
fs.writeFileSync('F:/vibe coding工程/sfx/proj/mrdata.json', JSON.stringify(data));
fs.writeFileSync('F:/vibe coding工程/sfx/proj/ghrel.json', JSON.stringify({ tag_name: 'v1.5.1', target_commitish: 'main', name: 'Sable Fix Core 1.5.1', body: changelog }));
console.log('1.5.1 payloads written');
