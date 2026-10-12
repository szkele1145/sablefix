// 列出 + 删除 gallery 图片
const https = require('https');
const t = require('fs').readFileSync('D:/sfx/.mrtoken', 'utf8').trim();
const H = { Authorization: t, 'User-Agent': 'sablefix-maint/1.0' };

function req(method, path, body) {
  return new Promise((resolve, reject) => {
    const r = https.request({ hostname: 'api.modrinth.com', path, method, headers: { ...H, ...(body ? { 'Content-Type': 'image/png', 'Content-Length': body.length } : {}) } }, res => {
      let d = '';
      res.on('data', c => d += c);
      res.on('end', () => resolve({ status: res.statusCode, text: d }));
    });
    r.on('error', reject);
    if (body) r.write(body);
    r.end();
  });
}

(async () => {
  if (process.argv[2] === 'delete') {
    const id = process.argv[3];
    const r = await req('DELETE', '/v2/project/sablefixcore/gallery/' + id);
    console.log('DELETE', id, '->', r.status);
    return;
  }
  const r = await req('GET', '/v2/project/sablefixcore/gallery');
  let arr;
  try { arr = JSON.parse(r.text); } catch { console.log('HTTP', r.status, r.text.substring(0, 200)); return; }
  if (!Array.isArray(arr)) { console.log('HTTP', r.status, JSON.stringify(arr).substring(0, 200)); return; }
  console.log('gallery images:', arr.length);
  for (const g of arr) console.log(g.id, '| featured:', g.featured, '|', g.title);
})();
