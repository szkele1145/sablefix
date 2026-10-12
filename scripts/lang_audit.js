// 语言文件静态对账：代码里所有 sablefix.* key 必须在 zh_cn/en_us 都存在，无缺失/无冗余
const fs = require('fs');
const path = require('path');

const SRC = 'F:/vibe coding工程/sfx/proj/src/main/java';
const LANG_DIR = 'F:/vibe coding工程/sfx/proj/src/main/resources/assets/sablefix/lang';

function walk(dir, out = []) {
  for (const f of fs.readdirSync(dir)) {
    const p = path.join(dir, f);
    const st = fs.statSync(p);
    if (st.isDirectory()) walk(p, out);
    else if (f.endsWith('.java')) out.push(p);
  }
  return out;
}

const used = new Set();
for (const file of walk(SRC)) {
  const t = fs.readFileSync(file, 'utf8');
  for (const m of t.matchAll(/"(sablefix\.[a-z0-9_.]+)"/g)) used.add(m[1]);
}

const zh = JSON.parse(fs.readFileSync(path.join(LANG_DIR, 'zh_cn.json'), 'utf8'));
const en = JSON.parse(fs.readFileSync(path.join(LANG_DIR, 'en_us.json'), 'utf8'));
const zhKeys = new Set(Object.keys(zh)), enKeys = new Set(Object.keys(en));

let bad = 0;
for (const k of used) {
  if (!zhKeys.has(k)) { console.log('MISSING zh_cn:', k); bad++; }
  if (!enKeys.has(k)) { console.log('MISSING en_us:', k); bad++; }
}
for (const k of zhKeys) if (!enKeys.has(k)) { console.log('ZH-ONLY:', k); bad++; }
for (const k of enKeys) if (!zhKeys.has(k)) { console.log('EN-ONLY:', k); bad++; }
console.log(`used=${used.size} zh=${zhKeys.size} en=${enKeys.size} -> ${bad === 0 ? 'ALL OK' : bad + ' problems'}`);
process.exit(bad === 0 ? 0 : 1);
