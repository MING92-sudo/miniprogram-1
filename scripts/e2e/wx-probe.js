// 微信登录 API 真实链路探测：模拟器 wx.login 取 code → 云托管 /auth/wx-login（jscode2session）。
// 只读探测：不落地会话；token 脱敏不打印。
const automator = require('miniprogram-automator'); const path = require('path')
const sleep = ms => new Promise(r => setTimeout(r, ms))
;(async () => {
  const mp = await automator.connect({ wsEndpoint: 'ws://localhost:9421', timeout: 60000 }); await sleep(1500)
  await mp.evaluate(() => {
    wx.__probe = null
    wx.login({
      success: (r) => {
        const code = r.code || ''
        if (!code) { wx.__probe = { step: 'wx.login', error: 'no code' }; return }
        wx.cloud.callContainer({
          config: { env: 'prod-d3gg6nba2f3160af9' },
          path: '/auth/wx-login', method: 'POST',
          header: { 'Content-Type': 'application/json', 'X-WX-SERVICE': 'springboot-l1ws' },
          data: { code },
          success: (res) => {
            const b = res.data || {}
            const d = b.data || {}
            wx.__probe = {
              codeLen: code.length, status: res.statusCode, code: b.code, message: b.message,
              hasToken: !!d.token, role: d.role || null, home: d.home || null
            }
          },
          fail: (e) => { wx.__probe = { step: 'callContainer', error: e && e.errMsg } }
        })
      },
      fail: (e) => { wx.__probe = { step: 'wx.login', error: e && e.errMsg } }
    })
  })
  await sleep(6000)
  const out = await mp.evaluate(() => wx.__probe)
  console.log('PROBE ' + JSON.stringify(out))
  await mp.disconnect()
})().catch(e => { console.error('ERR', e.message); process.exit(1) })