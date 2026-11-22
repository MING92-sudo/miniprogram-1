/** 小程序只读巡检：收集运行时异常、白屏和截图。 */
const fs = require('fs')
const path = require('path')
const automator = require('C:/Users/18223/AppData/Local/Temp/wxauto/node_modules/miniprogram-automator')

const PORT = Number(process.argv[2] || 9420)
const OUT = 'C:/Users/18223/AppData/Local/Temp/wxauto/bugs-1'
fs.mkdirSync(OUT, { recursive: true })

const events = []
let errCount = 0

function log(kind, text) {
  const t = String(text == null ? '' : text).slice(0, 800)
  events.push({ t: Date.now(), kind, text: t })
  if (kind === 'exception' || kind === 'console:error') errCount++
}

/** 读取当前页快照（在小程序上下文里执行） */
const SNAPSHOT = function () {
  const pages = getCurrentPages()
  const p = pages[pages.length - 1]
  if (!p) return { none: true }
  const d = p.data || {}
  let dataLen = 0
  try {
    dataLen = JSON.stringify(d).length
  } catch (e) {
    dataLen = -1
  }
  return {
    route: p.route,
    dataKeys: Object.keys(d).length,
    dataLen,
    // 关键数据字段抽样，便于判断空态到底是「设计如此」还是「没取到数据」
    sample: {
      loading: d.loading,
      list: Array.isArray(d.list) ? d.list.length : undefined,
      items: Array.isArray(d.items) ? d.items.length : undefined,
      errMsg: d.errMsg || d.error || d.errorMsg,
      empty: d.empty,
      needLogin: d.needLogin
    }
  }
}

async function main() {
  const mini = await automator.connect({ wsEndpoint: `ws://127.0.0.1:${PORT}` })
  log('connect', 'ok')
  try {
    await mini.checkVersion()
  } catch (e) {
    /* 版本检查非必需 */
  }

  mini.on('console', (msg) => {
    const lvl = (msg && (msg.type || msg.level)) || 'log'
    const args = (msg && (msg.args || msg.content)) || ''
    log('console:' + lvl, Array.isArray(args) ? args.join(' ') : args)
  })
  mini.on('exception', (e) => {
    log('exception', (e && (e.message || e.stack)) || JSON.stringify(e))
  })

  // ── 1. 登录页 ──
  const login = await mini.reLaunch('/pages/login/index')
  await login.waitFor(1500)
  await mini.screenshot({ path: path.join(OUT, '01-login.png') })
  const loginSnap = await mini.evaluate(SNAPSHOT)
  const loginData = await login.data()
  log('login', JSON.stringify(loginSnap))
  fs.writeFileSync(path.join(OUT, 'login-data.json'), JSON.stringify(loginData, null, 2))

  // ── 2. 逐页巡检 ──
  const appJson = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../app.json'), 'utf8'))
  const mainPages = appJson.pages || []
  const subs = (appJson.subpackages || appJson.subPackages || []).flatMap((p) =>
    (p.pages || []).map((x) => p.root.replace(/\/$/, '') + '/' + x)
  )
  const all = [...mainPages, ...subs].map((p) => (p.startsWith('/') ? p : '/' + p))
  log('routes', `main=${mainPages.length} sub=${subs.length} total=${all.length}`)

  const results = []
  for (const url of all) {
    const before = errCount
    const rec = { page: url, ok: true }
    try {
      const p = await mini.reLaunch(url)
      await p.waitFor(650)
      const snap = await mini.evaluate(SNAPSHOT)
      rec.realRoute = snap.route
      rec.dataKeys = snap.dataKeys
      rec.dataLen = snap.dataLen
      rec.sample = snap.sample
      // 只有一个 <page> 空壳且 data 几乎为空 → 白屏嫌疑
      if (snap.dataLen <= 2 && snap.dataKeys === 0) {
        rec.ok = false
        rec.reason = 'NO_DATA（页面无 data，疑似未注册/未初始化）'
      }
    } catch (e) {
      rec.ok = false
      rec.reason = 'LAUNCH_FAIL: ' + e.message
    }
    rec.newErrors = errCount - before
    if (rec.newErrors > 0) {
      rec.ok = false
      rec.reason = (rec.reason ? rec.reason + ' + ' : '') + `新增 ${rec.newErrors} 条报错`
    }
    results.push(rec)
    if (!rec.ok) {
      try {
        await mini.screenshot({ path: path.join(OUT, 'fail-' + url.replace(/[\/]/g, '_').slice(1) + '.png') })
      } catch (e) {
        /* ignore */
      }
    }
  }

  const bad = results.filter((r) => !r.ok)
  const report = {
    ts: new Date().toISOString(),
    port: PORT,
    routes: all.length,
    loginSnap,
    results,
    events,
    summary: {
      total: all.length,
      bad: bad.length,
      errors: errCount
    }
  }
  fs.writeFileSync(path.join(OUT, 'report.json'), JSON.stringify(report, null, 2))

  console.log('\n===== 巡检汇总 =====')
  console.log('路由总数', all.length, '｜异常页面', bad.length, '｜报错条数', errCount)
  if (bad.length) {
    console.log('\n--- 异常页面 ---')
    bad.forEach((b) => console.log(`  ✗ ${b.page}  ${b.reason || ''}`))
  }
  const errs = events.filter((e) => e.kind === 'exception' || e.kind === 'console:error')
  if (errs.length) {
    console.log('\n--- 报错明细（前 30 条）---')
    errs.slice(0, 30).forEach((e) => console.log(`[${e.kind}] ${e.text.replace(/\n/g, ' ')}`))
  }
  const warns = events.filter((e) => e.kind === 'console:warn')
  if (warns.length) {
    console.log('\n--- warn（前 10 条）---')
    warns.slice(0, 10).forEach((e) => console.log(`[warn] ${e.text.replace(/\n/g, ' ')}`))
  }

  await mini.disconnect()
}

main().catch((e) => {
  console.error('探测失败：', e.stack || e.message)
  process.exit(1)
})
