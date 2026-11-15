// 作业执行（M3，屏 04）：合并 order/detail+checklist+item-form 为单页双态（list 清单 / edit 填写）。
// 合规逻辑逐字保留：周期项 notInThisRun 豁免、关键项 photoRequired≥1 照片（NA 除外）、异常显式 S1–S7、
// 数值超限不得按正常保存、离线快照补传。清单按真实模板 freqLabel 分节（附件 A—D）。
const { getChecklist, getOrderDetail, runItemThisTime, submitChecklistItem } = require('../../../services/order')
const { uploadImage } = require('../../../services/upload')
const { reverseGeocode } = require('../../../services/location')
const { formatTime } = require('../../../utils/util')
const { CHECK_RESULT_TEXT, PROBLEM_CODES, TOAST_TEXT } = require('../../../constants/index')
const offline = require('../../../utils/offline')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const nav = require('../../../utils/nav')

const RESULT_TEXT = CHECK_RESULT_TEXT
const MAX_PHOTOS = 4
const PROBLEM_KEYS = Object.keys(PROBLEM_CODES).filter((k) => k !== 'S0')
const GLYPH = {
  NORMAL: { text: '✓', cls: 'is-ok' }, ABNORMAL: { text: '✗', cls: 'is-ng' },
  NA: { text: '—', cls: 'is-na' }, PENDING: { text: '○', cls: 'is-pending' }, SKIP: { text: '◇', cls: 'is-skip' }
}

function decorate(i) {
  const photos = i.photos || []
  const result = i.result
  let g; let sub = ''; let subClass = ''
  if (i.notInThisRun) { g = GLYPH.SKIP; sub = i.nextRunText || '本次无需执行'; subClass = 'is-skip' }
  else if (result === 'ABNORMAL') { g = GLYPH.ABNORMAL; sub = '隐患码 ' + (i.problemCode || '—') + (i.abnormalDesc ? ' · ' + i.abnormalDesc : ''); subClass = 'is-ng' }
  else if (result === 'NA') { g = GLYPH.NA; sub = i.skipReason || '不适用'; subClass = 'is-na' }
  else if (result === 'NORMAL') { g = GLYPH.NORMAL }
  else { g = GLYPH.PENDING }
  if (!sub) {
    if (i.value != null && i.value !== '') sub = '读数 ' + i.value + (i.valueUnit || '')
    else if (i.valueText) sub = i.valueText
    else sub = i.requirement || ''
  }
  return Object.assign({}, i, {
    resultText: result ? RESULT_TEXT[result] : '', glyph: g.text, glyphClass: g.cls,
    resultClass: result === 'NORMAL' ? 'res-ok' : result === 'ABNORMAL' ? 'res-ng' : result === 'NA' ? 'res-na' : 'res-pending',
    sub, subClass, photoCount: photos.length
  })
}

function groupByFreq(items) {
  const sections = []; const index = {}
  items.forEach(function (i) {
    const label = i.freqLabel || '检查项目'
    let s = index[label]
    if (!s) { s = { label, items: [], done: 0, total: 0 }; index[label] = s; sections.push(s) }
    s.items.push(i); s.total += 1; if (i.result) s.done += 1
  })
  return sections
}

