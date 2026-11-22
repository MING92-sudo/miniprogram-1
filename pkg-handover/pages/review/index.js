const { getHandoverDetail, getHandoverChecklist, reviewHandover, voidHandover } = require('../../../services/handover')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const nav = require('../../../utils/nav')

definePage(ROUTES['/pkg-handover/pages/review/index'], {
  data: {
    id: '', detail: null, check: null, loading: true, error: '',
    rejecting: false, rejectRemark: '', rejectDeadline: '', submitting: false
  },
  onLoad(query) { this.setData({ id: (query && query.id) || '' }); this.load() },
  onBack() { nav.back() },
  load() {
    this.setData({ loading: true, error: '' })
    Promise.all([
      getHandoverDetail(this.data.id),
      getHandoverChecklist(this.data.id).catch(() => null)
    ]).then(([detail, cl]) => {
      this.setData({ detail, check: cl && cl.check, loading: false, rejecting: false })
    }).catch((e) => this.setData({ loading: false, error: (e && e.message) || '加载失败' }))
  },
  onRetry() { this.load() },
  goInspect() { nav.open('/pkg-handover/pages/inspect/index?id=' + this.data.id) },
  goUpload() { nav.open('/pkg-handover/pages/upload/index?handoverId=' + this.data.id) },
  onApprove() {
    if (this.data.submitting) return
    this.setData({ submitting: true })
    reviewHandover(this.data.id, { action: 'APPROVE' })
      .then(() => { wx.showToast({ title: '审核通过', icon: 'success' }); this.setData({ submitting: false }); this.load() })
      .catch((e) => { this.setData({ submitting: false }); wx.showToast({ title: (e && e.message) || '审核失败', icon: 'none' }) })
  },
  onStartReject() { this.setData({ rejecting: true, rejectRemark: '', rejectDeadline: '' }) },
  onCancelReject() { this.setData({ rejecting: false }) },
  onRejectRemark(e) { this.setData({ rejectRemark: e.detail.value }) },
  onRejectDeadline(e) { this.setData({ rejectDeadline: e.detail.value }) },
  onConfirmReject() {
    if (!this.data.rejectRemark.trim()) return wx.showToast({ title: '请填写退回原因', icon: 'none' })
    if (!this.data.rejectDeadline.trim()) return wx.showToast({ title: '请填写整改期限', icon: 'none' })
    if (this.data.submitting) return
    this.setData({ submitting: true })
    reviewHandover(this.data.id, { action: 'REJECT', remark: this.data.rejectRemark.trim(), deadline: this.data.rejectDeadline.trim() })
      .then(() => { wx.showToast({ title: '已退回', icon: 'success' }); this.setData({ submitting: false }); this.load() })
      .catch((e) => { this.setData({ submitting: false }); wx.showToast({ title: (e && e.message) || '退回失败', icon: 'none' }) })
  },
  onVoid() {
    wx.showModal({
      title: '作废移交单', editable: true, placeholderText: '请填写作废原因（必填）',
      success: (res) => {
        if (!res.confirm) return
        const reason = (res.content || '').trim()
        if (!reason) return wx.showToast({ title: '作废原因必填', icon: 'none' })
        voidHandover(this.data.id, { reason })
          .then(() => { wx.showToast({ title: '已作废', icon: 'success' }); this.load() })
          .catch((e) => wx.showToast({ title: (e && e.message) || '作废失败', icon: 'none' }))
      }
    })
  }
})