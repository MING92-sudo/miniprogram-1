// 移交单整改与复验（docs/04 A.9 / HandoverService.submitRectify + recheckRectify）：
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
// 只对不合格项上传整改前/后照片（默认预选查验阶段记录的 FAIL 项），同一页提供复验 PASS/FAIL。
// 说明：GET /handovers/{id} 不下发查验明细，故不合格项由查验页写入本地缓存预选，缺失时由用户勾选。
const { getHandoverDetail, submitHandoverRectify, recheckHandoverRectify } = require('../../../services/handover')
const { uploadImage } = require('../../../services/upload')
const {
  ROLE,
  HANDOVER_STATUS,
  HANDOVER_CHECK_ITEMS,
  HANDOVER_TEXT,
  TOAST_TEXT,
  LOADING_TEXT
} = require('../../../constants/index')
const { ensureLogin } = require('../../../utils/guard')

// 整改照片：安装部现场整改（docs/14 §5 INSTALL_WORKER 整改照片）
const RECTIFY_ROLES = [
  ROLE.INSTALL_WORKER,
  ROLE.INSTALL_MANAGER,
  ROLE.INSTALL_CLERK,
  ROLE.ADMIN,
  ROLE.SYS_ADMIN
]
// 复验：接收工程师/班组长（HandoverService.NODE_ROLES：SITE_INSPECT）
const RECHECK_ROLES = [ROLE.WORKER, ROLE.LEADER, ROLE.ADMIN, ROLE.SYS_ADMIN]

function failItemsKey(id) {
  return 'handover_fail_' + id
}

definePage(ROUTES['/pkg-handover/pages/rectify/index'], {
  data: {
    id: '',
    handover: null,
    loading: true,
    loadingText: LOADING_TEXT,
    locked: false,
    canRectify: false,
    canRecheck: false,
    items: [],
    submitting: false,
    uploading: '',
    text: HANDOVER_TEXT
  },

  onLoad(query) {
    if (!ensureLogin()) return
    const id = (query && query.id) || ''
    const role = (getApp().globalData && getApp().globalData.role) || ''
    const cached = id ? wx.getStorageSync(failItemsKey(id)) : []
    const fails = Array.isArray(cached) ? cached : []
    const items = HANDOVER_CHECK_ITEMS.map(function (i) {
      return {
        itemType: i.itemType,
        label: i.label,
        selected: fails.indexOf(i.itemType) > -1,
        beforePhotoFileId: '',
        afterPhotoFileId: '',
        beforePath: '',
        afterPath: ''
      }
    })
    this.setData({
      id: id,
      items: items,
      canRectify: RECTIFY_ROLES.indexOf(role) > -1,
      canRecheck: RECHECK_ROLES.indexOf(role) > -1
    })
    if (!id) {
      this.setData({ loading: false, locked: true })
      return
    }
    this.fetch()
  },

  async fetch() {
    try {
      const h = await getHandoverDetail(this.data.id)
      this.setData({
        handover: h,
        loading: false,
        locked: h.status !== HANDOVER_STATUS.RECTIFYING
      })
    } catch (e) {
      this.setData({ loading: false, locked: true })
      wx.showToast({ title: e.message || TOAST_TEXT.LOAD_FAILED, icon: 'none' })
    }
  },

  onToggle(e) {
    const index = Number(e.currentTarget.dataset.index)
    const patch = {}
    patch['items[' + index + '].selected'] = !this.data.items[index].selected
    this.setData(patch)
  },

  // 拍照/相册 → uploadImage（services/upload.js）→ fileId
  onPick(e) {
    const index = Number(e.currentTarget.dataset.index)
    const kind = e.currentTarget.dataset.kind // before | after
    if (this.data.uploading) return
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      success: (res) => this.upload(index, kind, res.tempFiles[0].tempFilePath)
    })
  },

  async upload(index, kind, filePath) {
    const key = index + '-' + kind
    this.setData({ uploading: key })
    wx.showLoading({ title: '上传中' })
    try {
      const up = await uploadImage(filePath)
      const patch = {}
      if (kind === 'before') {
        patch['items[' + index + '].beforePhotoFileId'] = up.fileId
        patch['items[' + index + '].beforePath'] = filePath
      } else {
        patch['items[' + index + '].afterPhotoFileId'] = up.fileId
        patch['items[' + index + '].afterPath'] = filePath
      }
      this.setData(patch)
    } catch (e) {
      wx.showToast({ title: e.message || '上传失败', icon: 'none' })
    }
    wx.hideLoading()
    this.setData({ uploading: '' })
  },

  preview(e) {
    const src = e.currentTarget.dataset.src
    if (!src) return
    wx.previewImage({ urls: [src], current: src })
  },

  async onSubmitRectify() {
    const selected = this.data.items.filter(function (i) { return i.selected })
    if (!selected.length) {
      wx.showToast({ title: HANDOVER_TEXT.RECTIFY_REQUIRED, icon: 'none' })
      return
    }
    const missing = selected.some(function (i) { return !i.beforePhotoFileId || !i.afterPhotoFileId })
    if (missing) {
      wx.showToast({ title: HANDOVER_TEXT.PHOTO_REQUIRED, icon: 'none' })
      return
    }
    const payload = {
      rectifications: selected.map(function (i) {
        return {
          itemType: i.itemType,
          beforePhotoFileId: i.beforePhotoFileId,
          afterPhotoFileId: i.afterPhotoFileId
        }
      })
    }
    this.setData({ submitting: true })
    try {
      await submitHandoverRectify(this.data.id, payload)
      wx.showToast({ title: '整改已上传', icon: 'success' })
    } catch (e) {
      wx.showToast({ title: e.message || TOAST_TEXT.SAVE_FAILED, icon: 'none' })
    }
    this.setData({ submitting: false })
  },

  onRecheckPass() {
    this.submitRecheck('PASS')
  },

  onRecheckFail() {
    wx.showModal({
      title: HANDOVER_TEXT.REJECT_TITLE,
      content: HANDOVER_TEXT.REJECT_CONTENT,
      success: (res) => {
        if (res.confirm) this.submitRecheck('FAIL')
      }
    })
  },

  async submitRecheck(result) {
    const user = (getApp().globalData && getApp().globalData.userInfo) || {}
    this.setData({ submitting: true })
    try {
      await recheckHandoverRectify(this.data.id, {
        result: result,
        recheckerId: user.id || '',
        recheckerName: user.name || ''
      })
      wx.showToast({ title: result === 'PASS' ? '复验通过' : '已标记不通过', icon: 'none' })
      wx.navigateBack()
    } catch (e) {
      wx.showToast({ title: e.message || TOAST_TEXT.OPERATE_FAILED, icon: 'none' })
    }
    this.setData({ submitting: false })
  }
})
