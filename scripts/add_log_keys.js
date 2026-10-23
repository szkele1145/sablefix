// 给 en_us.json / zh_cn.json 追加 6 个"运行日志"key（十轮审计#77）。
// 这些 key 供 ModLog.infoT 使用（进面板 + 控制台），不带 § 格式码（日志不用颜色码）。
// 用法：node scripts/add_log_keys.js
const fs = require('fs');
const DIR = 'F:/vibe coding工程/sfx/proj/src/main/resources/assets/sablefix/lang';

const keys = [
  ['sablefix.scan.registry_cleared',
   '%s file(s) that are healthy again were taken off the damage list',
   '%s 个已恢复健康的文件从损伤登记簿移除'],
  ['sablefix.scan.unmask_ok',
   'Unlocked %s stale read-only sublevel file(s)',
   '%s 个残留只读的子层级文件已恢复可写'],
  ['sablefix.watch.save_observer_up',
   'Save observer up (polls every 30 s; records a stall only after it crosses a full poll)',
   '保存观察者启动（每 30s 轮询保存 future，跨过一轮仍卡住才登记）'],
  ['sablefix.net.channel_ok',
   'Network channel registered',
   '网络通道已注册'],
  ['sablefix.cmd.backup_deleted_log',
   'Backup deleted: %s',
   '已删除备份：%s'],
  ['sablefix.cmd.backup_purged_log',
   'Backups purged: %s deleted, %s failed',
   '备份清理完成：删除 %s 个，失败 %s 个'],
];

function patch(file, col) {
  const p = `${DIR}/${file}`;
  const lines = fs.readFileSync(p, 'utf8').split(/\r?\n/);
  let lastKey = -1;
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].trim().startsWith('"sablefix.')) lastKey = i;
  }
  if (lastKey < 0) throw new Error('找不到 key 行: ' + file);
  const add = [];
  for (const k of keys) {
    const exists = lines.some(l => l.trim().startsWith('"' + k[0] + '"'));
    if (!exists) add.push(`  "${k[0]}": "${k[col]}",`);
  }
  if (!add.length) { console.log(file + ': 全部已存在，跳过'); return; }
  lines.splice(lastKey, 0, ...add);
  fs.writeFileSync(p, lines.join('\n'), 'utf8');
  console.log(`${file}: 新增 ${add.length} 个 key`);
}

patch('en_us.json', 1);
patch('zh_cn.json', 2);
