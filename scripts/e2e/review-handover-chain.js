// 2026-11-16 审查修复 automator 走查·移交审核链（mock 模式，独立会话）：
// 创建→缺件 422→齐件提交→PENDING_INST_REVIEW→MGR 详情 canReview→审核页通过→INSPECTING。
// 用法: node review-handover-chain.js（需 IDE 自动化端口；与 review-fixes-flow.js 分会话跑，规避长会话挂起）
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
  let last = null
  for (let i = 0; i < (tries || 12); i++) {
    try {
      const p = await mp.currentPage(); const d = await p.data()
      last = { page: p, data: d }
      if (pred(d, p)) return last
    } catch (e) { /* 通道瞬时挂起：继续轮询 */ }
    await sleep(700)
  }
  return last || { page: { path: '' }, data: {} }
}
async function goto(mp, url) {
  const expectPath = url.split('?')[0].replace(/^\//, '')
  await mp.reLaunch(url)
  let r = await waitData(mp, (d, p) => p.path === expectPath && d.loading === false)
  if (r.page.path !== expectPath) { // reLaunch 抖动重试一次
    await mp.reLaunch(url)
    r = await waitData(mp, (d, p) => p.path === expectPath && d.loading === false)
  }
  return r
}
async function globalData(mp) {
  return mp.evaluate(() => {
    const d = getApp().globalData
    return { home: d.home, ws: d.workspace, qa: (d.quickActions || []).map(q => q.key) }
  })
}
// screenshot/callMethod 在 IDE 自动化下偶发挂起：超时保护，不阻塞断言主链路
function withTimeout(p, ms, label) {
  return Promise.race([p, sleep(ms).then(() => { throw new Error('timeout:' + label) })])
}
;(async () => {
  let mp
  try { mp = await automator.connect({ wsEndpoint: 'ws://localhost:23853', timeout: 60000 }) }
  catch (e) { mp = await automator.connect({ wsEndpoint: 'ws://localhost:9421', timeout: 60000 }) }
  await sleep(3000)
  await login(mp, '13800000013') // INST_PM 发起
  // 7 移交内部审核全链路：创建→缺件 422→齐件提交→PENDING_INST_REVIEW→MGR 详情 canReview→审核页通过→INSPECTING
  const created = await mp.evaluate(async () => {
    const h = require('services/handover.js')
    try {
      const o = await h.initHandover({ elevatorCode: 'EM-2024-002', elevatorName: '世纪大厦 2# 客梯', projectName: '世纪大厦', workTypeCode: 'HM' })
      return { id: o.id, status: o.status }
    } catch (e) { return { err: String(e && e.message) } }
  })
  assert('创建移交单 DOC_SUBMITTING', created.id && created.status === 'DOC_SUBMITTING', JSON.stringify(created))
  if (created.id) {
    const missing = await mp.evaluate(async (id) => {
      const h = require('services/handover.js')
      try { await h.submitHandoverDocs(id, { supervisionReportFileId: 'f1' }); return 'no422' }
      catch (e) { return String(e && e.code) }
    }, created.id)
    assert('缺件提交 422', missing === '422', missing)
    const submitted = await mp.evaluate(async (id) => {
      const h = require('services/handover.js')
      const o = await h.submitHandoverDocs(id, { supervisionReportFileId: 'f1', maintenanceManualFileId: 'f2', qualityCertificateFileId: 'f3' })
      return o.status
    }, created.id)
    assert('齐件提交→PENDING_INST_REVIEW', submitted === 'PENDING_INST_REVIEW', submitted)
    await login(mp, '13800000099') // INST_MGR 张工
    const det = await goto(mp, '/pkg-handover/pages/detail/index?id=' + created.id)
    const page0 = det.page; const cd = det.data
    assert('审核态中文文案', cd.statusText === '内部审核中', cd.statusText)
    assert('MGR canReview=true', cd.canReview === true, JSON.stringify({ canReview: cd.canReview, status: cd.statusText }))
    console.log('STEP goReview')
    try { await withTimeout(page0.callMethod('goReview'), 15000, 'goReview') }
    catch (e) { console.log('WARN goReview 调用超时，走 reLaunch 兜底') }
    let rev = await waitData(mp, (d, p) => p.path === 'pkg-handover/pages/review/index' && d.loading === false)
    if (rev.page.path !== 'pkg-handover/pages/review/index') { // 时序抖动/通道挂起：reLaunch 兜底
      console.log('STEP reLaunch review 兜底')
      try { await mp.reLaunch('/pkg-handover/pages/review/index?id=' + created.id) } catch (e) { console.log('WARN reLaunch 超时') }
      rev = await waitData(mp, (d, p) => p.path === 'pkg-handover/pages/review/index' && d.loading === false)
    }
    const page = rev.page
    assert('进入审核页', page.path === 'pkg-handover/pages/review/index', page.path)
    console.log('STEP onApprove')
    try { await withTimeout(page.callMethod('onApprove'), 15000, 'onApprove') } catch (e) { console.log('WARN onApprove 调用超时') }
    await sleep(2500)
    let after = ''
    for (let i = 0; i < 3 && after !== 'INSPECTING'; i++) {
      try {
        after = await mp.evaluate(async (id) => (await require('services/handover.js').getHandoverDetail(id)).status, created.id)
      } catch (e) { await sleep(1500) }
    }
    assert('审核通过→INSPECTING', after === 'INSPECTING', after)
  }

  console.log(fail === 0 ? 'ALL PASS' : fail + ' FAIL')
  await mp.disconnect()
  process.exit(fail === 0 ? 0 : 1)
})().catch(e => { console.error('ERR', e.message); process.exit(1) })
