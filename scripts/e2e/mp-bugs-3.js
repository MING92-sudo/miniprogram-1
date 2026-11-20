/**
 * 小程序端 BUG 探测（第三轮）：带真实 id 的纵深巡检 + 多角色
 * 用法：node mp-bugs-3.js <port> <phone> <password> <tag>
 * 思路：先登录 → 列表页取真实 id → 带 id 打开详情页 → 校验 error / 关键字段
 */
const fs = require('fs')
const path = require('path')
const automator = require('C:/Users/18223/AppData/Local/Temp/wxauto/node_modules/miniprogram-automator')

const PORT = Number(process.argv[2] || 9420)
const PHONE = process.argv[3]
const PASS = process.argv[4] || '123456'
const TAG = process.argv[5] || PHONE
const OUT = 'C:/Users/18223/AppData/Local/Temp/wxauto/bugs-3'
fs.mkdirSync(OUT, { recursive: true })

const events = []
let errCount = 0

function log(kind, text) {
  events.push({ kind, text: String(text == null ? '' : text).slice(0, 900) })
  if (kind === 'exception' || kind === 'console:error') errCount++
}

const SNAP = function () {
  const p = getCurrentPages().pop()
  if (!p) return { none: true }
  const d = p.data || {}
  return {
    route: p.route,
    dataKeys: Object.keys(d).length,
    error: d.error || d.errMsg || '',
    loading: d.loading,
    listLen: Array.isArray(d.list) ? d.list.length : undefined,
    title: d.title || (d.profile && d.profile.elevatorName) || (d.detail && d.detail.handoverNo) || ''
  }
}

/** 在页面 data 里递归找第一个 id（字符串），用于构造详情页参数 */
const FIRST_ID = function () {
  const p = getCurrentPages().pop()
  const seen = new Set()
  function find(o, depth) {
    if (!o || depth > 5 || typeof o !== 'object') return null
    if (seen.has(o)) return null
    seen.add(o)
    if (Array.isArray(o)) {
      for (const it of o) {
        const r = find(it, depth + 1)
        if (r) return r
      }
      return null
    }
    for (const k of ['id', 'elevatorId', 'orderId', 'recordId']) {
      if (typeof o[k] === 'string' && o[k]) return o[k]
    }
    for (const k of Object.keys(o)) {
      const r = find(o[k], depth + 1)
      if (r) return r
    }
    return null
  }
  return find(p.data || {}, 0)
}

