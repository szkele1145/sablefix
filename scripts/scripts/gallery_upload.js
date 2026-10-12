// 上传 Modrinth Gallery 图片：node gallery_upload.js <imgPath> <title> <desc> [featured]
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
const boundary = '----sfxgal' + Date.now();
const body = Buffer.concat([
  Buffer.from('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="image.' + ext + '"\r\nContent-Type: image/' + (ext === 'png' ? 'png' : 'jpeg') + '\r\n\r\n'),
  img,
  Buffer.from('\r\n--' + boundary + '--\r\n')
]);
const req = https.request({
  hostname: 'api.modrinth.com',
  path: '/v2/project/sablefixcore/gallery' + qs,
  method: 'POST',
  headers: {
    'Authorization': t,
    'User-Agent': 'sablefix-release/1.4.0',
    'Content-Type': 'multipart/form-data; boundary=' + boundary,
    'Content-Length': body.length,
  }
}, res => {
  let d = '';
  res.on('data', c => d += c);
  res.on('end', () => console.log('HTTP', res.statusCode, d.substring(0, 200)));
});
req.on('error', e => console.log('ERR', e.message));
req.write(body);
req.end();
