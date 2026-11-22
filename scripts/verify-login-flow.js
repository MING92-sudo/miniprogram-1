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

  // ── 登录页行为（注入假 services/auth，捕获页面跳转与绑定调用）──
  async function withLoginPage(loginCode, fn) {
    const calls = []
    const wxCalls = { modal: [], toast: [], navTo: [], reLaunch: [] }
    const storage = {}
    global.wx = {
      getStorageSync: (k) => (k in storage ? storage[k] : ''),
      setStorageSync: (k, v) => { storage[k] = v },
      removeStorageSync: (k) => { delete storage[k] },
      login: ({ success }) => success({ code: loginCode }),
      showModal: (o) => { wxCalls.modal.push(o) },
      showToast: (o) => { wxCalls.toast.push(o) },
      navigateTo: (o) => { wxCalls.navTo.push(o.url) },
      reLaunch: (o) => { wxCalls.reLaunch.push(o.url) }
    }
    global.getApp = () => ({ setAuth() {}, globalData: {} })
    global.Page = (opts) => { global.__lastPage = opts }
    const authPath = require.resolve(path.join(ROOT, 'services/auth.js'))
    require.cache[authPath] = {
      id: authPath, filename: authPath, loaded: true,
      exports: {
        accountLogin: async (phone) => {
          if (phone === '13800000012') return { needSelectUnit: true, selectionToken: 't1', units: [{ id: 'uu_1' }] }
          return { token: 'tok-1', userInfo: { name: '测试' }, role: 'MAINT_TECH', roles: ['MAINT_TECH'], perms: [], home: '/pages/maint/home/index', principalType: 'EMPLOYEE' }
        },
        // 422 = openid 未绑定（与 code 是否过期无关）
        wxLogin: async () => { const e = new Error('该微信尚未绑定账号，请先用账号密码登录并绑定微信'); e.code = 422; throw e },
        // bindWeChat 是 needAuth 接口：调用时 token 必须已写入（复现生产 401 的顺序回归）
        bindWeChat: async (code) => {
          if (!storage.em_token) throw new Error('bindWeChat 调用时 token 未写入（needAuth 接口会 401）')
          calls.push(['bindWeChat', code])
          if (code === 'EXPIRED') { const e = new Error('微信登录凭证已失效'); e.code = 422; throw e }
          return { ok: true }
        },
        applyLoginResult: (d) => { storage.em_token = d.token; return d }
      }
    }
    const pagePath = path.join(ROOT, 'pages/login/index.js')
    delete require.cache[require.resolve(pagePath)]
    require(pagePath)
    const def = global.__lastPage
    const page = Object.assign({}, def)
    page.setData = function (d) { Object.assign(this.data, d) }
    page.data = JSON.parse(JSON.stringify(def.data))
    page.onLoad()
    try { await fn(page, { calls, wxCalls, storage }) } finally { delete require.cache[authPath] }
  }

  await check('登录页协议链接 → 进入协议/隐私页', async () => {
    await withLoginPage('CODE1', async (page, { wxCalls }) => {
      page.openAgreement(); page.openPrivacy()
      assert.deepStrictEqual(wxCalls.navTo, ['/pages/login/agreement?type=agreement', '/pages/login/agreement?type=privacy'])
    })
  })

  await check('暂不登录 → 进入预览页', async () => {
    await withLoginPage('CODE1', async (page, { wxCalls }) => {
      page.browseAsGuest()
      assert.deepStrictEqual(wxCalls.navTo, ['/pages/preview/index'])
    })
  })

  await check('微信登录未绑定 422 → 引导且不进入应用', async () => {
    await withLoginPage('UNBOUND', async (page, { wxCalls }) => {
      page.data.agreed = true
      await page.onWechatLogin()
      assert.strictEqual(wxCalls.modal.length, 1, '应弹未绑定引导，实际 ' + JSON.stringify(wxCalls.modal))
      assert(/未绑定/.test(wxCalls.modal[0].content), '引导文案应含「未绑定」')
      assert.strictEqual(wxCalls.reLaunch.length, 0, '未绑定不应进入应用')
    })
  })

  await check('未绑定后账号密码登录 → 自动绑定微信并进入工作台', async () => {
    await withLoginPage('UNBOUND', async (page, { calls, wxCalls, storage }) => {
      page.data.agreed = true
      await page.onWechatLogin()
      page.data.phone = '13800000001'; page.data.password = 'x'
      await page.onSubmit()
      await new Promise((r) => setTimeout(r, 350))
      assert(calls.some((c) => c[0] === 'bindWeChat' && c[1] === 'UNBOUND'), '应自动 bindWeChat(UNBOUND)，实际 ' + JSON.stringify(calls))
      assert.strictEqual(storage.em_wechat_bound, true, '绑定成功应写 em_wechat_bound')
      assert.deepStrictEqual(wxCalls.reLaunch, ['/pages/maint/home/index'])
    })
  })

  await check('绑定失败（code 过期）→ 提示但不阻断登录', async () => {
    await withLoginPage('EXPIRED', async (page, { calls, wxCalls, storage }) => {
      page.data.agreed = true
      await page.onWechatLogin()
      page.data.phone = '13800000001'; page.data.password = 'x'
      await page.onSubmit()
      await new Promise((r) => setTimeout(r, 350))
      assert(calls.some((c) => c[0] === 'bindWeChat'), '应尝试绑定')
      assert.strictEqual(storage.em_wechat_bound, undefined, '绑定失败不写 em_wechat_bound')
      assert(wxCalls.toast.some((t) => /我的/.test(t.title || '')), '应提示可去「我的」绑定')
      assert.deepStrictEqual(wxCalls.reLaunch, ['/pages/maint/home/index'], '登录不应被阻断')
    })
  })

  await check('客户多单位账号 → 选单位分流，不触发自动绑定', async () => {
    await withLoginPage('UNBOUND', async (page, { calls, wxCalls }) => {
      page.data.agreed = true
      await page.onWechatLogin()
      page.data.phone = '13800000012'; page.data.password = 'x'
      await page.onSubmit()
      await new Promise((r) => setTimeout(r, 350))
      assert(!calls.some((c) => c[0] === 'bindWeChat'), 'needSelectUnit 分支不应触发绑定，实际 ' + JSON.stringify(calls))
      assert(wxCalls.navTo.some((u) => u.indexOf('/pages/client/select-unit/index') === 0), '应去选单位页')
    })
  })

  // ── 预览页导航栏单一（全局 custom 导航下不允许再叠原生栏）──
  await check('预览页无双导航栏（custom nav-bar ↔ 原生栏二选一）', async () => {
    const fs = require('fs')
    const json = JSON.parse(fs.readFileSync(path.join(ROOT, 'pages/preview/index.json'), 'utf8'))
    const wxml = fs.readFileSync(path.join(ROOT, 'pages/preview/index.wxml'), 'utf8')
    if (wxml.indexOf('<nav-bar') > -1) {
      assert(json.navigationStyle !== 'default', 'wxml 已渲染 custom nav-bar，json 又开原生导航栏 → 双导航栏')
    }
  })

  const failed = results.filter((r) => !r.ok)
  results.forEach((r) => console.log((r.ok ? 'PASS' : 'FAIL') + '  ' + r.name + (r.ok ? '' : '  → ' + r.err)))
  console.log(failed.length ? failed.length + '/' + results.length + ' 失败' : '全部 ' + results.length + ' 项通过')
  process.exit(failed.length ? 1 : 0)
})().catch((e) => { console.error('脚本异常', e); process.exit(1) })
