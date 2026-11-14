//
const { resolveByElevatorCode } = require('../../services/order')
const { getHomeSummary } = require('../../services/home')
const { isLoggedIn } = require('../../utils/auth')
const { refreshUnreadBadge } = require('../../utils/badge')
const { getQueue } = require('../../utils/offline')
const { viewOf } = require('../../utils/role-view')
const nav = require('../../utils/nav')

// tab 页不能用 navigateTo，宫格里命中 tab 时走 switchTab
const TAB_PAGES = [
  '/pages/home/index',
  '/pages/order/list',
  '/pages/message/index',
  '/pages/mine/index'
]

// 首页头部时段问候（个性化，替代营销式文案）
function greet() {
  const h = new Date().getHours()
  if (h < 6) return '凌晨好'
  if (h < 11) return '早上好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
}

Page({
  data: {
    guest: false,
    userInfo: null,
    // 未登录首页功能介绍（登录规范：先浏览功能，后自行选择登录）
    features: [
      { title: '维保工单', desc: '派单、执行、跟踪，全流程电子化' },
      { title: '现场签到', desc: '扫码到场，定位打卡留痕' },
      { title: '拍照留证', desc: '水印相机，作业过程可追溯' },
      { title: '电子签名', desc: '维保与使用单位双人确认' },
      { title: '设备信息', desc: '电梯档案、项目信息查询' },
      { title: '故障急修', desc: '现场登记、闭环跟踪' }
    ],
    roleText: '',
    scopeText: '',
    canOperate: false, // 现场作业岗才显示扫码签到；巡视/管理岗不显示
    greeting: '',
    summary: {
      dueToday: 0, dueSoon: 0, overdue: 0,
      inProgress: 0, unconfirmed: 0, platformTotal: 0,
      openFaults: 0, overdueInspects: 0, warnCount: 0
    },
    offlineCount: 0, // 离线待补传条数（docs/03 §3.2 项2：离线补传提示条）
    // 宫格按角色生成（docs/14 §7）：登录前为空，登录后由 refresh() 填充
    queryMenus: [],
  },

  onShow() {
    if (this._authReady) this.refresh()
  },

  onReady() {
    this._authReady = true
    this.refresh()
  },

  refresh() {
    // 未登录不强制跳转：展示登录引导空状态（登录规范：允许取消/暂不登录浏览）
    if (!isLoggedIn()) {
      this.setData({ guest: true })
      return
    }
    const app = getApp()
    const u = app.globalData.userInfo || {}
    const v = viewOf(app.globalData.role)
    this.setData({
      guest: false,
      userInfo: u,
      roleText: v.roleText,
      scopeText: v.scope,
      canOperate: v.canOperate,
      queryMenus: v.tiles.map(t => Object.assign({}, t)),
      greeting: greet() + (u.name ? '，' + u.name : ''),
      offlineCount: getQueue().length
    })
    this.fetchSummary()
    refreshUnreadBadge()
  },

  // 未登录引导 → 去登录页（navigateTo 保留返回，可退回首页）
  goLogin() {
    wx.navigateTo({ url: '/pages/login/index' })
  },

  async fetchSummary() {
    try {
      const s = await getHomeSummary()
      // 综合查询宫格：急修单角标 = 未闭环故障数
      const qi = this.data.queryMenus.findIndex(m => m.url === '/pages/fault/list')
      this.setData({
        summary: s,
        ...(qi > -1 ? { [`queryMenus[${qi}].badge`]: s.openFaults } : {})
      })
    } catch (e) {
      // 汇总失败不阻断首页，看板显示 0 值
    }
  },

  // 离线提示条 → 离线缓存管理页（手动补传）
  goOffline() {
    wx.navigateTo({ url: '/pages/mine/offline' })
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
    // dataset.tab 显式标记 + TAB_PAGES 兜底（tabBar 页 navigateTo 会被微信拒绝）
    const isTab = e.currentTarget.dataset.tab || TAB_PAGES.indexOf(url) > -1
    if (isTab) return nav.switchTab({ url })
    wx.navigateTo({ url })
  },

  // 维保记录情况三卡下钻（switchTab 页经存储传参）
  goRecordCard(e) {
    const jump = e.currentTarget.dataset.jump
    wx.setStorageSync('order_status_filter', jump === 'unconfirmed' ? 'DONE' : 'PROCESSING')
    nav.switchTab({ url: '/pages/order/list' })
  },

  // 三色统计卡下钻：跳转工单列表并按到期维度过滤
  goStat(e) {
    // /pages/order/list 是 tabBar 页：navigateTo 无法打开、switchTab 不能带参，
    // 过滤条件经临时存储传递（list 页 onShow 读取后立即清除）
    wx.setStorageSync('order_due_filter', e.currentTarget.dataset.due)
    nav.switchTab({ url: '/pages/order/list' })
  },

})
