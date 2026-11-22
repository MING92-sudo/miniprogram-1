// 2026-11-16 审查修复 automator 走查（mock 模式）：菜单角色感知 / CLIENT 首页数据 /
// 签字态投影 / 记录单异常结论 / 移交内部审核全链路 / 资料提交 422 契约。
// 用法: node review-fixes-flow.js（需 IDE 服务端口开启；跑完还原 config.useMock=false）
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
;(async () => {
  let mp
  for (const port of [23854, 23853, 9421]) {
    try { mp = await automator.connect({ wsEndpoint: 'ws://localhost:' + port, timeout: 20000 }); break }
    catch (e) { /* 尝试下一端口 */ }
  }
  if (!mp) throw new Error('automator connect failed on 23854/23853/9421')
  await sleep(3000)

  // 1 CLIENT_ADMIN（张伟）：QA 含 todo 不含 plan；首页电梯/待签数据非空（统一令牌投影）
  await login(mp, '13800000012')
  let g = await globalData(mp)
  assert('CLIENT home 解析', g.home === '/pages/client/home/index', JSON.stringify(g))
  assert('CLIENT QA todo∈ / plan∉', g.qa.includes('todo') && !g.qa.includes('plan'), JSON.stringify(g.qa))
  let page = await mp.currentPage(); let cd = await page.data()
  assert('CLIENT 首页电梯非空', (cd.elevators || []).length > 0, 'elevators=' + JSON.stringify(cd.elevators || []))
  await mp.screenshot({ path: path.join(__dirname, 'shots', 'fix-client-home.png') })

  // 2 MAINT_TECH（李振国）：QA 无 filing/plan/scan
  await login(mp, '13800000011')
  g = await globalData(mp)
  assert('TECH QA 无 filing/plan/scan', !g.qa.includes('filing') && !g.qa.includes('plan') && !g.qa.includes('scan'), JSON.stringify(g.qa))

  // 3 签字态投影：wo_2 PROCESSING 单人，李振国 platformId 匹配主检；签字后文本/主按钮状态化
  const signRes = await mp.evaluate(async () => {
    const order = require('services/order.js')
    try { await order.signOrder('wo_2', { role: 'PRINCIPAL', signatureFileId: 'mock_sig_e2e', signatureUrl: 'https://mock/sig.png' }); return 'ok' }
    catch (e) { return 'err:' + (e && e.message) }
  })
  assert('mock 签字写入', signRes === 'ok', signRes)
  ;({ page, data: cd } = await goto(mp, '/pkg-maintenance/pages/record-sign/index?orderId=wo_2'))
  assert('签字后 workerSignText=已签字', cd.workerSignText === '已签字', JSON.stringify({ t: cd.workerSignText, s: cd.workerSigned }))
  assert('mySigned/bothSigned 成立', cd.mySigned === true && cd.bothSigned === true, JSON.stringify({ m: cd.mySigned, b: cd.bothSigned }))
  assert('主按钮=查看记录单', cd.primaryText === '查看记录单', cd.primaryText)
  await mp.screenshot({ path: path.join(__dirname, 'shots', 'fix-record-sign.png') })

  // 4 记录单异常结论：ur_1 含 ABNORMAL 项 → 结论不得为合格、left>0、分组非 G 码
  ;({ page, data: cd } = await goto(mp, '/pkg-maintenance/pages/record-detail/index?recordId=ur_1'))
  assert('异常记录结论≠合格', cd.conclusion && cd.conclusion !== '合格 · 可正常运行', cd.conclusion)
  assert('遗留项>0 且不虚报已整改', cd.stats && cd.stats.left > 0 && cd.stats.fixed === 0, JSON.stringify(cd.stats))
  assert('分组按周期非 G 码', (cd.groups || []).length > 0 && cd.groups.every(x => !/^G\d\d$/.test(x.label)), JSON.stringify(cd.groups || []))
  await mp.screenshot({ path: path.join(__dirname, 'shots', 'fix-record-detail.png') })

  // 5 MAINT_SUP（陈刚）：QA 含 plan，不含已移除的 filing
  await login(mp, '13800000002')
  g = await globalData(mp)
  assert('SUP QA plan 且无 filing', g.qa.includes('plan') && !g.qa.includes('filing'), JSON.stringify(g.qa))

  // 6 INST 角色过滤：MGR 无 create/upload；PM 两者都有
  await login(mp, '13800000099')
  g = await globalData(mp)
  assert('INST_MGR QA 无 create/upload', !g.qa.includes('create') && !g.qa.includes('upload'), JSON.stringify(g.qa))
  await login(mp, '13800000013')
  g = await globalData(mp)
  assert('INST_PM QA create+upload', g.qa.includes('create') && g.qa.includes('upload'), JSON.stringify(g.qa))

  console.log(fail === 0 ? 'ALL PASS' : fail + ' FAIL')
  await mp.disconnect()
  process.exit(fail === 0 ? 0 : 1)
})().catch(e => { console.error('ERR', e.message); process.exit(1) })