definePage(ROUTES['/pkg-maintenance/pages/task/index'], {
  data: {
    orderId: '', order: null, collapsed: {}, expandedId: '', nextItemName: '',
    workTypeCode: '', workType: '', elevatorName: '', projectName: '',
    sections: [], photos: [], doneCount: 0, requiredTotal: 0, normalCount: 0, abnormalCount: 0, naCount: 0, pendingCount: 0, progress: 0,
    loading: true, error: '',
    // edit 态字段（迁自 item-form）
    itemId: '', item: null, result: '', value: '', valueText: '', abnormalDesc: '', problemCode: '', problemDesc: '',
    problemKeys: PROBLEM_KEYS, problemOptions: PROBLEM_KEYS.map((k) => k + ' ' + PROBLEM_CODES[k]),
    skipReason: '', editPhotos: [], submitting: false
  },
  onLoad(query) {
    this.setData({ orderId: (query && query.orderId) || (query && query.id) || '' })
    this.fetchOrder()
    this.fetchChecklist()
  },
  onShow() { if (this.data.orderId) this.fetchChecklist() },
  onBack() { nav.back() },
  fetchOrder() {
    getOrderDetail(this.data.orderId).then((order) => this.setData({ order })).catch(() => {})
  },
  fetchChecklist() {
    this.setData({ loading: true, error: '' })
    return getChecklist(this.data.orderId).then((checklist) => {
      const items = ((checklist && checklist.items) || []).map(decorate)
      const photos = []
      items.forEach((i) => (i.photos || []).forEach((p) => photos.push({ src: p, itemName: i.name })))
      const requiredTotal = items.filter((i) => !i.notInThisRun).length
      const doneCount = items.filter((i) => !i.notInThisRun && i.result).length
      const nextItem = items.find((i) => !i.notInThisRun && !i.result)
      this.setData({
        workTypeCode: checklist.workTypeCode || '', workType: checklist.workType || '',
        elevatorName: checklist.elevatorName || '', projectName: checklist.projectName || '',
        sections: groupByFreq(items), photos, doneCount, requiredTotal,
        nextItemName: nextItem ? nextItem.name : '',
        normalCount: items.filter((i) => i.result === 'NORMAL').length,
        abnormalCount: items.filter((i) => i.result === 'ABNORMAL').length,
        naCount: items.filter((i) => i.result === 'NA').length,
        pendingCount: requiredTotal - doneCount,
        progress: requiredTotal ? Math.round((doneCount / requiredTotal) * 100) : 0, loading: false
      })
    }).catch((e) => this.setData({ loading: false, error: (e && e.message) || TOAST_TEXT.LOAD_FAILED }))
  },
  onRetry() { this.fetchChecklist() },
  goItemForm(e) {
    const itemId = e.currentTarget.dataset.id
    if (this.data.expandedId === itemId) { this.setData({ expandedId: '', item: null }); return }
    let item = null
    this.data.sections.forEach((s) => s.items.forEach((i) => { if (i.id === itemId) item = i }))
    if (!item) return
    const fileIds = item.photoFileIds || []
    const urls = item.photos || []
    const editPhotos = urls.map((u, i) => ({ url: u, fileId: fileIds[i] || '' }))
    this.setData({
      expandedId: itemId, item,
      result: item.result || '', value: item.value != null ? String(item.value) : '', valueText: item.valueText || '',
      abnormalDesc: item.abnormalDesc || '', problemCode: item.problemCode || '',
      problemDesc: item.problemCode ? PROBLEM_CODES[item.problemCode] : '', skipReason: item.skipReason || '', editPhotos
    })
  },
  backToList() { this.setData({ expandedId: '', item: null }); this.fetchChecklist() },
  onToggleGroup(e) {
    const label = e.currentTarget.dataset.label
    this.setData({ ['collapsed.' + label]: !this.data.collapsed[label] })
  },
  onNextItem() {
    let target = null
    this.data.sections.forEach((s) => s.items.forEach((i) => { if (!target && !i.notInThisRun && !i.result) target = i }))
    if (!target) return wx.showToast({ title: '全部检查项已完成', icon: 'none' })
    this.goItemForm({ currentTarget: { dataset: { id: target.id } } })
    wx.pageScrollTo({ selector: '#item-' + target.id, offsetTop: -120, duration: 200 })
  },
  onSaveDraft() {
    if (!this.data.expandedId || !this.data.item) return wx.showToast({ title: '请先展开检查项', icon: 'none' })
    const { orderId, itemId } = this.data
    const payload = this.buildPayload(this.data.editPhotos.filter((p) => p.fileId).map((p) => p.fileId))
    const cached = offline.getCachedOrder(orderId)
    const snapshot = (cached && cached.snapshot) || { id: orderId, orderNo: orderId, checklist: [] }
    snapshot.checklist = snapshot.checklist || []
    const patch = Object.assign({}, this.data.item, {
      result: payload.result, value: payload.value, valueText: payload.valueText,
      abnormalDesc: payload.abnormalDesc, problemCode: payload.problemCode, skipReason: payload.skipReason, photos: payload.photoUrls
    })
    const idx = snapshot.checklist.findIndex((i) => i.id === itemId)
    if (idx > -1) snapshot.checklist[idx] = Object.assign({}, snapshot.checklist[idx], patch)
    else snapshot.checklist.push(patch)
    offline.cacheOrderSnapshot(snapshot)
    wx.showToast({ title: '草稿已保存', icon: 'success' })
  },
  onResultChange(e) { this.setData({ result: e.currentTarget.dataset.value }) },
  onValueInput(e) { this.setData({ value: e.detail.value }) },
  onValueTextInput(e) { this.setData({ valueText: e.detail.value }) },
  onAbnormalInput(e) { this.setData({ abnormalDesc: e.detail.value }) },
  onProblemChange(e) { const idx = Number(e.detail.value); this.setData({ problemCode: PROBLEM_KEYS[idx], problemDesc: this.data.problemOptions[idx] }) },
  onSkipInput(e) { this.setData({ skipReason: e.detail.value }) },
  choosePhoto() {
    if (this.data.editPhotos.length >= MAX_PHOTOS) return wx.showToast({ title: `最多 ${MAX_PHOTOS} 张`, icon: 'none' })
    const name = (this.data.item && this.data.item.name) || ''
    let qs = `from=item&orderId=${this.data.orderId}&itemId=${this.data.itemId}&itemName=${encodeURIComponent(name)}`
    wx.getLocation({
      type: 'gcj02',
      success: (loc) => {
        reverseGeocode(loc.latitude, loc.longitude).then((addr) => {
          if (!/^[\d.,\s]+$/.test(addr)) qs += `&address=${encodeURIComponent(addr)}`
          qs += `&lat=${loc.latitude}&lng=${loc.longitude}`
          wx.navigateTo({ url: `/pages/common/watermark-camera?${qs}` })
        })
      },
      fail: () => wx.navigateTo({ url: `/pages/common/watermark-camera?${qs}` })
    })
  },
  onPhotoReady(photo) {
    if (!photo) return
    if (this.data.expandedId) this.setData({ editPhotos: this.data.editPhotos.concat({ url: photo, fileId: '' }) })
  },
  removePhoto(e) {
    const idx = Number(e.currentTarget.dataset.index)
    const editPhotos = this.data.editPhotos.slice(); editPhotos.splice(idx, 1); this.setData({ editPhotos })
  },
  previewEditPhoto(e) { wx.previewImage({ current: e.currentTarget.dataset.src, urls: this.data.editPhotos.map((p) => p.url) }) },
  previewPhoto(e) { wx.previewImage({ current: e.currentTarget.dataset.src, urls: this.data.photos.map((p) => p.src) }) },
  buildPayload(fileIds) {
    const item = this.data.item || {}
    return {
      clientItemId: this.data.itemId, result: this.data.result,
      value: this.data.value === '' ? null : Number(this.data.value),
      valueText: item.judgeType === 'MANUFACTURER' ? this.data.valueText : '',
      abnormalDesc: this.data.result === 'ABNORMAL' ? this.data.abnormalDesc : '',
      problemCode: this.data.result === 'ABNORMAL' ? this.data.problemCode : '',
      skipReason: this.data.result === 'NA' ? this.data.skipReason : '',
      photoFileIds: fileIds, photoUrls: this.data.editPhotos.map((p) => p.url), recordedAt: formatTime()
    }
  },
  validate() {
    const item = this.data.item || {}
    if (!this.data.result) return '请选择检查结果'
    if (this.data.result !== 'NA' && item.judgeType === 'NUMERIC' && this.data.value === '') return '读数型检查项请填写测量读数'
    if (this.data.result === 'NORMAL' && item.judgeType === 'NUMERIC' && this.data.value !== '') {
      const v = Number(this.data.value)
      if (!Number.isNaN(v)) {
        const min = item.valueMin == null || item.valueMin === '' ? null : Number(item.valueMin)
        const max = item.valueMax == null || item.valueMax === '' ? null : Number(item.valueMax)
        if ((min != null && v < min) || (max != null && v > max)) {
          return '读数 ' + this.data.value + ' 超出标准范围（' + (min == null ? '—' : min) + ' ~ ' + (max == null ? '—' : max) + (item.valueUnit || '') + '）：请核实读数，确属异常请选择"异常"并附照片'
        }
      }
    }
    if (this.data.result === 'ABNORMAL') {
      if (!this.data.abnormalDesc) return '异常项请填写异常描述'
      if (!this.data.problemCode) return '异常项请选择隐患代码（S1-S7，随维保记录上报平台）'
    }
    if (this.data.result !== 'NA' && this.data.editPhotos.length === 0) {
      if (this.data.result === 'ABNORMAL') return '异常项必须至少拍摄 1 张现场照片'
      if (item.photoRequired) return '关键项为试验/测试/校验/检测类，必须拍摄照片留证（TSG 注A-2）'
    }
    if (this.data.result === 'NA' && !this.data.skipReason) return '不适用项请填写跳过原因（TSG 注 A-1）'
    return ''
  },
  onSubmitItem() {
    const err = this.validate()
    if (err) return wx.showToast({ title: err, icon: 'none' })
    this.setData({ submitting: true })
    const fileIds = this.data.editPhotos.filter((p) => p.fileId).map((p) => p.fileId)
    const news = this.data.editPhotos.filter((x) => !x.fileId)
    const uploadNext = (i) => {
      if (i >= news.length) return Promise.resolve()
      return uploadImage(news[i].url).then((r) => { fileIds.push(r.fileId); return uploadNext(i + 1) })
    }
    uploadNext(0)
      .then(() => submitChecklistItem(this.data.orderId, this.data.itemId, this.buildPayload(fileIds)))
      .then(() => { wx.showToast({ title: '已保存', icon: 'success' }); this.setData({ submitting: false }); this.backToList() })
      .catch((e) => {
        this.setData({ submitting: false })
        if (e && e.code === -1) { this.saveOffline(); wx.showToast({ title: '网络异常，已存入离线队列', icon: 'none' }); this.backToList() }
        else wx.showToast({ title: (e && e.message) || TOAST_TEXT.SAVE_FAILED, icon: 'none' })
      })
  },
  saveOffline() {
    const { orderId, itemId } = this.data
    const item = this.data.item || {}
    const existingFileIds = this.data.editPhotos.filter((p) => p.fileId).map((p) => p.fileId)
    const payload = this.buildPayload(existingFileIds)
    const cached = offline.getCachedOrder(orderId)
    const snapshot = (cached && cached.snapshot) || { id: orderId, orderNo: orderId, checklist: [] }
    snapshot.checklist = snapshot.checklist || []
    const patch = Object.assign({}, item, {
      result: payload.result, value: payload.value, valueText: payload.valueText,
      abnormalDesc: payload.abnormalDesc, problemCode: payload.problemCode, skipReason: payload.skipReason, photos: payload.photoUrls
    })
    const idx = snapshot.checklist.findIndex((i) => i.id === itemId)
    if (idx > -1) snapshot.checklist[idx] = Object.assign({}, snapshot.checklist[idx], patch)
    else snapshot.checklist.push(patch)
    offline.cacheOrderSnapshot(snapshot)
    offline.enqueue({
      url: `/work-orders/${orderId}/checklist/${itemId}`, method: 'POST', data: payload,
      photoPaths: this.data.editPhotos.filter((p) => !p.fileId).map((p) => p.url), desc: item.name || '检查项填写'
    })
  },
  onRunThisTime(e) {
    const itemId = e.currentTarget.dataset.id
    runItemThisTime(this.data.orderId, itemId)
      .then(() => { wx.showToast({ title: '已加入本次执行', icon: 'success' }); this.fetchChecklist() })
      .catch((err) => wx.showToast({ title: err.message || TOAST_TEXT.OPERATE_FAILED, icon: 'none' }))
  },
  goCheckout() {
    if (this.data.pendingCount > 0) return wx.showToast({ title: `还有 ${this.data.pendingCount} 项未填写`, icon: 'none' })
    nav.open('/pkg-maintenance/pages/checkout/index?orderId=' + this.data.orderId)
  },
  goCheckin() { nav.open('/pkg-maintenance/pages/checkin/index?orderId=' + this.data.orderId) },
  goSign() { nav.open('/pkg-maintenance/pages/record-sign/index?orderId=' + this.data.orderId) },
  goRecord() { nav.open('/pkg-maintenance/pages/record-detail/index?orderId=' + this.data.orderId) }
})