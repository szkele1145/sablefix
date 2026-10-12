// 生成 Modrinth 双语描述 patch（v1.2.0 定位澄清版：诊断优先、修复为辅）
const fs = require('fs');
const body = [
  '**Sable Fix Core** — deadlock forensics & save diagnostics for Sable (Aeronautics) worlds · 死锁取证与存档诊断核心',
  '',
  '## 🌍 English',
  '',
  '**Positioning — diagnosis first, repair second.** Deadlocks are usually caused by *other mods* patching the same save code (e.g. two mods both patching `ChunkMap#saveAllChunks`). No mod can "fix" that from the outside — but you can remove the culprit in 30 seconds **if you know who it is**. This mod makes sure you always know: it captures the full thread dump while the server is still alive, instead of letting the vanilla watchdog kill it at 60 s with zero clues.',
  '',
  '- 🛡️ **Deadlock forensics** — full thread dump after 20 s stall (vanilla kills at 60 s): who waits on whom, written to `logs/sablefix/`. This is evidence-gathering, not deadlock prevention.',
  '- 🔍 **Startup health check** — verifies every Sable sublevel pointer table (`.slvlr`/`.slvls`) & force-load tickets (read-only)',
  '- 🛠️ **Save repair** — where it *does* fix things: corrupt sublevel pointers (silently-lost ships/airships) and stuck force-load tickets. `/sablefix repair <file> dry|apply`, auto-backup, pointer tables only',
  '- 🖥️ **GUI panel** — `/sfxpanel` (bilingual EN/中文): overview, save check, sublevel browser, chunk diag, watchdog, logs, about',
  '- 📦 75 KB, zero dependencies, same jar on client & server, NeoForge 1.21.1',
  '',
  '**Feedback:** QQ 1129543042 (中文) · Telegram @maodie_mods (international, link in the in-mod About tab)',
  '',
  '---',
  '',
  '## 🇨🇳 中文',
  '',
  '**定位：诊断优先，修复为辅。** 死锁的根因通常在**别的模组**的代码里（比如两个 mod 同时 patch 了 `ChunkMap#saveAllChunks`）。任何模组都无法从外部"修好"这种冲突——但如果你**知道凶手是谁**，30 秒就能摘掉它。这个模组保证你永远知道：它抢在原版 60 秒杀服之前把全线程栈抓下来，而不是让你对着转圈的 save-all 干瞪眼。',
  '',
  '- 🛡️ **死锁取证** — 卡死 20 秒自动抓全线程栈（原版 60 秒杀服前抢下现场），谁在等谁一眼看到底。这是**取证**，不是预防死锁。',
  '- 🔍 **启动体检** — 逐个校验 Sable 子层级指针表（`.slvlr`/`.slvls`）与 force-load 票据（全程只读）',
  '- 🛠️ **存档修复** — 真正动手修的部分：损坏的子层级指针（船和飞行器静默丢失）、卡死的 force-load 票据。`/sablefix repair <file> dry|apply`，自动备份，只清指针表',
  '- 🖥️ **图形面板** — `/sfxpanel`（中英双语）：总览 / 存档体检 / 子层级浏览器 / 区块诊断 / 死锁监视 / 运行日志 / 关于',
  '- 📦 75KB 单 jar，零依赖，双端同装，NeoForge 1.21.1',
  '',
  '**反馈：** QQ 群 1129543042 · Telegram @maodie_mods（链接见模组内「关于」页）',
].join('\n');
fs.writeFileSync('D:/sfx/proj/mrpatch.json', JSON.stringify({ body }));
console.log('positioning-clarified body written,', body.length, 'chars');
