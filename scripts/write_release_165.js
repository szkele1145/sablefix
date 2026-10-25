// 生成 1.6.5 发布 payload：mrdata.json（Modrinth）+ ghrel.json（GitHub）。
// mods.toml 的 v1.6.5 条目已在 a2dbd17 提交，此处只更新两个载荷文件。
// 用法：node scripts/write_release_165.js
const fs = require('fs');
const PROJ = 'F:/vibe coding工程/sfx/proj';
const VER = '1.6.5';
const JAR = `sablefix-${VER}-1.21.1-neoforge.jar`;

const changelog = `## v1.6.5 — log, diagnostics and packaging polish

A small maintenance release on top of 1.6.4, from the continuing adversarial audit: one thing
per commit, each one proven before it shipped.

### The console no longer says the same thing twice
Startup lines that both the mod logger and ModLog printed now go through ModLog only, so every
line in the console is there exactly once.

### Everything the server says, the panel and the report can see
Channel registration, save-watcher state, ledger cleanup, backup deletion and read-only unlock
all go through ModLog now, so they reach the in-game panel and the diagnostics report instead of
vanishing in a raw log file.

### One bare translation key and one bare %s template fixed
- A startup line printed its raw key because the message fired before any language was loaded —
  it now says the thing in plain text instead.
- \`/sablefix diag\` printed its chunk header with a raw \`%s\` because the template had three
  placeholders and only two values were given.

### Small fixes
- \`/sablefix backup list\` no longer shows a redundant \`.\\\` in front of every path.
- The version banner and every log line are byte-identical in en and zh; one dead translation
  key that nothing referenced any more was removed.
- The mod now declares the NeoForge and Minecraft versions it needs, so loading it on the wrong
  version gives a clear message instead of a mysterious crash.

## v1.6.5 — 日志、诊断与打包打磨

在 1.6.4 基础上的小型维护版本，来自持续进行的对抗性审计：一次只修一件事，每件都在动手前证明过。

### 控制台不再把同一句话说两遍
启动时既走 mod logger 又走 ModLog 的行，现在只经 ModLog 输出，控制台每行恰好出现一次。

### 服务器说的话，面板与报告都看得见
通道注册、存档观察器状态、登记清账、备份删除与只读解锁全部改走 ModLog，能进游戏内面板与诊断报告，
不再消失在裸日志文件里。

### 修掉一个裸翻译 key 和一个裸 %s 模板
- 一条启动消息在语言加载前就打印，把原始 key 印了出来——现在改为直接说人话。
- \`/sablefix diag\` 的区块标题行印出了裸 \`%s\`：模板有三个占位符却只给了两个值。

### 小修
- \`/sablefix backup list\` 的每条路径前不再有多余的 \`.\\\`。
- 版本横幅与全部日志行在中英文下逐字节一致；清掉一个再无引用的死翻译 key。
- mod 现在声明自己需要的 NeoForge 与 Minecraft 版本，装错版本会给出清晰提示而不是离奇崩溃。`;

// ---- Modrinth ----
const mrPath = `${PROJ}/mrdata.json`;
const mr = JSON.parse(fs.readFileSync(mrPath, 'utf8'));
mr.name = `Sable Fix Core ${VER}`;
mr.version_number = VER;
mr.changelog = changelog;
mr.file_parts = [JAR];
fs.writeFileSync(mrPath, JSON.stringify(mr, null, 2));
console.log(`mrdata.json -> ${mr.version_number}, file_parts=${mr.file_parts.join(',')}, project=${mr.project_id}`);

// ---- GitHub ----
const ghPath = `${PROJ}/ghrel.json`;
const gh = JSON.parse(fs.readFileSync(ghPath, 'utf8'));
gh.tag_name = `v${VER}`;
gh.target_commitish = 'main';
gh.name = `Sable Fix Core ${VER}`;
gh.body = changelog;
fs.writeFileSync(ghPath, JSON.stringify(gh, null, 2));
console.log(`ghrel.json -> ${gh.tag_name} (${gh.body.length} chars)`);
