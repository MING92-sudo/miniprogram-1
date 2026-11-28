// 维保工单绝不套此七态（§1.3）。
const { getFaultDetail, updateFault, grabFault, arriveFault, submitFault, confirmFault, cancelFault } = require('../../../services/fault')
const { getUnitFaultDetail, confirmUnitFault, cancelUnitFault } = require('../../../services/unit')
const { uploadImage } = require('../../../services/upload')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const { getRoles, isCustomerPrincipal } = require('../../../utils/auth')
const nav = require('../../../utils/nav')
const { TOAST_TEXT, ORDER_TEXT, FAULT_STATUS_TEXT, FAULT_STATUS_TONE } = require('../../../constants/index')

const STATUS_TEXT = FAULT_STATUS_TEXT
const STATUS_TONE = FAULT_STATUS_TONE

definePage(ROUTES['/pages/order/detail/index'], {
  data: {
    id: '', fault: null, statusText: '', statusTone: 'dim', loading: true, error: '',
    siteDesc: '', todoDesc: '', handleDesc: '', submitting: false,
    repairPhotos: [], workerSignature: '', editable: false,
    canConfirm: false, isClient: false, text: ORDER_TEXT
  },
  onLoad(query) { this.setData({ id: (query && query.id) || '' }); this.fetchDetail() },
  onShow() {
    // 签名页返回时，签名回调先于 navigateBack 执行；此处清理未完成的确认标记
    this._pendingConfirmSignature = false
    if (this.data.id) this.fetchDetail()
  },
  onBack() { nav.back() },
  fetchDetail() {
    this.setData({ loading: true, error: '' })
    const client = isCustomerPrincipal()
    const fetch = client ? getUnitFaultDetail : getFaultDetail
    fetch(this.data.id).then((fault) => {
      const roles = getRoles()
      const isClient = client
      const tl = []
      const push = (label, time, tone) => { if (time) tl.push({ label, time: String(time).slice(11, 16), tone }) }
      push(ORDER_TEXT.TL_REPORT, fault.reportedAt || fault.createdAt, 'ok')
      push(ORDER_TEXT.TL_ARRIVE, fault.arrivedAt, 'ok')
      if (fault.rescue) push(ORDER_TEXT.TL_RESCUE, fault.rescuedAt, 'ok')
      push(ORDER_TEXT.TL_DONE, fault.finishedAt || fault.confirmedAt, fault.status === 'CLOSED' ? 'amber' : 'dim')
      const heroTitle = (fault.rescue && fault.status === 'CLOSED') ? ORDER_TEXT.RESCUE_DONE : (fault.desc || '')
      const responseText = fault.responseMinutes != null ? fault.responseMinutes + "'00\"" : ''
      const sourceText = fault.proxySource === 'WECHAT' ? '微信代报修'
        : fault.proxySource === 'ONSITE' ? '现场代报修'
          : fault.proxySource ? '电话代报修'
            : (String(fault.createdBy || '').indexOf('unit:') === 0 ? '使用单位报修' : '小程序报修')
      const heroSub = [fault.elevatorName, fault.floor ? fault.floor + 'F' : '', fault.trappedCount ? '被困 ' + fault.trappedCount + ' ' + ORDER_TEXT.TRAPPED_UNIT : '', fault.confirmedAt ? ORDER_TEXT.CONFIRMED_SHORT : ''].filter(Boolean).join(' · ')
      const editable = !isClient && (fault.status === 'PROCESSING' || fault.status === 'REFUSED')
      this.setData({
        fault, loading: false, timeline: tl, heroTitle, responseText, heroSub, sourceText,
        statusText: STATUS_TEXT[fault.status] || fault.status, statusTone: STATUS_TONE[fault.status] || 'dim',
        siteDesc: fault.siteDesc || '', todoDesc: fault.todoDesc || '', handleDesc: fault.handleDesc || fault.result || '',
        repairPhotos: fault.repairPhotos || [], workerSignature: fault.workerSignature || '', editable,
        isClient, canConfirm: roles.indexOf('CLIENT_SIGNER') > -1,
        canCancel: fault.status === 'OPEN'
          ? (isClient && roles.indexOf('CLIENT_ADMIN') > -1)
          : (!isClient && fault.status === 'ASSIGNED' && roles.indexOf('MAINT_SUP') > -1)
      })
    }).catch((e) => this.setData({ loading: false, error: (e && e.message) || TOAST_TEXT.LOAD_FAILED }))
  },
  onRetry() { this.fetchDetail() },
  onGrab() {
    if (this.data.isClient) return
    grabFault(this.data.id).then(() => { wx.showToast({ title: '已接单', icon: 'success' }); this.fetchDetail() })
      .catch((e) => wx.showToast({ title: (e && e.message) || '抢单失败', icon: 'none' }))
  },
  onArrive() {
    if (this.data.isClient) return
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
  onAddRepairPhoto() {
    if (this.data.repairPhotos.length >= 6) return wx.showToast({ title: '最多 6 张', icon: 'none' })
    wx.navigateTo({ url: '/pages/common/watermark-camera?from=fault&orderId=' + this.data.id })
  },
  onPhotoReady(photo) {
    if (!photo || this._uploadingPhoto) return
    this._uploadingPhoto = true
    wx.showLoading({ title: '上传中' })
    uploadImage(photo)
      .then((up) => updateFault(this.data.id, { repairPhotos: (this.data.repairPhotos || []).concat(up.url) }))
      .then(() => {
        wx.hideLoading()
        this._uploadingPhoto = false
        wx.showToast({ title: '照片已添加', icon: 'success' })
        this.fetchDetail()
      })
      .catch((e) => {
        wx.hideLoading()
        this._uploadingPhoto = false
        wx.showToast({ title: (e && e.message) || '照片上传失败', icon: 'none' })
      })
  },
  onRemoveRepairPhoto(e) {
    const url = e.currentTarget.dataset.url
    const repairPhotos = (this.data.repairPhotos || []).filter((u) => u !== url)
    wx.showLoading({ title: '更新中' })
    updateFault(this.data.id, { repairPhotos })
      .then(() => { wx.hideLoading(); this.fetchDetail() })
      .catch((er) => { wx.hideLoading(); wx.showToast({ title: (er && er.message) || '照片删除失败', icon: 'none' }) })
  },
  onPreviewRepairPhoto(e) {
    const urls = this.data.editable ? this.data.repairPhotos : ((this.data.fault && this.data.fault.repairPhotos) || [])
    wx.previewImage({ urls, current: e.currentTarget.dataset.url })
  },
  onSignWorker() {
    wx.navigateTo({ url: '/pages/common/signature?from=fault&orderId=' + this.data.id })
  },
  onSignatureReady(data) {
    if (!data || !data.path || this._signing) return
    this._signing = true
    wx.showLoading({ title: this._pendingConfirmSignature ? '签署中' : '上传中' })
    if (this._pendingConfirmSignature) {
      this._pendingConfirmSignature = false
      const confirm = this.data.isClient ? confirmUnitFault : confirmFault
      uploadImage(data.path)
        .then((up) => confirm(this.data.id, true, '', up.url))
        .then(() => {
          wx.hideLoading()
          this._signing = false
          wx.showToast({ title: '已确认闭环', icon: 'success' })
          this.fetchDetail()
        })
        .catch((e) => {
          wx.hideLoading()
          this._signing = false
          wx.showToast({ title: (e && e.message) || '确认失败', icon: 'none' })
        })
      return
    }
    uploadImage(data.path)
      .then((up) => updateFault(this.data.id, { workerSignature: up.url }))
      .then(() => {
        wx.hideLoading()
        this._signing = false
        wx.showToast({ title: '签字已保存', icon: 'success' })
        this.fetchDetail()
      })
      .catch((e) => {
        wx.hideLoading()
        this._signing = false
        wx.showToast({ title: (e && e.message) || '签字上传失败', icon: 'none' })
      })
  },
  onSaveProcess() {
    if (this.data.isClient) return
    if (this.data.submitting) return
    this.setData({ submitting: true })
    updateFault(this.data.id, {
      siteDesc: this.data.siteDesc, todoDesc: this.data.todoDesc, handleDesc: this.data.handleDesc,
      repairPhotos: this.data.repairPhotos || [], workerSignature: this.data.workerSignature || ''
    })
      .then(() => { wx.showToast({ title: '维修过程已保存', icon: 'success' }); this.setData({ submitting: false }) })
      .catch((e) => { this.setData({ submitting: false }); wx.showToast({ title: (e && e.message) || TOAST_TEXT.SAVE_FAILED, icon: 'none' }) })
  },
  onSubmitFault() {
    if (this.data.isClient) return
    if (!this.data.handleDesc) return wx.showToast({ title: '请填写处理结果（措施）', icon: 'none' })
    if (!this.data.siteDesc) return wx.showToast({ title: '请填写现场情况（原因）', icon: 'none' })
    if (!this.data.workerSignature) return wx.showToast({ title: '请先由维保人员签字', icon: 'none' })
    submitFault(this.data.id, {
      siteDesc: this.data.siteDesc, todoDesc: this.data.todoDesc, handleDesc: this.data.handleDesc,
      repairPhotos: this.data.repairPhotos || [], workerSignature: this.data.workerSignature
    })
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
          const confirm = this.data.isClient ? confirmUnitFault : confirmFault
          confirm(this.data.id, false, reason).then(() => { wx.showToast({ title: '已退回整改', icon: 'success' }); this.fetchDetail() })
            .catch((er) => wx.showToast({ title: (er && er.message) || '操作失败', icon: 'none' }))
        }
      })
    }
    // 确认闭环须使用单位安全管理员横屏签字，签名落在急修单 PDF 第五区块
    this._pendingConfirmSignature = true
    wx.navigateTo({ url: '/pages/common/signature?from=fault-confirm&orderId=' + this.data.id })
  },
  onCancel() {
    wx.showModal({
      title: '取消急修单', editable: true, placeholderText: '请填写取消原因（必填）',
      success: (res) => {
        if (!res.confirm) return
        const reason = (res.content || '').trim()
        if (!reason) return wx.showToast({ title: '取消原因必填', icon: 'none' })
        const cancel = this.data.isClient ? cancelUnitFault : cancelFault
        cancel(this.data.id, reason).then(() => { wx.showToast({ title: '已取消', icon: 'success' }); this.fetchDetail() })
          .catch((er) => wx.showToast({ title: (er && er.message) || '取消失败', icon: 'none' }))
      }
    })
  },
  onCallReporter() {
    const phone = this.data.fault && this.data.fault.reporterPhone
    if (!phone) return
    wx.makePhoneCall({ phoneNumber: phone, fail: () => {} })
  },
  onPreviewPhoto(e) { wx.previewImage({ urls: this.data.fault.photos || [], current: e.currentTarget.dataset.url }) }
})