// 移交单确认归档（docs/04 A.9 / HandoverService.confirm）：维保部经理二次确认后归档，
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
// 后端同事务写电梯档案并预建首保工单（重复确认幂等）。
const { getHandoverDetail, confirmHandover } = require('../../../services/handover')
const {
  ROLE,
  HANDOVER_STATUS,
  HANDOVER_DOCS,
  HANDOVER_TEXT,
  TOAST_TEXT,
  LOADING_TEXT
} = require('../../../constants/index')
const { ensureLogin } = require('../../../utils/guard')
const { hasAnyRole } = require('../../../utils/auth')

// 确认归档岗位（HandoverService.NODE_ROLES：MAINTENANCE_CONFIRM + 维保经理）
// 2026-11-21 审查修复：SYS_ADMIN 并入 PLATFORM_ADMIN 后补规范角色码（与 detail 页 ACTION_ROLES 同口径），
// 否则 PLATFORM_ADMIN/MAINT_MGR/MAINT_SUP 账号丢失「确认归档」入口（仅 UI 门禁，后端强校验不变）
const CONFIRM_ROLES = [ROLE.MAINT_MANAGER, ROLE.LEADER, ROLE.ADMIN, 'MAINT_MGR', 'MAINT_SUP', 'PLATFORM_ADMIN']

definePage(ROUTES['/pkg-handover/pages/sign/index'], {
  data: {
    id: '',
    handover: null,
    loading: true,
    loadingText: LOADING_TEXT,
    locked: false,
    canConfirm: false,
    docCount: 0,
    docTotal: HANDOVER_DOCS.length,
    confirmerName: '',
    submitting: false,
    text: HANDOVER_TEXT
  },

  onLoad(query) {
    if (!ensureLogin()) return
    const id = (query && query.id) || ''
    const role = (getApp().globalData && getApp().globalData.role) || ''
    const user = (getApp().globalData && getApp().globalData.userInfo) || {}
    this.setData({
      id: id,
      confirmerName: user.name || '',
      canConfirm: CONFIRM_ROLES.indexOf(role) > -1
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
      const docCount = HANDOVER_DOCS.filter(function (d) { return !!h[d.field] }).length
      this.setData({
        handover: h,
        docCount: docCount,
        loading: false,
        locked: h.status !== HANDOVER_STATUS.PENDING_CONFIRM || !this.data.canConfirm
      })
    } catch (e) {
      this.setData({ loading: false, locked: true })
      wx.showToast({ title: e.message || TOAST_TEXT.LOAD_FAILED, icon: 'none' })
    }
  },

  onNameInput(e) {
    this.setData({ confirmerName: e.detail.value })
  },

  onSubmit() {
    const name = (this.data.confirmerName || '').trim()
    if (!name) {
      wx.showToast({ title: HANDOVER_TEXT.CONFIRMER_REQUIRED, icon: 'none' })
      return
    }
    wx.showModal({
      title: HANDOVER_TEXT.CONFIRM_TITLE,
      content: HANDOVER_TEXT.CONFIRM_CONTENT,
      success: (res) => {
        if (res.confirm) this.doConfirm(name)
      }
    })
  },

  async doConfirm(name) {
    const user = (getApp().globalData && getApp().globalData.userInfo) || {}
    this.setData({ submitting: true })
    try {
      await confirmHandover(this.data.id, {
        confirmedById: user.id || '',
        confirmedByName: name
      })
      wx.showToast({ title: '已归档', icon: 'success' })
      wx.redirectTo({ url: '/pkg-handover/pages/detail/index?id=' + this.data.id })
    } catch (e) {
      wx.showToast({ title: e.message || TOAST_TEXT.OPERATE_FAILED, icon: 'none' })
    }
    this.setData({ submitting: false })
  }
})
