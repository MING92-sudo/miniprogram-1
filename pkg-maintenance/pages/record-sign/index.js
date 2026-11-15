// 维保双人签字（M3，屏 05）：主检/配合各签各的（POST /work-orders/{id}/signature，后端严格校验角色）+ 版本号/哈希核对。
// 同一账号不能同时主检与配合；双签齐才可发起使用单位签字（record-detail）。
const { getOrderDetail, signOrder } = require('../../../services/order')
const { uploadImage } = require('../../../services/upload')
const { getUserInfo } = require('../../../utils/auth')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const nav = require('../../../utils/nav')

definePage(ROUTES['/pkg-maintenance/pages/record-sign/index'], {
  data: {
    orderId: '', order: null, loading: true, error: '',
    isPrincipal: false, isAssistant: false, isDual: false,
    workerSigned: false, assistantSigned: false, myRoleText: ''
  },
  onLoad(query) { this.setData({ orderId: (query && query.orderId) || '' }); this.fetchOrder() },
  onShow() { if (this.data.orderId) this.fetchOrder() },
  onBack() { nav.back() },
  fetchOrder() {
    this.setData({ loading: true, error: '' })
    getOrderDetail(this.data.orderId).then((order) => {
      const me = getUserInfo() || {}
      const isDual = !!order.assistantName
      const isPrincipal = !!order.workerName && (me.name === order.workerName || (!!order.workerPlatformId && me.platformId === order.workerPlatformId))
      const isAssistant = !!order.assistantName && (me.name === order.assistantName || (!!order.assistantPlatformId && me.platformId === order.assistantPlatformId))
      this.setData({
        order, loading: false, isDual, isPrincipal, isAssistant,
        workerSigned: !!order.workerSignatureUrl, assistantSigned: !!order.assistantSignatureUrl,
        myRoleText: isPrincipal ? '主检人' : isAssistant ? '配合人' : '当前账号不在本工单签字名单'
      })
    }).catch((e) => this.setData({ loading: false, error: (e && e.message) || '加载失败' }))
  },
  onRetry() { this.fetchOrder() },
  goSign() {
    if (this._signing) return
    if (!this.data.isPrincipal && !this.data.isAssistant) return wx.showToast({ title: '当前账号不在本工单签字名单', icon: 'none' })
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
  goRecord() { nav.open('/pkg-maintenance/pages/record-detail/index?orderId=' + this.data.orderId) }
})