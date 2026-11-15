const automator=require('miniprogram-automator');const path=require('path');const fs=require('fs')
const SHOTS=path.join(__dirname,'shots');if(!fs.existsSync(SHOTS))fs.mkdirSync(SHOTS,{recursive:true})
const sleep=ms=>new Promise(r=>setTimeout(r,ms))
async function cur(mp){for(let i=0;i<8;i++){try{const p=await mp.currentPage();if(p&&p.path)return p}catch(e){}await sleep(600)}return null}
async function inject(mp,roles,perms,home){await mp.evaluate((r,p,h)=>{wx.clearStorageSync();wx.setStorageSync('em_token','mock-g');wx.setStorageSync('em_roles',r);wx.setStorageSync('em_perms',p);wx.setStorageSync('em_role',r[0]);wx.setStorageSync('em_workspace',r[0]);wx.setStorageSync('em_home',h);wx.setStorageSync('em_user',{id:'emp_1',name:'李振国',role:r[0],groupName:'维保一组',jobNo:'T3101',unitName:'启元物业',jobTitle:'值班经理'})},roles,perms,home);await mp.evaluate(()=>{const a=getApp();a.restoreAuth();return a.loadMenus()});await sleep(1200)}
async function cap(mp,url,name){try{await mp.reLaunch(url);await sleep(2800);await mp.screenshot({path:path.join(SHOTS,name+'.png')});console.log('shot '+name)}catch(e){console.log('FAIL '+name+' '+e.message)}}
;(async()=>{
  const mp=await automator.connect({wsEndpoint:'ws://localhost:9421',timeout:60000});await sleep(3000)
  const ALL=['elevator:view','elevator:export','order:view_pool','order:view_own','order:grab','order:arrive','order:submit','order:confirm','order:cancel','order:create','record:view','record:sign_tech','record:sign_request','record:export','record:sign','checklist:fill','task:accept','plan:view','handover:view','handover:create','handover:inspect','handover:rectify','handover:review','handover:doc_upload','handover:sign_install','handover:sign_maint','client:sign','client:evaluate','project:view','contract:view','user:view','stats:view','audit:view','config:manage','role:assign']
  await inject(mp,['MAINT_TECH'],ALL,'/pages/maint/home/index');await sleep(600)
  await cap(mp,'/pages/maint/home/index','p01-maint-home')
  await cap(mp,'/pages/elevator/detail/index?id=el_1','p08-elevator-detail')
  await cap(mp,'/pages/order/list/index','p07-order-list')
  await cap(mp,'/pages/order/detail/index?id=ft_1','p06-order-detail')
  await cap(mp,'/pkg-maintenance/pages/task/index?orderId=wo_1','p04-task')
  await cap(mp,'/pkg-maintenance/pages/record-sign/index?orderId=wo_1','p05-record-sign')
  await cap(mp,'/pages/403/index','p12-403')
  await inject(mp,['CLIENT_ADMIN','CLIENT_SIGNER'],ALL,'/pages/client/home/index');await sleep(600)
  await cap(mp,'/pages/client/home/index','p10-client-home')
  await cap(mp,'/pkg-client/pages/sign/index?taskId=ur_1&token=mock','p11-client-sign')
  await inject(mp,['INST_PM'],ALL,'/pages/install/home/index');await sleep(600)
  await cap(mp,'/pages/install/home/index','p-install-home')
  await cap(mp,'/pkg-handover/pages/create/index','p09-handover-create')
  console.log('GALLERY DONE')
  await mp.disconnect()
})().catch(e=>{console.error('ERR',e.message);process.exit(1)})
