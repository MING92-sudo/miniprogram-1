/**
 * 登录链路诊断：区分「后端拒绝」「前端逻辑」「守卫误跳」三类原因
 * 用法：node mp-diag.js <port> <phone> <password>
 */
const automator = require('C:/Users/18223/AppData/Local/Temp/wxauto/node_modules/miniprogram-automator')
const PORT = Number(process.argv[2] || 9420)
const PHONE = process.argv[3]
const PASS = process.argv[4] || '123456'

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function main() {
  const mini = await automator.connect({ wsEndpoint: `ws://127.0.0.1:${PORT}` })
  const logs = []
  mini.on('console', (m) => {
    const a = (m && (m.args || m.content)) || ''
    logs.push(`[${(m && (m.type || m.level)) || 'log'}] ${Array.isArray(a) ? a.join(' ') : a}`)
  })
  mini.on('exception', (e) => logs.push('[EXCEPTION] ' + (e && (e.message || JSON.stringify(e)))))

  // 清态
  await mini.evaluate(function () {
    try {
      wx.clearStorageSync()
    } catch (e) {}
  })

  // ① 直连后端（绕过所有前端逻辑）
  const direct = await mini.evaluate(
    function (args) {
      return new Promise((resolve) => {
        wx.request({
          url: 'http://localhost:8080/api/auth/login',
          method: 'POST',
          header: { 'Content-Type': 'application/json' },
          data: { phone: args.phone, password: args.password },
          success: (r) => resolve({ status: r.statusCode, body: r.data }),
          fail: (e) => resolve({ fail: String(e && e.errMsg) })
        })
      })
    },
    { phone: PHONE, password: PASS }
  )
  const d = direct && direct.body && direct.body.data
  console.log('① 直连接口：', JSON.stringify({
    status: direct && direct.status,
    code: direct && direct.body && direct.body.code,
    msg: direct && direct.body && direct.body.message,
    principalType: d && d.principalType,
    home: d && d.home,
    roles: d && d.roles,
    needSelectUnit: d && d.needSelectUnit,
    tokenLen: d && d.token ? String(d.token).length : 0
  }))

  // ② 走页面 onSubmit（全程用 currentPage 重新取句柄，避免 reLaunch 后旧句柄失效）
  const login = await mini.reLaunch('/pages/login/index')
  await login.waitFor(1500)
  await login.setData({ phone: PHONE, password: PASS, agreed: true })
  const beforeData = await login.data()
  await login.callMethod('onSubmit')
  await sleep(400)
  const mid = await mini.evaluate(function () {
    const p = getCurrentPages().pop()
    return { route: p && p.route, logging: p && p.data && p.data.logging }
  })
  await sleep(2600)
  const after = await mini.evaluate(function () {
    const p = getCurrentPages().pop()
    return {
      route: p && p.route,
      logging: p && p.data && p.data.logging,
      tokenLen: String(wx.getStorageSync('em_token') || '').length,
      role: wx.getStorageSync('em_role') || '',
      principal: wx.getStorageSync('em_principal') || '',
      home: wx.getStorageSync('em_home') || ''
    }
  })
  console.log('② onSubmit：', JSON.stringify({
    beforeAgreed: beforeData.agreed,
    beforePhone: beforeData.phone,
    loggingMid: mid.logging,
    routeMid: mid.route,
    routeAfter: after.route,
    loggingAfter: after.logging,
    tokenLen: after.tokenLen,
    role: after.role,
    principal: after.principal,
    home: after.home
  }))

  // ③ toast 文本（hook showToast 抓一次；用 currentPage 取句柄）
  await mini.mockWxMethod('showToast', function (o) {
    wx.setStorageSync('__last_toast', JSON.stringify(o))
  })
  const p2 = await mini.reLaunch('/pages/login/index')
  await p2.waitFor(1200)
  await p2.setData({ phone: PHONE, password: PASS, agreed: true })
  await p2.callMethod('onSubmit')
  await sleep(2400)
  const toast = await mini.evaluate(function () {
    return wx.getStorageSync('__last_toast') || ''
  })
  await mini.restoreWxMethod('showToast')
  console.log('③ 最后一次 toast：', toast)

  console.log('\n--- console（尾 15 条）---')
  logs.slice(-15).forEach((l) => console.log('  ' + l.slice(0, 200)))

  await mini.disconnect()
}

main().catch((e) => {
  console.error('诊断失败：', e.stack || e.message)
  process.exit(1)
})
