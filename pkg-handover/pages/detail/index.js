// 移交单详情（屏 02 · 原型 V1.4 对齐）：plate 抬头 + 四节点进度 + 技术资料容缺格 + 现场验收分组摘要 + 底部动作条。
// 状态机/权限/上传/分配逻辑沿用 docs/04 A.9；查验摘要取 74 项清单按 G01—G13 分组（docs/15 附录 A）。
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const {
  getHandoverDetail,
  getHandoverChecklist,
  submitHandoverDocs,
  assignHandoverReceiver,
  getReceiverCandidates
} = require('../../../services/handover')
const { uploadImage } = require('../../../services/upload')
const {
  ROLE,
  HANDOVER_STATUS,
  HANDOVER_STATUS_TEXT,
  HANDOVER_DOCS,
  HANDOVER_GROUP_LABEL,
  WORK_TYPE_CODES,
  HANDOVER_TEXT,
  TOAST_TEXT,
  LOADING_TEXT
} = require('../../../constants/index')
const { ensureLogin } = require('../../../utils/guard')
const { hasAnyRole } = require('../../../utils/auth')

// 各动作的允许岗位（docs/14 §5 移交单）
const ACTION_ROLES = {
  docs: [ROLE.INSTALL_MANAGER, ROLE.INSTALL_CLERK, ROLE.ADMIN, ROLE.SYS_ADMIN, 'INST_PM', 'INST_CLERK', 'INST_MGR', 'PLATFORM_ADMIN'],
  assign: [ROLE.MAINT_MANAGER, ROLE.LEADER, ROLE.ADMIN, ROLE.SYS_ADMIN, 'MAINT_MGR', 'MAINT_SUP', 'PLATFORM_ADMIN'],
  inspect: [ROLE.WORKER, ROLE.LEADER, ROLE.ADMIN, ROLE.SYS_ADMIN, 'MAINT_TECH', 'MAINT_SUP', 'PLATFORM_ADMIN'],
  rectify: [ROLE.INSTALL_WORKER, ROLE.INSTALL_MANAGER, ROLE.INSTALL_CLERK, ROLE.ADMIN, ROLE.SYS_ADMIN, 'INSTALLER', 'INST_PM', 'INST_CLERK', 'PLATFORM_ADMIN'],
  confirm: [ROLE.MAINT_MANAGER, ROLE.LEADER, ROLE.ADMIN, ROLE.SYS_ADMIN, 'MAINT_MGR', 'MAINT_SUP', 'PLATFORM_ADMIN'],
  review: [ROLE.INSTALL_MANAGER, ROLE.ADMIN, ROLE.SYS_ADMIN, 'INST_MGR', 'PLATFORM_ADMIN']
}

// 原型四节点（安装部提交/内部审核/维保验收/双方签字）；RECTIFYING 落位维保验收
const STEPS4 = ['安装部提交', '内部审核', '维保验收', '双方签字']
function buildSteps4(status) {
  let active = 0
  if (status === HANDOVER_STATUS.DOC_SUBMITTING) active = 0
  else if (status === HANDOVER_STATUS.PENDING_INST_REVIEW) active = 1
  else if (status === HANDOVER_STATUS.INSPECTING || status === HANDOVER_STATUS.RECTIFYING) active = 2
  else if (status === HANDOVER_STATUS.PENDING_CONFIRM) active = 3
  else if (status === HANDOVER_STATUS.ARCHIVED) active = 4
  return STEPS4.map(function (label, i) {
    return { label: label, state: i < active ? 'done' : (i === active ? 'active' : '') }
  })
}

function statusTagClass(status) {
  if (status === HANDOVER_STATUS.RECTIFYING) return 'tag-signal'
  if (status === HANDOVER_STATUS.ARCHIVED) return 'tag-ok'
  if (status === HANDOVER_STATUS.PENDING_CONFIRM) return 'tag-ok'
  if (status === HANDOVER_STATUS.DOC_SUBMITTING || status === HANDOVER_STATUS.PENDING_INST_REVIEW) return 'tag-amber'
  return 'tag-amber'
}

