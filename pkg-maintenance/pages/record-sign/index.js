// 维保双人签字（M3，屏 05）：主检/配合各签各的（POST /work-orders/{id}/signature，后端严格校验角色）+ 版本号/哈希核对。
// 同一账号不能同时主检与配合；双签齐才可发起使用单位签字（record-detail）。
const { getOrderDetail, signOrder } = require('../../../services/order')
const { uploadImage } = require('../../../services/upload')
const { getUserInfo } = require('../../../utils/auth')
const { buildSignState } = require('../../../utils/record-view')
const { RECORD_TEXT } = require('../../../constants/index')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const nav = require('../../../utils/nav')

definePage(ROUTES['/pkg-maintenance/pages/record-sign/index'], {
  data: {
    orderId: '', order: null, loading: true, error: '',
    isPrincipal: false, isAssistant: false, isDual: false,
    workerSigned: false, assistantSigned: false, myRoleText: '',
    workerSignText: RECORD_TEXT.UNSIGNED, assistantSignText: RECORD_TEXT.UNSIGNED,
    mySigned: false, bothSigned: false, primaryText: RECORD_TEXT.SIGN_SELF, primaryDisabled: false
  },
  onLoad(query) { this.setData({ orderId: (query && query.orderId) || '' }); this.fetchOrder() },
  onShow() { if (this.data.orderId) this.fetchOrder() },
  onBack() { nav.back() },
  fetchOrder() {
    this.setData({ loading: true, error: '' })
    getOrderDetail(this.data.orderId).then((order) => {
      // 布尔口径 workerSigned/assistantSigned（WorkOrderService.toMap）；文本/主按钮随状态投影（2026-11-16 审查修复）
      this.setData(Object.assign({ order: order, loading: false }, buildSignState(order, getUserInfo() || {})))
    }).catch((e) => this.setData({ loading: false, error: (e && e.message) || '加载失败' }))
  },
  onRetry() { this.fetchOrder() },
  goSign() {
    if (this._signing) return
    if (!this.data.isPrincipal && !this.data.isAssistant) return wx.showToast({ title: RECORD_TEXT.ROLE_NONE, icon: 'none' })
    wx.navigateTo({ url: `/pages/common/signature?from=record-sign&orderId=${this.data.orderId}` })
  },
  onSignatureReady(data) {
    if (!data || !data.path || this._signing) return
    this._signing = true
    const role = this.data.isAssistant ? 'ASSISTANT' : 'PRINCIPAL'
    uploadImage(data.path)
      .then((up) => signOrder(this.data.orderId, { role, signatureFileId: up.fileId, signatureUrl: up.url || '' }))
      .then(() => { wx.showToast({ title: '签字成功', icon: 'success' }); this._signing = false; this.fetchOrder() })
      .catch((e) => { this._signing = false; wx.showToast({ title: (e && e.message) || '签字失败', icon: 'none' }) })
  },
  onPrimary() {
    const d = this.data
    if (d.primaryDisabled) return
    if (d.mySigned && d.bothSigned) return this.goRecord()
    this.goSign()
  },
  goRecord() { nav.open('/pkg-maintenance/pages/record-detail/index?orderId=' + this.data.orderId) }
})