async function main() {
  const mini = await automator.connect({ wsEndpoint: `ws://127.0.0.1:${PORT}` })
  mini.on('console', (msg) => {
    const lvl = (msg && (msg.type || msg.level)) || 'log'
    const a = (msg && (msg.args || msg.content)) || ''
    log('console:' + lvl, Array.isArray(a) ? a.join(' ') : a)
  })
  mini.on('exception', (e) => log('exception', (e && (e.message || e.stack)) || JSON.stringify(e)))

  // 登录（definePage 包装后方法挂载有时序竞态，重试 3 次并区分"未挂载"与"凭证错"）
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
  // 关键：先清登录态。IDE 会保留上次会话的 em_token，guard 直接把页面导向上次角色的首页，
  // 会让"登录失败"看起来像"登录成功但分流错误"（第一轮就踩了，三个账号全落同一页）。
  await mini.evaluate(function () {
    try {
      wx.clearStorageSync()
    } catch (e) {
      /* ignore */
    }
  })
  const readAuth = function () {
    try {
      return {
        tokenLen: String(wx.getStorageSync('em_token') || '').length,
        role: wx.getStorageSync('em_role') || '',
        roles: wx.getStorageSync('em_roles') || [],
        principal: wx.getStorageSync('em_principal') || '',
        home: wx.getStorageSync('em_home') || ''
      }
    } catch (e) {
      return { error: String(e && e.message) }
    }
  }

  let landing = { route: 'pages/login/index' }
  let loggedIn = false
  let auth = {}
  for (let attempt = 1; attempt <= 3 && !loggedIn; attempt++) {
    const login = await mini.reLaunch('/pages/login/index')
    await login.waitFor(1600)
    await login.setData({ phone: PHONE, password: PASS, agreed: true })
    const before = errCount
    try {
      await login.callMethod('onSubmit')
    } catch (e) {
      log('callMethod-fail', `attempt=${attempt} ${e.message}`)
    }
    await sleep(2800)
    auth = await mini.evaluate(readAuth)
    landing = await mini.evaluate(SNAP)
    // 以「拿到 token」为登录成功判据，而不是「离开了登录页」——后者会被旧登录态污染
    loggedIn = auth.tokenLen > 0 && !!(landing.route && landing.route !== 'pages/login/index')
    if (!loggedIn) log('login-retry', `attempt=${attempt} route=${landing.route} tokenLen=${auth.tokenLen} newErrors=${errCount - before}`)
  }
  console.log(`登录 ${PHONE} → ${landing.route} ${loggedIn ? '✓' : '✗'}  role=${auth.role || '-'} roles=${JSON.stringify(auth.roles || [])} principal=${auth.principal || '-'} home=${auth.home || '-'}`)
  if (!loggedIn) {
    fs.writeFileSync(path.join(OUT, `${TAG}-login-fail.json`), JSON.stringify({ events }, null, 2))
    await mini.disconnect()
    process.exit(3)
  }

  // 列表 → 详情 的纵深链路（路径以 app.json 实际注册为准）
  const chains = [
    { name: '电梯详情', list: '/pages/elevator/list/index', detail: '/pages/elevator/detail/index', param: 'id' },
    { name: '订单详情', list: '/pages/order/list/index', detail: '/pages/order/detail/index', param: 'id' },
    { name: '移交单详情', list: '/pkg-handover/pages/list/index', detail: '/pkg-handover/pages/detail/index', param: 'id' },
    { name: '维保工单→记录详情', list: '/pkg-maintenance/pages/orders/index', detail: '/pkg-maintenance/pages/record-detail/index', param: 'id' },
    { name: '维保工单→签到页', list: '/pkg-maintenance/pages/orders/index', detail: '/pkg-maintenance/pages/checkin/index', param: 'orderId' },
    { name: '维保工单→填报页', list: '/pkg-maintenance/pages/orders/index', detail: '/pkg-maintenance/pages/task/index', param: 'orderId' },
    { name: '客户待办→签字页', list: '/pkg-client/pages/todo/index', detail: '/pkg-client/pages/sign/index', param: 'id' },
    { name: '管理端账号页', list: '/pkg-admin/pages/users/index', detail: '/pkg-admin/pages/users/index', param: 'id' }
  ]

  const rows = []
  for (const c of chains) {
    const row = { name: c.name, list: c.list, detail: c.detail, ok: true }
    const before = errCount
    try {
      const lp = await mini.reLaunch(c.list)
      await lp.waitFor(1000)
      const ls = await mini.evaluate(SNAP)
      row.listRoute = ls.route
      if (ls.route === 'pages/login/index') {
        row.ok = false
        row.reason = '列表被踢回登录'
        rows.push(row)
        continue
      }
      if (ls.route === 'pages/403/index') {
        row.skip = '列表 403（角色不可见）'
        rows.push(row)
        continue
      }
      const id = await mini.evaluate(FIRST_ID)
      row.id = id
      if (!id) {
        row.skip = '列表无数据，无 id 可测'
        rows.push(row)
        continue
      }
      const dp = await mini.reLaunch(`${c.detail}?${c.param}=${encodeURIComponent(id)}`)
      await dp.waitFor(1400)
      const ds = await mini.evaluate(SNAP)
      row.detailRoute = ds.route
      row.error = ds.error
      row.dataKeys = ds.dataKeys
      row.title = ds.title
      if (ds.route === 'pages/login/index') {
        row.ok = false
        row.reason = '详情被踢回登录'
      } else if (ds.route === 'pages/403/index') {
        row.ok = false
        row.reason = '详情 403'
      } else if (ds.error) {
        row.ok = false
        row.reason = '页面显示错误：' + ds.error
      }
      await mini.screenshot({ path: path.join(OUT, `${TAG}-${row.name}.png`) })
    } catch (e) {
      row.ok = false
      row.reason = 'ERR: ' + e.message.slice(0, 160)
    }
    row.newErrors = errCount - before
    if (row.newErrors > 0) {
      row.ok = false
      row.reason = (row.reason ? row.reason + ' + ' : '') + row.newErrors + ' 条报错'
    }
    rows.push(row)
  }

  const bad = rows.filter((r) => !r.ok && !r.skip)
  const report = { ts: new Date().toISOString(), phone: PHONE, tag: TAG, landing: landing.route, loggedIn, rows, events, summary: { total: rows.length, bad: bad.length, errors: errCount } }
  fs.writeFileSync(path.join(OUT, `${TAG}-report.json`), JSON.stringify(report, null, 2))

  console.log('\n--- 列表→详情 链路 ---')
  rows.forEach((r) => {
    const flag = r.skip ? '－' : r.ok ? '✓' : '✗'
    console.log(`  ${flag} ${r.name}  ${r.skip || r.reason || ''}  id=${r.id || '-'} title=${r.title || '-'}`)
  })
  const errs = events.filter((e) => e.kind === 'exception' || e.kind === 'console:error')
  if (errs.length) {
    console.log('\n--- 报错（前 15）---')
    errs.slice(0, 15).forEach((e) => console.log(`[${e.kind}] ${e.text.replace(/\n/g, ' ').slice(0, 200)}`))
  }
  await mini.disconnect()
}

main().catch((e) => {
  console.error('失败：', e.stack || e.message)
  process.exit(1)
})
