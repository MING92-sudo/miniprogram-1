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
// IDE 自动化连续 reLaunch 偶发时序抖动：轮询页面数据就绪，导航失败重试一次
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
;(async () => {
  let mp
  for (const port of [9420, 23854, 23853, 9421]) {
    try { mp = await automator.connect({ wsEndpoint: 'ws://localhost:' + port, timeout: 20000 }); break }
    catch (e) { /* 尝试下一端口 */ }
  }
  if (!mp) throw new Error('automator connect failed on 23854/23853/9421')
  await sleep(3000)

  // ① 客户王芳（uu_1 CLIENT_ADMIN）登录
  await login(mp, '13800000003')

  // ② 待办签字列表：ur_1 待签且带 shareToken
  let r = await goto(mp, '/pkg-client/pages/todo/index', (d) => d.loading === false)
  const rec = (r.data.records || []).find((x) => x.id === 'ur_1')
  assert('待办签字含 ur_1 且带 shareToken', !!rec && !!rec.shareToken, JSON.stringify({ n: (r.data.records || []).length }))

  // ③ 签署确认页：维保记录预览渲染
  r = await goto(mp, '/pkg-client/pages/sign/index?taskId=ur_1&token=sg-mock-1', (d) => !!d.view || !!d.loadError)
  const d = r.data
  const page = r.page
  assert('sign-view 加载成功无错误', d.loadError === '' && !!d.view, JSON.stringify({ e: d.loadError }))
  assert('使用单位/维保日期真实下发（失配修复）', d.view && d.view.useUnitName === '重庆世纪物业管理有限公司' && /^\d{4}-\d{2}-\d{2}$/.test(d.view.workDate || ''), JSON.stringify({ u: d.view && d.view.useUnitName, w: d.view && d.view.workDate }))
  const items = (d.view && d.view.items) || []
  const ab = items.filter((i) => i.result === 'ABNORMAL')
  assert('维保项目明细非空且含异常项', items.length > 0 && ab.length > 0 && !!ab[0].abnormalDesc, JSON.stringify({ n: items.length, ab: ab.length }))
  assert('异常项带序号/徽标/文案装饰', ab.length > 0 && ab[0].seq > 0 && ab[0].resultClass === 'ab' && ab[0].resultText === '异常', JSON.stringify(ab[0] && { seq: ab[0].seq, rc: ab[0].resultClass, rt: ab[0].resultText }))
  assert('summary 聚合异常整改文本', !!d.summary && (d.summary.abnormalDesc || '').indexOf('报警') > -1, JSON.stringify({ s: d.summary && d.summary.abnormalDesc }))
  assert('现场照片墙非空', d.view && (d.view.photos || []).length > 0, JSON.stringify({ n: d.view && (d.view.photos || []).length }))
  assert('签署前核对含版本号/哈希', d.view && !!d.view.recordVersion && !!d.view.hashPrefix, JSON.stringify({ v: d.view && d.view.recordVersion, h: d.view && d.view.hashPrefix }))
  assert('待签状态（未确认未拒签）', d.confirmed === false && d.refused === false, JSON.stringify({ c: d.confirmed, r: d.refused }))

  // ④ DOM 渲染核对（WXML 绑定生效）
  const rows = await page.$$('.ci-row')
  assert('DOM 维保项目行数=明细数', rows.length === items.length, 'rows=' + rows.length + ' items=' + items.length)
  const abBadge = await page.$('.ci-badge.ab')
  assert('DOM 异常徽标渲染', !!abBadge)
  const thumbs = await page.$$('.photo-row .thumb')
  assert('DOM 照片缩略图渲染', thumbs.length > 0, 'thumbs=' + thumbs.length)
  await mp.screenshot({ path: path.join(__dirname, 'shots', 'client-sign-preview.png') })

  console.log(fail === 0 ? 'ALL PASS' : fail + ' FAIL')
  await mp.disconnect()
  process.exit(fail === 0 ? 0 : 1)
})().catch(e => { console.error('ERR', e.message); process.exit(1) })
