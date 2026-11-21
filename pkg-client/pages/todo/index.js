// 待办签字（M5）：待签记录单 + 待确认急修单合并视图。P1 T8-B5：client:sign 为 CLIENT_ADMIN/
// CLIENT_SIGNER 双 Y（docs/14 §5.4 两角色能力相同），签字权由路由 perm 保证，页内不再算 canSign。
// P1 T8-I2（fix round 1，spec D1 精神）：客户急修确认前后端双重关闭（order:confirm 收回 T2、
// POST /faults/{id}/confirm 不在客户白名单 T3），客户主体隐藏「待确认急修单」区块并停发
// getUnitFaults（首页 KPI 聚合用途不受影响）；非客户主体保持原合并视图。
// V2.80：①签字提醒（临期/超期消息，点「知道了」标读）；②每条待签显示 SLA 剩余/超期标签并按紧迫排序；
// ③批量签字模式（勾选多条 → 批量签署页，同一签名一次确认，≤20 条）。
const unit = require('../../../services/unit')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const { isCustomerPrincipal } = require('../../../utils/auth')
const { parseTime } = require('../../../utils/util')
const nav = require('../../../utils/nav')

const BATCH_LIMIT = 20

// 每条待签的 SLA 标签（后端 signDeadline/overdue，V56）：超期 sig；<24h amber；其余 dim
function decorate(r) {
  const out = Object.assign({}, r)
  out.overdue = !!r.overdue
  out.deadlineLabel = ''
  out.deadlineClass = ''
  if (r.signDeadline) {
    const ms = parseTime(r.signDeadline) - Date.now()
    if (r.overdue || ms <= 0) {
      out.overdue = true
      out.deadlineLabel = '已超期'
      out.deadlineClass = 'sig'
    } else if (ms < 24 * 3600000) {
      out.deadlineLabel = '剩 ' + Math.max(1, Math.floor(ms / 3600000)) + ' 小时'
      out.deadlineClass = 'amber'
    } else {
      out.deadlineLabel = '剩 ' + Math.floor(ms / 86400000) + ' 天'
      out.deadlineClass = 'dim'
    }
  }
  return out
}

// 紧迫度排序：超期在前，其余按截止时间近远；无截止的排最后
function urgency(a, b) {
  if (!!a.overdue !== !!b.overdue) return a.overdue ? -1 : 1
  const ta = a.signDeadline ? parseTime(a.signDeadline) : Infinity
  const tb = b.signDeadline ? parseTime(b.signDeadline) : Infinity
  return ta - tb
}

definePage(ROUTES['/pkg-client/pages/todo/index'], {
  data: {
    records: [], faults: [], loading: true, error: '', showFaults: false,
    messages: [], batchMode: false, selected: {}, selectedCount: 0, batchLimit: BATCH_LIMIT
  },
  onShow() { this.load() },
  onBack() { nav.back() },
  load() {
    const customer = isCustomerPrincipal()
    this.setData({ loading: true, error: '', showFaults: !customer, batchMode: false, selected: {}, selectedCount: 0 })
    Promise.all([
      unit.getUnitPendingSigns().catch(() => []),
      customer ? Promise.resolve([]) : unit.getUnitFaults().catch(() => []),
      unit.getUnitMessages().catch(() => [])
    ]).then(([records, faults, messages]) => {
      this.setData({
        records: (records || []).map(decorate).sort(urgency),
        faults: (faults || []).filter((f) => f.status === 'PENDING_CONFIRM'),
        messages: (messages || []).filter((m) => !m.readFlag).slice(0, 3),
        loading: false
      })
    }).catch((e) => this.setData({ loading: false, error: (e && e.message) || '加载失败' }))
  },
  onRetry() { this.load() },
  // 签字提醒标读（「知道了」）：先本地移除再通知后端，失败不打扰（下次 onShow 会拉回）
  dismissMessage(e) {
    const id = e.currentTarget.dataset.id
    this.setData({ messages: this.data.messages.filter((m) => m.id !== id) })
    unit.readUnitMessage(id).catch(() => {})
  },
  toggleBatch() {
    const on = !this.data.batchMode
    this.setData({ batchMode: on, selected: {}, selectedCount: 0 })
  },
  toggleSelect(e) {
    const id = e.currentTarget.dataset.id
    const selected = Object.assign({}, this.data.selected)
    if (selected[id]) {
      delete selected[id]
    } else {
      if (Object.keys(selected).length >= BATCH_LIMIT) {
        return wx.showToast({ title: '单次最多批量签署 ' + BATCH_LIMIT + ' 条', icon: 'none' })
      }
      selected[id] = true
    }
    this.setData({ selected, selectedCount: Object.keys(selected).length })
  },
  goBatchSign() {
    const ids = Object.keys(this.data.selected)
    if (!ids.length) return wx.showToast({ title: '请先勾选要签署的记录', icon: 'none' })
    nav.open('/pkg-client/pages/sign/index?mode=batch&ids=' + ids.join(','))
  },
  // 记录卡点击：WXML 事件绑定为静态字符串，按模式在 JS 内分支（批量=勾选，否则=去签字）
  onRecordTap(e) {
    if (this.data.batchMode) return this.toggleSelect(e)
    this.goSign(e)
  },
  goSign(e) {
    const r = e.currentTarget.dataset.item
    nav.open('/pkg-client/pages/sign/index?taskId=' + r.id + '&token=' + (r.shareToken || ''))
  },
  goFault(e) { nav.open('/pages/order/detail/index?id=' + e.currentTarget.dataset.id) }
})
