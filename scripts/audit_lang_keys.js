// 死 key 审计：找出 lang 里定义了、但源码里从未引用的翻译 key。
// 用法：node scripts/audit_lang_keys.js
// 判定方式：把 src/main/java 下所有 .java 拼成一个大串（+ scripts 里的验证脚本），
// 对每个 lang key 做子串查找。找到 = 活；找不到 = 疑似死 key（打印出来人工判断）。
// 说明：key 可能由字符串拼接生成，故「找不到」只报「疑似」，不自动删。
const fs = require('fs');
const path = require('path');

const PROJ = path.resolve(__dirname, '..');
const LANG_DIR = path.join(PROJ, 'src/main/resources/assets/sablefix/lang');
const SCAN_DIRS = [path.join(PROJ, 'src/main/java')];

function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.java')) out.push(p);
  }
  return out;
}

function parseLang(p) {
  const txt = fs.readFileSync(p, 'utf8');
  const m = new Map();
  const re = /"((?:[^"\\]|\\.)*)"\s*:\s*"((?:[^"\\]|\\.)*)"/g;
  let x;
  while ((x = re.exec(txt))) m.set(x[1], x[2]);
  return m;
}

const javaFiles = SCAN_DIRS.flatMap(d => walk(d));
const corpus = javaFiles.map(f => fs.readFileSync(f, 'utf8')).join('\n');
console.log(`扫描源码：${javaFiles.length} 个 .java，共 ${(corpus.length / 1024).toFixed(0)} KB`);

const en = parseLang(path.join(LANG_DIR, 'en_us.json'));
const zh = parseLang(path.join(LANG_DIR, 'zh_cn.json'));
console.log(`lang：en ${en.size} key，zh ${zh.size} key`);

// 1) 双向差异（应为空）
const onlyEn = [...en.keys()].filter(k => !zh.has(k));
const onlyZh = [...zh.keys()].filter(k => !en.has(k));
console.log(onlyEn.length === 0 && onlyZh.length === 0
  ? '双向差异：无'
  : `双向差异：en-only=${JSON.stringify(onlyEn)} zh-only=${JSON.stringify(onlyZh)}`);

// 2) 死 key
const dead = [...en.keys()].filter(k => !corpus.includes(k));
console.log(`\n引用不到的 key：${dead.length} 个`);
for (const k of dead) console.log(`  ${k}  =  ${en.get(k)}`);

// 3) 占位符数量对照（en 与 zh 必须一致，否则运行时 %s 会错位/漏填）
let mismatch = 0;
for (const [k, v] of en) {
  const z = zh.get(k);
  if (z === undefined) continue;
  const ce = (v.match(/%s/g) || []).length;
  const cz = (z.match(/%s/g) || []).length;
  if (ce !== cz) {
    console.log(`  占位符不一致 ${k}: en=${ce} zh=${cz}`);
    mismatch++;
  }
}
console.log(`\n占位符数量不一致：${mismatch} 个`);
process.exit(dead.length === 0 && mismatch === 0 && onlyEn.length === 0 && onlyZh.length === 0 ? 0 : 1);
