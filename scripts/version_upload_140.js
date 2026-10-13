// Modrinth 版本上传（multipart, node 直传绕 shell 转义坑）
const https = require('https');
const fs = require('fs');

const t = fs.readFileSync('F:/vibe coding工程/sfx/.mrtoken', 'utf8').trim();
const boundary = '----sfxrel' + Date.now();
const jar = fs.readFileSync('F:/sfxbuild/build/libs/sablefix-1.4.0.jar');
const dataPart = fs.readFileSync('F:/vibe coding工程/sfx/proj/mrdata.json');

const body = Buffer.concat([
  Buffer.from('--' + boundary + '\r\nContent-Disposition: form-data; name="data"\r\nContent-Type: application/json\r\n\r\n'),
  dataPart,
  Buffer.from('\r\n--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="sablefix-1.4.0.jar"\r\nContent-Type: application/java-archive\r\n\r\n'),
  jar,
  Buffer.from('\r\n--' + boundary + '--\r\n')
]);

const req = https.request({
  hostname: 'api.modrinth.com', path: '/v2/version', method: 'POST',
  headers: { 'Authorization': t, 'User-Agent': 'sablefix-release/1.4.0', 'Content-Type': 'multipart/form-data; boundary=' + boundary, 'Content-Length': body.length }
}, res => {
  let d = '';
  res.on('data', c => d += c);
  res.on('end', () => {
    try { const j = JSON.parse(d); console.log('✅ Modrinth 1.4.0: id=' + j.id + ' status=' + j.status); }
    catch (e) { console.log('HTTP', res.statusCode, d.substring(0, 250)); }
  });
});
req.on('error', e => console.log('ERR', e.message));
req.write(body);
req.end();
