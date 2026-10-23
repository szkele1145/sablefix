// 发布前构件自检：确认要上传的 jar 真的是这一版、且真的是最新代码。
// 用法：node scripts/verify_jar.js 1.6.4
// 做四件事：
//   1) jar 存在且大小合理（防空构建 / 拿到旧产物）
//   2) jar 内 neoforge.mods.toml 的 version == 目标版本，且 description 里已有该版本条目
//   3) 反编译 NetHandler，确认 PROTOCOL_VERSION 常量与源码一致（防构建命中旧缓存）
//   4) 统计类文件数并列出缺失的关键类
// 退出码 0 = 全部通过；1 = 有失败（打印每项 PASS/FAIL）。
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');

const JDK = 'F:/vibe coding工程/sfx/jdkfull/jdk-21.0.12.1+1/bin';
const PROJ = 'F:/vibe coding工程/sfx/proj';
const VER = process.argv[2];
if (!/^\d+\.\d+\.\d+$/.test(VER || '')) {
  console.error('用法: node verify_jar.js <x.y.z>');
  process.exit(1);
}
const JAR = `${PROJ}/build/libs/sablefix-${VER}-1.21.1-neoforge.jar`;

let failed = 0;
const check = (name, ok, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  [' + detail + ']' : ''}`);
  if (!ok) failed++;
};

function jarTool() {
  const p = path.join(JDK, 'jar.exe');
  if (!fs.existsSync(p)) throw new Error('找不到 jar.exe: ' + p);
  return p;
}
function javaTool(name) {
  const p = path.join(JDK, name + '.exe');
  if (!fs.existsSync(p)) throw new Error('找不到 ' + name + '.exe: ' + p);
  return p;
}

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'sfxverify-'));
try {
  // 1) 存在性 + 大小
  if (!fs.existsSync(JAR)) { console.error('构件缺失: ' + JAR); process.exit(1); }
  const size = fs.statSync(JAR).size;
  check('jar 存在且大小合理', size > 150_000 && size < 5_000_000, size + ' B');

  // 2) mods.toml 版本 + changelog 条目
  execFileSync(jarTool(), ['xf', JAR, 'META-INF/neoforge.mods.toml'], { cwd: tmp, stdio: 'pipe' });
  const toml = fs.readFileSync(path.join(tmp, 'META-INF/neoforge.mods.toml'), 'utf8');
  const m = toml.match(/^version\s*=\s*"([^"]+)"/m);
  check('mods.toml 版本 == 目标', !!m && m[1] === VER, m ? m[1] : '未找到 version 行');
  check('mods.toml 含 v' + VER + ' 说明', toml.includes('v' + VER));

  // 3) PROTOCOL_VERSION 常量 vs 源码
  const src = fs.readFileSync(`${PROJ}/src/main/java/dev/sfx/sablefix/net/NetHandler.java`, 'utf8');
  const want = (src.match(/PROTOCOL_VERSION\s*=\s*"([^"]+)"/) || [])[1];
  execFileSync(jarTool(), ['xf', JAR, 'dev/sfx/sablefix/net/NetHandler.class'], { cwd: tmp, stdio: 'pipe' });
  const out = execFileSync(javaTool('javap'), ['-p', '-constants',
    path.join(tmp, 'dev/sfx/sablefix/net/NetHandler.class')], { stdio: 'pipe' }).toString();
  const got = (out.match(/PROTOCOL_VERSION\s*=\s*"([^"]+)"/) || [])[1];
  check('jar 内 PROTOCOL_VERSION == 源码', !!want && got === want, 'jar=' + got + ' src=' + want);

  // 4) 类文件清单
  const listing = execFileSync(jarTool(), ['tf', JAR], { stdio: 'pipe' }).toString().split(/\r?\n/).filter(Boolean);
  const classes = listing.filter(f => f.endsWith('.class'));
  check('类文件数量合理', classes.length >= 40, classes.length + ' 个');
  const must = [
    'dev/sfx/sablefix/SableFixMod.class',
    'dev/sfx/sablefix/net/NetHandler.class',
    'dev/sfx/sablefix/client/SfxPanelScreen.class',
    'dev/sfx/sablefix/scan/DamageRegistry.class',
    'dev/sfx/sablefix/watchdog/ThreadWatchdog.class',
  ];
  const missing = must.filter(x => !listing.includes(x));
  check('关键类齐全', missing.length === 0, missing.length ? '缺 ' + missing.join(', ') : '5/5');
  check('未混入探针/测试类', !listing.some(f => /Probe|Test\.class$/.test(f)));
} finally {
  try { fs.rmSync(tmp, { recursive: true, force: true }); } catch (e) { /* 清不掉就算了 */ }
}

console.log(failed === 0 ? `构件自检通过：${VER}` : `构件自检失败：${failed} 项`);
process.exit(failed === 0 ? 0 : 1);
