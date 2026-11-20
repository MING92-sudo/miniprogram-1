// 派单引擎验证脚本（node scripts/verify-dispatch.js，验证后自动清理）
// 区域抢单池口径（D2/M4，2026-11-20）：派单不带人——worker*/assistant* 一律为空，
// 工单落 regionId 快照（= 电梯所在区域），状态 PENDING=待接单，由同区域持平台ID的维保员签到认领。
const { formatTime } = require('../utils/util')
const d = require('../mock/data')
const DAY = 86400000
const now = Date.now()

// 场景：同一项目（uu_1）6 台电梯同日到期（maintenance 里随意填了历史绑定字段——派单链路不再读取）
// → 到期一次性全部派单，计划时间统一为当日 09:00:00，工单不带人、落 regionId 快照。
for (let i = 1; i <= 6; i++) {
  d.db.elevators.push({
    id: 'vt_' + i, elevatorCode: 'VT-' + i, elevatorName: '验证梯-' + i, useUnitId: 'uu_1', category: '曳引与强制驱动电梯',
    regionId: 'rg_pool',
    maintenance: {
      intervalDays: 15, workerName: '李强', workerPhone: '13800000004',
      workerPlatformId: '6901284774286860288', assistantName: '', assistantPlatformId: '',
      lastMaintenanceAt: formatTime(new Date(now - 16 * DAY)).slice(0, 10)
    }
  })
}

const created = d.ensureDueOrders().filter(function (o) { return o.elevatorId.indexOf('vt_') === 0 })
console.log('== 同项目 6 台电梯同日到期，发布到区域池 ==')
created.forEach(function (o) {
  const el = d.getElevator(o.elevatorId) || {}
  console.log('  ' + (el.elevatorName || o.elevatorId) + '  →  ' + o.planTime + '  区域:' + o.regionId + '  认领人:' + (o.workerName || '（待接单）') + '  清单:' + o.checklist.length + '项')
})
const today = formatTime().slice(0, 10)
const t1 = created.filter(function (o) { return o.planTime.slice(0, 10) === today })
const t2 = created.filter(function (o) { return o.planTime.slice(0, 10) !== today })
const times = t1.map(function (o) { return o.planTime.slice(11) })
const allMorning = times.every(function (t) { return t === '09:00:00' })
console.log('当日台数(应=6):', t1.length, '| 顺延次日(应=0):', t2.length)
console.log('当日计划时间(应全部 09:00:00):', times.join(','))
// 池化断言：6 张工单全部不带人、regionId = 电梯 regionId 快照、状态 PENDING
const unstaffed = created.filter(function (o) {
  return o.workerName || o.workerPlatformId || o.assistantName || o.assistantPlatformId
})
const wrongRegion = created.filter(function (o) { return o.regionId !== 'rg_pool' })
const notPending = created.filter(function (o) { return o.status !== 'PENDING' })
console.log('带人工单(应=0):', unstaffed.length, '| regionId 错误(应=0):', wrongRegion.length, '| 非 PENDING(应=0):', notPending.length)
if (t1.length !== 6 || t2.length !== 0 || !allMorning) process.exitCode = 1
if (created.length !== 6 || unstaffed.length !== 0 || wrongRegion.length !== 0 || notPending.length !== 0) process.exitCode = 1

// 清理测试数据
d.db.elevators = d.db.elevators.filter(function (e) { return e.id.indexOf('vt_') !== 0 })
d.db.orders = d.db.orders.filter(function (o) { return o.elevatorId.indexOf('vt_') !== 0 })
d.db.messages = d.db.messages.filter(function (m) { return (m.content || '').indexOf('验证梯') === -1 })
console.log('测试数据已清理，真实工单数:', d.db.orders.length)
