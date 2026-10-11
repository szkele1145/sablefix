// 生成 Modrinth 1.2.0 版本 data.json
const fs = require('fs');
const data = {
  name: 'Sable Fix Core 1.2.0',
  version_number: '1.2.0',
  changelog: [
    '## v1.2.0 — Repair',
    '',
    '- 🛠️ **New: sublevel repair** — `/sablefix repair <file> dry|apply`',
    '  - `dry` previews exactly which dead slots will be cleared (nothing written)',
    '  - `apply` backs up the original to `<world>/backups/sablefix/` with a timestamp, then clears the dead slots (pointer tables only, data area never touched)',
    '  - tab-completion lists only problem files; clear "file locked" message when Sable holds the handle',
    '- 🌍 Bilingual project page (EN / 中文)',
    '- 🔧 version-bump tooling (`scripts/bump_version.js`), jshell-verified repair logic on synthetic corrupt files',
    '',
    '## 中文',
    '- 🛠️ **新增：子层级修复** — `dry` 预览要清零的死槽（不写盘），`apply` 自动备份到 `<存档>/backups/sablefix/` 后只清指针表死槽（绝不碰数据区）；Tab 补全只列问题文件；文件被占用时给出停服提示',
    '- 🌍 项目页双语化',
  ].join('\n'),
  dependencies: [{ project_id: 'T9PomCSv', dependency_type: 'optional' }],
  game_versions: ['1.21.1'],
  loaders: ['neoforge'],
  featured: true,
  release_channel: 'release',
  project_id: 'rPkYnI7j',
  file_parts: ['sablefix-1.2.0.jar'],
};
fs.writeFileSync('D:/sfx/proj/mrdata.json', JSON.stringify(data));
console.log('1.2.0 data.json written');
