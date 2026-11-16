// 逐账号渲染面断言（2026-11-15 裁决）：tabs/home/quickActions 与权限面一致，无跨部门渲染。
const automator = require('miniprogram-automator'); const path = require('path')
const sleep = ms => new Promise(r => setTimeout(r, ms))
const EXPECT = {
  '13800000001': { home: '/pages/maint/home/index', tabs: ['maint-home', 'maint-orders', 'maint-elevators', 'profile'] },
  '13800000002': { home: '/pages/maint/home/index', tabs: ['maint-home', 'maint-orders', 'maint-projects', 'stats', 'profile'] },
  '13800000015': { home: '/pages/maint/home/index', tabs: ['maint-home', 'maint-projects', 'maint-contracts', 'stats', 'profile'], qa: 'filing' },
  '13800000013': { home: '/pages/install/home/index', tabs: ['install-home', 'handover-list', 'install-projects', 'profile'], noqa: 'filing' },
  '13800000014': { home: '/pages/install/home/index', tabs: ['install-home', 'handover-list', 'handover-docs', 'profile'] },
  '13800000099': { home: '/pages/install/home/index', tabs: ['install-home', 'handover-list', 'handover-review', 'stats', 'profile'] },
  '13800000017': { home: '/pages/install/home/index', tabs: ['install-home', 'handover-list', 'handover-docs', 'profile'] },
  // P1 T6：客户快捷动作收敛为 elevators+todo，报修/急修进度下线（tabs 不变）
  '13800000003': { home: '/pages/client/home/index', tabs: ['client-home', 'client-todo', 'client-elevators', 'profile'], qa: ['elevators', 'todo'], noqa: ['report', 'emergency'] },
  '13800000018': { home: '/pages/client/home/index', tabs: ['client-home', 'client-todo', 'client-elevators', 'profile'], qa: ['elevators', 'todo'], noqa: ['report', 'emergency'] },
  '13800000009': { home: '/pkg-admin/pages/users/index', tabs: ['admin-org', 'stats', 'admin-audit', 'admin-settings', 'profile'] },
  '13800000010': { home: '/pkg-admin/pages/users/index', tabs: ['admin-org', 'stats', 'admin-audit', 'admin-settings', 'profile'] }
}
const SHOTS = ['13800000010', '13800000009', '13800000017', '13800000003', '13800000015']
;(async () => {
  const mp = await automator.connect({ wsEndpoint: 'ws://localhost:9421', timeout: 60000 }); await sleep(2000)
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
    // qa/noqa 支持字符串或数组（P1 T6 客户账号双键断言）：qa 全含 + noqa 全不含
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