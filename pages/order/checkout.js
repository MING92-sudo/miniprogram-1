// 签退自检：自检项确认 + 签名 + 提交
// 业务规则：签到—签退间隔不少于 30 分钟（constants.MIN_WORK_DURATION_MINUTES，前后端双重校验）
// 提交后触发记录生成与监管平台上报（后端）
const { getOrderDetail, checkout } = require('../../services/order')
const { uploadImage } = require('../../services/upload')
const { formatTime, formatDuration, parseTime } = require('../../utils/util')
const { MIN_WORK_DURATION_MINUTES } = require('../../constants/index')
const offline = require('../../utils/offline')

Page({
  data: {
    orderId: '',
    elevatorName: '',
    checkinTime: '',
    elapsedText: '-:--:--', // 已作业时长（24小时制 HH:mm:ss）
    durationOk: false,
    remainMinutes: 0,
    selfChecks: [
      { key: 'tools', label: '工具已清点带离井道', checked: false },
      { key: 'site', label: '现场已清理干净', checked: false },
      { key: 'power', label: '电梯电源已恢复', checked: false },
      { key: 'run', label: '电梯试运行正常', checked: false }
    ],
    signature: '',
    assistantSignature: '',
    hasAssistant: false, // 工单配有配合人员时需双人签字（docs/04 A.2 签退自检）
    submitting: false
  },

  onLoad(query) {
    this.setData({ orderId: query.orderId || '' })
    this.fetchOrder()
  },

  onUnload() {
    this.stopTicker()
  },

  async fetchOrder() {
    try {
      const order = await getOrderDetail(this.data.orderId)
      this.setData({
        elevatorName: (order.elevator && order.elevator.elevatorName) || '',
        checkinTime: order.checkinTime || '',
        hasAssistant: !!(order.assistantName && order.assistantName !== '无')
      })
      this.startTicker()
    } catch (e) {
      // 头部信息加载失败不阻断签退
    }
  },

  // 时长实时刷新（每 30 秒）
  startTicker() {
    this.stopTicker()
    this.updateElapsed()
    this._ticker = setInterval(() => this.updateElapsed(), 30 * 1000)
  },

  stopTicker() {
    if (this._ticker) {
      clearInterval(this._ticker)
      this._ticker = null
    }
  },

  updateElapsed() {
    if (!this.data.checkinTime) return
    const elapsed = Date.now() - parseTime(this.data.checkinTime)
    const minMs = MIN_WORK_DURATION_MINUTES * 60000
    const ok = elapsed >= minMs
    this.setData({
      elapsedText: formatDuration(elapsed),
      durationOk: ok,
      remainMinutes: ok ? 0 : Math.ceil((minMs - elapsed) / 60000)
    })
  },

  onCheckToggle(e) {
    const key = e.currentTarget.dataset.key
    this.setData({
      selfChecks: this.data.selfChecks.map((c) =>
        c.key === key ? Object.assign({}, c, { checked: !c.checked }) : c
      )
    })
  },

  // 跳转签名板（主维保/配合人员两个签字位）
  goSignature(e) {
    // 签退成功后的 Toast 等待期内页面即将 reLaunch，禁止再发起新路由（避免路由竞态）
    if (this._leaving) return
    this._sigTarget = e.currentTarget.dataset.field
    wx.navigateTo({
      url: `/pages/common/signature?from=checkout&orderId=${this.data.orderId}`
    })
  },

  // 签名页直接方法回传（EventChannel 降级通道）
  onSignatureReady(data) {
    if (this._sigTarget) this.setData({ [this._sigTarget]: data.path })
  },

  async onSubmit() {
    const allChecked = this.data.selfChecks.every((c) => c.checked)
    if (!allChecked) return wx.showToast({ title: '请完成全部自检项', icon: 'none' })
    if (!this.data.signature) return wx.showToast({ title: '请完成主维保人员签名', icon: 'none' })
    if (this.data.hasAssistant && !this.data.assistantSignature) {
      return wx.showToast({ title: '请完成配合人员签名（双人签字）', icon: 'none' })
    }
    // 时长下限前端校验（后端 422 双保险）
    if (!this.data.durationOk) {
      return wx.showToast({
        title: `作业时长不足 30 分钟，还需约 ${this.data.remainMinutes} 分钟`,
        icon: 'none'
      })
    }
    this.setData({ submitting: true })
    const signatureUploads = []
    const uploadedFields = {}
    if (this.data.signature) {
      signatureUploads.push({ field: 'signatureFileId', path: this.data.signature })
    }
    if (this.data.assistantSignature) {
      signatureUploads.push({ field: 'assistantSignatureFileId', path: this.data.assistantSignature })
    }
    const signatureData = {
      signatureUrl: this.data.signature, // mock 演示回显；真实后端忽略
      assistantSignatureUrl: this.data.assistantSignature || '',
      collectedAt: formatTime()
    }
    try {
      for (const upload of signatureUploads) {
        if (!uploadedFields[upload.field]) {
          const r = await uploadImage(upload.path)
          uploadedFields[upload.field] = r.fileId
        }
      }
      // 响应：{ duration, originalRecordId, reportStatus, recordId, shareToken }（docs/04 A.2）
      const resp = await checkout(this.data.orderId, {
        ...signatureData,
        ...uploadedFields
      })
      // 签退成功 → 进入签名确认页（安全管理员本机代签，或分享链接远程签字）
      wx.showToast({ title: '签退成功', icon: 'success' })
      this._leaving = true
      setTimeout(() => {
        wx.redirectTo({ url: `/pages/unit/sign?rid=${resp.recordId}&token=${resp.shareToken}` })
      }, 800)
    } catch (e) {
      if (e && e.code === -1) {
        // 签名已上传的部分写入 data；未上传部分交由离线队列按字段断点续传。
        const data = { ...signatureData }
        Object.keys(uploadedFields).forEach((field) => {
          data[field] = uploadedFields[field]
        })
        const remainingUploads = signatureUploads
          .filter((upload) => !uploadedFields[upload.field])
          .map((upload) => ({ ...upload }))
        offline.enqueue({
          url: `/work-orders/${this.data.orderId}/checkout`,
          method: 'POST',
          data,
          uploads: remainingUploads,
          desc: '签退提交'
        })
        wx.showToast({ title: '网络异常，签退已存入离线补传', icon: 'none' })
        this._leaving = true
        setTimeout(() => {
          wx.reLaunch({ url: '/pages/home/index' })
        }, 1200)
      } else {
        wx.showToast({ title: e.message || '签退失败', icon: 'none' })
      }
    }
    this.setData({ submitting: false })
  }
})
