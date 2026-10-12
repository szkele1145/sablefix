// 同步 F:\sfxbuild → 真实仓库 F:\vibe coding工程\sfx\proj（src/scripts/build 配置），
// 用 robocopy /MIR 镜像替代 Copy-Item -Recurse（后者会把 dev 拷进 dev\dev 嵌套——踩过两次）
// 用法：node sync_real_repo.js
const { execSync } = require('child_process');
const SRC = 'F:\\sfxbuild';
const DST = 'F:\\vibe coding工程\\sfx\\proj';
const PAIRS = [
  ['src\\main\\java', 'src\\main\\java'],
  ['src\\main\\resources', 'src\\main\\resources'],
  ['scripts', 'scripts'],
];
for (const [s, d] of PAIRS) {
  // robocopy: /MIR 镜像 /NFL /NDL 无文件日志 /NJH 无头 /NP 无进度
  // robocopy 退出码 0-7 都是成功（1=有文件拷贝），execSync 视非零为炸——手动判断
  try {
    execSync(`robocopy "${SRC}\\${s}" "${DST}\\${d}" /MIR /NFL /NDL /NJH /NP`, { stdio: 'ignore' });
  } catch (e) {
    const code = e.status;
    if (code === undefined || code > 7) throw e;   // >7 才是真错
  }
  console.log(`mirrored ${s} -> ${d}`);
}
// build 配置文件单拷
for (const f of ['build.gradle', 'gradle.properties', 'settings.gradle']) {
  execSync(`copy /Y "${SRC}\\${f}" "${DST}\\${f}"`.replace('copy /Y', 'cmd /c copy /Y'), { stdio: 'ignore' });
}
console.log('sync complete');
