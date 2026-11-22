const automator = require('miniprogram-automator'); const path = require('path')
const sleep = ms => new Promise(r => setTimeout(r, ms))
const EXPECT = {
  '13800000001': { home: '/pages/maint/home/index', tabs: ['maint-home', 'maint-orders', 'maint-elevators', 'profile'] },
  '13800000002': { home: '/pages/maint/home/index', tabs: ['maint-home', 'maint-orders', 'maint-projects', 'stats', 'profile'] },
  '13800000015': { home: '/pages/maint/home/index', tabs: ['maint-home', 'maint-projects', 'maint-contracts', 'stats', 'profile'], noqa: 'filing' },
  '13800000013': { home: '/pages/install/home/index', tabs: ['install-home', 'handover-list', 'install-projects', 'profile'], noqa: 'filing' },
  '13800000014': { home: '/pages/install/home/index', tabs: ['install-home', 'handover-list', 'handover-docs', 'profile'] },
  '13800000099': { home: '/pages/install/home/index', tabs: ['install-home', 'handover-list', 'handover-review', 'stats', 'profile'] },
  '13800000017': { home: '/pages/install/home/index', tabs: ['install-home', 'handover-list', 'handover-docs', 'profile'] },
  '13800000003': { home: '/pages/client/home/index', tabs: ['client-home', 'client-todo', 'client-elevators', 'profile'], qa: ['report', 'elevators', 'todo'], noqa: ['emergency'] },
  '13800000018': { home: '/pages/client/home/index', tabs: ['client-home', 'client-todo', 'client-elevators', 'profile'], qa: ['report', 'elevators', 'todo'], noqa: ['emergency'] },
  '13800000009': { home: '/pkg-admin/pages/users/index', tabs: ['admin-org', 'stats', 'admin-audit', 'admin-settings', 'profile'] }
}
const SHOTS = ['13800000009', '13800000017', '13800000003', '13800000015']
;(async () => {
  let mp
  for (const port of [23854, 23853, 9421]) {
    try { mp = await automator.connect({ wsEndpoint: 'ws://localhost:' + port, timeout: 15000 }); break }
    catch (e) { /* 尝试下一端口 */ }
  }
  if (!mp) throw new Error('automator connect failed on 23854/23853/9421')
  await sleep(2000)
  let fail = 0
  for (const account of Object.keys(EXPECT)) {
    await mp.evaluate(() => wx.clearStorageSync())
    await mp.reLaunch('/pages/login/index'); await sleep(1800)
    const page = await mp.currentPage()
    await page.setData({ phone: account, password: '123456', agreed: true })
    await page.callMethod('onSubmit'); await sleep(2600)
    const g = await mp.evaluate(() => {
      const d = getApp().globalData
      return { home: d.home, ws: d.workspace, tabs: (d.tabs || []).map(t => t.key), qa: (d.quickActions || []).map(q => q.key) }
    })
    const e = EXPECT[account]
    const okHome = g.home === e.home
    const okTabs = JSON.stringify(g.tabs) === JSON.stringify(e.tabs)
    const okQa = [].concat(e.qa || []).every(k => g.qa.includes(k)) && ![].concat(e.noqa || []).some(k => g.qa.includes(k))
    const ok = okHome && okTabs && okQa
    if (!ok) fail++
    console.log((ok ? 'PASS ' : 'FAIL ') + account + ' ' + JSON.stringify(g))
    if (SHOTS.includes(account)) {
      await mp.screenshot({ path: path.join(__dirname, 'shots', 'role-' + account + '.png') })
    }
  }
  console.log(fail === 0 ? 'ALL PASS' : fail + ' FAIL')
  await mp.disconnect()
  process.exit(fail === 0 ? 0 : 1)
})().catch(e => { console.error('ERR', e.message); process.exit(1) })