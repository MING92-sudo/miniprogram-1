const fs = require('fs')
const path = require('path')
const html = fs.readFileSync('C:/Users/18223/Desktop/UI/index.html', 'utf8')
const m = html.match(/font-family:'ZCOOL QingKe HuangYou'[^}]*url\("?data:([^;]+);base64,([A-Za-z0-9+/=]+)"?\)/)
if (!m) { console.error('未找到 ZCOOL 内嵌子集'); process.exit(1) }
const ext = m[1].includes('woff2') ? 'woff2' : m[1].includes('woff') ? 'woff' : 'ttf'
const dir = path.join(__dirname, '../../assets/fonts')
fs.mkdirSync(dir, { recursive: true })
fs.writeFileSync(path.join(dir, 'zcool-proto-subset.' + ext), Buffer.from(m[2], 'base64'))
const REQUIRED = '博威无纸化电梯维保作业系统工作台移交验收半月保急修报修档案记录签字归档统计审计配置组织项目合同人员角色待办评价我的登录无权限空态加载失败早安今日任务班次已同步周五城东大厦客梯货梯困人救援完成进行中合格异常整改复验容缺审核资料上传发起双方使用单位签署确认拒绝原因版本修订导出搜索筛选全部本周一次合格率超时工单监管平台数据同步正常'
fs.writeFileSync(path.join(__dirname, 'required.txt'), [...new Set(REQUIRED)].join(''))
console.log(JSON.stringify({ protoExt: ext, requiredChars: [...new Set(REQUIRED)].length }))