const { getOrderDetail, signRequest, getRecordArchive, getRecordDetail } = require('../../../services/order')
const { CHECK_RESULT_TEXT, TOAST_TEXT, RECORD_TEXT, RECORD_LIFECYCLE_TEXT, RECORD_LIFECYCLE_TONE } = require('../../../constants/index')
const { buildRecordSummary } = require('../../utils/record-view')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const nav = require('../../../utils/nav')

const LIFECYCLE_TEXT = RECORD_LIFECYCLE_TEXT
const LIFECYCLE_TONE = RECORD_LIFECYCLE_TONE

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
        this.recordId = (o && (o.recordId || (o.recordInfo && o.recordInfo.id) || (o.record && o.record.id))) || ''
        this.load()
      }).catch(() => this.load())
    }
  },
  onShow() { this.load() },
  onBack() { nav.back() },
  load() {
    if (!this.recordId) { this.setData({ loading: false, error: '未找到记录单' }); return }
    this.setData({ loading: true, error: '' })
    getRecordDetail(this.recordId).then((r) => {
      const all = r.items || []
      const executed = all.filter((i) => i.notInThisRun !== true)
      const ctx = r.context || {}
      // 快照外的兜底：姓名为记录顶层字段；维保单位名快照键为 companyName；作业日期退签到/签退日
      ctx.workerName = ctx.workerName || r.workerName || ''
      ctx.assistantName = ctx.assistantName || r.assistantName || ''
      ctx.maintUnitName = ctx.companyName || ''
      ctx.workDate = ctx.workDate || String(r.checkoutTime || r.checkinTime || '').slice(0, 10)
      const photoList = []
      const seenPhotos = {}
      const addPhoto = (url, abnormal, itemName) => {
        if (!url || seenPhotos[url]) return
        seenPhotos[url] = true
        photoList.push({ url, abnormal: !!abnormal, itemName: itemName || '' })
      }
      executed.forEach((i) => (i.photos || []).forEach((url) => addPhoto(url, i.result === 'ABNORMAL', i.name)))
      ;(r.photos || []).forEach((url) => addPhoto(url, false, ''))
      const lc = r.lifecycleStatus || ''
      // 结论/统计/分组走纯函数投影：有异常项不得显示合格，无整改数据不得虚报已整改
      const summary = buildRecordSummary(executed)
      const stats = summary.stats
      const groups = summary.groups
      const abnormalDesc = summary.abnormalDesc
      const conclusion = summary.conclusion
      const signatures = { main: r.workerSignatureUrl || '', mainName: r.workerName || '', assist: r.assistantSignatureUrl || '', assistName: r.assistantName || '', client: r.signatureUrl || '', clientPending: r.confirmStatus !== 'CONFIRMED' }
      // frozenAt 为 mock 种子字段，保留兜底兼容
      const verify = (r.version || r.hashPrefix) ? { version: r.version ? 'v' + r.version : 'v1', hash: r.hashPrefix || '', time: r.archivedAt || r.frozenAt || '' } : null
      this.setData({ stats, groups, abnormalDesc, conclusion, signatures, verify,
        loading: false, record: r, context: ctx,
        items: executed.map((i) => Object.assign({}, i, {
          resultText: i.result ? (CHECK_RESULT_TEXT[i.result] || i.result) : '-',
          remark: i.abnormalDesc || i.valueText || (i.value != null ? '读数 ' + i.value + (i.valueUnit || '') : '') || i.skipReason || ''
        })),
        itemTotal: ctx.itemTotal || all.length,
        photoList,
        problemText: (r.problemCodes && r.problemCodes.length) ? r.problemCodes.join('、') : 'S0',
        confirmed: r.confirmStatus === 'CONFIRMED',
        lifecycleText: LIFECYCLE_TEXT[lc] || '', lifecycleTone: LIFECYCLE_TONE[lc] || 'dim'
      })
    }).catch((e) => this.setData({ loading: false, error: (e && e.message) || TOAST_TEXT.LOAD_FAILED }))
  },
  onRetry() { this.load() },
  previewPhoto(e) { wx.previewImage({ current: e.currentTarget.dataset.url, urls: this.data.photoList.map((p) => p.url) }) },
  onSignRequest() {
    signRequest(this.recordId || (this.data.record && this.data.record.id))
      .then(() => { wx.showToast({ title: '已发起使用单位签署', icon: 'success' }); this.load() })
      .catch((e) => wx.showToast({ title: (e && e.message) || '发起失败', icon: 'none' }))
  },
  onArchive() {
    getRecordArchive(this.recordId || (this.data.record && this.data.record.id))
      .then((a) => this.setData({ archive: a }))
      .catch((e) => wx.showToast({ title: (e && e.message) || '暂无归档', icon: 'none' }))
  },
  copySignLink() {
    const r = this.data.record
    if (!r || !r.shareToken) return wx.showToast({ title: '暂无确认链接', icon: 'none' })
    wx.setClipboardData({ data: '/pkg-client/pages/sign/index?taskId=' + r.id + '&token=' + r.shareToken, success: () => wx.showToast({ title: '确认链接已复制', icon: 'none' }) })
  }
})