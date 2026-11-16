// 工作台汇总（M2，规格 §5.1）：客户端聚合既有端点，不新增后端接口。
// 每个数据源独立 catch 降级（该区块空态，不整页失败）；页面只调本模块。
const { get } = require('../utils/request')
const order = require('./order')
const fault = require('./fault')
const handover = require('./handover')
const unit = require('./unit')
const { STATUS_TEXT } = require('../constants/index')

function arr(x) { return Array.isArray(x) ? x : ((x && x.list) || []) }
function pad(n) { return String(n || 0).padStart(2, '0') }
function today() { const d = new Date(); return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) }
function hm(t) { return String(t || '').slice(11, 16) }
function orderTone(s) { return s === 'DONE' ? 'ok' : 'amber' }

// 维保工作台（屏 01）：KPI / 今日任务 / 困人急修横幅 / 周统计 / 同步注
function getMaintHome() {
  return Promise.all([
    get('/home/summary').catch(() => ({})),
    order.getOrderList({ page: 1, size: 20 }).catch(() => ({ list: [] })),
    fault.getFaultList({ page: 1, size: 20 }).catch(() => ({ list: [] }))
  ]).then(([summary, ordersRes, faultsRes]) => {
    const orders = arr(ordersRes)
    const faults = arr(faultsRes)
    const open = faults.filter((f) => f.status === 'OPEN')
    const trapped = open.filter((f) => (f.trappedCount || 0) > 0)
    const due = (summary.dueToday || 0) + (summary.dueSoon || 0) + (summary.overdue || 0)
    const kpis = [
      { value: pad(due), label: '待维保', path: '/pkg-maintenance/pages/orders/index' },
      { value: pad(trapped.length), label: '急修待接', alert: true, path: '/pages/order/list/index?state=urgent' },
      { value: pad(open.length - trapped.length), label: '报修待接', path: '/pages/order/list/index?state=pending' }
    ]
    const t = today()
    const tasks = orders
      .filter((o) => String(o.planTime || '').slice(0, 10) === t)
      .slice(0, 3)
      .map((o) => ({
        id: o.id, kind: 'order', time: hm(o.planTime),
        title: (o.workType || '维保') + ' · ' + (o.elevatorName || o.elevatorCode || ''),
        desc: (o.projectName || '') + ' · ' + (STATUS_TEXT[o.status] || ''),
        tagText: STATUS_TEXT[o.status] || '待执行', tagTone: orderTone(o.status)
      }))
    open.slice(0, 2).forEach((f) => tasks.push({
      id: f.id, kind: 'fault', time: hm(f.createdAt),
      title: '急修 · ' + (f.elevatorName || f.elevatorCode || ''),
      desc: f.desc || '', tagText: '急修', tagTone: 'signal'
    }))
    const sosSrc = trapped[0] || faults.filter((f) => f.status === 'CLOSED')[0] || null
    const sos = sosSrc ? {
      id: sosSrc.id,
      title: sosSrc.elevatorName || sosSrc.elevatorCode || '急修单',
      desc: sosSrc.desc || '',
      no: sosSrc.id, time: hm(sosSrc.createdAt),
      statusText: sosSrc.status === 'CLOSED' ? '困人急修 · 已闭环' : '困人急修 · 待接单',
      statusTone: sosSrc.status === 'CLOSED' ? 'ok' : 'signal'
    } : null
    const doneWeek = orders.filter((o) => o.status === 'DONE').length
    const week = [
      { value: pad(doneWeek), label: '本周完成 / 台次', tone: 'amber' },
      { value: '—', label: '一次合格率' },
      { value: pad(summary.overdue), label: '超时工单', tone: 'ok' }
    ]
    const sync = { title: '监管平台数据同步正常', desc: '已对接电梯 ' + (summary.platformTotal || 0) + ' 台', status: '已同步' }
    return { kpis, tasks, sos, week, sync }
  })
}

// 安装工作台（屏 01 范式适配）：移交 KPI / 进行中移交 / 周统计
function getInstallHome() {
  return handover.getHandoverList({ page: 1, size: 20 }).catch(() => ({ list: [] })).then((res) => {
    const list = arr(res)
    const count = (s) => list.filter((h) => h.status === s).length
    const kpis = [
      { value: pad(count('DOC_SUBMITTING') + count('INIT')), label: '待移交' },
      { value: pad(count('PENDING_INST_REVIEW')), label: '待审核', alert: true },
      { value: pad(count('RECTIFYING')), label: '整改中' }
    ]
    const tasks = list.filter((h) => h.status !== 'ARCHIVED').slice(0, 3).map((h) => ({
      id: h.id, kind: 'handover', time: hm(h.createdAt),
      title: '移交 · ' + (h.projectName || h.id),
      desc: (h.elevatorName || '') + ' · ' + (h.status || ''),
      tagText: '移交', tagTone: 'amber'
    }))
    const week = [
      { value: pad(list.length), label: '移交单总数', tone: 'amber' },
      { value: pad(count('ARCHIVED')), label: '已生效', tone: 'ok' },
      { value: pad(count('RECTIFYING')), label: '整改中' }
    ]
    return { kpis, tasks, week }
  })
}

// 使用单位工作台（屏 10）：待签/待评价 / KPI / 我的电梯 / 最近记录 / 年检提醒
function getClientHome() {
  return Promise.all([
    unit.getUnitPendingSigns().catch(() => []),
    unit.getUnitFaults().catch(() => []),
    unit.getUnitElevators().catch(() => [])
  ]).then(([signsRes, faultsRes, elevRes]) => {
    const signs = arr(signsRes)
    const faults = arr(faultsRes)
    const elevators = arr(elevRes)
    const signAlerts = signs.slice(0, 2).map((r) => ({
      id: r.id, kind: 'record',
      title: (r.elevatorName || '') + ' · 半月保记录单',
      desc: (r.id || '') + ' · 待使用单位签署', action: '去签字'
    }))
    faults.filter((f) => f.status === 'CLOSED' && !f.evaluatedAt).slice(0, 1).forEach((f) => signAlerts.push({
      id: f.id, kind: 'fault',
      title: (f.elevatorName || f.elevatorCode || '') + ' · 急修单',
      desc: (f.desc || '') + ' · 已闭环 · 7 天内可评价', action: '去评价'
    }))
    const kpis = [
      { value: pad(elevators.length), label: '在管电梯', tone: 'amber' },
      { value: pad(faults.filter((f) => f.status === 'CLOSED').length), label: '本月已维保' },
      { value: pad(signs.length), label: '待签署', tone: 'sig' }
    ]
    const elevRows = elevators.slice(0, 2).map((e) => ({
      id: e.id, name: e.elevatorName || e.elevatorCode,
      desc: '下次维保 ' + String(e.nextMaintenanceAt || e.nextCheckDate || '').slice(0, 10),
      tagText: '正常', tagTone: 'ok'
    }))
    const records = signs.slice(0, 2).map((r) => ({
      id: r.id, title: (r.elevatorName || '') + ' · 半月保记录单',
      desc: '待使用单位签署', action: '签署'
    }))
    const notice = elevators.length ? '电梯年检到期前 90 天系统自动提醒，请留意消息中心。' : ''
    return { signAlerts, kpis, elevators: elevRows, records, notice }
  })
}

module.exports = { getMaintHome, getInstallHome, getClientHome }