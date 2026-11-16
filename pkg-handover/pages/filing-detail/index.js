// 外接建档单详情（docs/04 A.10）：状态 + 资料清单（缺件标红）+ 接管查验结果 + 交接确认，
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
// 按状态渲染动作（资料收集中→上传资料、接管查验→接管查验、待交接确认→交接确认）；
// 无权限的动作直接隐藏，不渲染成点击报错的按钮（AGENTS §4.1）。
const { getFilingDetail, submitFilingDocs } = require('../../../services/filing')
const { uploadImage } = require('../../../services/upload')
const {
  FILING_STATUS,
  FILING_STATUS_TEXT,
  FILING_STEPS,
  FILING_DOC_TYPES,
  TAKEOVER_CHECK_ITEMS,
  FILING_WRITE_ROLES,
  FILING_READ_ROLES,
  HANDOVER_RESULT_TEXT,
  TAKEOVER_RESPONSIBILITY_TEXT,
  WORK_TYPE_CODES,
  FILING_TEXT,
  TOAST_TEXT,
  LOADING_TEXT
} = require('../../../constants/index')
const { ensureLogin } = require('../../../utils/guard')
const { hasAnyRole } = require('../../../utils/auth')

// 接管查验项中文名（itemType → 中文，后端只回码值）
const ITEM_LABELS = {}
TAKEOVER_CHECK_ITEMS.forEach(function (i) {
  ITEM_LABELS[i.itemType] = i.label
})

// 进度条：INIT 不在流程内（与后端一致），状态缺省时退化为首格
function buildSteps(status) {
  const idx = FILING_STEPS.map(function (s) { return s.key }).indexOf(status)
  if (idx < 0) return []
  return FILING_STEPS.map(function (s, i) {
    let state = 'todo'
    if (i < idx) state = 'done'
    else if (i === idx) state = status === FILING_STATUS.ARCHIVED ? 'done' : 'current'
    return { key: s.key, label: s.label, state: state }
  })
}

