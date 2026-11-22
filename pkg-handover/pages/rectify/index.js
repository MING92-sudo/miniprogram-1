const { getHandoverRectifications, rectifyHandoverItem, recheckHandoverItem, getHandoverDetail } = require('../../../services/handover')
const { uploadImage } = require('../../../services/upload')
const { getUserInfo } = require('../../../utils/auth')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const nav = require('../../../utils/nav')

definePage(ROUTES['/pkg-handover/pages/rectify/index'], {
  data: { id: '', handover: null, rects: [], loading: true, error: '', submitting: false },
  onLoad(query) { this.setData({ id: (query && query.id) || '' }); this.load() },
  onShow() { if (this.data.id) this.load() },
  onBack() { nav.back() },
  load() {
    this.setData({ loading: true, error: '' })
    getHandoverDetail(this.data.id).then((h) => this.setData({ handover: h })).catch(() => {})
    getHandoverRectifications(this.data.id).then((list) => {
      const rects = (list || []).map((r, idx) => Object.assign({}, r, { idx, rectMeasure: r.rectMeasure || '', photos: [], afterPhotoFileId: r.afterPhotoFileId || '' }))
      this.setData({ rects, loading: false })
    }).catch((e) => this.setData({ loading: false, error: (e && e.message) || '加载失败' }))
  },
  onRetry() { this.load() },
  onMeasure(e) {
    const idx = Number(e.currentTarget.dataset.idx)
    const rects = this.data.rects.slice(); rects[idx] = Object.assign({}, rects[idx], { rectMeasure: e.detail.value }); this.setData({ rects })
  },
  choosePhoto(e) {
    this._photoIdx = Number(e.currentTarget.dataset.idx)
    wx.navigateTo({ url: `/pages/common/watermark-camera?from=rectify&orderId=${this.data.id}` })
  },
  onPhotoReady(photo) {
    if (!photo || this._photoIdx == null) return
    const idx = this._photoIdx
    uploadImage(photo).then((up) => {
      const rects = this.data.rects.slice()
      rects[idx] = Object.assign({}, rects[idx], { photos: rects[idx].photos.concat(up.url), afterPhotoFileId: up.fileId })
      this.setData({ rects })
    }).catch((e) => wx.showToast({ title: (e && e.message) || '上传失败', icon: 'none' }))
  },
  onRectify(e) {
    const idx = Number(e.currentTarget.dataset.idx)
    const r = this.data.rects[idx]
    if (!r.rectMeasure) return wx.showToast({ title: '请填写整改措施', icon: 'none' })
    const me = getUserInfo() || {}
    rectifyHandoverItem(this.data.id, r.id, { rectMeasure: r.rectMeasure, afterPhotoFileId: r.afterPhotoFileId, rectifierName: me.name || '' })
      .then(() => { wx.showToast({ title: '整改已上传', icon: 'success' }); this.load() })
      .catch((er) => wx.showToast({ title: (er && er.message) || '提交失败', icon: 'none' }))
  },
  onRecheck(e) {
    const idx = Number(e.currentTarget.dataset.idx)
    const result = e.currentTarget.dataset.result
    const r = this.data.rects[idx]
    const me = getUserInfo() || {}
    recheckHandoverItem(this.data.id, r.id, { result, recheckerId: me.id || '', recheckerName: me.name || '' })
      .then(() => { wx.showToast({ title: result === 'PASS' ? '复验通过' : '复验不通过', icon: 'success' }); this.load() })
      .catch((er) => wx.showToast({ title: (er && er.message) || '复验失败', icon: 'none' }))
  }
})