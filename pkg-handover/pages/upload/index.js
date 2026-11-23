const { getHandoverDetail, submitHandoverDocs } = require('../../../services/handover')
const { uploadImage } = require('../../../services/upload')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const nav = require('../../../utils/nav')

// 3 项强制资料：docType → 后端平铺字段（HandoverService.submitDocs 缺一 422）
const DOC_DEFS = [
  { docType: 'INSPECTION_REPORT', docName: '监督检验报告', field: 'supervisionReportFileId' },
  { docType: 'MAINTENANCE_MANUAL', docName: '维护使用说明书', field: 'maintenanceManualFileId' },
  { docType: 'QUALITY_CERT', docName: '整机质量证明', field: 'qualityCertificateFileId' }
]

definePage(ROUTES['/pkg-handover/pages/upload/index'], {
  data: { id: '', docs: [], loading: true, error: '', submitting: false },
  onLoad(query) { this.setData({ id: (query && (query.handoverId || query.id)) || '' }); this.load() },
  onBack() { nav.back() },
  load() {
    this.setData({ loading: true, error: '' })
    return getHandoverDetail(this.data.id).then((d) => {
      const detail = d || {}
      const incoming = {}
      ;(detail.docs || []).forEach((doc) => { incoming[doc.docType] = doc })
      const docs = DOC_DEFS.map((def, idx) => {
        const doc = incoming[def.docType] || {}
        return {
          idx,
          docType: def.docType,
          docName: doc.docName || def.docName,
          fileId: doc.fileId || detail[def.field] || '',
          missing: !!doc.missing,
          required: true
        }
      })
      this.setData({ docs, loading: false })
    }).catch((e) => { this.setData({ loading: false, error: (e && e.message) || '加载失败' }) })
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
    if (this.data.submitting) return
    const payload = {}
    DOC_DEFS.forEach((def) => {
      const d = this.data.docs.find((x) => x.docType === def.docType)
      payload[def.field] = (d && d.fileId && !d.missing) ? d.fileId : ''
    })
    const absent = DOC_DEFS.find((def) => !payload[def.field])
    if (absent) {
      const d = this.data.docs.find((x) => x.docType === absent.docType)
      return wx.showToast({ title: '强制资料「' + ((d && d.docName) || absent.docName) + '」须上传', icon: 'none' })
    }
    this.setData({ submitting: true })
    return submitHandoverDocs(this.data.id, payload)
      .then(() => { wx.showToast({ title: '资料已提交', icon: 'success' }); setTimeout(() => nav.back(), 800) })
      .catch((e) => { this.setData({ submitting: false }); wx.showToast({ title: (e && e.message) || '提交失败', icon: 'none' }) })
  }
})