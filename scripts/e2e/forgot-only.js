const automator = require('miniprogram-automator'); const path = require('path')
const sleep = ms => new Promise(r => setTimeout(r, ms))
;(async () => {
  const mp = await automator.connect({ wsEndpoint: 'ws://localhost:9421', timeout: 60000 }); await sleep(1500)
  await mp.reLaunch('/pages/login/forgot'); await sleep(3000)
  const p = await mp.currentPage(); console.log('current:', p && p.path)
  await mp.screenshot({ path: path.join(__dirname, 'shots', 'forgot-page.png') }); console.log('shot forgot')
  await mp.disconnect()
})().catch(e => { console.error('ERR', e.message); process.exit(1) })