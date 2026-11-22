// 请求层看门狗回归（2026-11-16 真机调试登录挂起根因修复）：
// 真机调试通道下 wx.cloud.callContainer 可能永不回调（平台限制），request 必须自行兜底 settle，
// 否则登录页 logging 恒 true、按钮禁用、无任何提示。node scripts/test-request-watchdog.js
const assert = require('assert')
const path = require('path')
const ROOT = path.join(__dirname, '..')
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// wx 桩：callContainer 默认黑洞（永不回调），可按用例改写
const calls = { reLaunch: [] }
global.wx = {
  getStorageSync: () => '',
  setStorageSync: () => {},
  removeStorageSync: () => {},
  reLaunch: (o) => calls.reLaunch.push(o && o.url),
  showToast: () => {},
  cloud: { callContainer: () => {} }
}
global.getCurrentPages = () => [{ route: 'pages/login/index' }]

const config = require(path.join(ROOT, 'config/index.js'))
// 本脚本专测 callContainer 黑洞；显式固定云通道，避免随本地 ENV='dev' 漂移。
config.cloud.enabled = true
config.useMock = false
const { post } = require(path.join(ROOT, 'utils/request.js'))
const results = []
async function check(name, fn) {
  try { await fn(); results.push({ name, ok: true }) } catch (e) { results.push({ name, ok: false, err: e.message }) }
}
// 3s 内不 settle 即判挂起（看门狗应在 timeout+2s 内 reject）
function withHangGuard(p) {
  return Promise.race([p, sleep(3000).then(() => { throw new Error('HANG: 请求未在 3s 内 settle') })])
}

;(async () => {
  await check('callContainer 黑洞 → 看门狗超时 reject（不挂起）', async () => {
    global.wx.cloud.callContainer = () => {} // 永不回调
    try {
      await withHangGuard(post('/auth/login', { phone: 'x', password: 'y' }, { needAuth: false, timeout: 300 }))
      assert(false, '应当 reject')
    } catch (e) {
      assert(/超时/.test(e.message), '错误文案应含超时提示，实际: ' + e.message)
      assert.strictEqual(e.code, -1)
    }
  })

  await check('正常 success 仍 resolve，且看门狗不重复 settle', async () => {
    global.wx.cloud.callContainer = (o) => setTimeout(() => o.success({ statusCode: 200, data: { code: 0, data: { token: 't' } } }), 50)
    const data = await withHangGuard(post('/auth/login', {}, { needAuth: false, timeout: 300 }))
    assert.strictEqual(data.token, 't')
    await sleep(600) // 看门狗到期后不得产生二次 reject/unhandled
  })

  await check('迟到回调（看门狗已 reject 后）被忽略', async () => {
    let lateSuccess
    global.wx.cloud.callContainer = (o) => { lateSuccess = o.success }
    try {
      await withHangGuard(post('/auth/login', {}, { needAuth: false, timeout: 200 }))
      assert(false, '应当 reject')
    } catch (e) { assert(/超时/.test(e.message)) }
    lateSuccess({ statusCode: 200, data: { code: 0, data: {} } }) // 不得抛 unhandled
    await sleep(100)
  })

  await check('401 业务码仍 reject 且文案透传（登录页可显示）', async () => {
    global.wx.cloud.callContainer = (o) => setTimeout(() => o.success({ statusCode: 401, data: { code: 401, message: '账号或密码错误' } }), 30)
    try {
      await withHangGuard(post('/auth/login', {}, { needAuth: false, timeout: 300 }))
      assert(false, '应当 reject')
    } catch (e) {
      assert.strictEqual(e.message, '账号或密码错误')
      assert.strictEqual(calls.reLaunch.length, 0, '已在登录页不得再 reLaunch')
    }
  })

  await check('fail 回调仍走网络异常 reject', async () => {
    global.wx.cloud.callContainer = (o) => setTimeout(() => o.fail({ errMsg: 'cloud.callContainer:fail' }), 30)
    try {
      await withHangGuard(post('/auth/login', {}, { needAuth: false, timeout: 300 }))
      assert(false, '应当 reject')
    } catch (e) { assert.strictEqual(e.code, -1) }
  })

  const failed = results.filter((r) => !r.ok)
  results.forEach((r) => console.log((r.ok ? 'PASS' : 'FAIL') + '  ' + r.name + (r.ok ? '' : '  → ' + r.err)))
  console.log(failed.length ? failed.length + '/' + results.length + ' 失败' : '全部 ' + results.length + ' 项通过')
  process.exit(failed.length ? 1 : 0)
})()