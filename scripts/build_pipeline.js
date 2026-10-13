// 构建流水线：proj（真实仓库，中文路径）→ temp 副本（ASCII 路径，gradle worker 可用）→ 构建
// 工作流纪律：所有临时文件只进系统 temp，不碰 F:\ 根
// 用法：node build_pipeline.js        （构建 + 产物回拷 proj\build\libs）
const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const os = require('os');

const PROJ = 'F:/vibe coding工程/sfx/proj';
const TMP = path.join(os.tmpdir(), 'sfxbuild');
const GRADLE = 'F:/vibe coding工程/sfx/gradle-8.10.2/bin/gradle.bat';
const JAVA_HOME = 'F:/vibe coding工程/sfx/jdkfull/jdk-21.0.12.1+1';

function run(cmd) {
  try { execSync(cmd, { stdio: 'inherit' }); }
  catch (e) { const code = e.status; if (code === undefined || code > 7) throw e; }  // robocopy 0-7 成功
}

console.log('[1/4] 同步源码到 temp 副本…');
fs.mkdirSync(TMP, { recursive: true });
for (const [src, dst] of [
  [`${PROJ}/src`, `${TMP}/src`],
  [`${PROJ}/scripts`, `${TMP}/scripts`],
]) {
  run(`robocopy "${src}" "${dst}" /MIR /NFL /NDL /NJH /NP`);
}
for (const f of ['build.gradle', 'gradle.properties', 'settings.gradle']) {
  fs.copyFileSync(`${PROJ}/${f}`, `${TMP}/${f}`);
}

console.log('[2/4] 构建…');
const env = { ...process.env, JAVA_HOME, GRADLE_USER_HOME: path.join(os.tmpdir(), 'gradlehome') };
execSync(`"${GRADLE}" --no-daemon build --console=plain`, { cwd: TMP, env, stdio: 'inherit' });

console.log('[3/4] 产物回拷…');
fs.mkdirSync(`${PROJ}/build/libs`, { recursive: true });
const libs = fs.readdirSync(`${TMP}/build/libs`).filter(f => f.endsWith('.jar'));
for (const f of libs) {
  fs.copyFileSync(`${TMP}/build/libs/${f}`, `${PROJ}/build/libs/${f}`);
  console.log(`  => ${f}`);
}

console.log('[4/4] 完成');
