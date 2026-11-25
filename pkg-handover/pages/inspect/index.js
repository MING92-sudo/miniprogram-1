// 7.2/7.3 判定由后端冻结，前端仅采集 + 有条件移交三条件输入。
const { getHandoverChecklist, submitHandoverChecklist } = require('../../../services/handover')
const { uploadImage } = require('../../../services/upload')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const { HANDOVER_GROUP_LABEL, HANDOVER_RESULTS, HANDOVER_RESULT_TEXT, HANDOVER_NA_CODES } = require('../../../constants/index')
const nav = require('../../../utils/nav')

definePage(ROUTES['/pkg-handover/pages/inspect/index'], {
  data: {
    id: '', check: null, groups: [], items: [], loading: true, error: '', submitting: false,
    naCodes: HANDOVER_NA_CODES, naLabels: HANDOVER_NA_CODES.map((n) => n.code + ' ' + n.label),
    results: HANDOVER_RESULTS, resultText: HANDOVER_RESULT_TEXT,
    conditional: { siteLimited: false, tripartiteAgreed: false, controlMeasures: '' },
    doneCount: 0
  },
  onLoad(query) { this.setData({ id: (query && query.id) || '' }); this.load() },
  onBack() { nav.back() },
  load() {
    this.setData({ loading: true, error: '' })
    getHandoverChecklist(this.data.id).then((res) => {
      const items = ((res && res.items) || []).map((it, idx) => Object.assign({}, it, {
        idx, result: it.result || '', measuredValue: it.measuredValue || '', failDesc: it.failDesc || '',
        naReason: it.naReason || '', photos: [], photoFileIds: it.photoFileIds ? String(it.photoFileIds).split(',') : []
      }))
      const groupMap = {}
      const groups = []
      items.forEach((it) => {
        const g = it.itemGroup || 'G01'
        if (!groupMap[g]) { groupMap[g] = { group: g, groupLabel: HANDOVER_GROUP_LABEL[g] || '', items: [] }; groups.push(groupMap[g]) }
        groupMap[g].items.push(it)
      })
      this.setData({ check: res && res.check, items, groups, loading: false, doneCount: items.filter((i) => i.result).length })
    }).catch((e) => this.setData({ loading: false, error: (e && e.message) || '加载失败' }))
  },
  onRetry() { this.load() },
  updateItem(idx, patch) {
    const items = this.data.items.slice()
    items[idx] = Object.assign({}, items[idx], patch)
    const groups = this.data.groups.map((g) => Object.assign({}, g, { items: g.items.map((it) => it.idx === idx ? items[idx] : it) }))
    this.setData({ items, groups, doneCount: items.filter((i) => i.result).length })
  },
  onResult(e) { this.updateItem(Number(e.currentTarget.dataset.idx), { result: e.currentTarget.dataset.value }) },
  onMeasure(e) { this.updateItem(Number(e.currentTarget.dataset.idx), { measuredValue: e.detail.value }) },
  onFailDesc(e) { this.updateItem(Number(e.currentTarget.dataset.idx), { failDesc: e.detail.value }) },
  onNaReason(e) { this.updateItem(Number(e.currentTarget.dataset.idx), { naReason: HANDOVER_NA_CODES[Number(e.detail.value)].code }) },
  choosePhoto(e) {
    const idx = Number(e.currentTarget.dataset.idx)
    this._photoIdx = idx
    const it = this.data.items[idx]
    wx.navigateTo({ url: `/pages/common/watermark-camera?from=handover&orderId=${this.data.id}&itemName=${encodeURIComponent(it.itemName || '')}` })
  },
  onPhotoReady(photo) {
    if (!photo || this._photoIdx == null) return
    const idx = this._photoIdx
    uploadImage(photo).then((up) => {
      const it = this.data.items[idx]
      this.updateItem(idx, { photos: it.photos.concat(up.url), photoFileIds: it.photoFileIds.concat(up.fileId) })
    }).catch((e) => wx.showToast({ title: (e && e.message) || '上传失败', icon: 'none' }))
  },
  removePhoto(e) {
    const idx = Number(e.currentTarget.dataset.idx)
    const pidx = Number(e.currentTarget.dataset.pidx)
    const it = this.data.items[idx]
    this.updateItem(idx, { photos: it.photos.filter((_, n) => n !== pidx), photoFileIds: it.photoFileIds.filter((_, n) => n !== pidx) })
  },
  onCondToggle(e) {
    const key = e.currentTarget.dataset.key
    this.setData({ ['conditional.' + key]: !this.data.conditional[key] })
  },
  onCondInput(e) { this.setData({ 'conditional.controlMeasures': e.detail.value }) },
  onSubmit() {
    const items = this.data.items
    for (const it of items) {
      if (!it.result) return wx.showToast({ title: '第 ' + it.itemNo + ' 项未选结果', icon: 'none' })
      if (it.result === 'FAIL' && !it.failDesc) return wx.showToast({ title: '第 ' + it.itemNo + ' 项不合格须填描述', icon: 'none' })
      if (it.result === 'FAIL' && (it.important || it.itemNo >= 57) && !it.photoFileIds.length) return wx.showToast({ title: '第 ' + it.itemNo + ' 项不合格须附照片', icon: 'none' })
      if (it.result === 'NA' && !it.naReason) return wx.showToast({ title: '第 ' + it.itemNo + ' 项不适用须选原因码', icon: 'none' })
    }
    this.setData({ submitting: true })
    const payload = {
      items: items.map((it) => ({ itemNo: it.itemNo, result: it.result, measuredValue: it.measuredValue, failDesc: it.failDesc, naReason: it.naReason, photoFileIds: it.photoFileIds.join(',') })),
      conditional: this.data.conditional
    }
    submitHandoverChecklist(this.data.id, payload)
      .then(() => { wx.showToast({ title: '查验已提交', icon: 'success' }); setTimeout(() => nav.back(), 800) })
      .catch((e) => { this.setData({ submitting: false }); wx.showToast({ title: (e && e.message) || '提交失败', icon: 'none' }) })
  }
})