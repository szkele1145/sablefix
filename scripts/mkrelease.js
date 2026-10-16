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

// 每个版本必须在此登记文案：载荷文案与版本号解耦会导致新版静默沿用旧文案（1.6.0 真实事故）。
// 未登记 = 直接报错，绝不发出描述错误的更新说明。
const CHANGELOGS = {
  '1.6.1': {
    en: `## v${VER} — fix a regression I shipped in 1.6.0 🩹

### Regression fix (please update if you are on 1.6.0)
- 🩹 **The async damage-ledger writer could corrupt its own state.** 1.6.0 moved the disk write off the lock, but took the snapshot *inside* the queued task — so the writer thread read the plain \`LinkedHashMap\` with no lock held, concurrently with the main thread's \`periodicWarn\` / \`onPlayerJoin\`. That is a \`ConcurrentModificationException\` (or a half-updated map) waiting for a bad tick. Snapshots are now **deep-copied while the class lock is held**, and the writer only ever touches its own private copy; \`snapshot()\` is deep-copied too.
- 🩹 **Pending ledger writes are no longer lost on shutdown.** With the write on a daemon thread, nothing drained the queue when the server stopped — a normal stop or a panel force-kill could silently drop the last few registrations. A \`flush\` barrier now blocks until the queue is empty before shutdown proceeds.

### Unchanged from 1.6.0
- ⚡ Ledger writes stay off the \`synchronized\` critical section (no more 1–50 ms \`GSON\` + atomic-move under the lock stalling a server tick).

Protocol v5 (compatible with 1.5.2+).`,
    zh: `## 中文

### 回归修复（1.6.0 用户请务必更新）
- 🩹 **异步写盘可能损坏自己的状态。** 1.6.0 把磁盘写移出了锁，但快照是在**排队任务内部**取的 —— 等于写线程在完全不持锁的情况下读普通的 \`LinkedHashMap\`，与主线程的 \`periodicWarn\` / \`onPlayerJoin\` 并发。这是一颗随时会炸的 \`ConcurrentModificationException\`（或半更新状态）。现在快照改为**在类锁内深拷贝**，写线程只碰自己的私有副本；\`snapshot()\` 同样深拷贝。
- 🩹 **停服不再丢待写的登记。** 写盘挪到守护线程后，服务器停止时没有任何人排空队列 —— 正常停服或面板强杀都可能静默丢掉最后几次登记。新增 \`flush\` 屏障：停服前阻塞到队列排空。

### 沿用 1.6.0
- ⚡ 登记簿写盘继续留在 \`synchronized\` 临界区之外（不再有锁内 1–50ms 的 \`GSON\` + 原子替换拖慢服务器 tick）。

协议 v5（与 1.5.2 及以上兼容）。`,
  },
  '1.6.0': {
    en: `## v${VER} — damage-ledger writes no longer stall a server tick ⚡

### Optimization
- ⚡ **Ledger persistence moved off the \`synchronized\` critical section.** \`DamageRegistry.register\` / \`clear\` previously ran \`GSON\` + \`ATOMIC_MOVE\` (1–50 ms) while holding the class lock, which the main thread contends for every tick via \`periodicWarn\` — an invisible source of single-tick stalls. Writes now go through a single daemon writer thread; last write wins.

Protocol v5 (compatible with 1.5.2 / 1.5.3).`,
    zh: `## 中文

### 优化
- ⚡ **登记簿落盘移出 \`synchronized\` 临界区。** 原先 \`DamageRegistry.register\` / \`clear\` 在持有类锁的情况下执行 \`GSON\` + \`ATOMIC_MOVE\`（1–50ms），而主线程每 tick 的 \`periodicWarn\` 都要争这把锁 —— 一个隐形的单 tick 卡顿源。现在写盘走单线程守护队列，"最后写赢"。

协议 v5（与 1.5.2 / 1.5.3 兼容）。`,
  },
};

const cl = CHANGELOGS[VER];
if (!cl) {
  console.error(`!! 未登记 ${VER} 的更新文案。请在 mkrelease.js 的 CHANGELOGS 中补上该版本，`);
  console.error(`   否则会把旧版本文案当成 ${VER} 发出去（1.6.0 事故）。`);
  process.exit(1);
}
const EN = cl.en;
const ZH = cl.zh;

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
