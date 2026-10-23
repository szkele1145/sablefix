// 给 neoforge.mods.toml 追加依赖声明（十轮审计#75）。
// 用法：node scripts/add_deps_toml.js
const fs = require('fs');
const p = 'F:/vibe coding工程/sfx/proj/src/main/resources/META-INF/neoforge.mods.toml';
let t = fs.readFileSync(p, 'utf8');

if (t.includes('[[dependencies')) {
  console.log('已存在 dependencies 段，跳过');
  process.exit(0);
}

const block = [
  '',
  '',
  '# 依赖声明（十轮审计#75）：原本没有任何 [[dependencies]] 段 → 错误的 MC/NeoForge',
  '# 版本上 mod 仍会被装载并可能崩溃，而不是给出「需要 1.21.1 / NeoForge 21.1.x」的清晰提示。',
  '[[dependencies.sablefix]]',
  'modId = "neoforge"',
  'type = "required"',
  'versionRange = "[21.1.0,)"',
  'ordering = "NONE"',
  'side = "BOTH"',
  '',
  '[[dependencies.sablefix]]',
  'modId = "minecraft"',
  'type = "required"',
  'versionRange = "[1.21.1,1.21.2)"',
  'ordering = "NONE"',
  'side = "BOTH"',
  '',
].join('\n');

t = t.trimEnd() + '\n' + block;
fs.writeFileSync(p, t, 'utf8');
console.log('dependencies 段已追加');
