// 派单引擎验证脚本（node scripts/verify-dispatch.js，验证后自动清理）
const { formatTime } = require('../utils/util')
const d = require('../mock/data')
const DAY = 86400000
const now = Date.now()

// 场景：同一项目（uu_1）6 台电梯同日到期，绑定同一人李强 → 期望当日错开时段 4 台 + 次日 2 台
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
console.log('当日台数(应=4):', t1.length, '| 顺延次日(应=2):', t2.length)
console.log('时段错开(应互不相同):', t1.map(function (o) { return o.planTime.slice(11) }).join(','))

// 清理测试数据
d.db.elevators = d.db.elevators.filter(function (e) { return e.id.indexOf('vt_') !== 0 })
d.db.orders = d.db.orders.filter(function (o) { return o.elevatorId.indexOf('vt_') !== 0 })
d.db.messages = d.db.messages.filter(function (m) { return (m.content || '').indexOf('验证梯') === -1 })
console.log('测试数据已清理，真实工单数:', d.db.orders.length)
