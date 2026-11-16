// 记录单详情（M3 迁自 order/record-preview + 生命周期 UI）：展示实际执行项 + 生命周期状态条 + 发起签署/归档投影。
// 生命周期叠加在签退之后（V42）；record:review 在管理端执行，小程序仅展示状态（M0 澄清）。
const { getRecord } = require('../../../services/unit')
const { getOrderDetail, signRequest, getRecordArchive } = require('../../../services/order')
const { CHECK_RESULT_TEXT, TOAST_TEXT, RECORD_TEXT } = require('../../../constants/index')
const { buildRecordSummary } = require('../../../utils/record-view')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const nav = require('../../../utils/nav')

const LIFECYCLE_TEXT = {
  PENDING_REVIEW: '待复核', PENDING_CLIENT_SIGN: '待使用单位签字', PENDING_CLIENT_SIGNATURE: '待使用单位签署',
  SIGNED: '已签署', ARCHIVED: '已归档', REFUSED_RETURNED: '拒签退回'
}
const LIFECYCLE_TONE = {
  PENDING_REVIEW: 'amber', PENDING_CLIENT_SIGN: 'amber', PENDING_CLIENT_SIGNATURE: 'amber',
  SIGNED: 'ok', ARCHIVED: 'ok', REFUSED_RETURNED: 'signal'
}

definePage(ROUTES['/pkg-maintenance/pages/record-detail/index'], {
  data: {
    loading: true, error: '', record: null, context: null, items: [], itemTotal: 0, photoList: [],
    problemText: 'S0', confirmed: false, lifecycleText: '', lifecycleTone: 'dim', archive: null, text: RECORD_TEXT,
    stats: null, groups: [], abnormalDesc: '', conclusion: '', signatures: null, verify: null
  },
  onLoad(options) {
    this.recordId = (options && options.recordId) || ''
    this.orderId = (options && options.orderId) || ''
    if (!this.recordId && this.orderId) {
      getOrderDetail(this.orderId).then((o) => {
        this.recordId = (o && (o.recordId || o.record && o.record.id)) || ''
        this.load()
      }).catch(() => this.load())
    }
  },
  onShow() { this.load() },
  onBack() { nav.back() },
  load() {
    if (!this.recordId) { this.setData({ loading: false, error: '未找到记录单' }); return }
    this.setData({ loading: true, error: '' })
    getRecord(this.recordId).then((r) => {
      const all = r.items || []
      const executed = all.filter((i) => i.notInThisRun !== true)
      const ctx = r.context || {}
      const photoList = []
      executed.forEach((i) => (i.photos || []).forEach((url) => photoList.push({ url, abnormal: i.result === 'ABNORMAL', itemName: i.name })))
      const lc = r.lifecycleStatus || ''
      // 结论/统计/分组走纯函数投影：有异常项不得显示合格，无整改数据不得虚报已整改（2026-11-16 审查修复）
      const summary = buildRecordSummary(executed)
      const stats = summary.stats
      const groups = summary.groups
      const abnormalDesc = summary.abnormalDesc
      const conclusion = summary.conclusion
      const signatures = { main: r.workerSignatureUrl || '', mainName: r.workerName || '', assist: r.assistantSignatureUrl || '', assistName: r.assistantName || '', client: r.signatureUrl || '', clientPending: r.confirmStatus !== 'CONFIRMED' }
      const verify = (r.recordVersion || r.hashPrefix) ? { version: r.recordVersion || 'v1', hash: r.hashPrefix || '', time: r.frozenAt || '' } : null
      this.setData({ stats, groups, abnormalDesc, conclusion, signatures, verify,
        loading: false, record: r, context: ctx,
        items: executed.map((i) => Object.assign({}, i, {
          resultText: i.result ? (CHECK_RESULT_TEXT[i.result] || i.result) : '-',
          remark: i.abnormalDesc || i.valueText || (i.value != null ? '读数 ' + i.value + (i.valueUnit || '') : '') || i.skipReason || ''
        })),
        itemTotal: ctx.itemTotal || all.length,
        photoList: photoList.length ? photoList : (r.photos || []).map((url) => ({ url, abnormal: false, itemName: '' })),
        problemText: (r.problemCodes && r.problemCodes.length) ? r.problemCodes.join('、') : 'S0',
        confirmed: r.confirmStatus === 'CONFIRMED',
        lifecycleText: LIFECYCLE_TEXT[lc] || '', lifecycleTone: LIFECYCLE_TONE[lc] || 'dim'
      })
    }).catch((e) => this.setData({ loading: false, error: (e && e.message) || TOAST_TEXT.LOAD_FAILED }))
  },
  onRetry() { this.load() },
  previewPhoto(e) { wx.previewImage({ current: e.currentTarget.dataset.url, urls: this.data.photoList.map((p) => p.url) }) },
  onSignRequest() {
    signRequest(this.orderId || (this.data.record && this.data.record.workOrderId) || this.recordId)
      .then(() => { wx.showToast({ title: '已发起使用单位签署', icon: 'success' }); this.load() })
      .catch((e) => wx.showToast({ title: (e && e.message) || '发起失败', icon: 'none' }))
  },
  onArchive() {
    getRecordArchive(this.orderId || this.recordId)
      .then((a) => this.setData({ archive: a }))
      .catch((e) => wx.showToast({ title: (e && e.message) || '暂无归档', icon: 'none' }))
  },
  copySignLink() {
    const r = this.data.record
    if (!r || !r.shareToken) return wx.showToast({ title: '暂无确认链接', icon: 'none' })
    wx.setClipboardData({ data: '/pkg-client/pages/sign/index?taskId=' + r.id + '&token=' + r.shareToken, success: () => wx.showToast({ title: '确认链接已复制', icon: 'none' }) })
  }
})