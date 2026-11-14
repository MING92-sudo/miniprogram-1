// M2 数据源接口 + mock 实现（spec §6.2）；M3 起逐域换真实 API，组件不变
import { mockData, scopeElevatorCount } from '../mock-data'
export const provider = {
  getKPIs(scope) {
    const elev = scopeElevatorCount(scope)
    return [
      { key: 'projects', label: '在管项目', value: mockData.projects.length },
      { key: 'elevators', label: '在管电梯', value: elev },
      { key: 'handoverReview', label: '待审核移交', value: mockData.handovers.filter(h => h.status === 'REVIEWING').length },
      { key: 'recordReview', label: '待复核', value: mockData.records.filter(r => r.status === 'REVIEWING').length },
      { key: 'slaOverdue', label: 'SLA 超时', value: mockData.orders.filter(o => o.elapsed > o.slaMinutes).length },
      { key: 'contractExpiring', label: '合同临期≤90天', value: mockData.contracts.filter(c => c.daysLeft <= 90).length },
      { key: 'dualSignPending', label: '双签待补', value: mockData.records.filter(r => r.status === 'SIGNING').length }
    ]
  },
  getTodos(_scope) {
    return mockData.handovers.filter(h => h.status === 'REVIEWING').slice(0, 5)
      .map(h => ({ id: h.id, text: '移交待审核 ' + h.no, link: '/handovers' }))
  },
  getTableRows(domain) { return mockData[domain] || [] }
}