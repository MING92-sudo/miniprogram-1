/**
 * 小程序端 BUG 探测（第二轮）：真实登录 + 角色页面巡检
 * 用法：node mp-bugs-2.js <port> <phone> <password> [tag]
 * 输出：Temp/wxauto/bugs-2/<tag>-report.json + 失败页截图
 */
const fs = require('fs')
const path = require('path')
const automator = require('C:/Users/18223/AppData/Local/Temp/wxauto/node_modules/miniprogram-automator')

const PORT = Number(process.argv[2] || 9420)
const PHONE = process.argv[3]
const PASS = process.argv[4] || '123456'
const TAG = process.argv[5] || PHONE
const OUT = 'C:/Users/18223/AppData/Local/Temp/wxauto/bugs-2'
fs.mkdirSync(OUT, { recursive: true })

const events = []
let errCount = 0
const NET_FAILS = []

function log(kind, text) {
  events.push({ t: Date.now(), kind, text: String(text == null ? '' : text).slice(0, 900) })
  if (kind === 'exception' || kind === 'console:error') errCount++
}

const SNAP = function () {
  const pages = getCurrentPages()
  const p = pages[pages.length - 1]
  if (!p) return { none: true }
  const d = p.data || {}
  return {
    route: p.route,
    options: p.options || null,
    dataKeys: Object.keys(d).length,
    dataLen: (() => {
      try {
        return JSON.stringify(d).length
      } catch (e) {
        return -1
      }
    })(),
    loading: d.loading,
    errMsg: d.errMsg || d.error || d.errorMsg || '',
    listLen: Array.isArray(d.list) ? d.list.length : Array.isArray(d.orders) ? d.orders.length : undefined
  }
}

async function main() {
  const mini = await automator.connect({ wsEndpoint: `ws://127.0.0.1:${PORT}` })

  mini.on('console', (msg) => {
    const lvl = (msg && (msg.type || msg.level)) || 'log'
    const args = (msg && (msg.args || msg.content)) || ''
    const text = Array.isArray(args) ? args.join(' ') : String(args)
    if (/fail|error|失败|异常/i.test(text) && lvl === 'log') NET_FAILS.push(text.slice(0, 300))
    log('console:' + lvl, text)
  })
  mini.on('exception', (e) => log('exception', (e && (e.message || e.stack)) || JSON.stringify(e)))

  // ── 登录 ──
  const login = await mini.reLaunch('/pages/login/index')
  await login.waitFor(900)
  await login.setData({ phone: PHONE, password: PASS, agreed: true })
  await login.callMethod('onSubmit')
  await new Promise((r) => setTimeout(r, 2600))

  const after = await mini.evaluate(SNAP)
  const loggedIn = after.route && after.route !== 'pages/login/index'
  log('login-result', `${PHONE} -> ${after.route} (loggedIn=${loggedIn})`)
  await mini.screenshot({ path: path.join(OUT, `${TAG}-landing.png`) })

  const storage = await mini.evaluate(function () {
    try {
      const t = wx.getStorageSync('token') || wx.getStorageSync('em_token') || ''
      const info = wx.getStorageSync('userInfo') || wx.getStorageSync('user_info') || null
      return { hasToken: !!t, tokenLen: String(t).length, role: info && (info.role || info.roleName), principalType: info && info.principalType }
    } catch (e) {
      return { error: String(e && e.message) }
    }
  })
  log('storage', JSON.stringify(storage))

  // ── 逐页巡检（登录态）──
  const appJson = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../app.json'), 'utf8'))
  const subs = (appJson.subpackages || appJson.subPackages || []).flatMap((p) =>
    (p.pages || []).map((x) => p.root.replace(/\/$/, '') + '/' + x)
  )
  const all = [...(appJson.pages || []), ...subs]
    .filter((p) => !/^pages\/login\//.test(p))
    .map((p) => '/' + p)

  const results = []
  for (const url of all) {
    const before = errCount
    const rec = { page: url, ok: true }
    try {
      const p = await mini.reLaunch(url)
      await p.waitFor(800)
      const snap = await mini.evaluate(SNAP)
      rec.realRoute = snap.route
      rec.dataKeys = snap.dataKeys
      rec.dataLen = snap.dataLen
      rec.errMsg = snap.errMsg
      rec.listLen = snap.listLen
      // 被守卫踢回登录页 → 说明该页对本角色不可访问（记录，不算崩溃）
      if (snap.route === 'pages/login/index') {
        rec.kickedToLogin = true
      }
      // 落地到 403 页 → 权限拦截生效
      if (snap.route === 'pages/403/index') {
        rec.forbidden = true
      }
      if (snap.dataLen <= 2 && snap.dataKeys === 0) {
        rec.ok = false
        rec.reason = 'NO_DATA'
      }
    } catch (e) {
      rec.ok = false
      rec.reason = 'ERR: ' + e.message.slice(0, 160)
    }
    rec.newErrors = errCount - before
    if (rec.newErrors > 0) {
      rec.ok = false
      rec.reason = (rec.reason ? rec.reason + ' + ' : '') + `${rec.newErrors} 条报错`
    }
    results.push(rec)
    if (!rec.ok) {
      try {
        await mini.screenshot({ path: path.join(OUT, `${TAG}-fail-${url.replace(/[\/]/g, '_').slice(1)}.png`) })
      } catch (e) {
        /* ignore */
      }
    }
  }

  const bad = results.filter((r) => !r.ok)
  const realPages = results.filter((r) => !r.kickedToLogin && !r.forbidden)
  const report = {
    ts: new Date().toISOString(),
    phone: PHONE,
    tag: TAG,
    landing: after.route,
    loggedIn,
    storage,
    routes: all.length,
    visitedReal: realPages.length,
    results,
    events,
    summary: { bad: bad.length, errors: errCount, kicked: results.filter((r) => r.kickedToLogin).length, forbidden: results.filter((r) => r.forbidden).length }
  }
  fs.writeFileSync(path.join(OUT, `${TAG}-report.json`), JSON.stringify(report, null, 2))

  console.log(`\n===== ${TAG} 巡检 =====`)
  console.log(`登录：${PHONE} → ${after.route} ${loggedIn ? '✓' : '✗'}  role=${storage.role || '-'} principal=${storage.principalType || '-'}`)
  console.log(`路由 ${all.length}｜真实进入 ${realPages.length}｜被踢回登录 ${report.summary.kicked}｜403 ${report.summary.forbidden}｜异常页 ${bad.length}｜报错 ${errCount}`)
  if (bad.length) {
    console.log('\n--- 异常页面 ---')
    bad.forEach((b) => console.log(`  ✗ ${b.page} → ${b.realRoute || '-'}  ${b.reason || ''}`))
  }
  const errs = events.filter((e) => e.kind === 'exception' || e.kind === 'console:error')
  if (errs.length) {
    console.log('\n--- 报错（前 20）---')
    errs.slice(0, 20).forEach((e) => console.log(`[${e.kind}] ${e.text.replace(/\n/g, ' ').slice(0, 220)}`))
  }
  if (NET_FAILS.length) {
    console.log('\n--- 请求/业务失败（前 12）---')
    ;[...new Set(NET_FAILS)].slice(0, 12).forEach((t) => console.log('  ' + t))
  }

  await mini.disconnect()
}

main().catch((e) => {
  console.error('失败：', e.stack || e.message)
  process.exit(1)
})
