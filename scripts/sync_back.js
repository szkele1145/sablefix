// 反向同步：temp 副本（源码改动处）→ proj（真实仓库）。
// 改代码时直接改 proj，需要构建时才 build_pipeline 拷出去——所以本脚本用于
// 极端情况（如需要在 ASCII 路径下直接改+编译的场景回灌）。
const { execSync } = require('child_process');
const path = require('path');
const os = require('os');
const PROJ = 'F:/vibe coding工程/sfx/proj';
const TMP = path.join(os.tmpdir(), 'sfxbuild');
try { execSync(`robocopy "${TMP}\\src" "${PROJ}\\src" /MIR /NFL /NDL /NJH /NP`, { stdio: 'ignore' }); }
catch (e) { if (e.status === undefined || e.status > 7) throw e; }
try { execSync(`robocopy "${TMP}\\scripts" "${PROJ}\\scripts" /MIR /NFL /NDL /NJH /NP`, { stdio: 'ignore' }); }
catch (e) { if (e.status === undefined || e.status > 7) throw e; }
console.log('synced back to proj');
