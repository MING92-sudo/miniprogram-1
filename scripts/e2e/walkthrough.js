// 博威无纸化小程序 M2 模拟器走查（真实绘制取证）：登录→三工作台→切 Tab→403。
// 运行：node scripts/e2e/walkthrough.js（需微信开发者工具已登录；mock 模式，无需后端）。
// 截图存 scripts/e2e/shots/（gitignore）；控制台 error 收集；结构断言 + 真实绘制分开标注。
const path = require('path')
const fs = require('fs')
const automator = require('miniprogram-automator')

const CLI = 'C:\\Program Files (x86)\\Tencent\\微信web开发者工具\\cli.bat'
const PROJECT = path.join(__dirname, '..', '..')
const SHOTS = path.join(__dirname, 'shots')
if (!fs.existsSync(SHOTS)) fs.mkdirSync(SHOTS, { recursive: true })

const results = []
const consoleErrors = []
function ok(name, pass, detail) { results.push({ name, pass, detail: detail || '' }); console.log((pass ? 'PASS ' : 'FAIL ') + name + (detail ? ' — ' + detail : '')) }
async function shot(mp, name) { try { await mp.screenshot({ path: path.join(SHOTS, name + '.png') }) } catch (e) { console.log('shot fail ' + name + ': ' + e.message) } }
async function login(mp, phone) {
  await mp.reLaunch('/pages/login/index')
  await mp.waitFor(600)
  const page = await mp.currentPage()
  await page.waitFor(300)
  const inputs = await page.$$('input')
  if (inputs.length >= 2) { await inputs[0].input(phone); await inputs[1].input('123456') }
  // 勾选协议（第二个 .cb 为协议；第一个为记住账号）
  const cbs = await page.$$('.cb')
  if (cbs.length >= 2) await cbs[1].tap()
  const btn = await page.$('.lg-submit')
  if (btn) await btn.tap()
  await mp.waitFor(1200)
}

;(async () => {
  let mp
  try {
    if (process.env.WS_ENDPOINT) {
      mp = await automator.connect({ wsEndpoint: process.env.WS_ENDPOINT })
    } else {
      mp = await automator.launch({ cliPath: CLI, projectPath: PROJECT, args: ['--auto-port', '9420'] })
    }
  } catch (e) {
    console.log('LAUNCH_FAIL: ' + e.message)
    console.log('降级：开发者工具自动化不可用（未登录/端口/版本），真实绘制走查列待办，结构取证已交付。')
    process.exit(2)
  }
  try {
    mp.on('console', (msg) => { if (msg.type === 'error') consoleErrors.push(msg.args && msg.args.join(' ')) })
    // 切 mock 模式（运行时覆盖 config 单例，不改 tracked 文件）+ 清存储
    await mp.evaluate(() => { require('./config/index').useMock = true; wx.clearStorageSync() })
    await mp.waitFor(400)

    // ① 维保人员登录 → maint/home + 4 Tab
    await login(mp, '13800000011')
    let p = await mp.currentPage()
    ok('维保登录落 maint/home', p.path === 'pages/maint/home/index', p.path)
    await shot(mp, '01-maint-home')
    let tabs = await p.$$('.ct-item')
    ok('维保 4 Tab', tabs.length === 4, 'count=' + tabs.length)

    // ② 切 Tab：电梯（新页）
    const tabbar = await p.$('.ct-bar')
    if (tabbar) {
      const items = await p.$$('.ct-item')
      if (items[2]) await items[2].tap()
      await mp.waitFor(900)
      p = await mp.currentPage()
      ok('切电梯 Tab 落 elevator/list', p.path === 'pages/elevator/list/index', p.path)
      await shot(mp, '02-elevator-list')
    }

    // ③ 我的 Tab → profile
    p = await mp.currentPage()
    let items = await p.$$('.ct-item')
    if (items[3]) await items[3].tap()
    await mp.waitFor(900)
    p = await mp.currentPage()
    ok('切我的 Tab 落 profile', p.path === 'pages/profile/index', p.path)
    await shot(mp, '03-profile')

    // ④ 退出 → 登录张伟（CLIENT 双角色）→ client/home
    const logoutBtn = await p.$('.btn-ghost')
    if (logoutBtn) await logoutBtn.tap()
    await mp.waitFor(1000)
    await login(mp, '13800000012')
    p = await mp.currentPage()
    ok('张伟登录落 client/home', p.path === 'pages/client/home/index', p.path)
    await shot(mp, '04-client-home')

    // ⑤ 登录王强（INST_PM）→ install/home
    await mp.reLaunch('/pages/login/index')
    await mp.waitFor(500)
    await mp.evaluate(() => { wx.clearStorageSync() })
    await login(mp, '13800000013')
    p = await mp.currentPage()
    ok('王强登录落 install/home', p.path === 'pages/install/home/index', p.path)
    await shot(mp, '05-install-home')

    // ⑥ 403：维保人员访问 pkg-admin/settings（无权限）
    await mp.evaluate(() => { wx.clearStorageSync() })
    await login(mp, '13800000011')
    await mp.reLaunch('/pkg-admin/pages/settings/index')
    await mp.waitFor(900)
    p = await mp.currentPage()
    ok('无权限路由落 403', p.path === 'pages/403/index', p.path)
    await shot(mp, '06-403')

    ok('控制台无 error', consoleErrors.length === 0, 'errors=' + consoleErrors.length)
  } catch (e) {
    ok('走查异常', false, e.message)
  } finally {
    const passed = results.filter((r) => r.pass).length
    console.log('\nSUMMARY ' + passed + '/' + results.length + ' passed; consoleErrors=' + consoleErrors.length)
    fs.writeFileSync(path.join(__dirname, 'walkthrough-result.json'), JSON.stringify({ results, consoleErrors }, null, 2))
    if (mp) await mp.close()
    process.exit(passed === results.length ? 0 : 1)
  }
})()