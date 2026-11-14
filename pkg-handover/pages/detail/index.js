// 移交单详情（docs/04 A.9）：状态徽标 + 电梯信息快照 + 3 必传资料 + 接收工程师 + 流程进度，
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
// 按状态与岗位渲染操作（资料上传 / 分配接收人 / 现场查验 / 整改 / 确认归档）；
// 无权限的动作直接隐藏，不渲染成点击报错的按钮（AGENTS §4.1）。
const {
  getHandoverDetail,
  submitHandoverDocs,
  assignHandoverReceiver,
  getReceiverCandidates
} = require('../../../services/handover')
const { uploadImage } = require('../../../services/upload')
const {
  ROLE,
  HANDOVER_STATUS,
  HANDOVER_STATUS_TEXT,
  HANDOVER_STEPS,
  HANDOVER_DOCS,
  WORK_TYPE_CODES,
  HANDOVER_TEXT,
  TOAST_TEXT,
  LOADING_TEXT
} = require('../../../constants/index')
const { ensureLogin } = require('../../../utils/guard')

// 各动作的允许岗位（docs/14 §5 移交单：安装部发起/传资料/整改照片，维保经理分配与确认，接收工程师查验）
const ACTION_ROLES = {
  docs: [ROLE.INSTALL_MANAGER, ROLE.INSTALL_CLERK, ROLE.ADMIN, ROLE.SYS_ADMIN],
  assign: [ROLE.MAINT_MANAGER, ROLE.LEADER, ROLE.ADMIN, ROLE.SYS_ADMIN],
  inspect: [ROLE.WORKER, ROLE.LEADER, ROLE.ADMIN, ROLE.SYS_ADMIN],
  rectify: [ROLE.INSTALL_WORKER, ROLE.INSTALL_MANAGER, ROLE.INSTALL_CLERK, ROLE.ADMIN, ROLE.SYS_ADMIN],
  confirm: [ROLE.MAINT_MANAGER, ROLE.LEADER, ROLE.ADMIN, ROLE.SYS_ADMIN]
}

function buildSteps(status) {
  const idx = HANDOVER_STEPS.map(function (s) { return s.key }).indexOf(status)
  if (idx < 0) return []
  return HANDOVER_STEPS.map(function (s, i) {
    let state = 'todo'
    if (i < idx) state = 'done'
    else if (i === idx) state = status === HANDOVER_STATUS.ARCHIVED ? 'done' : 'current'
    return { key: s.key, label: s.label, state: state }
  })
}

definePage(ROUTES['/pkg-handover/pages/detail/index'], {
  data: {
    id: '',
    handover: null,
    loading: true,
    loadingText: LOADING_TEXT,
    statusText: '',
    steps: [],
    docs: [],
    canDocs: false,
    canAssign: false,
    canInspect: false,
    canRectify: false,
    canConfirm: false,
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
      this.setData({ loading: false })
      wx.showToast({ title: TOAST_TEXT.LOAD_FAILED, icon: 'none' })
    }
  },

  // 查验/整改/确认页返回后刷新状态
  onShow() {
    if (this.data.id) this.fetch()
  },

  async fetch() {
    this.setData({ loading: true })
    try {
      const h = await getHandoverDetail(this.data.id)
      this.apply(h)
    } catch (e) {
      this.setData({ loading: false })
      wx.showToast({ title: e.message || TOAST_TEXT.LOAD_FAILED, icon: 'none' })
    }
  },

  apply(h) {
    const role = (getApp().globalData && getApp().globalData.role) || ''
    const hasRole = function (list) { return list.indexOf(role) > -1 }
    const status = h.status
    const session = this._fileIds || {}
    const docs = HANDOVER_DOCS.map(function (d) {
      return { field: d.field, label: d.label, uploaded: !!h[d.field] || !!session[d.field] }
    })
    const canDocs = status === HANDOVER_STATUS.DOC_SUBMITTING && hasRole(ACTION_ROLES.docs)
    const canAssign = status === HANDOVER_STATUS.INSPECTING && hasRole(ACTION_ROLES.assign)
    this.setData({
      handover: h,
      statusText: HANDOVER_STATUS_TEXT[status] || status,
      workTypeText: h.workTypeCode ? (WORK_TYPE_CODES[h.workTypeCode] || h.workTypeCode) : '',
      steps: buildSteps(status),
      docs: docs,
      canDocs: canDocs,
      canAssign: canAssign,
      canInspect: status === HANDOVER_STATUS.INSPECTING && hasRole(ACTION_ROLES.inspect),
      canRectify: status === HANDOVER_STATUS.RECTIFYING && hasRole(ACTION_ROLES.rectify),
      canConfirm: status === HANDOVER_STATUS.PENDING_CONFIRM && hasRole(ACTION_ROLES.confirm),
      receiverIndex: -1,
      loading: false
    })
    if (canAssign && !this._receiverTried) {
      this._receiverTried = true
      this.loadReceivers()
    }
  },

  // 接收工程师候选：人员目录接口无权限（非管理端可读角色）时降级为手工填写
  async loadReceivers() {
    try {
      const list = await getReceiverCandidates()
      this.setData({
        receiverNames: list.map(function (c) {
          return c.name + (c.groupName ? '（' + c.groupName + '）' : '')
        }),
        manualReceiver: list.length === 0
      })
      this._receivers = list
    } catch (e) {
      this.setData({ manualReceiver: true, receiverNames: [] })
    }
  },

  onReceiverChange(e) {
    this.setData({ receiverIndex: Number(e.detail.value) })
  },

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
      if (!d.manualReceiverId) {
        wx.showToast({ title: HANDOVER_TEXT.RECEIVER_REQUIRED, icon: 'none' })
        return
      }
      payload = { receiverId: d.manualReceiverId, receiverName: d.manualReceiverName }
    } else {
      if (d.receiverIndex < 0) {
        wx.showToast({ title: HANDOVER_TEXT.RECEIVER_REQUIRED, icon: 'none' })
        return
      }
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

  // 3 必传资料：每个资料位单独上传，全部就位后一次性提交（后端缺一 422）
  onPickDoc(e) {
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
    if (d.docs.some(function (x) { return !x.uploaded })) {
      wx.showToast({ title: HANDOVER_TEXT.DOCS_REQUIRED, icon: 'none' })
      return
    }
    const h = d.handover || {}
    const ids = this._fileIds || {}
    // 已上传过的资料位沿用详情返回的 fileId（刷新后 _fileIds 为空，避免重复上传）
    const payload = {}
    HANDOVER_DOCS.forEach(function (doc) {
      payload[doc.field] = ids[doc.field] || h[doc.field] || ''
    })
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

  goInspect() {
    wx.navigateTo({ url: '/pkg-handover/pages/inspect/index?id=' + this.data.id })
  },

  goRectify() {
    wx.navigateTo({ url: '/pkg-handover/pages/rectify/index?id=' + this.data.id })
  },

  goConfirm() {
    wx.navigateTo({ url: '/pkg-handover/pages/sign/index?id=' + this.data.id })
  }
})
