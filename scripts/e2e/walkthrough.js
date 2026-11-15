// 博威无纸化小程序模拟器走查（真实绘制取证）：登录→三工作台→切 Tab→403。
// 前置（dev 测试态，跑完还原）：① config/index.js useMock=true；② project.config.json packOptions.ignore 临时移除 mock 项（否则 mock 不入编译包）；③ 重编译。
// 运行：cli auto --project <m2> --auto-port 9420，再 WS_ENDPOINT=ws://localhost:9420 node walkthrough.js
// 截图存 scripts/e2e/shots/（gitignore）。注：automator 登录表单输入偶有残留抖动，必要时用 evaluate 直接注入 em_* 会话绕过登录。
const path = require('path')
const fs = require('fs')
const automator = require('miniprogram-automator')

const CLI = 'C:\\Program Files (x86)\\Tencent\\微信web开发者工具\\cli.bat'
const PROJECT = path.join(__dirname, '..', '..')
const SHOTS = path.join(__dirname, 'shots')
if (!fs.existsSync(SHOTS)) fs.mkdirSync(SHOTS, { recursive: true })

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
async function curPage(mp){ for(let i=0;i<8;i++){ try{ const pg=await curPage(mp); if(pg&&pg.path) return pg }catch(e){} await sleep(700) } return await curPage(mp) }
const results = []
const consoleErrors = []
function ok(name, pass, detail) { results.push({ name, pass, detail: detail || '' }); console.log((pass ? 'PASS ' : 'FAIL ') + name + (detail ? ' — ' + detail : '')) }
async function getTabs(page){ try{ const tb=await page.$('custom-tabbar'); if(tb) return await tb.$('.ct-item'); }catch(e){} return await page.$('.ct-item') }
async function shot(mp, name) { try { await mp.screenshot({ path: path.join(SHOTS, name + '.png') }); console.log('  shot ' + name) } catch (e) { console.log('  shot fail ' + name + ': ' + e.message) } }
async function login(mp, phone) {
  await mp.reLaunch('/pages/login/index')
  await sleep(2000)
  const page = await curPage(mp)
  const inputs = await page.$$('input')
  if (inputs.length >= 2) { await inputs[0].input(phone); await inputs[1].input('123456') }
  const cbs = await page.$$('.cb')
  if (cbs.length >= 2) await cbs[1].tap()
  await sleep(200)
  const btn = await page.$('.lg-submit')
  if (btn) await btn.tap()
  await sleep(1800)
}

;(async () => {
  let mp
  try {
    if (process.env.WS_ENDPOINT) mp = await automator.connect({ wsEndpoint: process.env.WS_ENDPOINT })
    else mp = await automator.launch({ cliPath: CLI, projectPath: PROJECT, args: ['--auto-port', '9420'] })
  } catch (e) {
    console.log('LAUNCH_FAIL: ' + e.message)
    process.exit(2)
  }
  try {
    await sleep(3000)
    mp.on('console', (msg) => { if (msg.type === 'error') consoleErrors.push((msg.args || []).join(' ')) })
    await mp.evaluate(() => { wx.clearStorageSync() })
    await sleep(500)

    // ① 维保人员登录 → maint/home + 4 Tab
    await login(mp, '13800000011')
    let p = await curPage(mp)
    ok('维保登录落 maint/home', p.path === 'pages/maint/home/index', p.path)
    await shot(mp, '01-maint-home')
    let tabs = await getTabs(p)
    ok('维保 4 Tab', tabs.length === 4, 'count=' + tabs.length)

    // ② 切电梯 Tab
    let items = await getTabs(p)
    if (items[2]) { await items[2].tap(); await sleep(1200) }
    p = await curPage(mp)
    ok('切电梯 Tab 落 elevator/list', p.path === 'pages/elevator/list/index', p.path)
    await shot(mp, '02-elevator-list')

    // ③ 切我的 Tab
    items = await getTabs(p)
    if (items[3]) { await items[3].tap(); await sleep(1200) }
    p = await curPage(mp)
    ok('切我的 Tab 落 profile', p.path === 'pages/profile/index', p.path)
    await shot(mp, '03-profile')

    // ④ 退出 → 张伟（CLIENT 双角色）→ client/home
    const logoutBtn = await p.$('.btn-ghost')
    if (logoutBtn) { await logoutBtn.tap(); await sleep(1200) }
    await login(mp, '13800000012')
    p = await curPage(mp)
    ok('张伟登录落 client/home', p.path === 'pages/client/home/index', p.path)
    await shot(mp, '04-client-home')
    tabs = await getTabs(p)
    ok('张伟 4 Tab（含待办）', tabs.length === 4, 'count=' + tabs.length)

    // ⑤ 王强（INST_PM）→ install/home
    await mp.evaluate(() => { wx.clearStorageSync() })
    await login(mp, '13800000013')
    p = await curPage(mp)
    ok('王强登录落 install/home', p.path === 'pages/install/home/index', p.path)
    await shot(mp, '05-install-home')

    // ⑥ 403：维保人员访问 pkg-admin/settings（无权限）
    await mp.evaluate(() => { wx.clearStorageSync() })
    await login(mp, '13800000011')
    await mp.reLaunch('/pkg-admin/pages/settings/index')
    await sleep(1200)
    p = await curPage(mp)
    ok('无权限路由落 403', p.path === 'pages/403/index', p.path)
    await shot(mp, '06-403')

    ok('控制台无 error', consoleErrors.length === 0, 'errors=' + consoleErrors.length)
    if (consoleErrors.length) console.log('ERRORS:\n' + consoleErrors.slice(0, 10).join('\n'))
  } catch (e) {
    ok('走查异常', false, e.message)
  } finally {
    const passed = results.filter((r) => r.pass).length
    console.log('\nSUMMARY ' + passed + '/' + results.length + ' passed; consoleErrors=' + consoleErrors.length)
    fs.writeFileSync(path.join(__dirname, 'walkthrough-result.json'), JSON.stringify({ results, consoleErrors }, null, 2))
    try { if (mp) await mp.disconnect() } catch (e) { /* keep IDE open */ }
    process.exit(passed === results.length ? 0 : 1)
  }
})()