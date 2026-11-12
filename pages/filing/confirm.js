// 交接确认（docs/04 A.10 / ExternalFilingService.confirm）：钥匙工具核对 + 遗留问题 +
// 对方签字人姓名与手写签名（必填）→ 提交归档（后端同事务建电梯 archive_source=2 + 预建首保）。
// 签名复用 pages/common/signature，签名图经 services/upload.js 上传取 fileId（AGENTS §4.1）。
const { getFilingDetail, confirmFiling } = require('../../services/filing')
const { uploadImage } = require('../../services/upload')
const {
  FILING_STATUS,
  FILING_WRITE_ROLES,
  FILING_TEXT,
  TOAST_TEXT,
  LOADING_TEXT
} = require('../../constants/index')
const { ensureLogin } = require('../../utils/guard')

Page({
  data: {
    id: '',
    filing: null,
    loading: true,
    loadingText: LOADING_TEXT,
    locked: false,
    counterpartName: '',
    keyToolCheck: '',
    leftoverIssue: '',
    counterpartPath: '',
    ourPath: '',
    submitting: false,
    text: FILING_TEXT
  },

  onLoad(query) {
    if (!ensureLogin()) return
    const id = (query && query.id) || ''
    const role = (getApp().globalData && getApp().globalData.role) || ''
    const user = (getApp().globalData && getApp().globalData.userInfo) || {}
    this.setData({
      id: id,
      confirmerName: user.name || '',
      confirmRoleOk: FILING_WRITE_ROLES.indexOf(role) > -1
    })
    if (!id) {
      this.setData({ loading: false, locked: true })
      return
    }
    this.fetch()
  },

  async fetch() {
    try {
      const f = await getFilingDetail(this.data.id)
      const statusLocked = f.status !== FILING_STATUS.PENDING_CONFIRM
      this.setData({
        filing: f,
        loading: false,
        locked: statusLocked || !this.data.confirmRoleOk
      })
    } catch (e) {
      this.setData({ loading: false, locked: true })
      wx.showToast({ title: e.message || TOAST_TEXT.LOAD_FAILED, icon: 'none' })
    }
  },

  onInput(e) {
    const field = e.currentTarget.dataset.field
    if (!field) return
    const patch = {}
    patch[field] = e.detail.value
    this.setData(patch)
  },

  // 签名板：区分「对方签字 / 我方签字」两个位置，回调统一由 onSignatureReady 接收
  openSignature(e) {
    this._slot = e.currentTarget.dataset.slot === 'our' ? 'our' : 'counterpart'
    wx.navigateTo({ url: '/pages/common/signature' })
  },

  onSignatureReady(data) {
    const path = (data && data.path) || ''
    if (!path) return
    if (this._slot === 'our') this.setData({ ourPath: path })
    else this.setData({ counterpartPath: path })
  },

  onSubmit() {
    const d = this.data
    if (!d.counterpartName.trim()) {
      wx.showToast({ title: FILING_TEXT.SIGN_NAME_REQUIRED, icon: 'none' })
      return
    }
    if (!d.counterpartPath) {
      wx.showToast({ title: FILING_TEXT.SIGN_FILE_REQUIRED, icon: 'none' })
      return
    }
    wx.showModal({
      title: FILING_TEXT.CONFIRM_TITLE,
      content: FILING_TEXT.CONFIRM_CONTENT,
      success: (res) => {
        if (res.confirm) this.doConfirm()
      }
    })
  },

  async doConfirm() {
    const d = this.data
    const user = (getApp().globalData && getApp().globalData.userInfo) || {}
    this.setData({ submitting: true })
    wx.showLoading({ title: '提交中' })
    try {
      const counterpart = await uploadImage(d.counterpartPath)
      let ourSignFileId = ''
      if (d.ourPath) {
        const ours = await uploadImage(d.ourPath)
        ourSignFileId = ours.fileId
      }
      await confirmFiling(d.id, {
        counterpartSignName: d.counterpartName.trim(),
        counterpartSignFileId: counterpart.fileId,
        keyToolCheck: (d.keyToolCheck || '').trim(),
        leftoverIssue: (d.leftoverIssue || '').trim(),
        ourSignFileId: ourSignFileId,
        confirmedById: user.id || '',
        confirmedByName: this.data.confirmerName || user.name || ''
      })
      wx.hideLoading()
      wx.showToast({ title: '已归档', icon: 'success' })
      wx.redirectTo({ url: '/pages/filing/detail?id=' + d.id })
    } catch (e) {
      wx.hideLoading()
      wx.showToast({ title: e.message || TOAST_TEXT.OPERATE_FAILED, icon: 'none' })
    }
    this.setData({ submitting: false })
  }
})
