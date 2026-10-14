// 1.5.3 发布 payload（四五六审 9 项）
const fs = require('fs');
const changelog = [
  '## v1.5.3 — Audit Rounds 4-6 🧹',
  '',
  'Nine fixes from three more adversarial audit rounds (GUI interactions, ledger closure, protocol symmetry).',
  '',
  '- ⌨️ **Number-tab keys no longer lock up**: focusing the diag-page input boxes then switching tabs used to silently disable keys 1-7 panel-wide until reopened (vanilla `setEditable` doesn\'t clear focus).',
  '- 🧊 **Empty repair plan no longer dead-ends the confirm button** (slots=0 no longer enters confirm state).',
  '- 🛡️ **Demo hijack guard**: the `sfx.demo` system property was so generically named that any other mod defining it could force-change your GUI scale, show fake data, and **quit your game** — now namespaced `sablefix.*` and production-gated.',
  '- 🔕 **STALE_READONLY closed loop**: running `/sablefix unmask` (or a clean startup scan) now clears its ledger entry — previously the 5-minute warning repeated forever even after fixing.',
  '- 📚 **Docs honest again**: README/About now document `fix`/`unmask`/`report` (and the write-back lesson); legacy status docs annotated.',
  '- 🔌 **Protocol symmetry hardening**: dims write-side truncation aligned with the read clamp (payload misalignment in >256-dim pathology); the "N healthy" summary row moved to head (was silently cut off on huge archives); readUtf explicit caps matching the write side.',
  '',
  'Protocol v5 (compatible with 1.5.2).',
  '',
  '## 中文',
  '',
  '四、五、六轮对抗审计，9 项修复：',
  '',
  '- ⌨️ **数字键切页锁死修复**：诊断页输入框聚焦后切页，1-7 键全页静默失灵（vanilla setEditable 不清焦点）。',
  '- 🧊 **空修复计划不再挂死确认按钮**。',
  '- 🛡️ **demo 属性劫持防护**：原 `sfx.demo` 命名太通用，任何 mod 定义同名属性即可强改 GUI、展示假数据、**直接退出游戏**——已改 `sablefix.*` 命名空间 + 生产环境闸。',
  '- 🔕 **STALE_READONLY 闭环**：unmask/干净启动自动清登记——原来修完照样每 5 分钟误报。',
  '- 📚 **文档回真**：README/关于页补全 fix/unmask/report（含写回覆盖教训）。',
  '- 🔌 **协议对称加固**：dims 写侧截断与读侧 clamp 对齐；"N 个健康"汇总行置顶（大存档不再被静默切掉）；readUtf 显式上限。',
  '',
  '协议 v5（与 1.5.2 兼容）。',
].join('\n');
const data = {
  name: 'Sable Fix Core 1.5.3', version_number: '1.5.3', changelog,
  dependencies: [{ project_id: 'T9PomCSv', dependency_type: 'optional' }],
  game_versions: ['1.21.1'], loaders: ['neoforge'], featured: false, release_channel: 'release',
  project_id: 'rPkYnI7j', file_parts: ['sablefix-1.5.3-1.21.1-neoforge.jar'],
};
fs.writeFileSync('F:/vibe coding工程/sfx/proj/mrdata.json', JSON.stringify(data));
fs.writeFileSync('F:/vibe coding工程/sfx/proj/ghrel.json', JSON.stringify({ tag_name: 'v1.5.3', target_commitish: 'main', name: 'Sable Fix Core 1.5.3', body: changelog }));
console.log('1.5.3 payloads written');
