//
const config = require('../../config/index')
const { resolveByElevatorCode } = require('../../services/order')
const { getHomeSummary } = require('../../services/home')
const { ensureLogin } = require('../../utils/guard')
const { refreshUnreadBadge } = require('../../utils/badge')

// tab 页不能用 navigateTo，宫格里命中 tab 时走 switchTab
const TAB_PAGES = [
  '/pages/home/index',
  '/pages/order/list',
  '/pages/message/index',
  '/pages/mine/index'
]

Page({
  data: {
    useMock: config.useMock,
    userInfo: null,
    roleText: '',
    summary: {
      dueToday: 0, dueSoon: 0, overdue: 0,
      inProgress: 0, unconfirmed: 0, platformTotal: 0,
      openFaults: 0, overdueInspects: 0, warnCount: 0
    },
    quickMenus: [],
    queryMenus: [
      { title: '维保记录', icon: '📋', color: 'orange', url: '/pages/order/list' },
      { title: '待确认', icon: '✅', color: 'green', url: '/pages/unit/pending' },
      { title: '消息通知', icon: '💬', color: 'blue', url: '/pages/message/index', tab: true },
      { title: '知识库', icon: '📚', color: 'purple', url: '/pages/knowledge/index' }
    ],
  },

  onShow() {
    // 冷启动首次 onShow 时初始路由可能尚未完成，此时守卫发起 reLaunch 在真机上会丢失导航
    if (this._authReady) {
      if (!ensureLogin()) return
      this.refresh()
    }
  },

  onReady() {
    this._authReady = true
    if (!ensureLogin()) return
    this.refresh()
  },

  refresh() {
    const app = getApp()
    this.setData({
      userInfo: app.globalData.userInfo,
      roleText: (app.globalData.userInfo && app.globalData.userInfo.roleText) || ''
    })
    this.fetchSummary()
    refreshUnreadBadge()
  },

  async fetchSummary() {
    try {
      const s = await getHomeSummary()
      // 辅助功能宫格：角标 = 待办数量（急修单=未闭环故障、年检预警=自行检查逾期台数）
      this.setData({
        summary: s,
        quickMenus: [
          { title: '急修单', icon: '🛠️', color: 'blue', url: '/pages/fault/report', badge: s.openFaults },
          { title: '救援登记', icon: '🚨', color: 'red', url: '/pages/rescue/create', badge: 0 },
          { title: '自行检查', icon: '📁', color: 'purple', url: '/pages/compliance/inspect', badge: s.overdueInspects },
          { title: '应急演练', icon: '📢', color: 'orange', url: '/pages/compliance/drill', badge: 0 }
        ]
      })
    } catch (e) {
      // 汇总失败不阻断首页，看板显示 0 值
    }
  },

  // 扫码签到主入口：扫电梯二维码 → 定位工单
  onScanTap() {
    wx.scanCode({
      onlyFromCamera: true,
      success: (res) => {
        // 二维码内容 = 纯 elevatorCode（docs/04 约定）
        this.resolveElevator(res.result)
      }
    })
  },

  // 演示入口：模拟扫到世纪大厦 1# 客梯（仅 mock 模式渲染）
  onMockScan() {
    this.resolveElevator('EM-2024-001')
  },

  async resolveElevator(elevatorCode) {
    try {
      const order = await resolveByElevatorCode(elevatorCode)
      wx.navigateTo({ url: `/pages/order/detail?orderId=${order.id}` })
    } catch (e) {
      wx.showModal({ title: '扫码结果', content: e.message || '未找到关联工单', showCancel: false })
    }
  },

  onGridTap(e) {
    const url = e.currentTarget.dataset.url
    const isTab = e.currentTarget.dataset.tab
    if (isTab) return wx.switchTab({ url })
    wx.navigateTo({ url })
  },

  // 三色统计卡下钻：跳转工单列表并按到期维度过滤
  goStat(e) {
    // /pages/order/list 是 tabBar 页：navigateTo 无法打开、switchTab 不能带参，
    // 过滤条件经临时存储传递（list 页 onShow 读取后立即清除）
    wx.setStorageSync('order_due_filter', e.currentTarget.dataset.due)
    wx.switchTab({ url: '/pages/order/list' })
  },

})
