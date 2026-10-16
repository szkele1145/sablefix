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
  '1.6.2': {
    en: `## v${VER} — check now shows registered damage with per-type recovery advice 🛠️

### Check command: registered damage + hints
- **/sablefix check** now prints *“Registered damage: N items”* alongside live scan counts — so a post-restart "still damaged" alert tells you **which kind** of damage (SUBLVL_DEAD / STALE_READONLY / TICKETS_STUCK / SAVE_STALLED) and what to do:
  - STALE_READONLY → \`/sablefix unmask\` (repair will not help)
  - TICKETS_STUCK → \`/sablefix tickets-purge apply\` (while stopped)
  - SUBLVL_DEAD → \`/sablefix repair-all apply\` (or one-shot \`/sablefix fix\`)
- Periodic WARN now aggregates by type with per-type recovery commands instead of raw \`file×count\`.

### Under the hood
- \`DamageRegistry.onPlayerJoin\` returns a **deep copy** of each entry — fixed the same 1.6.0-style tear as \`snapshot()\`: callers reading \`e.count\` off the lock could see half-updated numbers.
- \`ModLog.add\` logger output moved out of the synchronized section (no more I/O under the class lock).

Protocol v5 (compatible with 1.5.2+).`,
    zh: `## 中文

### check 命令：已登记损伤摘要 + 按类型处置建议
- **/sablefix check** 现在会在体检计数之外打印“已登记损伤: N 项” —— 重启后“仍损坏”的提示能一眼看出是哪一类损伤（SUBLVL_DEAD / STALE_READONLY / TICKETS_STUCK / SAVE_STALLED），并给出处置命令：
  - STALE_READONLY → \`/sablefix unmask\`（repair 无效）
  - TICKETS_STUCK → \`/sablefix tickets-purge apply\`（停服后）
  - SUBLVL_DEAD → \`/sablefix repair-all apply\`（或一键 \`/sablefix fix\`）
- 定期 WARN 现在按类型聚合并附带处置命令，不再打原始 \`file×count\`。

### 内部修
- \`DamageRegistry.onPlayerJoin\` 返回条目**深拷贝**——修复与 \`snapshot()\` 同源的 1.6.0 样撕裂：调用方在锁外读 \`e.count\` 可能看到半更新数量。
- \`ModLog.add\` 的 LOGGER 输出移出 synchronized 区（不再在类锁内做 I/O）。

协议 v5（与 1.5.2 及以上兼容）。`,
  },
  '1.6.3': {
    en: `## v1.6.3 — maintenance release 🔧

### P0 fix: /sablefix fix no longer reports "damaged" after restart
- \`endFix()\` now runs before \`halt(false)\`; the write mask is no longer lifted before shutdown, preventing Sable's dying write-back from overwriting freshly-cleared slots. Unlocking is handled by \`ServerStoppedEvent\` (after save) plus a startup directory fallback that clears \`STALE_READONLY\`.

### Audit complete
- SfxPanelScreen + SfxCommands command surface audited: all 13 subcommands bound to OP level 2; every destructive op has dry→apply confirmation or permission gating. No changes needed.

### Backlog verified
- Log scrolling hitbox, repairBtns click target, backup name truncation, version number sync (4 places), and saveLater atomic tmp+replace write all confirmed OK. No changes needed.

Protocol v5 (compatible with 1.5.2+).`,
    zh: `## v1.6.3 — 维护版 🔧

### P0 修复：/sablefix fix 重启后不再误报“已损坏”
- \`endFix()\` 现前置于 \`halt(false)\`；停服前不再提前解除写掩码，防止 Sable 垂死写回覆盖刚清零的槽位。解锁改由 \`ServerStoppedEvent\`（存档落盘后）+ 启动目录兜底（清理 \`STALE_READONLY\`）处理。

### 审计完成
- SfxPanelScreen + SfxCommands 命令暴露面已审计：全部 13 个子命令绑定 OP 2 级权限；每项破坏性操作均有 dry→apply 确认或权限限制。无需改动。

### Backlog 已核实
- 日志滚动命中区、repairBtns 点击目标、备份名显示截断、版本号四处同步、saveLater 原子 tmp+replace 写盘均已确认无误。无需改动。

协议 v5（与 1.5.2 及以上兼容）。`,
  },
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
