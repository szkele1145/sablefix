// Modrinth 上传（multipart）——node 文件形式绕 PowerShell 转义
// 用法：node upload_mr.js <版本号> [payload文件]（jar 名按规范拼接；payload 默认 mrdata.json）
const https = require('https');
const fs = require('fs');

const VER = process.argv[2] || '1.5.0';
const PAYLOAD = process.argv[3] || 'F:/vibe coding工程/sfx/proj/mrdata.json';
const JAR_NAME = `sablefix-${VER}-1.21.1-neoforge.jar`;
const t = fs.readFileSync('F:/vibe coding工程/sfx/.mrtoken', 'utf8').trim();
const boundary = '----sfxrel' + Date.now();
const jar = fs.readFileSync(`F:/vibe coding工程/sfx/proj/build/libs/${JAR_NAME}`);
const dataPart = fs.readFileSync(PAYLOAD);

const body = Buffer.concat([
  Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="data"\r\nContent-Type: application/json\r\n\r\n`),
  dataPart,
  Buffer.from(`\r\n--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="${JAR_NAME}"\r\nContent-Type: application/java-archive\r\n\r\n`),
  jar,
  Buffer.from(`\r\n--${boundary}--\r\n`)
]);

const req = https.request({
  hostname: 'api.modrinth.com', path: '/v2/version', method: 'POST',
  headers: { 'Authorization': t, 'User-Agent': `sablefix-release/${VER}`, 'Content-Type': 'multipart/form-data; boundary=' + boundary, 'Content-Length': body.length }
}, res => {
  let d = '';
  res.on('data', c => d += c);
  res.on('end', () => {
    try { const j = JSON.parse(d); console.log(`✅ Modrinth ${VER}: id=` + j.id + ' status=' + j.status); }
    catch (e) { console.log('HTTP', res.statusCode, d.substring(0, 250)); }
  });
});
req.on('error', e => console.log('ERR', e.message));
req.write(body);
req.end();
