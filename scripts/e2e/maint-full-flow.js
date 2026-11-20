// 维保员工全链路走查（mock 模式，AGENTS §7.3 合规主干）：
// 李振国 13800000011（MAINT_TECH，platformId 6901282369105174537 命中 wo_1—wo_5 主维保）——
// 登录/工作台 → 工单列表 → 签到（主+配合/动态码/定位缺失与重复签到负面）→ 作业清单（五项 422 负面 + 填满）→
// 各签各的 → 签退（wo_1 时长不足 422 负面 + wo_2 完整签退生成记录）→ 记录预览（结论/S0/分组）。
// 写操作只发生在 mock 内存库，重编译即还原，可重复执行。
// 用法: node maint-full-flow.js（需 IDE 自动化端口 + useMock=true + packOptions 去 mock 排除；跑完还原）
const automator = require('miniprogram-automator'); const path = require('path')
const sleep = ms => new Promise(r => setTimeout(r, ms))
let fail = 0
function assert(name, cond, extra) {
  console.log((cond ? 'PASS ' : 'FAIL ') + name + (cond ? '' : '  → ' + (extra || '')))
  if (!cond) fail++
}
async function login(mp, account) {
  await mp.evaluate(() => wx.clearStorageSync())
  await mp.reLaunch('/pages/login/index'); await sleep(1800)
  const page = await mp.currentPage()
  await page.setData({ phone: account, password: '123456', agreed: true })
  await page.callMethod('onSubmit'); await sleep(2600)
}
// IDE 自动化连续 reLaunch 偶发时序抖动（docs/12 V1.17 环境限制）：轮询页面数据就绪，导航失败重试一次
async function waitData(mp, pred, tries) {
  for (let i = 0; i < (tries || 12); i++) {
    const p = await mp.currentPage(); const d = await p.data()
    if (pred(d, p)) return { page: p, data: d }
    await sleep(700)
  }
  const p = await mp.currentPage(); return { page: p, data: await p.data() }
}
async function goto(mp, url, ready) {
  const expectPath = url.split('?')[0].replace(/^\//, '')
  await mp.reLaunch(url)
  let r = await waitData(mp, (d, p) => p.path === expectPath && ready(d))
  if (r.page.path !== expectPath) { // reLaunch 抖动重试一次
    await mp.reLaunch(url)
    r = await waitData(mp, (d, p) => p.path === expectPath && ready(d))
  }
  return r
}
async function globalData(mp) {
  return mp.evaluate(() => {
    const d = getApp().globalData
    return { home: d.home, ws: d.workspace, qa: (d.quickActions || []).map(q => q.key) }
  })
}
// V1.17 IDE 自动化挂起自愈：evaluate 超时 → 重连 WS 重试一次
//（重连不丢状态：mock 内存库与登录 storage 都在运行中的 app 实例里，reconnect 不触发重编译）
let mp
async function connect() {
  for (const port of [9420, 23854, 23853, 9421]) {
    try { return await automator.connect({ wsEndpoint: 'ws://localhost:' + port, timeout: 20000 }) }
    catch (e) { /* 尝试下一端口 */ }
  }
  throw new Error('automator connect failed on 9420/23854/23853/9421')
}
async function reconnect() {
  try { await mp.disconnect() } catch (e) { /* 已断开 */ }
  mp = await connect()
  await sleep(2500)
}
// 服务层调用（app 内 evaluate，走真实 request 封装 → mock 路由）
async function svc(fn, ...args) {
  try { return await mp.evaluate(fn, ...args) } catch (e) {
    if (!/timeout/i.test((e && e.message) || '')) throw e
    console.log('  (evaluate 超时，重连重试一次)')
    await reconnect()
    return mp.evaluate(fn, ...args)
  }
}

;(async () => {
  mp = await connect()
  await sleep(3000)

  // ── A. 登录与工作台 ──
  await login(mp, '13800000011')
  const g = await globalData(mp)
  assert('TECH home=维保工作台', typeof g.home === 'string' && g.home.indexOf('maint/home') > -1, JSON.stringify(g))
  assert('TECH QA 无 filing/plan/scan', !g.qa.includes('filing') && !g.qa.includes('plan') && !g.qa.includes('scan'), JSON.stringify(g.qa))

  // ── B. 工单列表（UI）──
  let r = await goto(mp, '/pkg-maintenance/pages/orders/index', (d) => d.loading === false)
  const ids = (r.data.list || []).map((o) => o.id)
  assert('工单列表含 wo_1(待执行)/wo_2(进行中)', ids.includes('wo_1') && ids.includes('wo_2'), JSON.stringify(ids))
  await mp.screenshot({ path: path.join(__dirname, 'shots', 'maint-orders.png') })

  // ── C. 签到链（wo_1 双人）──
  // 主维保签到（el_1 坐标 106.633520,29.719210 → distance≈0）
  let c = await svc(async () => {
    const order = require('services/order.js')
    try {
      const x = await order.checkin('wo_1', { latitude: 29.719210, longitude: 106.633520, role: 'PRINCIPAL', photoFileId: 'mock_file_self' })
      return { ok: true, role: x.role, distance: x.distance }
    } catch (e) { return { ok: false, code: e && e.code, message: e && e.message } }
  })
  assert('主维保签到成功 role=PRINCIPAL', c.ok === true && c.role === 'PRINCIPAL', JSON.stringify(c))
  // 重复主签到 → 1003
  c = await svc(async () => {
    const order = require('services/order.js')
    try { await order.checkin('wo_1', { latitude: 29.719210, longitude: 106.633520 }); return { ok: true } }
    catch (e) { return { ok: false, code: e && e.code } }
  })
  assert('重复主签到拦截 1003', c.ok === false && c.code === 1003, JSON.stringify(c))
  // 缺定位坐标（wo_2 未留 checkinExtra，先撞 422 无副作用）
  c = await svc(async () => {
    const order = require('services/order.js')
    try { await order.checkin('wo_2', {}); return { ok: true } }
    catch (e) { return { ok: false, code: e && e.code } }
  })
  assert('缺定位坐标签到拦截 422', c.ok === false && c.code === 422, JSON.stringify(c))
  // 动态码：签发 → 错码 1003 → 正码 ok → 配合签到消费 → 同码复用 1003
  const dc = await svc(async () => {
    const order = require('services/order.js')
    const issued = await order.issueDynamicCode('wo_1')
    const out = { code: issued.code, expiresAt: issued.expiresAt }
    try { await order.verifyDynamicCode('wo_1', { code: '000000' }); out.badVerify = 'pass' }
    catch (e) { out.badVerify = e && e.code }
    try { await order.verifyDynamicCode('wo_1', { code: issued.code }); out.goodVerify = 'ok' }
    catch (e) { out.goodVerify = e && e.code }
    return out
  })
  assert('动态码签发 6 位 + 有效期', /^\d{6}$/.test(dc.code || '') && !!dc.expiresAt, JSON.stringify(dc))
  assert('错动态码校验 1003 / 正码通过', dc.badVerify === 1003 && dc.goodVerify === 'ok', JSON.stringify(dc))
  c = await svc(async (code) => {
    const order = require('services/order.js')
    try {
      const x = await order.checkin('wo_1', { latitude: 29.719210, longitude: 106.633520, role: 'ASSISTANT', dynamicCode: code })
      return { ok: true, role: x.role }
    } catch (e) { return { ok: false, code: e && e.code, message: e && e.message } }
  }, dc.code)
  assert('配合人员签到成功（消费动态码）', c.ok === true && c.role === 'ASSISTANT', JSON.stringify(c))
  c = await svc(async (code) => {
    const order = require('services/order.js')
    try { await order.checkin('wo_1', { latitude: 29.719210, longitude: 106.633520, role: 'ASSISTANT', dynamicCode: code }); return { ok: true } }
    catch (e) { return { ok: false, code: e && e.code } }
  }, dc.code)
  assert('配合重复签到/同码复用拦截 1003', c.ok === false && c.code === 1003, JSON.stringify(c))

  // ── D. 作业清单（wo_1）：五项 422 负面 → 填满（注入 1 项异常 S5）──
  const neg = await svc(async () => {
    const order = require('services/order.js')
    const cl = await order.getChecklist('wo_1')
    const it = (cl.items || []).find((i) => !i.notInThisRun)
    const out = { hasItem: !!it }
    const cases = {
      noDesc: { result: 'ABNORMAL' },
      naNoReason: { result: 'NA' },
      noCode: { result: 'ABNORMAL', abnormalDesc: 'x', photoUrls: ['https://mock.local/a.jpg'] },
      badCode: { result: 'ABNORMAL', abnormalDesc: 'x', problemCode: 'S9', photoUrls: ['https://mock.local/a.jpg'] },
      noPhoto: { result: 'ABNORMAL', abnormalDesc: 'x', problemCode: 'S5' }
    }
    for (const k of Object.keys(cases)) {
      try { await order.submitChecklistItem('wo_1', it.id, cases[k]); out[k] = 'pass' }
      catch (e) { out[k] = e && e.code }
    }
    return out
  })
  assert('清单负面：异常缺描述 422', neg.noDesc === 422, JSON.stringify(neg))
  assert('清单负面：NA 缺跳过原因 422', neg.naNoReason === 422, JSON.stringify(neg))
  assert('清单负面：异常缺隐患码 422', neg.noCode === 422, JSON.stringify(neg))
  assert('清单负面：隐患码越界 S9 422', neg.badCode === 422, JSON.stringify(neg))
  assert('清单负面：异常缺照片 422', neg.noPhoto === 422, JSON.stringify(neg))

  const fill1 = await svc(async () => {
    const order = require('services/order.js')
    const cl = await order.getChecklist('wo_1')
    const items = (cl.items || []).filter((i) => !i.notInThisRun)
    let ok = 0; let abInjected = false
    for (const it of items) {
      const body = { result: 'NORMAL' }
      if (it.judgeType === 'NUMERIC') body.value = it.valueMin != null ? it.valueMin + 1 : 8
      if (it.judgeType === 'MANUFACTURER') body.valueText = '符合本机说明书要求'
      if (it.isKey && it.photoRequired) body.photoUrls = ['https://mock.local/demo.jpg']
      if (!abInjected) {
        body.result = 'ABNORMAL'; body.abnormalDesc = '走查注入异常：部件磨损，已现场处理'
        body.problemCode = 'S5'; body.photoUrls = ['https://mock.local/abn.jpg']
        abInjected = true
      }
      const r = await order.submitChecklistItem('wo_1', it.id, body)
      if (r && r.ok) ok++
    }
    return { total: items.length, ok, abInjected }
  })
  assert('wo_1 清单全部填写成功（含 1 项异常 S5）', fill1.total > 0 && fill1.ok === fill1.total && fill1.abInjected === true, JSON.stringify(fill1))

  // ── E. 各签各的 + wo_1 时长门禁负面 ──
  c = await svc(async () => {
    const order = require('services/order.js')
    const w = await order.signOrder('wo_1', { role: 'PRINCIPAL', signatureFileId: 'mock_file_sig_w' })
    const a = await order.signOrder('wo_1', { role: 'ASSISTANT', signatureFileId: 'mock_file_sig_a' })
    return { w: w.ok, a: a.ok }
  })
  assert('主/配合各自签字成功', c.w === true && c.a === true, JSON.stringify(c))
  c = await svc(async () => {
    const order = require('services/order.js')
    try { await order.checkout('wo_1', {}); return { ok: true } }
    catch (e) { return { ok: false, code: e && e.code, message: e && e.message } }
  })
  assert('wo_1 作业时长不足 30 分钟拦截 422', c.ok === false && c.code === 422, JSON.stringify(c))

  // ── F. wo_2 完整签退（08:00 已签到，solo，时长足）──
  const fill2 = await svc(async () => {
    const order = require('services/order.js')
    const cl = await order.getChecklist('wo_2')
    const items = (cl.items || []).filter((i) => !i.notInThisRun)
    let ok = 0
    for (const it of items) {
      const body = { result: 'NORMAL' }
      if (it.judgeType === 'NUMERIC') body.value = it.valueMin != null ? it.valueMin + 1 : 8
      if (it.judgeType === 'MANUFACTURER') body.valueText = '符合本机说明书要求'
      if (it.isKey && it.photoRequired) body.photoUrls = ['https://mock.local/demo.jpg']
      const r = await order.submitChecklistItem('wo_2', it.id, body)
      if (r && r.ok) ok++
    }
    await order.signOrder('wo_2', { role: 'PRINCIPAL', signatureFileId: 'mock_file_sig_w2' })
    return { total: items.length, ok }
  })
  assert('wo_2 清单填满 + 主签字完成', fill2.total > 0 && fill2.ok === fill2.total, JSON.stringify(fill2))
  const co = await svc(async () => {
    const order = require('services/order.js')
    try {
      const x = await order.checkout('wo_2', {})
      return { ok: true, recordId: x.recordId, shareToken: x.shareToken, reportStatus: x.reportStatus, duration: x.duration }
    } catch (e) { return { ok: false, code: e && e.code, message: e && e.message } }
  })
  assert('wo_2 签退成功生成记录', co.ok === true && !!co.recordId && !!co.shareToken, JSON.stringify(co))
  assert('签退自动上报 2.6 成功 + 时长≥30分钟', co.reportStatus === 'REPORTED' && typeof co.duration === 'string' && co.duration > '00:29', JSON.stringify(co))

  // ── G. 记录预览（UI + 内部读投影）──
  const rd = await svc(async (rid) => {
    const order = require('services/order.js')
    try {
      const x = await order.getRecordDetail(rid)
      return { ok: true, id: x.id, problemCodes: x.problemCodes, lifecycleStatus: x.lifecycleStatus, items: (x.items || []).length }
    } catch (e) { return { ok: false, code: e && e.code, message: e && e.message } }
  }, co.recordId)
  assert('新记录内部读 200 + 无异常自动补 S0', rd.ok === true && (rd.problemCodes || []).join() === 'S0' && rd.items > 0, JSON.stringify(rd))
  r = await goto(mp, '/pkg-maintenance/pages/record-detail/index?recordId=' + co.recordId, (d) => d.loading === false)
  assert('记录预览页渲染无错', r.data.error === '' && r.data.record && r.data.record.id === co.recordId, JSON.stringify({ e: r.data.error }))
  assert('记录预览：结论合格 + 分组按周期', r.data.conclusion === '合格 · 可正常运行' && (r.data.groups || []).length > 0, JSON.stringify({ c: r.data.conclusion, g: (r.data.groups || []).length }))
  await mp.screenshot({ path: path.join(__dirname, 'shots', 'maint-record-detail.png') })

  console.log(fail === 0 ? 'ALL PASS' : fail + ' FAIL')
  await mp.disconnect()
  process.exit(fail === 0 ? 0 : 1)
})().catch(e => { console.error('ERR', e.message); process.exit(1) })
