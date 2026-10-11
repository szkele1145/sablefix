// Gallery 上传 v2：文件字节直接作为请求体（raw image body，非 multipart）
const https = require('https');
const fs = require('fs');

const [imgPath, title, desc, featured] = process.argv.slice(2);
const t = fs.readFileSync('D:/sfx/.mrtoken', 'utf8').trim();
const img = fs.readFileSync(imgPath);
const ext = imgPath.toLowerCase().endsWith('.png') ? 'png' : 'jpg';
const qs = '?ext=' + ext
  + '&featured=' + (featured === 'featured' ? 'true' : 'false')
  + '&title=' + encodeURIComponent(title)
  + '&description=' + encodeURIComponent(desc || '');
const req = https.request({
  hostname: 'api.modrinth.com',
  path: '/v2/project/sablefixcore/gallery' + qs,
  method: 'POST',
  headers: {
    'Authorization': t,
    'User-Agent': 'sablefix-release/1.4.0',
    'Content-Type': ext === 'png' ? 'image/png' : 'image/jpeg',
    'Content-Length': img.length,
  }
}, res => {
  let d = '';
  res.on('data', c => d += c);
  res.on('end', () => console.log('HTTP', res.statusCode, d.substring(0, 200)));
});
req.on('error', e => console.log('ERR', e.message));
req.write(img);
req.end();
