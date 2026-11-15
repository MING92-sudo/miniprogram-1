// 逐页真实绘制取证（ resilient 版）：每页独立 try/catch，单页超时不中断整体。
const path=require('path'); const fs=require('fs'); const automator=require('miniprogram-automator')
const SHOTS=path.join(__dirname,'shots'); if(!fs.existsSync(SHOTS))fs.mkdirSync(SHOTS,{recursive:true})
const sleep=ms=>new Promise(r=>setTimeout(r,ms))
const results=[]
function ok(n,p,d){results.push({n,p,d:d||''});console.log((p?'PASS ':'FAIL ')+n+(d?' — '+d:''))}
async function cur(mp){for(let i=0;i<6;i++){try{const pg=await mp.currentPage();if(pg&&pg.path)return pg}catch(e){}await sleep(500)}return null}
async function shot(mp,n){try{await mp.screenshot({path:path.join(SHOTS,n+'.png')});console.log('  shot '+n)}catch(e){console.log('  shot fail '+n)}}
async function login(mp,phone){await mp.reLaunch('/pages/login/index');await sleep(2200);const pg=await cur(mp);if(!pg)return;const ins=await pg.$$('input');if(ins&&ins.length>=2){await ins[0].input(phone);await ins[1].input('123456')}const cbs=await pg.$$('.cb');if(cbs&&cbs.length>=2)await cbs[1].tap();await sleep(200);const b=await pg.$('.lg-submit');if(b)await b.tap();await sleep(2500)}
async function visit(mp,url,expect,name){try{await mp.reLaunch(url);await sleep(2500);const pg=await cur(mp);const got=pg?pg.path:'(null)';ok(name,got===expect,got);await shot(mp,name)}catch(e){ok(name,false,'timeout/err')}}
;(async()=>{
  const mp=await automator.connect({wsEndpoint:'ws://localhost:9420',timeout:60000})
  await sleep(3000)
  try{ await mp.evaluate(()=>{wx.clearStorageSync()}) }catch(e){}
  await sleep(500)
  // 维保
  try{await login(mp,'13800000011');const pg=await cur(mp);ok('维保→maint/home',pg&&pg.path==='pages/maint/home/index',pg?pg.path:'null');await shot(mp,'g01-maint-home')}catch(e){ok('维保登录',false,e.message)}
  await visit(mp,'/pages/elevator/list/index','pages/elevator/list/index','g02-elevator-list')
  await visit(mp,'/pages/elevator/detail/index?id=el_1','pages/elevator/detail/index','g03-elevator-detail')
  await visit(mp,'/pkg-maintenance/pages/orders/index','pkg-maintenance/pages/orders/index','g04-maint-orders')
  await visit(mp,'/pkg-maintenance/pages/plan/index','pkg-maintenance/pages/plan/index','g05-plan')
  await visit(mp,'/pages/profile/index','pages/profile/index','g06-profile')
  await visit(mp,'/pkg-admin/pages/settings/index','pages/403/index','g07-403')
  // 单位
  try{await mp.evaluate(()=>{wx.clearStorageSync()})}catch(e){}
  try{await login(mp,'13800000012');const pg=await cur(mp);ok('张伟→client/home',pg&&pg.path==='pages/client/home/index',pg?pg.path:'null');await shot(mp,'g08-client-home')}catch(e){ok('单位登录',false,e.message)}
  await visit(mp,'/pkg-client/pages/todo/index','pkg-client/pages/todo/index','g09-client-todo')
  // 平台
  try{await mp.evaluate(()=>{wx.clearStorageSync()})}catch(e){}
  try{await login(mp,'13800000016');const pg=await cur(mp);ok('平台→admin/users',pg&&pg.path==='pkg-admin/pages/users/index',pg?pg.path:'null');await shot(mp,'g10-admin-users')}catch(e){ok('平台登录',false,e.message)}
  await visit(mp,'/pkg-admin/pages/stats/index','pkg-admin/pages/stats/index','g11-stats')
  await visit(mp,'/pkg-admin/pages/settings/index','pkg-admin/pages/settings/index','g12-settings')
  const passed=results.filter(r=>r.p).length
  console.log('\nGALLERY SUMMARY '+passed+'/'+results.length)
  fs.writeFileSync(path.join(__dirname,'gallery-result.json'),JSON.stringify(results,null,2))
  try{await mp.disconnect()}catch(e){}
  process.exit(0)
})()
