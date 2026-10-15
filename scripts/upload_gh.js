// GitHub Release 发布：建 release（读 ghrel.json）+ 上传规范命名的 jar 资产。
// 用法：node upload_gh.js <version>
// 幂等：已存在同 tag 的 release 就复用，资产已存在就跳过（不重复上传）。
// 纪律：token 只从文件读取，日志只打印掩码前缀与 HTTP 状态码，绝不输出 token 明文。
const https = require('https');
const fs = require('fs');

const VER = process.argv[2];
if (!/^\d+\.\d+\.\d+$/.test(VER || '')) {
  console.error('用法: node upload_gh.js <version>   例: node upload_gh.js 1.5.4');
  process.exit(1);
}

const OWNER = 'szkele1145';
const REPO = 'sablefix';
const TAG = `v${VER}`;
const JAR = `sablefix-${VER}-1.21.1-neoforge.jar`;
const PROJ = 'F:/vibe coding工程/sfx/proj';
const TOKEN = fs.readFileSync('F:/vibe coding工程/sfx/.ghtoken', 'utf8').trim();
console.log(`token ${TOKEN.slice(0, 4)}*** (len=${TOKEN.length})  repo ${OWNER}/${REPO}  tag ${TAG}`);

function api(method, path, body, host, ctype) {
  return new Promise((resolve, reject) => {
    const payload = body ? (Buffer.isBuffer(body) ? body : Buffer.from(JSON.stringify(body))) : null;
    const req = https.request({
      hostname: host || 'api.github.com',
      path,
      method,
      headers: Object.assign({
        Authorization: `Bearer ${TOKEN}`,
        'User-Agent': `sablefix-release/${VER}`,
        Accept: 'application/vnd.github+json',
      }, payload ? { 'Content-Type': ctype || 'application/json', 'Content-Length': payload.length } : {}),
    }, res => {
      let d = '';
      res.on('data', c => d += c);
      res.on('end', () => {
        let json = null;
        try { json = JSON.parse(d); } catch (e) { /* 非 JSON 响应 */ }
        resolve({ status: res.statusCode, json, raw: d });
      });
    });
    req.on('error', reject);
    if (payload) req.write(payload);
    req.end();
  });
}

(async () => {
  // 1) 建 release（已存在则复用）
  let rel = (await api('GET', `/repos/${OWNER}/${REPO}/releases/tags/${TAG}`)).json;
  if (rel && rel.id) {
    console.log(`release ${TAG} 已存在 (id=${rel.id})，复用`);
  } else {
    const payload = JSON.parse(fs.readFileSync(`${PROJ}/ghrel.json`, 'utf8'));
    if (payload.tag_name !== TAG) throw new Error(`ghrel.json tag 不匹配: ${payload.tag_name} != ${TAG}`);
    const r = await api('POST', `/repos/${OWNER}/${REPO}/releases`, payload);
    if (!r.json || !r.json.id) throw new Error(`建 release 失败 HTTP ${r.status}: ${r.raw.slice(0, 300)}`);
    rel = r.json;
    console.log(`release ${TAG} 已创建 (id=${rel.id}) status=${r.status}`);
  }

  // 2) 上传 jar 资产（同名已存在则跳过）
  const existing = (rel.assets || []).find(a => a.name === JAR);
  if (existing) {
    console.log(`资产 ${JAR} 已存在，跳过上传`);
  } else {
    const jarPath = `${PROJ}/build/libs/${JAR}`;
    if (!fs.existsSync(jarPath)) throw new Error(`构建产物缺失: ${jarPath}`);
    const jar = fs.readFileSync(jarPath);
    const r = await api('POST',
      `/repos/${OWNER}/${REPO}/releases/${rel.id}/assets?name=${encodeURIComponent(JAR)}`,
      jar, 'uploads.github.com', 'application/java-archive');
    if (!r.json || !r.json.id) throw new Error(`上传资产失败 HTTP ${r.status}: ${r.raw.slice(0, 300)}`);
    console.log(`资产已上传: ${r.json.name} (${r.json.size} bytes) state=${r.json.state} status=${r.status}`);
  }

  // 3) 复核
  const check = (await api('GET', `/repos/${OWNER}/${REPO}/releases/tags/${TAG}`)).json;
  console.log(`复核: ${check.tag_name} assets=[${(check.assets || []).map(a => a.name).join(', ')}]`);
})();
