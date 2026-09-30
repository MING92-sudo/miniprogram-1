// 检查项填写：结果 + 读数/说明书判定 + 异常描述/隐患码 + 跳过原因 + 现场照片
// 枚举与字段对齐 docs/04 A.2：result=NORMAL/ABNORMAL/NA；NA 必填 skipReason；
// NUMERIC 填 value；MANUFACTURER 填 valueText；ABNORMAL 必填 abnormalDesc + problemCode(S1-S7)
const { getChecklist, submitChecklistItem } = require('../../services/order')
const { uploadImage } = require('../../services/upload')
const { reverseGeocode } = require('../../services/location')
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
    photos: [],
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
        photos: item.photos || []
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

  choosePhoto() {
    if (this.data.photos.length >= MAX_PHOTOS) {
      return wx.showToast({ title: `最多 ${MAX_PHOTOS} 张`, icon: 'none' })
    }
    // 拍照统一走水印相机：现场照片须带时间/工单/检查项/位置水印留证（docs/04）
    // 水印相机内已完成合成与压缩（长边 ≤1200px、≤500KB），回传直接入列
    const name = (this.data.item && this.data.item.name) || ''
    let qs = `from=item&orderId=${this.data.orderId}` +
      `&itemId=${this.data.itemId}&itemName=${encodeURIComponent(name)}`
    // 现场照定位留证：拍照时实时取点 + 逆地址解析（失败降级为仅时间/工单水印，不阻断拍照）
    wx.getLocation({
      type: 'gcj02',
      success: (loc) => {
        reverseGeocode(loc.latitude, loc.longitude).then((addr) => {
          const isCoord = /^[\d.,\s]+$/.test(addr)
          if (!isCoord) qs += `&address=${encodeURIComponent(addr)}`
          qs += `&lat=${loc.latitude}&lng=${loc.longitude}`
          wx.navigateTo({ url: `/pages/common/watermark-camera?${qs}` })
        })
      },
      fail: () => {
        wx.navigateTo({ url: `/pages/common/watermark-camera?${qs}` })
      }
    })
  },

  // 由水印相机页面回传（已完成水印合成与压缩）
  onPhotoReady(photo) {
    if (!photo) return
    this.setData({ photos: this.data.photos.concat(photo) })
  },

  removePhoto(e) {
    const idx = Number(e.currentTarget.dataset.index)
    const photos = this.data.photos.slice()
    photos.splice(idx, 1)
    this.setData({ photos })
  },

  previewPhoto(e) {
    wx.previewImage({
      current: e.currentTarget.dataset.src,
      urls: this.data.photos
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
      photoUrls: this.data.photos, // mock 演示回显（本地路径）；真实后端忽略
      recordedAt: formatTime() // 离线补传时为本地原始时间戳
    }
  },

  validate() {
    const item = this.data.item || {}
    if (!this.data.result) return '请选择检查结果'
    if (this.data.result !== 'NA' && item.judgeType === 'NUMERIC' && this.data.value === '') {
      return '读数型检查项请填写测量读数'
    }
    if (this.data.result === 'ABNORMAL') {
      if (!this.data.abnormalDesc) return '异常项请填写异常描述'
      if (!this.data.problemCode) return '异常项请选择隐患代码（S1-S7，随维保记录上报平台）'
      if (item.photoRequired && this.data.photos.length === 0) {
        return '关键项异常必须至少拍摄 1 张现场照片'
      }
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
        const r = await uploadImage(p)
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
      photoPaths: this.data.photos.slice(),
      desc: item.name || '检查项填写'
    })
  }
})
