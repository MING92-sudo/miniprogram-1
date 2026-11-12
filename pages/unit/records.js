// 本单位维保记录（W3，docs/04 A.11 GET /unit/records/list）：只读列表；
// 记录签字走维保单位发起的分享链接（pages/unit/sign），列表接口不下发 shareToken 时不出确认入口。
const { getUnitRecords, isUnitLoggedIn } = require('../../services/unit')
const { UNIT_PORTAL_TEXT, UNIT_CONFIRM_STATUS_TEXT, TOAST_TEXT } = require('../../constants/index')

function decorate(r) {
  const status = r.confirmStatus || 'PENDING'
  return Object.assign({}, r, {
    confirmText: UNIT_CONFIRM_STATUS_TEXT[status] || status,
    confirmed: status === 'CONFIRMED',
    subtitle: [r.elevatorName, r.elevatorCode].filter(function (x) { return !!x }).join(' · '),
    // 仅在接口下发 shareToken 时提供签字入口（当前后端未下发 → 保持只读）
    shareToken: r.shareToken || ''
  })
}

Page({
  data: {
    list: [],
    loading: true,
    failed: false,
    text: UNIT_PORTAL_TEXT
  },

  onShow() {
    if (!isUnitLoggedIn()) {
      wx.reLaunch({ url: '/pages/unit/login' })
      return
    }
    this.fetch()
  },

  async fetch() {
    this.setData({ loading: true, failed: false })
    try {
      const list = await getUnitRecords()
      this.setData({ list: (list || []).map(decorate), loading: false })
    } catch (e) {
      this.setData({ loading: false, failed: true })
      wx.showToast({ title: e.message || TOAST_TEXT.LOAD_FAILED, icon: 'none' })
    }
  },

  onRetry() {
    this.fetch()
  },

  // 维保记录签字（免登录分享链接页）：仅当记录带 shareToken 时展示
  goSign(e) {
    const rid = e.currentTarget.dataset.rid
    const token = e.currentTarget.dataset.token
    if (!rid || !token) return
    wx.navigateTo({ url: '/pages/unit/sign?rid=' + rid + '&token=' + token })
  }
})
