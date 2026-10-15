// 生成发布载荷：mrdata.json（Modrinth）+ ghrel.json（GitHub Release）
// 用法：node mkrelease.js <version>
// 纪律：JSON 由 JSON.stringify 生成，中文/emoji 一律不手写转义，避免载荷损坏。
const fs = require('fs');

const VER = process.argv[2];
if (!/^\d+\.\d+\.\d+$/.test(VER || '')) {
  console.error('用法: node mkrelease.js <version>   例: node mkrelease.js 1.5.4');
  process.exit(1);
}

const PROJ = 'F:/vibe coding工程/sfx/proj';
const JAR = `sablefix-${VER}-1.21.1-neoforge.jar`;

const EN = `## v${VER} — P0 fix: damage survived the auto-restart 🛠️

### P0 (reported in-game, now fixed)
- 🩹 **\`/sablefix fix\` still reported the same damage after the automatic restart.** Root cause: the repair lifted the write-block *before* \`server.halt(false)\`, so during the shutdown save the dying Sable process flushed its stale in-memory sublevel index straight back over the slots we had just zeroed. The files now stay **read-only for the entire shutdown**, and are unlocked only in \`ServerStoppedEvent\` (after the save has landed). A fresh process also unmask-sweeps on startup, so even a hard kill self-heals.
- 🧹 A persisted \`STALE_READONLY\` entry is now cleared unconditionally after that startup sweep — previously an already-unlocked world kept warning every 5 minutes, across restarts.

### Hardening
- 🔒 The stale-read-only ledger key is now a single constant (\`DamageRegistry.STALE_READONLY_FILE\`) shared by the writer and all four clearers, removing the risk that three hand-copied magic strings drift apart and make \`clear\` fail silently.

Protocol v5 (compatible with 1.5.2 / 1.5.3).`;

const ZH = `## 中文

### P0（实机反馈，已修复）
- 🩹 **\`/sablefix fix\` 自动重启后仍报同样损坏。** 根因：修复在 \`server.halt(false)\` **之前**就解除了写回屏蔽 —— 停服存档时，将死的 Sable 进程把内存里陈旧的子层级索引原样刷回，正好覆盖掉我们刚清零的槽位。现在文件**在整个关服过程中保持只读**，只在 \`ServerStoppedEvent\`（存档落盘之后）解锁；新进程启动时另有兜底扫描解锁，即使上次被强杀也能自愈。
- 🧹 启动兜底解锁后**无条件**清除持久化的 \`STALE_READONLY\` 登记 —— 原先已解锁的世界仍会每 5 分钟误报，且跨重启残留。

### 加固
- 🔒 残留只读的登记名收敛为单一常量 \`DamageRegistry.STALE_READONLY_FILE\`（登记方 + 四个清除方共用），消除三处手抄魔法字符串将来失配、导致 clear 静默失效的风险。

协议 v5（与 1.5.2 / 1.5.3 兼容）。`;

const changelog = `${EN}\n\n${ZH}\n`;

const mrdata = {
  name: `Sable Fix Core ${VER}`,
  version_number: VER,
  changelog,
  dependencies: [{ project_id: 'T9PomCSv', dependency_type: 'optional' }],
  game_versions: ['1.21.1'],
  loaders: ['neoforge'],
  featured: false,
  release_channel: 'release',
  project_id: 'rPkYnI7j',
  file_parts: [JAR],
};

const ghrel = {
  tag_name: `v${VER}`,
  target_commitish: 'main',
  name: `Sable Fix Core ${VER}`,
  body: changelog,
};

// 发送前校验：载荷必须能解析、版本号与 jar 名一致（历史教训：误发过一版 404 载荷）
for (const [f, o] of [['mrdata.json', mrdata], ['ghrel.json', ghrel]]) {
  const s = JSON.stringify(o, null, f === 'ghrel.json' ? 0 : 2);
  const back = JSON.parse(s);
  if (back.version_number && back.version_number !== VER) throw new Error(`${f}: version mismatch`);
  if (back.file_parts && !back.file_parts[0].includes(VER)) throw new Error(`${f}: jar name mismatch`);
  fs.writeFileSync(`${PROJ}/${f}`, s, 'utf8');
  console.log(`  ${f}: ${s.length} bytes OK`);
}

// jar 必须已经构建出来，否则载荷指向不存在的文件
const jarPath = `${PROJ}/build/libs/${JAR}`;
if (!fs.existsSync(jarPath)) {
  console.error(`!! 构建产物缺失: ${jarPath}`);
  process.exit(1);
}
console.log(`载荷已生成: ${JAR} (${fs.statSync(jarPath).size} bytes)`);
