// 签退自检：自检项确认 + 签名 + 提交
// 业务规则：签到—签退间隔不少于 N 分钟（config.minWorkDurationMinutes，前后端双重校验）
// 提交后触发记录生成与监管平台上报（后端）
const { getOrderDetail, checkout, requestSignEvidence } = require('../../services/order')
const { uploadImage } = require('../../services/upload')
const { formatTime, formatDuration, parseTime } = require('../../utils/util')
const config = require('../../config/index')
const offline = require('../../utils/offline')

Page({
  data: {
    orderId: '',
    elevatorName: '',
    checkinTime: '',
    minMinutes: config.minWorkDurationMinutes, // 作业时长下限（分钟，测试可经 config 调小）
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
    signFetching: false,
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
    const minMs = config.minWorkDurationMinutes * 60000
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

  // 打开签名板前先向服务端换取证令牌（绑定工单 + 签名角色 + 服务端时间）。
  // 令牌按角色保存，与签名图一同提交；服务端验签后用 fileId 反查签名图 URL，不采信客户端地址。
  async goSignature(e) {
    // 签退成功后的 Toast 等待期内页面即将 reLaunch，禁止再发起新路由（避免路由竞态）
    if (this._leaving) return
    if (this.data.signFetching) return
    const field = e.currentTarget.dataset.field
    const role = field === 'assistantSignature' ? 'ASSISTANT' : 'PRINCIPAL'
    this.setData({ signFetching: true })
    try {
      const ev = await requestSignEvidence(this.data.orderId, role)
      this._signEvidence = this._signEvidence || {}
      this._signEvidence[role] = ev.token
      this._sigTarget = field
      wx.navigateTo({
        url: `/pages/common/signature?from=checkout&orderId=${this.data.orderId}`
      })
    } catch (err) {
      wx.showToast({ title: (err && err.message) || '签名取证失败，请重试', icon: 'none' })
    }
    this.setData({ signFetching: false })
  },

  // 签名页直接方法回传（EventChannel 降级通道）
  onSignatureReady(data) {
    if (!this._sigTarget || !data) return
    this.setData({ [this._sigTarget]: data.path })
  },

  async onSubmit() {
    const allChecked = this.data.selfChecks.every((c) => c.checked)
    if (!allChecked) return wx.showToast({ title: '请完成全部自检项', icon: 'none' })
    if (!this.data.signature) return wx.showToast({ title: '请完成主维保人员签名', icon: 'none' })
    if (this.data.hasAssistant && !this.data.assistantSignature) {
      return wx.showToast({ title: '请完成配合人员签名（双人签字）', icon: 'none' })
    }
    // 每个签名位都必须有服务端取证令牌，否则服务端拒绝签退（fail closed）
    const sigEvidence = this._signEvidence || {}
    if (!sigEvidence.PRINCIPAL) {
      return wx.showToast({ title: '主维保人员签名缺少取证令牌，请重新签署', icon: 'none' })
    }
    if (this.data.hasAssistant && !sigEvidence.ASSISTANT) {
      return wx.showToast({ title: '配合人员签名缺少取证令牌，请重新签署', icon: 'none' })
    }
    // 时长下限前端校验（后端 422 双保险）
    if (!this.data.durationOk) {
      return wx.showToast({
        title: `作业时长不足 ${this.data.minMinutes} 分钟，还需约 ${this.data.remainMinutes} 分钟`,
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
    // 只提交签名图的 fileId 与取证令牌；签名图 URL 由服务端按 fileId 反查，
    // 不再上报本地临时路径（否则合规记录里存的是取不到图的设备路径）
    const signatureData = {
      signatureEvidence: {
        principal: sigEvidence.PRINCIPAL,
        assistant: sigEvidence.ASSISTANT || ''
      },
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
