// 登录流程契约校验：wx-login 未绑定 422、bind-wechat 形状（mock 与后端 AuthController 同口径）
const path = require('path')
const assert = require('assert')
const ROOT = path.join(__dirname, '..')

global.wx = {
  getStorageSync: () => null,
  setStorageSync: () => {},
  removeStorageSync: () => {}
}
const { mockRequest } = require(path.join(ROOT, 'mock/server.js'))

function req(method, url, data) {
  return mockRequest({ method, url, data })
}

/** 断言请求以指定业务码失败（mock 与后端同口径：throw {code,message}） */
async function expectCode(code, method, url, data) {
  try {
    const r = await req(method, url, data)
    throw new assert.AssertionError({ message: '期望 ' + code + '，实际成功返回 ' + JSON.stringify(r).slice(0, 120) })
  } catch (e) {
    if (e instanceof assert.AssertionError) throw e
    assert.strictEqual(e && e.code, code, '期望 code=' + code + '，实际 ' + JSON.stringify(e))
    return e
  }
}

const results = []
async function check(name, fn) {
  try { await fn(); results.push({ name, ok: true }) } catch (e) { results.push({ name, ok: false, err: e.message }) }
}

;(async () => {
  await check('wx-login UNBOUND → 422 未绑定', async () => {
    await expectCode(422, 'POST', '/auth/wx-login', { code: 'UNBOUND' })
  })

  await check('wx-login UNBOUND 422 文案与后端一致', async () => {
    const e = await expectCode(422, 'POST', '/auth/wx-login', { code: 'UNBOUND' })
    assert.strictEqual(e.message, '该微信尚未绑定账号，请先用账号密码登录并绑定微信')
  })

  await check('wx-login 普通 code → 登录载荷', async () => {
    const r = await req('POST', '/auth/wx-login', { code: 'any-code' })
    assert(r && typeof r.token === 'string' && r.token.length > 0, 'wx-login 未返回 token')
  })

  await check('bind-wechat 带 code → ok', async () => {
    const r = await req('POST', '/auth/bind-wechat', { code: 'c1' })
    assert.strictEqual(r.ok, true)
  })

  // ── 预览页路由与未登录返回（小程序端）──
  await check('app.json 注册 pages/preview/index', async () => {
    const appJson = JSON.parse(require('fs').readFileSync(path.join(ROOT, 'app.json'), 'utf8'))
    assert(appJson.pages.indexOf('pages/preview/index') > -1, 'app.json pages 缺 pages/preview/index')
  })

  await check('routes.js 预览页 auth:false', async () => {
    const { ROUTES } = require(path.join(ROOT, 'config/routes.js'))
    const meta = ROUTES['/pages/preview/index']
    assert(meta, 'routes.js 缺 /pages/preview/index')
    assert.strictEqual(meta.auth, false, '预览页必须 auth:false')
  })

  await check('back() 未登录无栈 → reLaunch 预览页（不死循环）', async () => {
    const calls = []
    global.getCurrentPages = () => [] // 无返回栈（入口页）
    global.wx.getStorageSync = () => '' // 未登录（无 token）
    global.wx.reLaunch = (o) => { calls.push(['reLaunch', o.url]) }
    global.wx.navigateBack = () => { calls.push(['navigateBack']) }
    delete require.cache[require.resolve(path.join(ROOT, 'utils/auth.js'))]
    delete require.cache[require.resolve(path.join(ROOT, 'utils/nav.js'))]
    const nav = require(path.join(ROOT, 'utils/nav.js'))
    nav.back()
    assert.deepStrictEqual(calls, [['reLaunch', '/pages/preview/index']],
      '未登录无栈返回应去预览页，实际 ' + JSON.stringify(calls))
  })

  await check('back() 有栈 → navigateBack（行为不变）', async () => {
    const calls = []
    global.getCurrentPages = () => [{ route: 'pages/preview/index' }, { route: 'pages/login/index' }]
    global.wx.reLaunch = (o) => { calls.push(['reLaunch', o.url]) }
    global.wx.navigateBack = () => { calls.push(['navigateBack']) }
    delete require.cache[require.resolve(path.join(ROOT, 'utils/nav.js'))]
    const nav = require(path.join(ROOT, 'utils/nav.js'))
    nav.back()
    assert.deepStrictEqual(calls, [['navigateBack']], '有栈应 navigateBack，实际 ' + JSON.stringify(calls))
  })

  await check('back() 已登录无栈 → reLaunch 工作台/兜底（不回预览页）', async () => {
    const calls = []
    global.getCurrentPages = () => []
    global.wx.getStorageSync = (k) => (k === 'em_token' ? 'tok-1' : '')
    global.wx.reLaunch = (o) => { calls.push(['reLaunch', o.url]) }
    global.wx.navigateBack = () => { calls.push(['navigateBack']) }
    delete require.cache[require.resolve(path.join(ROOT, 'utils/auth.js'))]
    delete require.cache[require.resolve(path.join(ROOT, 'utils/nav.js'))]
    const nav = require(path.join(ROOT, 'utils/nav.js'))
    nav.back()
    assert.strictEqual(calls.length, 1, '应恰好一次 reLaunch')
    assert(calls[0][0] === 'reLaunch' && calls[0][1] !== '/pages/preview/index',
      '已登录无栈不应回预览页，实际 ' + JSON.stringify(calls))
  })

  const failed = results.filter((r) => !r.ok)
  results.forEach((r) => console.log((r.ok ? 'PASS' : 'FAIL') + '  ' + r.name + (r.ok ? '' : '  → ' + r.err)))
  console.log(failed.length ? failed.length + '/' + results.length + ' 失败' : '全部 ' + results.length + ' 项通过')
  process.exit(failed.length ? 1 : 0)
})().catch((e) => { console.error('脚本异常', e); process.exit(1) })