definePage(ROUTES['/pkg-handover/pages/filing-detail/index'], {
  data: {
    id: '',
    filing: null,
    loading: true,
    loadingText: LOADING_TEXT,
    statusText: '',
    steps: [],
    docs: [],
    checks: [],
    confirmation: null,
    canDocs: false,
    canInspect: false,
    canConfirm: false,
    uploadingCode: '',
    submitting: false,
    forbidden: false,
    text: FILING_TEXT
  },

  onLoad(query) {
    if (!ensureLogin()) return
    const id = (query && query.id) || ''
    // 无 filing:view 的岗位（如安装员）不发起详情请求，直接给只读提示，避免点开即报错（多角色并集判定）
    if (!hasAnyRole(FILING_READ_ROLES)) {
      this.setData({ id: id, loading: false, forbidden: true })
      return
    }
    this.setData({ id: id })
    if (!id) {
      this.setData({ loading: false })
      wx.showToast({ title: TOAST_TEXT.LOAD_FAILED, icon: 'none' })
    }
  },

  // 查验/确认页返回后刷新状态
  onShow() {
    if (this.data.id && !this.data.forbidden) this.fetch()
  },

  async fetch() {
    this.setData({ loading: true })
    try {
      const f = await getFilingDetail(this.data.id)
      this.apply(f)
    } catch (e) {
      this.setData({ loading: false })
      wx.showToast({ title: e.message || TOAST_TEXT.LOAD_FAILED, icon: 'none' })
    }
  },

  apply(f) {
    const canWrite = hasAnyRole(FILING_WRITE_ROLES)
    const status = f.status
    const submitted = f.docs || []
    const cache = this._fileIds || {}
    // 资料清单固定 6 类：已提交的取最近一条（同一类可能重复提交），未提交的显示待上传
    const docs = FILING_DOC_TYPES.map(function (t) {
      const hit = submitted.filter(function (d) { return d.docType === t.code }).pop() || null
      return {
        code: t.code,
        label: t.label,
        fileId: hit ? hit.fileId || '' : cache[t.code] || '',
        uploaded: !!(hit && hit.fileId) || !!cache[t.code],
        missing: hit ? !!hit.missingFlag : false
      }
    })
    const checkViews = (f.takeoverChecks || []).map(function (c) {
      return {
        id: c.id || c.checkedAt || '',
        checkerName: c.checkerName || '',
        checkedAt: c.checkedAt || '',
        remark: c.remark || '',
        items: (c.items || []).map(function (i) {
          return {
            itemType: i.itemType,
            label: ITEM_LABELS[i.itemType] || i.itemType,
            resultText: HANDOVER_RESULT_TEXT[i.result] || i.result || '-',
            result: i.result,
            responsibilityText: i.responsibility ? (TAKEOVER_RESPONSIBILITY_TEXT[i.responsibility] || i.responsibility) : '',
            remark: i.remark || ''
          }
        })
      }
    })
    this.setData({
      filing: f,
      statusText: FILING_STATUS_TEXT[status] || status,
      workTypeText: f.workTypeCode ? (WORK_TYPE_CODES[f.workTypeCode] || f.workTypeCode) : '',
      steps: buildSteps(status),
      docs: docs,
      checks: checkViews,
      confirmation: f.confirmation || null,
      canDocs: status === FILING_STATUS.DOC_COLLECTING && canWrite,
      canInspect: status === FILING_STATUS.TAKEOVER_CHECKING && canWrite,
      canConfirm: status === FILING_STATUS.PENDING_CONFIRM && canWrite,
      loading: false
    })
  },

  onPickDoc(e) {
    const code = e.currentTarget.dataset.code
    if (this.data.uploadingCode) return
    wx.showActionSheet({
      itemList: ['拍照或相册选择', '聊天记录中的文件'],
      success: (res) => {
        if (res.tapIndex === 0) {
          wx.chooseMedia({
            count: 1,
            mediaType: ['image'],
            success: (r) => this.uploadDoc(code, r.tempFiles[0].tempFilePath)
          })
        } else {
          wx.chooseMessageFile({
            count: 1,
            type: 'file',
            extension: ['pdf', 'jpg', 'jpeg', 'png'],
            success: (r) => this.uploadDoc(code, r.tempFiles[0].path)
          })
        }
      }
    })
  },

  async uploadDoc(code, filePath) {
    this.setData({ uploadingCode: code })
    wx.showLoading({ title: '上传中' })
    try {
      const up = await uploadImage(filePath)
      this._fileIds = this._fileIds || {}
      this._fileIds[code] = up.fileId
      const idx = this.data.docs.map(function (d) { return d.code }).indexOf(code)
      if (idx > -1) {
        const patch = {}
        patch['docs[' + idx + '].uploaded'] = true
        patch['docs[' + idx + '].missing'] = false
        this.setData(patch)
      }
      wx.showToast({ title: '已上传', icon: 'success' })
    } catch (e) {
      wx.showToast({ title: e.message || '上传失败', icon: 'none' })
    }
    wx.hideLoading()
    this.setData({ uploadingCode: '' })
  },

  // 缺件待补办：标记后本类资料无需上传（后端 missing=true 允许缺件）
  onToggleMissing(e) {
    const idx = Number(e.currentTarget.dataset.index)
    const doc = this.data.docs[idx]
    if (!doc) return
    const next = !doc.missing
    if (next && this._fileIds) delete this._fileIds[doc.code]
    const patch = {}
    patch['docs[' + idx + '].missing'] = next
    if (next) patch['docs[' + idx + '].uploaded'] = false
    this.setData(patch)
  },

  async onSubmitDocs() {
    const docs = this.data.docs
    const undecided = docs.filter(function (d) { return !d.uploaded && !d.missing })
    if (undecided.length) {
      wx.showToast({ title: FILING_TEXT.DOCS_REQUIRED, icon: 'none' })
      return
    }
    const payload = {
      docs: docs
        .filter(function (d) { return d.uploaded || d.missing })
        .map(function (d) {
          return {
            docType: d.code,
            fileId: d.missing ? null : d.fileId || '',
            missing: !!d.missing
          }
        })
    }
    if (!payload.docs.length) {
      wx.showToast({ title: FILING_TEXT.DOCS_EMPTY, icon: 'none' })
      return
    }
    this.setData({ submitting: true })
    try {
      await submitFilingDocs(this.data.id, payload)
      wx.showToast({ title: '资料已提交', icon: 'success' })
      this._fileIds = {}
      this.fetch()
    } catch (e) {
      wx.showToast({ title: e.message || TOAST_TEXT.SAVE_FAILED, icon: 'none' })
    }
    this.setData({ submitting: false })
  },

  goInspect() {
    wx.navigateTo({ url: '/pkg-handover/pages/filing-inspect/index?id=' + this.data.id })
  },

  goConfirm() {
    wx.navigateTo({ url: '/pkg-handover/pages/filing-confirm/index?id=' + this.data.id })
  }
})
