// 版本号统一升级工具：node bump_version.js 1.2.0
const fs = require('fs');
const v = process.argv[2];
if (!/^\d+\.\d+\.\d+$/.test(v)) { console.error('usage: node bump_version.js x.y.z'); process.exit(1); }

let g = fs.readFileSync('D:/sfx/proj/build.gradle', 'utf8');
g = g.replace(/version = '\d+\.\d+\.\d+'/, `version = '${v}'`);
fs.writeFileSync('D:/sfx/proj/build.gradle', g);

let t = fs.readFileSync('D:/sfx/proj/src/main/resources/META-INF/neoforge.mods.toml', 'utf8');
t = t.replace(/version = "\d+\.\d+\.\d+"/, `version = "${v}"`);
fs.writeFileSync('D:/sfx/proj/src/main/resources/META-INF/neoforge.mods.toml', t);

let m = fs.readFileSync('D:/sfx/proj/src/main/java/dev/sfx/sablefix/SableFixMod.java', 'utf8');
m = m.replace(/VERSION = "\d+\.\d+\.\d+"/, `VERSION = "${v}"`);
fs.writeFileSync('D:/sfx/proj/src/main/java/dev/sfx/sablefix/SableFixMod.java', m);

for (const f of ['zh_cn.json', 'en_us.json']) {
  const p = 'D:/sfx/proj/src/main/resources/assets/sablefix/lang/' + f;
  let s = fs.readFileSync(p, 'utf8');
  s = s.replace(/Sable Fix Core v\d+\.\d+\.\d+/g, `Sable Fix Core v${v}`);
  fs.writeFileSync(p, s);
}
console.log('version bumped to', v);
