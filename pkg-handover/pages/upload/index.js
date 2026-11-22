const { getHandoverDetail, submitHandoverDocs } = require('../../../services/handover')
const { uploadImage } = require('../../../services/upload')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const nav = require('../../../utils/nav')

// 3 项强制卡点 docType（与后端 HandoverService 必传口径一致）
const REQUIRED = ['INSPECTION_REPORT', 'MAINTENANCE_MANUAL', 'QUALITY_CERT']

definePage(ROUTES['/pkg-handover/pages/upload/index'], {
  data: { id: '', docs: [], loading: true, error: '', submitting: false },
  onLoad(query) { this.setData({ id: (query && (query.handoverId || query.id)) || '' }); this.load() },
  onBack() { nav.back() },
  load() {
    this.setData({ loading: true, error: '' })
    getHandoverDetail(this.data.id).then((d) => {
      const docs = ((d && d.docs) || []).map((doc, idx) => Object.assign({}, doc, { idx, required: REQUIRED.indexOf(doc.docType) > -1 }))
      this.setData({ docs, loading: false })
    }).catch((e) => this.setData({ loading: false, error: (e && e.message) || '加载失败' }))
  },
  onRetry() { this.load() },
  onUpload(e) {
    const idx = Number(e.currentTarget.dataset.idx)
    wx.chooseMedia({
      count: 1, mediaType: ['image'],
      success: (res) => {
        wx.showLoading({ title: '上传中' })
        uploadImage(res.tempFiles[0].tempFilePath).then((up) => {
          wx.hideLoading()
          const docs = this.data.docs.slice()
          docs[idx] = Object.assign({}, docs[idx], { fileId: up.fileId, url: up.url, missing: false })
          this.setData({ docs })
        }).catch((er) => { wx.hideLoading(); wx.showToast({ title: (er && er.message) || '上传失败', icon: 'none' }) })
      }
    })
  },
  onToggleMissing(e) {
    const idx = Number(e.currentTarget.dataset.idx)
    const docs = this.data.docs.slice()
    docs[idx] = Object.assign({}, docs[idx], { missing: !docs[idx].missing })
    this.setData({ docs })
  },
  onSubmit() {
    const docs = this.data.docs
    for (const d of docs) {
      if (d.required && !d.fileId && !d.missing) return wx.showToast({ title: '强制资料「' + (d.docName || d.docType) + '」须上传或标记缺件', icon: 'none' })
    }
    this.setData({ submitting: true })
    submitHandoverDocs(this.data.id, { docs: docs.map((d) => ({ docType: d.docType, fileId: d.fileId || '', missing: !!d.missing })) })
      .then(() => { wx.showToast({ title: '资料已提交', icon: 'success' }); setTimeout(() => nav.back(), 800) })
      .catch((e) => { this.setData({ submitting: false }); wx.showToast({ title: (e && e.message) || '提交失败', icon: 'none' }) })
  }
})