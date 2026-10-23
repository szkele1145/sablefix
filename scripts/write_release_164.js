// 生成 1.6.4 发布 payload：mrdata.json（Modrinth）+ ghrel.json（GitHub）+ mods.toml changelog 条目。
// 用法：node scripts/write_release_164.js
const fs = require('fs');
const PROJ = 'F:/vibe coding工程/sfx/proj';
const VER = '1.6.4';
const JAR = `sablefix-${VER}-1.21.1-neoforge.jar`;

const changelog = `## v1.6.4 — 98 fixes since 1.6.3

This release is the result of a long adversarial audit. Every change below was found by reading
the source, proving the behaviour with a standalone probe, fixing one thing per commit.

### Saves of one world no longer bleed into another
The scan cache, the damage ledger, the runtime log, the per player notice state and both watchers
now reset with the server, so a second world never shows the report, the damage list or the log
lines of the first one.

### Backups are told the truth about, and are never overwritten
A backup is the only copy of a file before it is repaired, so nothing overwrites one: a fresh
timestamp, a collision suffix and a placeholder created with CREATE_NEW, then the finished file is
moved into place. A purge that meets one locked file finishes the rest instead of stopping halfway.
The count shown by the command and by the page is now the real count, not the number that happened
to be sent over the network.

### The page says when it is showing only part of a list
The list of the files and the list of the log lines are cut down before they travel, to keep the
packet small. The page is now told the real number and says so, instead of looking complete when it
is not.

### The world check and the damage ledger agree
The check, the repair of everything and the startup scan now go through one shared path, so the
numbers they print and the entries they keep cannot drift apart. A file that became healthy again
has its entry removed, and the ticket ledger is synced before the numbers are read.

### Diagnostics you can hand to someone else
Reports are named after the world, say how long the server has been up, keep only the recent ones
(twenty), and gather every error at the top. A thread dump from an earlier run of another world is
never shown as this one.

### Panel fixes
Buttons and scrollbars can only be clicked while they are actually drawn, scroll offsets are
brought back in range before they are used, the log page has a real scrollbar, and a hint line can
no longer be drawn outside its card on a short window.

### Read and write lines up exactly
Every string that goes over the network is cut by UTF-8 bytes on the way out and bounded again on
the way in, so a very long name can no longer break the connection.

## v1.6.4 — 1.6.3 之后的 98 项修复

本次是长期对抗性审计的成果：只读源码、用独立探针证明行为，一次只修一个缺陷。

### 各世界的存档不再互相串味
扫描缓存、损伤登记簿、运行日志、每玩家提示状态和两个观察器都随服务器实例重置，第二个世界不会
再看到第一个世界的报告、损伤列表或日志行。

### 备份绝不被覆盖，数量也说得实话
备份是修复前的唯一副本，因此绝不覆盖：时间戳 + 冲突后缀 + CREATE_NEW 占位，落盘后再移动到位。
清理时遇到被占用的单个文件会跳过它并继续处理其余，不再半途而废。命令与面板显示的备份数量是
真实总数，而不是恰好通过网络上传输的那一份。

### 面板会说明它只显示了一部分
文件列表与日志列表在发送前会被截短以控制包大小；现在面板会拿到真实总数并显示出来，不再把
截断后的列表当成全部。

### 体检结论与损伤登记簿一致
体检、批量修复与启动扫描走同一条共享路径，打印的数字与保留的条目不会互相矛盾；恢复健康的
文件会被清除登记，票据登记也在读取数字之前完成同步。

### 可以放心递给别人的诊断
报告以世界命名、写明服务器已运行时长、只保留最近 20 份，并把所有错误汇总在顶部；另一个世界
早先运行的线程栈绝不会被当成本次的结果。

### 面板修复
按钮与滚动条只有真正画出来时才能点；滚动偏移在使用前回到合法范围；日志页有真正的滚动条；
矮窗口中提示行不再画到卡片之外。

### 收发严格对齐
所有经网络传输的字符串都按 UTF-8 字节在写侧截断、在读侧再次限界，超长文件名不再能撑断连接。`;

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

// ---- mods.toml 的 description 顶部加一条 ----
const tomlPath = `${PROJ}/src/main/resources/META-INF/neoforge.mods.toml`;
let toml = fs.readFileSync(tomlPath, 'utf8');
const anchor = 'v1.6.3：';
if (!toml.includes('v1.6.4：')) {
  const line = `v1.6.4：1.6.3 之后的 98 项修复——各世界存档状态隔离（扫描缓存/登记簿/日志/观察器随服务器重置）；备份绝不覆盖且数量如实上报；面板如实说明列表被截断（协议 v6→v10，新增备份/文件/日志总数）；体检、批量修复与启动扫描三入口同源，恢复健康的文件自动清账；诊断报告按世界命名、保留最近 20 份并汇总全部错误；面板命中区帧级回收、滚动偏移先 clamp 后使用、日志页真滚动条、矮窗口提示行不越界；写读两侧按 UTF-8 字节严格对齐。\n`;
  const i = toml.indexOf(anchor);
  if (i < 0) throw new Error('mods.toml 里找不到 v1.6.3：锚点');
  toml = toml.slice(0, i) + line + toml.slice(i);
  fs.writeFileSync(tomlPath, toml, 'utf8');
  console.log('mods.toml: v1.6.4 条目已插入');
} else {
  console.log('mods.toml: 已有 v1.6.4 条目，跳过');
}
