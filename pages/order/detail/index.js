// 急修单详情（M3，屏 06）：七态流转（M0 §3）OPEN→ASSIGNED→PROCESSING→PENDING_CONFIRM→CLOSED/REFUSED/CANCELLED。
// 接 M1 端点 grab/arrive/submit/confirm/cancel/evaluate；到场显式 GPS 打卡；维保工单绝不套此七态（§1.3）。
const { getFaultDetail, updateFault, grabFault, arriveFault, submitFault, confirmFault, cancelFault } = require('../../../services/fault')
const { formatTime } = require('../../../utils/util')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const { getRoles } = require('../../../utils/auth')
const nav = require('../../../utils/nav')
const { TOAST_TEXT } = require('../../../constants/index')

const STATUS_TEXT = {
  OPEN: '待接单', ASSIGNED: '已接单', PROCESSING: '处置中', PENDING_CONFIRM: '待使用单位确认',
  CLOSED: '已闭环', REFUSED: '待整改', CANCELLED: '已取消'
}
const STATUS_TONE = {
  OPEN: 'signal', ASSIGNED: 'amber', PROCESSING: 'amber', PENDING_CONFIRM: 'amber',
  CLOSED: 'ok', REFUSED: 'signal', CANCELLED: 'dim'
}

definePage(ROUTES['/pages/order/detail/index'], {
  data: {
    id: '', fault: null, statusText: '', statusTone: 'dim', loading: true, error: '',
    siteDesc: '', todoDesc: '', handleDesc: '', submitting: false,
    canConfirm: false, isClient: false
  },
  onLoad(query) { this.setData({ id: (query && query.id) || '' }); this.fetchDetail() },
  onShow() { if (this.data.id) this.fetchDetail() },
  onBack() { nav.back() },
  fetchDetail() {
    this.setData({ loading: true, error: '' })
    getFaultDetail(this.data.id).then((fault) => {
      const roles = getRoles()
      const isClient = roles.some((r) => r.indexOf('CLIENT') === 0)
      this.setData({
        fault, loading: false,
        statusText: STATUS_TEXT[fault.status] || fault.status, statusTone: STATUS_TONE[fault.status] || 'dim',
        siteDesc: fault.siteDesc || '', todoDesc: fault.todoDesc || '', handleDesc: fault.handleDesc || fault.result || '',
        isClient, canConfirm: roles.indexOf('CLIENT_SIGNER') > -1
      })
    }).catch((e) => this.setData({ loading: false, error: (e && e.message) || TOAST_TEXT.LOAD_FAILED }))
  },
  onRetry() { this.fetchDetail() },
  onGrab() {
    grabFault(this.data.id).then(() => { wx.showToast({ title: '已接单', icon: 'success' }); this.fetchDetail() })
      .catch((e) => wx.showToast({ title: (e && e.message) || '抢单失败', icon: 'none' }))
  },
  onArrive() {
    wx.getLocation({
      type: 'gcj02',
      success: (loc) => arriveFault(this.data.id, loc.latitude, loc.longitude)
        .then(() => { wx.showToast({ title: '已到场打卡', icon: 'success' }); this.fetchDetail() })
        .catch((e) => wx.showToast({ title: (e && e.message) || '打卡失败', icon: 'none' })),
      fail: () => wx.showToast({ title: '到场打卡需要定位权限', icon: 'none' })
    })
  },
  onSiteInput(e) { this.setData({ siteDesc: e.detail.value }) },
  onTodoInput(e) { this.setData({ todoDesc: e.detail.value }) },
  onHandleInput(e) { this.setData({ handleDesc: e.detail.value }) },
  onSaveProcess() {
    if (this.data.submitting) return
    this.setData({ submitting: true })
    updateFault(this.data.id, { siteDesc: this.data.siteDesc, todoDesc: this.data.todoDesc, handleDesc: this.data.handleDesc, finishedAt: formatTime() })
      .then(() => { wx.showToast({ title: '维修过程已保存', icon: 'success' }); this.setData({ submitting: false }) })
      .catch((e) => { this.setData({ submitting: false }); wx.showToast({ title: (e && e.message) || TOAST_TEXT.SAVE_FAILED, icon: 'none' }) })
  },
  onSubmitFault() {
    if (!this.data.handleDesc) return wx.showToast({ title: '请填写处理结果（措施）', icon: 'none' })
    if (!this.data.siteDesc) return wx.showToast({ title: '请填写现场情况（原因）', icon: 'none' })
    submitFault(this.data.id, { siteDesc: this.data.siteDesc, todoDesc: this.data.todoDesc, handleDesc: this.data.handleDesc })
      .then(() => { wx.showToast({ title: '已提交，待使用单位确认', icon: 'success' }); this.fetchDetail() })
      .catch((e) => wx.showToast({ title: (e && e.message) || '提交失败', icon: 'none' }))
  },
  onConfirm(e) {
    const pass = e.currentTarget.dataset.pass !== false && e.currentTarget.dataset.pass !== 'false'
    if (!pass) {
      return wx.showModal({
        title: '拒绝并说明', editable: true, placeholderText: '请填写拒绝原因（必填）',
        success: (res) => {
          if (!res.confirm) return
          const reason = (res.content || '').trim()
          if (!reason) return wx.showToast({ title: '拒绝原因必填', icon: 'none' })
          confirmFault(this.data.id, false, reason).then(() => { wx.showToast({ title: '已退回整改', icon: 'success' }); this.fetchDetail() })
            .catch((er) => wx.showToast({ title: (er && er.message) || '操作失败', icon: 'none' }))
        }
      })
    }
    confirmFault(this.data.id, true, '').then(() => { wx.showToast({ title: '已确认闭环', icon: 'success' }); this.fetchDetail() })
      .catch((er) => wx.showToast({ title: (er && er.message) || '操作失败', icon: 'none' }))
  },
  onCancel() {
    wx.showModal({
      title: '取消急修单', editable: true, placeholderText: '请填写取消原因（必填）',
      success: (res) => {
        if (!res.confirm) return
        const reason = (res.content || '').trim()
        if (!reason) return wx.showToast({ title: '取消原因必填', icon: 'none' })
        cancelFault(this.data.id, reason).then(() => { wx.showToast({ title: '已取消', icon: 'success' }); this.fetchDetail() })
          .catch((er) => wx.showToast({ title: (er && er.message) || '取消失败', icon: 'none' }))
      }
    })
  },
  onEvaluate() { nav.open('/pkg-client/pages/evaluate/index?orderId=' + this.data.id) },
  onPreviewPhoto(e) { wx.previewImage({ urls: this.data.fault.photos || [], current: e.currentTarget.dataset.url }) }
})