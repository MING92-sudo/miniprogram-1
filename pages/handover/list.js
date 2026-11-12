// 移交单列表（docs/04 A.9 GET /handovers）：状态筛选 + 分页，点行进详情；
// 有写权限的岗位顶部可发起移交（安装部）。列表接口不可用时给出重试，不留死链。
const { getHandoverList } = require('../../services/handover')
const {
  HANDOVER_STATUS,
  HANDOVER_STATUS_TEXT,
  HANDOVER_WRITE_ROLES,
  HANDOVER_TEXT,
  TOAST_TEXT,
  LOADING_TEXT
} = require('../../constants/index')
const { ensureLogin } = require('../../utils/guard')

const SIZE = 20

const FILTERS = [
  { key: '', label: '全部' },
  { key: HANDOVER_STATUS.DOC_SUBMITTING, label: HANDOVER_STATUS_TEXT.DOC_SUBMITTING },
  { key: HANDOVER_STATUS.INSPECTING, label: HANDOVER_STATUS_TEXT.INSPECTING },
  { key: HANDOVER_STATUS.RECTIFYING, label: HANDOVER_STATUS_TEXT.RECTIFYING },
  { key: HANDOVER_STATUS.PENDING_CONFIRM, label: HANDOVER_STATUS_TEXT.PENDING_CONFIRM },
  { key: HANDOVER_STATUS.ARCHIVED, label: HANDOVER_STATUS_TEXT.ARCHIVED }
]

function decorate(o) {
  return Object.assign({}, o, {
    statusText: HANDOVER_STATUS_TEXT[o.status] || o.status,
    subtitle: [o.elevatorName, o.elevatorCode].filter(function (x) { return !!x }).join(' · ')
  })
}

Page({
  data: {
    filters: FILTERS,
    activeStatus: '',
    list: [],
    total: 0,
    page: 1,
    hasMore: false,
    loading: true,
    loadingText: LOADING_TEXT,
    failed: false,
    canWrite: false,
    text: HANDOVER_TEXT
  },

  onLoad() {
    if (!ensureLogin()) return
    const role = (getApp().globalData && getApp().globalData.role) || ''
    this.setData({ canWrite: HANDOVER_WRITE_ROLES.indexOf(role) > -1 })
    this._ready = true
  },

  // 发起/详情返回后刷新（首次进入由 onShow 触发）
  onShow() {
    if (!this._ready) return
    this.fetch(1)
  },

  async fetch(page) {
    this.setData({ loading: true, failed: false })
    try {
      const res = await getHandoverList({ status: this.data.activeStatus, page: page, size: SIZE })
      const rows = (res && res.list ? res.list : []).map(decorate)
      const merged = page === 1 ? rows : this.data.list.concat(rows)
      this.setData({
        list: merged,
        total: (res && res.total) || merged.length,
        page: page,
        hasMore: merged.length < ((res && res.total) || merged.length),
        loading: false
      })
    } catch (e) {
      this.setData({ loading: false, failed: true, list: page === 1 ? [] : this.data.list })
      wx.showToast({ title: e.message || TOAST_TEXT.LOAD_FAILED, icon: 'none' })
    }
  },

  onFilter(e) {
    const key = e.currentTarget.dataset.key || ''
    this.setData({ activeStatus: key })
    this.fetch(1)
  },

  onMore() {
    this.fetch(this.data.page + 1)
  },

  onTapRow(e) {
    const id = e.currentTarget.dataset.id
    if (!id) return
    wx.navigateTo({ url: '/pages/handover/detail?id=' + id })
  },

  goInit() {
    wx.navigateTo({ url: '/pages/handover/init' })
  },

  onRetry() {
    this.fetch(1)
  }
})