definePage(ROUTES['/pkg-handover/pages/detail/index'], {
  data: {
    id: '',
    handover: null,
    loading: true,
    loadingText: LOADING_TEXT,
    error: '',
    statusText: '',
    statusTag: 'tag-amber',
    steps4: [],
    docs: [],
    missingCount: 0,
    capacityText: '-',
    uploaderText: '-',
    checkGroups: [],
    checkTotal: 0,
    passCount: 0,
    checkStarted: false,
    keys: [],
    canDocs: false,
    canAssign: false,
    canInspect: false,
    canRectify: false,
    canConfirm: false,
    canReview: false,
    hasRectify: false,
    receiverNames: [],
    receiverIndex: -1,
    manualReceiver: false,
    manualReceiverId: '',
    manualReceiverName: '',
    submitting: false,
    uploadingIndex: -1,
    text: HANDOVER_TEXT
  },

  onLoad(query) {
    if (!ensureLogin()) return
    const id = (query && query.id) || ''
    this.setData({ id: id })
    if (!id) {
      this.setData({ loading: false, error: TOAST_TEXT.LOAD_FAILED })
    }
  },

  onShow() {
    if (this.data.id) this.fetch()
  },

  async fetch() {
    this.setData({ loading: true, error: '' })
    try {
      const h = await getHandoverDetail(this.data.id)
      this.apply(h)
      this.loadChecklist()
    } catch (e) {
      this.setData({ loading: false, error: (e && e.message) || TOAST_TEXT.LOAD_FAILED })
    }
  },

  onRetry() { this.fetch() },

  // 74 项清单分组摘要（只读投影；逐项留证在 inspect 页）
  loadChecklist() {
    getHandoverChecklist(this.data.id).then((res) => {
      const items = (res && res.items) || []
      const check = (res && res.check) || {}
      const map = {}
      const groups = []
      items.forEach(function (it) {
        const g = it.itemGroup || 'G01'
        if (!map[g]) { map[g] = { group: g, label: HANDOVER_GROUP_LABEL[g] || g, total: 0, done: 0, fail: 0, names: [] }; groups.push(map[g]) }
        map[g].total += 1
        if (it.result) map[g].done += 1
        if (it.result === 'FAIL') map[g].fail += 1
        if (map[g].names.length < 2 && it.itemName) map[g].names.push(it.itemName)
      })
      const started = items.some(function (it) { return !!it.result })
      this.setData({
        checkGroups: groups.map(function (g) {
          return { label: g.label, sub: g.names.join('、') || '—', fail: g.fail > 0, done: g.done, total: g.total, started: g.done > 0 }
        }),
        checkTotal: items.length,
        passCount: check.passCount || items.filter(function (it) { return it.result === 'PASS' }).length,
        checkStarted: started
      })
    }).catch(() => { /* 清单未生成时不阻断详情 */ })
  },

  apply(h) {
    const hasRole = hasAnyRole
    const status = h.status
    const session = this._fileIds || {}
    // 资料格：优先 D 目录（上传页 docs），回退 3 必传
    let docs
    if (h.docs && h.docs.length) {
      docs = h.docs.map(function (d) {
        return { field: d.docType, label: d.docName || d.docType, uploaded: !!d.fileId, missing: !!d.missing }
      })
    } else {
      docs = HANDOVER_DOCS.map(function (d) {
        return { field: d.field, label: d.label, uploaded: !!h[d.field] || !!session[d.field], missing: false }
      })
    }
    const missingCount = docs.filter(function (d) { return !d.uploaded }).length
    const canDocs = status === HANDOVER_STATUS.DOC_SUBMITTING && hasRole(ACTION_ROLES.docs)
    const canAssign = status === HANDOVER_STATUS.INSPECTING && hasRole(ACTION_ROLES.assign)
    const capacity = (h.ratedLoad ? h.ratedLoad + (h.ratedLoadUnit || 'kg') : '') + (h.ratedSpeed ? ' / ' + h.ratedSpeed + (h.ratedSpeedUnit || 'm/s') : '')
    this.setData({
      handover: h,
      statusText: HANDOVER_STATUS_TEXT[status] || status,
      statusTag: statusTagClass(status),
      workTypeText: h.workTypeCode ? (WORK_TYPE_CODES[h.workTypeCode] || h.workTypeCode) : '',
      steps4: buildSteps4(status),
      docs: docs,
      missingCount: missingCount,
      capacityText: capacity || '-',
      uploaderText: h.uploaderName || h.initiatorName || '-',
      keys: h.keys || [],
      hasRectify: !!(h.rectifications && h.rectifications.length),
      canDocs: canDocs,
      canAssign: canAssign,
      canInspect: status === HANDOVER_STATUS.INSPECTING && hasRole(ACTION_ROLES.inspect),
      canRectify: status === HANDOVER_STATUS.RECTIFYING && hasRole(ACTION_ROLES.rectify),
      canConfirm: status === HANDOVER_STATUS.PENDING_CONFIRM && hasRole(ACTION_ROLES.confirm),
      canReview: status === HANDOVER_STATUS.PENDING_INST_REVIEW && hasRole(ACTION_ROLES.review),
      receiverIndex: -1,
      loading: false
    })
    if (canAssign && !this._receiverTried) {
      this._receiverTried = true
      this.loadReceivers()
    }
  },

  async loadReceivers() {
    try {
      const list = await getReceiverCandidates()
      this.setData({
        receiverNames: list.map(function (c) { return c.name + (c.groupName ? '（' + c.groupName + '）' : '') }),
        manualReceiver: list.length === 0
      })
      this._receivers = list
    } catch (e) {
      this.setData({ manualReceiver: true, receiverNames: [] })
    }
  },

  onReceiverChange(e) { this.setData({ receiverIndex: Number(e.detail.value) }) },
  onManualReceiverInput(e) {
    const field = e.currentTarget.dataset.field
    const patch = {}
    patch[field] = (e.detail.value || '').trim()
    this.setData(patch)
  },

  async onAssign() {
    const d = this.data
    let payload = null
    if (d.manualReceiver) {
      if (!d.manualReceiverId) { wx.showToast({ title: HANDOVER_TEXT.RECEIVER_REQUIRED, icon: 'none' }); return }
      payload = { receiverId: d.manualReceiverId, receiverName: d.manualReceiverName }
    } else {
      if (d.receiverIndex < 0) { wx.showToast({ title: HANDOVER_TEXT.RECEIVER_REQUIRED, icon: 'none' }); return }
      const c = (this._receivers || [])[d.receiverIndex] || {}
      payload = { receiverId: c.id, receiverName: c.name }
    }
    this.setData({ submitting: true })
    try {
      await assignHandoverReceiver(d.id, payload)
      wx.showToast({ title: '已分配', icon: 'success' })
      this.fetch()
    } catch (e) {
      wx.showToast({ title: e.message || TOAST_TEXT.OPERATE_FAILED, icon: 'none' })
    }
    this.setData({ submitting: false })
  },

  // 资料格点击：可上传岗位走上传；其余只读
  onPickDoc(e) {
    if (!this.data.canDocs) return
    const index = Number(e.currentTarget.dataset.index)
    if (this.data.uploadingIndex > -1) return
    wx.showActionSheet({
      itemList: ['拍照或相册选择', '聊天记录中的文件'],
      success: (res) => {
        if (res.tapIndex === 0) this.chooseImage(index)
        else this.chooseFile(index)
      }
    })
  },

  chooseImage(index) {
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      success: (res) => this.uploadDoc(index, res.tempFiles[0].tempFilePath)
    })
  },

  chooseFile(index) {
    wx.chooseMessageFile({
      count: 1,
      type: 'file',
      extension: ['pdf', 'jpg', 'jpeg', 'png'],
      success: (res) => this.uploadDoc(index, res.tempFiles[0].path)
    })
  },

  async uploadDoc(index, filePath) {
    this.setData({ uploadingIndex: index })
    wx.showLoading({ title: '上传中' })
    try {
      const up = await uploadImage(filePath)
      const patch = {}
      patch['docs[' + index + '].uploaded'] = true
      this.setData(patch)
      this._fileIds = this._fileIds || {}
      this._fileIds[this.data.docs[index].field] = up.fileId
      wx.showToast({ title: '已上传', icon: 'success' })
    } catch (e) {
      wx.showToast({ title: e.message || '上传失败', icon: 'none' })
    }
    wx.hideLoading()
    this.setData({ uploadingIndex: -1 })
  },

  async onSubmitDocs() {
    const d = this.data
    const h = d.handover || {}
    const ids = this._fileIds || {}
    // 后端契约恒为 3 平铺必传字段（HandoverService.submitDocs 缺一 422）；D 目录格仅展示，不进提交体
    const payload = {}
    HANDOVER_DOCS.forEach(function (doc) { payload[doc.field] = ids[doc.field] || h[doc.field] || '' })
    if (HANDOVER_DOCS.some(function (doc) { return !payload[doc.field] })) {
      wx.showToast({ title: HANDOVER_TEXT.DOCS_REQUIRED, icon: 'none' })
      return
    }
    this.setData({ submitting: true })
    try {
      await submitHandoverDocs(d.id, payload)
      wx.showToast({ title: '资料已提交', icon: 'success' })
      this.fetch()
    } catch (e) {
      wx.showToast({ title: e.message || TOAST_TEXT.SAVE_FAILED, icon: 'none' })
    }
    this.setData({ submitting: false })
  },

  goInspect() { wx.navigateTo({ url: '/pkg-handover/pages/inspect/index?id=' + this.data.id }) },
  goRectify() { wx.navigateTo({ url: '/pkg-handover/pages/rectify/index?id=' + this.data.id }) },
  goConfirm() { wx.navigateTo({ url: '/pkg-handover/pages/sign/index?id=' + this.data.id }) },
  goUpload() { wx.navigateTo({ url: '/pkg-handover/pages/upload/index?id=' + this.data.id }) },
  goReview() { wx.navigateTo({ url: '/pkg-handover/pages/review/index?id=' + this.data.id }) }
})
