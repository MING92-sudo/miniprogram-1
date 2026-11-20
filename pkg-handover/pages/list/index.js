// 移交单列表（docs/04 A.9 GET /handovers）：状态筛选 + 分页，点行进详情；
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
// 有写权限的岗位顶部可发起移交（安装部）。列表接口不可用时给出重试，不留死链。
const { getHandoverList } = require('../../../services/handover')
const {
  HANDOVER_STATUS,
  HANDOVER_STATUS_TEXT,
  HANDOVER_WRITE_ROLES,
  HANDOVER_TEXT,
  TOAST_TEXT,
  LOADING_TEXT
} = require('../../../constants/index')
const { ensureLogin } = require('../../../utils/guard')
const { getRoles } = require('../../../utils/auth')

const SIZE = 20

// 维保部读收敛（2026-11-20 真机反馈③，与后端 HandoverService.list 同口径）：纯维保部角色
// 的筛选 Tab 只保留维保验收阶段，安装内部阶段（资料移交中/内部审核中）不出现
const MAINT_VISIBLE_FILTERS = ['', 'INSPECTING', 'RECTIFYING', 'PENDING_CONFIRM', 'ARCHIVED']
function filtersFor(roles) {
  const maintOnly = (roles || []).some((r) => r.indexOf('MAINT') === 0)
    && !(roles || []).some((r) => r.indexOf('INST') === 0 || r === 'INSTALLER' || r === 'PLATFORM_ADMIN' || r === 'SYS_ADMIN' || r === 'ADMIN')
  return maintOnly ? FILTERS.filter((f) => MAINT_VISIBLE_FILTERS.indexOf(f.key) > -1) : FILTERS
}

const FILTERS = [
  { key: '', label: '全部' },
  { key: HANDOVER_STATUS.DOC_SUBMITTING, label: HANDOVER_STATUS_TEXT.DOC_SUBMITTING },
  { key: HANDOVER_STATUS.PENDING_INST_REVIEW, label: HANDOVER_STATUS_TEXT.PENDING_INST_REVIEW },
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

definePage(ROUTES['/pkg-handover/pages/list/index'], {
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
    this.setData({ canWrite: HANDOVER_WRITE_ROLES.indexOf(role) > -1, filters: filtersFor(getRoles()) })
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
    wx.navigateTo({ url: '/pkg-handover/pages/detail/index?id=' + id })
  },

  goInit() {
    wx.navigateTo({ url: '/pkg-handover/pages/create/index' })
  },

  onRetry() {
    this.fetch(1)
  }
})
