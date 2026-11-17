// C-24 闭环走查（mock 模式）：员工内部读记录详情 GET /records/{id}（record:view + V7 班组谓词）。
// ① MAINT_TECH（李振国，platformId 命中 ur_1 主维保）→ record-detail?recordId=ur_1 真实谓词 200 渲染；
// ② MAINT_MGR（赵经理，主角色规范码不落旧码 DEPT_WIDE 集合 → 本人兜底 fail-closed）→ 同页 1403 错误提示；
// ③ 服务层直调 getRecordDetail 不存在记录 1404（内部路径保持）。
// 用法: node c24-record-detail.js [--full]（需 IDE 服务端口 + useMock=true + packOptions 去 mock 排除；跑完还原）
//   默认仅跑①段（单次登录，可重复执行，D-03 口径）；--full 追加②③段（多次登录）。
// 2026-11-17 实测：① 6 项全 PASS（截图 shots/c24-record-detail-tech.png）；②③ 在第二次 login 处
// automator 超时挂起（docs/12 V1.17 连续 reLaunch 已知限制，两次复现）→ 按门禁规则停跑；
// 1403/1404/浅拷贝谓词等价已由 scripts/verify-p1-mock.js B8/I1/I2（Node 级 47 项）覆盖，UI 1403 提示归人工门禁。
const automator = require('miniprogram-automator'); const path = require('path')
const sleep = ms => new Promise(r => setTimeout(r, ms))
const FULL = process.argv.includes('--full') // ②③段多次登录有 IDE 挂起风险（V1.17），默认只跑①段
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
// 服务层直调内部端点，回传 ok/code 与投影字段
async function directDetail(mp, recordId) {
  return mp.evaluate(async (id) => {
    const order = require('services/order.js')
    try {
      const r = await order.getRecordDetail(id)
      return { ok: true, id: r.id, lifecycleStatus: r.lifecycleStatus, version: r.version, hashPrefix: r.hashPrefix, items: (r.items || []).length }
    } catch (e) { return { ok: false, code: e && e.code, message: e && e.message } }
  }, recordId)
}
;(async () => {
  let mp
  for (const port of [23854, 23853, 9421]) {
    try { mp = await automator.connect({ wsEndpoint: 'ws://localhost:' + port, timeout: 20000 }); break }
    catch (e) { /* 尝试下一端口 */ }
  }
  if (!mp) throw new Error('automator connect failed on 23854/23853/9421')
  await sleep(3000)

  // ① TECH 李振国：真实谓词命中（platformId=ur_1.workerPlatformId）→ 200 页面渲染
  await login(mp, '13800000011')
  let d = await directDetail(mp, 'ur_1')
  assert('TECH 直调 /records/ur_1 = 200', d.ok === true && d.id === 'ur_1', JSON.stringify(d))
  assert('内部投影含 lifecycleStatus/version/hashPrefix', d.ok && d.lifecycleStatus === 'PENDING_CLIENT_SIGNATURE' && d.version === 1 && !!d.hashPrefix, JSON.stringify(d))
  // mock ur_1 数据行无 context 字段（后端 toMap 才有，页面 r.context||{} 兜底）→ 只断言 items 明细
  assert('内部投影含 items 明细', d.ok && d.items > 0, JSON.stringify(d))
  let r = await goto(mp, '/pkg-maintenance/pages/record-detail/index?recordId=ur_1')
  assert('record-detail 页无错误', r.data.error === '' && !!r.data.record, JSON.stringify({ e: r.data.error, rec: !!r.data.record }))
  assert('页面记录=ur_1 且分组非空', r.data.record && r.data.record.id === 'ur_1' && (r.data.groups || []).length > 0, JSON.stringify({ id: r.data.record && r.data.record.id, g: (r.data.groups || []).length }))
  assert('生命周期=待使用单位签署 + 版本条 v1/哈希', r.data.lifecycleText === '待使用单位签署' && r.data.verify && r.data.verify.version === 'v1' && !!r.data.verify.hash, JSON.stringify({ lc: r.data.lifecycleText, v: r.data.verify }))
  await mp.screenshot({ path: path.join(__dirname, 'shots', 'c24-record-detail-tech.png') })

  if (!FULL) {
    console.log('SKIP ②③段（多次登录有 IDE 连续 reLaunch 挂起风险，docs/12 V1.17；--full 显式开启）')
  } else {
    // ② MAINT_MGR 赵经理：主角色规范码 MAINT_MGR 不落旧码 DEPT_WIDE 集合（MAINT_MANAGER）→ 本人兜底 fail-closed，platformId 990015 不命中 → 1403
    await login(mp, '13800000015')
    d = await directDetail(mp, 'ur_1')
    assert('MGR 直调 /records/ur_1 = 1403 班组谓词', d.ok === false && d.code === 1403, JSON.stringify(d))
    r = await goto(mp, '/pkg-maintenance/pages/record-detail/index?recordId=ur_1')
    assert('1403 页显错误且不渲染记录', r.data.error === '仅可查看本人或本班组记录' && !r.data.record, JSON.stringify({ e: r.data.error, rec: !!r.data.record }))
    await mp.screenshot({ path: path.join(__dirname, 'shots', 'c24-record-detail-1403.png') })

    // ③ 不存在记录（内部路径 1404 保持，TECH 会话）
    await login(mp, '13800000011')
    d = await directDetail(mp, 'ur_missing')
    assert('TECH 直调不存在记录 = 1404（内部路径保持）', d.ok === false && d.code === 1404, JSON.stringify(d))
  }

  console.log(fail === 0 ? 'ALL PASS' : fail + ' FAIL')
  await mp.disconnect()
  process.exit(fail === 0 ? 0 : 1)
})().catch(e => { console.error('ERR', e.message); process.exit(1) })
