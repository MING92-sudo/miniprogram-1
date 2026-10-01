// 检查项填写：结果 + 读数/说明书判定 + 异常描述/隐患码 + 跳过原因 + 现场照片
// 枚举与字段对齐 docs/04 A.2：result=NORMAL/ABNORMAL/NA；NA 必填 skipReason；
// NUMERIC 填 value；MANUFACTURER 填 valueText；ABNORMAL 必填 abnormalDesc + problemCode(S1-S7)
const { getChecklist, submitChecklistItem, requestShotEvidence } = require('../../services/order')
const { uploadImage } = require('../../services/upload')
const { formatTime } = require('../../utils/util')
const { PROBLEM_CODES } = require('../../constants/index')
const offline = require('../../utils/offline')

const MAX_PHOTOS = 4

// 异常项可选隐患码（S1-S7；S0"无隐患"仅用于整单无异常时由签退快照自动填充）
const PROBLEM_KEYS = Object.keys(PROBLEM_CODES).filter((k) => k !== 'S0')

Page({
  data: {
    orderId: '',
    itemId: '',
    item: null, // 检查项（含模板字段：judgeType/requirement/isKey/photoRequired 等）
    result: '',
    value: '',
    valueText: '',
    abnormalDesc: '',
    problemCode: '',
    problemKeys: PROBLEM_KEYS,
    problemOptions: PROBLEM_KEYS.map((k) => k + ' ' + PROBLEM_CODES[k]),
    skipReason: '',
    photos: [], // [{ path, evidenceToken }]，令牌与 photoFileIds 按下标一一对应
    shooting: false,
    submitting: false
  },

  onLoad(query) {
    this.setData({ orderId: query.orderId || '', itemId: query.itemId || '' })
    this.fetchCurrentItem()
  },

  // 回显已保存的填写内容
  async fetchCurrentItem() {
    try {
      const checklist = await getChecklist(this.data.orderId)
      const item = ((checklist && checklist.items) || []).find((i) => i.id === this.data.itemId)
      if (!item) return
      this.setData({
        item,
        result: item.result || '',
        value: item.value != null ? String(item.value) : '',
        valueText: item.valueText || '',
        abnormalDesc: item.abnormalDesc || '',
        problemCode: item.problemCode || '',
        problemDesc: item.problemCode ? PROBLEM_CODES[item.problemCode] : '',
        skipReason: item.skipReason || '',
        // 回显的历史照片无取证令牌（令牌不外发）：如需修改本项须重新拍照，否则提交会被服务端拒绝
        photos: (item.photos || []).map((p) => ({ path: p, evidenceToken: '' }))
      })
    } catch (e) {
      // 回显失败不阻断填写
    }
  },

  onResultChange(e) {
    this.setData({ result: e.currentTarget.dataset.value })
  },

  onValueInput(e) {
    this.setData({ value: e.detail.value })
  },

  onValueTextInput(e) {
    this.setData({ valueText: e.detail.value })
  },

  onAbnormalInput(e) {
    this.setData({ abnormalDesc: e.detail.value })
  },

  onProblemChange(e) {
    const idx = Number(e.detail.value)
    this.setData({
      problemCode: PROBLEM_KEYS[idx],
      problemDesc: this.data.problemOptions[idx]
    })
  },

  onSkipInput(e) {
    this.setData({ skipReason: e.detail.value })
  },

  // 现场照片：先向服务端换取证令牌（绑定工单+检查项+已校验坐标+服务端时间），
  // 再由水印相机按令牌渲染水印。令牌与照片按下标一一对应，提交时随photoFileIds 一并回传。
  async choosePhoto() {
    if (this.data.shooting) return
    if (this.data.photos.length >= MAX_PHOTOS) {
      return wx.showToast({ title: `最多 ${MAX_PHOTOS} 张`, icon: 'none' })
    }
    this.setData({ shooting: true })
    wx.showLoading({ title: '取证中...', mask: true })
    let evidence
    try {
      const loc = await new Promise((resolve, reject) => {
        wx.getLocation({
          type: 'gcj02',
          success: resolve,
          fail: () => reject(new Error('定位失败，请检查定位权限'))
        })
      })
      evidence = await requestShotEvidence(this.data.orderId, this.data.itemId, {
        latitude: loc.latitude,
        longitude: loc.longitude,
        locationAccuracy: loc.accuracy || 0
      })
    } catch (e) {
      wx.hideLoading()
      this.setData({ shooting: false })
      if (e && e.code === 1001) {
        wx.showModal({
          title: '拍摄被拦截',
          content: (e.message || '拍摄位置超出允许范围') + '。如确属到场，可提交申诉。',
          showCancel: false
        })
      } else {
        wx.showToast({ title: (e && e.message) || '取证失败，请重试', icon: 'none' })
      }
      return
    }
    wx.hideLoading()
    this.setData({ shooting: false })
    wx.navigateTo({
      url: '/pages/common/watermark-camera?from=item&evidence=' + encodeURIComponent(evidence.token)
    })
  },

  // 由水印相机页面回传（已完成水印合成与压缩）+ 服务端取证令牌
  onPhotoReady(photo, evidenceToken) {
    if (!photo) return
    if (!evidenceToken) {
      return wx.showToast({ title: '照片缺少取证令牌，请重新拍摄', icon: 'none' })
    }
    this.setData({ photos: this.data.photos.concat({ path: photo, evidenceToken }) })
  },

  removePhoto(e) {
    const idx = Number(e.currentTarget.dataset.index)
    const photos = this.data.photos.slice()
    photos.splice(idx, 1)
    this.setData({ photos })
  },

  previewPhoto(e) {
    const cur = this.data.photos[Number(e.currentTarget.dataset.index)]
    wx.previewImage({
      current: cur ? cur.path : '',
      urls: this.data.photos.map((p) => p.path)
    })
  },

  // 构造提交 payload（docs/04 A.2，clientItemId 为幂等键）
  buildPayload(fileIds) {
    const item = this.data.item || {}
    return {
      clientItemId: this.data.itemId,
      result: this.data.result,
      value: this.data.value === '' ? null : Number(this.data.value),
      valueText: item.judgeType === 'MANUFACTURER' ? this.data.valueText : '',
      abnormalDesc: this.data.result === 'ABNORMAL' ? this.data.abnormalDesc : '',
      problemCode: this.data.result === 'ABNORMAL' ? this.data.problemCode : '',
      skipReason: this.data.result === 'NA' ? this.data.skipReason : '',
      photoFileIds: fileIds,
      // 与 photoFileIds 按下标一一对应；服务端按下标验签并写入权威拍摄存证
      photoEvidence: this.data.photos.map((p) => ({ evidenceToken: p.evidenceToken })),
      photoUrls: this.data.photos.map((p) => p.path), // 本地路径仅用于提交前预览回显
      recordedAt: formatTime() // 离线补传时为本地原始时间戳；服务端以取证令牌时间为准
    }
  },

  validate() {
    const item = this.data.item || {}
    if (!this.data.result) return '请选择检查结果'
    if (this.data.result !== 'NA' && item.judgeType === 'NUMERIC' && this.data.value === '') {
      return '读数型检查项请填写测量读数'
    }
    // 照片校验与后端 mock/server.js 完全对齐（避免前端放行、后端 422 打回的体验割裂）：
    // ① 任何异常项必须附照片；② 关键项（试验/测试/校验/检测，TSG 注A-2）执行（非NA）必须附照片
    if (this.data.result === 'ABNORMAL') {
      if (!this.data.abnormalDesc) return '异常项请填写异常描述'
      if (!this.data.problemCode) return '异常项请选择隐患代码（S1-S7，随维保记录上报平台）'
    }
    if (this.data.result !== 'NA' && this.data.photos.length === 0) {
      if (this.data.result === 'ABNORMAL') return '异常项必须至少拍摄 1 张现场照片'
      if (item.photoRequired) return '关键项为试验/测试/校验/检测类，必须拍摄照片留证（TSG 注A-2）'
    }
    if (this.data.result === 'NA' && !this.data.skipReason) {
      return '不适用项请填写跳过原因（TSG 注 A-1）'
    }
    return ''
  },

  async onSubmit() {
    const err = this.validate()
    if (err) return wx.showToast({ title: err, icon: 'none' })
    this.setData({ submitting: true })
    try {
      // 照片已在选取时压缩至约定范围（长边 ≤1200px、≤500KB）
      const fileIds = []
      for (const p of this.data.photos) {
        const r = await uploadImage(p.path)
        fileIds.push(r.fileId)
      }
      await submitChecklistItem(this.data.orderId, this.data.itemId, this.buildPayload(fileIds))
      wx.showToast({ title: '已保存', icon: 'success' })
      wx.navigateBack()
    } catch (e) {
      if (e && e.code === -1) {
        // 弱网/断网：本地快照 + 入补传队列，网络恢复后自动重放（幂等）
        this.saveOffline()
        wx.showToast({ title: '网络异常，已存入离线队列', icon: 'none' })
      } else {
        wx.showToast({ title: e.message || '保存失败', icon: 'none' })
      }
    }
    this.setData({ submitting: false })
  },

  // 离线暂存：合并工单快照并入补传队列
  saveOffline() {
    const { orderId, itemId } = this.data
    const item = this.data.item || {}
    const payload = this.buildPayload([])
    const cached = offline.getCachedOrder(orderId)
    const snapshot = (cached && cached.snapshot) || { id: orderId, orderNo: orderId, checklist: [] }
    snapshot.checklist = snapshot.checklist || []
    const patch = Object.assign({}, item, {
      result: payload.result,
      value: payload.value,
      valueText: payload.valueText,
      abnormalDesc: payload.abnormalDesc,
      problemCode: payload.problemCode,
      skipReason: payload.skipReason,
      photos: payload.photoUrls
    })
    const idx = snapshot.checklist.findIndex((i) => i.id === itemId)
    if (idx > -1) {
      snapshot.checklist[idx] = Object.assign({}, snapshot.checklist[idx], patch)
    } else {
      snapshot.checklist.push(patch)
    }
    offline.cacheOrderSnapshot(snapshot)
    offline.enqueue({
      url: `/work-orders/${orderId}/checklist/${itemId}`,
      method: 'POST',
      data: payload,
      // 本地照片路径随任务入队，补传时先上传文件再回填 photoFileIds（docs/08 P1）
      // 顺序与 data.photoEvidence 一致，服务端按下标验签
      photoPaths: this.data.photos.map((p) => p.path),
      desc: item.name || '检查项填写'
    })
  }
})
