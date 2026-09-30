// 派单引擎验证脚本（node scripts/verify-dispatch.js，验证后自动清理）
const { formatTime } = require('../utils/util')
const d = require('../mock/data')
const DAY = 86400000
const now = Date.now()

// 场景：同一项目（uu_1）6 台电梯同日到期，绑定同一人李强 → 按用户 2026-09-30 确认口径，
// 到期一次性全部派单，计划时间统一为当日 09:00:00，作业时长由签退 30 分钟校验把关。
for (let i = 1; i <= 6; i++) {
  d.db.elevators.push({
    id: 'vt_' + i, elevatorCode: 'VT-' + i, elevatorName: '验证梯-' + i, useUnitId: 'uu_1', category: '曳引驱动电梯',
    maintenance: {
      intervalDays: 15, workerName: '李强', workerPhone: '13800000004',
      workerPlatformId: '6901284774286860288', assistantName: '', assistantPlatformId: '',
      lastMaintenanceAt: formatTime(new Date(now - 16 * DAY)).slice(0, 10)
    }
  })
}

const created = d.ensureDueOrders().filter(function (o) { return o.elevatorId.indexOf('vt_') === 0 })
console.log('== 同项目 6 台电梯同日到期，派给同一人 ==')
created.forEach(function (o) {
  const el = d.getElevator(o.elevatorId) || {}
  console.log('  ' + (el.elevatorName || o.elevatorId) + '  →  ' + o.planTime + '  派给:' + o.workerName + '  清单:' + o.checklist.length + '项')
})
const today = formatTime().slice(0, 10)
const t1 = created.filter(function (o) { return o.planTime.slice(0, 10) === today })
const t2 = created.filter(function (o) { return o.planTime.slice(0, 10) !== today })
const times = t1.map(function (o) { return o.planTime.slice(11) })
const allMorning = times.every(function (t) { return t === '09:00:00' })
console.log('当日台数(应=6):', t1.length, '| 顺延次日(应=0):', t2.length)
console.log('当日计划时间(应全部 09:00:00):', times.join(','))
if (t1.length !== 6 || t2.length !== 0 || !allMorning) process.exitCode = 1

// 清理测试数据
d.db.elevators = d.db.elevators.filter(function (e) { return e.id.indexOf('vt_') !== 0 })
d.db.orders = d.db.orders.filter(function (o) { return o.elevatorId.indexOf('vt_') !== 0 })
d.db.messages = d.db.messages.filter(function (m) { return (m.content || '').indexOf('验证梯') === -1 })
console.log('测试数据已清理，真实工单数:', d.db.orders.length)
