// 逆地址解析鉴权回归：/location/reverse 必须带登录态（needAuth:true，request.js 默认），
// 且 401/失败永不 reject（回退坐标），mock 模式不触网。调用方仅限登录后员工包。
const assert = require('assert')
const fs = require('fs')
const path = require('path')
const Module = require('module')

const ROOT = path.join(__dirname, '..')
const LOCATION_PATH = path.resolve(ROOT, 'pkg-maintenance/services/location.js')

function loadLocation(useMock, getImpl, getCalls) {
  delete require.cache[LOCATION_PATH]
  const originalLoad = Module._load
  Module._load = function (request, parent) {
    if (parent && parent.filename === LOCATION_PATH) {
      if (request === '../../utils/request') {
        return { get: (url, params, opts) => { getCalls.push({ url, params, opts }); return getImpl() } }
      }
      if (request === '../../config/index') return { useMock }
    }
    return originalLoad.call(this, request, parent)
  }
  const mod = require(LOCATION_PATH)
  Module._load = originalLoad
  return mod
}

async function main() {
  // 1) 生产路径：必须 needAuth:true（缺省会吞 401 → 全局登出误伤签到/拍照链路）
  {
    const calls = []
    const location = loadLocation(false, () => Promise.reject({ code: 401, message: '未登录' }), calls)
    const text = await location.reverseGeocode(29.56, 106.55)
    assert.strictEqual(calls.length, 1, '未发起 /location/reverse 请求')
    assert.strictEqual(calls[0].url, '/location/reverse')
    // 契约：不得显式覆盖 needAuth:false（省略或 true 均可，request.js 默认 true）
    assert.ok(!(calls[0].opts && calls[0].opts.needAuth === false),
      'reverseGeocode 覆盖了 needAuth:false——401 会触发 request.js 全局登出')
    assert.strictEqual(text, '29.56000, 106.55000', '401 未回退坐标展示')
  }

  // 2) mock 模式：不触网直接返回演示地址
  {
    const calls = []
    const location = loadLocation(true, () => Promise.reject(new Error('不应触网')), calls)
    const text = await location.reverseGeocode(29.56, 106.55)
    assert.strictEqual(calls.length, 0, 'mock 模式不应发起请求')
    assert.strictEqual(text, '重庆市渝北区龙山一路 88 号世纪大厦')
  }

  // 3) 调用方场景：reverseGeocode 仅允许出现在登录后的员工包（pkg-maintenance）
  const offenders = []
  for (const dir of ['pages', 'pkg-client', 'pkg-admin', 'pkg-handover']) {
    const abs = path.join(ROOT, dir)
    if (!fs.existsSync(abs)) continue
    const stack = [abs]
    while (stack.length) {
      const cur = stack.pop()
      for (const entry of fs.readdirSync(cur, { withFileTypes: true })) {
        const full = path.join(cur, entry.name)
        if (entry.isDirectory()) { stack.push(full); continue }
        if (!entry.name.endsWith('.js')) continue
        const src = fs.readFileSync(full, 'utf8')
        if (src.includes('services/location')) {
          offenders.push(path.relative(ROOT, full))
        }
      }
    }
  }
  assert.deepStrictEqual(offenders, [],
    '登录前/非员工包页面引用 location 服务（needAuth:true 后登录前调用会 401）: ' + JSON.stringify(offenders))

  console.log('verify-location-auth: 全部 3 项通过')
}

main().then(() => process.exit(0)).catch((e) => { console.error('FAIL', e.message); process.exit(1) })
