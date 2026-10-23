// 用 RCON 对一个**运行中的**专用服务器执行只读命令，把响应打出来。
// 用于真实环境验证命令层（13 个子命令）——之前从未真跑过。
//
// 用法：node scripts/rcon_probe.js [port] [password]
// 前置：
//   1) run/server.properties 里 enable-rcon=true、rcon.password=...、rcon.port=25575
//   2) 服务器已启动到 "Done ("
// 只发只读命令：check / list / tickets / backups / help（**不发** repair/purge/fix 这类会改世界的）
const net = require('net');

const PORT = parseInt(process.argv[2] || '25575', 10);
const PASS = process.argv[3] || 'sfxtest';
const HOST = '127.0.0.1';

const TYPE_LOGIN = 3, TYPE_COMMAND = 2;

let buf = Buffer.alloc(0);
let nextId = 1;
const pending = new Map();   // requestId -> resolve

function packet(id, type, body) {
  const payload = Buffer.from(body, 'utf8');
  const pkt = Buffer.alloc(4 + 4 + 4 + payload.length + 2);
  pkt.writeInt32LE(4 + 4 + payload.length + 2, 0);   // length = id + type + body + 2 nulls
  pkt.writeInt32LE(id, 4);
  pkt.writeInt32LE(type, 8);
  payload.copy(pkt, 12);
  pkt.writeInt16LE(0, 12 + payload.length);
  return pkt;
}

function send(sock, type, body) {
  const id = nextId++;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { pending.delete(id); reject(new Error('timeout id=' + id)); }, 15000);
    pending.set(id, (v) => { clearTimeout(timer); resolve(v); });
    sock.write(packet(id, type, body));
  });
}

function onData(chunk) {
  buf = Buffer.concat([buf, chunk]);
  while (buf.length >= 4) {
    const len = buf.readInt32LE(0);
    if (buf.length < 4 + len) break;
    const body = buf.slice(4, 4 + len);
    const id = body.readInt32LE(0);
    const type = body.readInt32LE(4);
    const text = body.slice(8, body.length - 2).toString('utf8');
    buf = buf.slice(4 + len);
    const cb = pending.get(id);
    if (cb) { pending.delete(id); cb({ type, text }); }
  }
}

(async () => {
  const sock = net.createConnection({ host: HOST, port: PORT });
  sock.on('data', onData);
  sock.on('error', e => { console.error('连接失败:', e.message); process.exit(1); });

  await new Promise((res, rej) => { sock.once('connect', res); sock.once('error', rej); });
  const login = await send(sock, TYPE_LOGIN, PASS);
  if (login.type === -1) { console.error('RCON 认证失败'); process.exit(1); }
  console.log('RCON 已连接\n');

  // 命令列表：第 4 个参数可传逗号分隔的自定义命令；默认只跑只读命令。
  const custom = process.argv[4];
  const cmds = custom
    ? custom.split(',').map(s => s.trim()).filter(Boolean)
    : [
        'sablefix help',
        'sablefix check',
        'sablefix list',
        'sablefix tickets',
        'sablefix backup list',
        'sablefix repair-all dry',
        'sablefix tickets-purge dry',
        'sablefix diag 0 64 0',
      ];

  let problems = 0;
  for (const c of cmds) {
    const r = await send(sock, TYPE_COMMAND, c);
    const text = r.text.replace(/\u00a7./g, '').trim();   // 去掉 MC 颜色码
    console.log('──────── /' + c + ' ────────');
    console.log(text || '(空响应)');
    if (/Exception|NullPointer|Error:|失败的堆栈/.test(text)) {
      console.log('  ^^^ 检测到异常文本');
      problems++;
    }
    console.log('');
  }
  sock.end();
  console.log(problems === 0 ? '命令探测完成：未发现异常文本' : `命令探测完成：${problems} 条命令出现异常文本`);
  process.exit(problems === 0 ? 0 : 1);
})().catch(e => { console.error('探测失败:', e.message); process.exit(1); });
