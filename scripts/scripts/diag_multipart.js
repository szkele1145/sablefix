// 诊断：打印 gallery_upload 实际构造的 multipart body 首尾
const fs = require('fs');
const img = fs.readFileSync('D:/sfx/shots/r4/mini.png');
const boundary = '----sfxgal' + Date.now();
const head = '--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="image.png"\r\nContent-Type: image/png\r\n\r\n';
const body = Buffer.concat([Buffer.from(head), img, Buffer.from('\r\n--' + boundary + '--\r\n')]);
console.log('total:', body.length);
console.log('head:', JSON.stringify(body.slice(0, 130).toString('latin1')));
console.log('tail:', JSON.stringify(body.slice(-40).toString('latin1')));
// 对比 gallery_upload.js 里构造的差异（逐字段）
const t = fs.readFileSync('D:/sfx/.mrtoken', 'utf8').trim();
console.log('token prefix:', t.slice(0, 4), 'len:', t.length);
