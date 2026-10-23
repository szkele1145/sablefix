// 启动日志健康检查：分析一次 runServer 的日志，判定启动链路与日志质量。
//
// 用法（两步，第二步才是本脚本）：
//   1) 产生日志（本脚本不启动服务器——沙箱禁止子进程）：
//        $env:JAVA_HOME='F:\vibe coding工程\sfx\jdkfull\jdk-21.0.12.1+1'
//        $env:GRADLE_USER_HOME='C:\gradle-home'
//        & 'F:\vibe coding工程\sfx\gradle-8.10.2\bin\gradle.bat' runServer --console=plain *> "$env:TEMP\rs.txt"
//   2) node scripts/check_runtime_log.js %TEMP%\rs.txt
//
// 检查 5 项：完整启动 / 启动扫描跑完 / 无裸翻译 key / 无英文+中文重复行 / SableFix 无未捕获异常
// 退出码 0 = 全部通过
const fs = require('fs');
const path = require('path');
const os = require('os');

const logPath = process.argv[2] || path.join(os.tmpdir(), 'rs.txt');
if (!fs.existsSync(logPath)) {
  console.error('日志不存在: ' + logPath);
  console.error('见脚本头部注释：先用 gradle runServer 产生日志（本脚本不启动进程）。');
  process.exit(1);
}

// PowerShell 的 `*>` 重定向在 5.1 下写 UTF-16LE（带 FF FE BOM），在其他环境是 UTF-8。
// 按 BOM 自动识别，否则日志会被读成乱码、所有正则全部落空（曾因此误报 2 项 FAIL）。
function readLog(p) {
  const buf = fs.readFileSync(p);
  if (buf.length >= 2 && buf[0] === 0xff && buf[1] === 0xfe) return buf.slice(2).toString('utf16le');
  if (buf.length >= 2 && buf[0] === 0xfe && buf[1] === 0xff) {
    const sw = Buffer.from(buf.slice(2));
    sw.swap16();
    return sw.toString('utf16le');
  }
  return buf.toString('utf8').replace(/^\uFEFF/, '');
}

const text = readLog(logPath);
const lines = text.split(/\r?\n/);
let failed = 0;

const check = (name, ok, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  [' + detail + ']' : ''}`);
  if (!ok) failed++;
};

console.log(`分析: ${logPath}  (${(fs.statSync(logPath).size / 1024).toFixed(1)} KB, ${lines.length} 行)`);

const done = text.match(/Done \([\d.]+s\)/);
check('服务器完整启动', !!done, done ? done[0] : '未到 Done');

const scan = text.match(/\[log\] Sublevel scan done:[^\r\n]*/);
check('启动扫描跑完', !!scan, scan ? scan[0].replace('[log] ', '') : '未找到 scan done');

const bare = text.match(/\[log\] sablefix\.[a-z0-9_.]+/g);
check('无裸翻译 key', !bare, bare ? bare.slice(0, 3).join(', ') : 'clean');

const pairs = [
  [/\[log\] Sublevel scan done/, /子层级体检：扫描/],
  [/\[log\] Ticket scan/, /票据体检：/],
  [/\[log\] Main-thread watchdog up/, /主线程监视器已启动/],
  [/\[log\] Save observer up/, /保存观察者启动/],
];
const dups = pairs.filter(([a, b]) => a.test(text) && b.test(text));
check('无英文+中文重复行', dups.length === 0, dups.length ? dups.length + ' 对' : 'clean');

const ex = lines.filter(l => /SableFix/.test(l) && /Exception|NullPointerException/.test(l));
check('SableFix 无未捕获异常', ex.length === 0, ex.length ? ex[0].slice(0, 120) : 'clean');

console.log(failed === 0 ? '启动日志检查通过' : `启动日志检查失败：${failed} 项`);
process.exit(failed === 0 ? 0 : 1);
