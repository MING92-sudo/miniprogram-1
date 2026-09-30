// 工单台（首页）：今日任务概览 + 扫码签到大按钮
const { getOrderList, resolveByElevatorCode } = require('../../services/order')
const { STATUS_TEXT } = require('../../constants/index')
const { ensureLogin } = require('../../utils/guard')
const { refreshUnreadBadge } = require('../../utils/badge')

Page({
  data: {
    userInfo: null,
    roleText: '',
    todayTasks: [],
    stats: { total: 0, todo: 0, done: 0 },
    loading: false
  },

  onShow() {
    // 冷启动首次 onShow 时初始路由可能尚未完成，此时守卫发起 reLaunch 在真机上会丢失导航
    // （表现为白屏，开发者工具仅报 routeDone 错误）。首次守卫延迟到 onReady；后续 onShow
    // 页面已加载完毕，可安全守卫。
    if (this._authReady) {
      if (!ensureLogin()) return
      this.refresh()
    }
  },

  onReady() {
    // onReady 时页面初始路由必定已完成，此时 reLaunch 安全
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
    this.fetchTodayTasks()
    // 刷新消息 tab 未读角标
    refreshUnreadBadge()
  },

  async fetchTodayTasks() {
    this.setData({ loading: true })
    try {
      const data = await getOrderList({ page: 1, size: 20 })
      const list = ((data && data.list) || []).map((o) =>
        Object.assign({}, o, { statusText: STATUS_TEXT[o.status] || o.status })
      )
      this.setData({
        todayTasks: list,
        stats: {
          total: list.length,
          todo: list.filter((o) => o.status !== 'DONE').length,
          done: list.filter((o) => o.status === 'DONE').length
        }
      })
    } catch (e) {
      wx.showToast({ title: e.message || '加载失败', icon: 'none' })
    }
    this.setData({ loading: false })
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

  // 演示入口：模拟扫到世纪大厦 1# 客梯
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

  onTaskTap(e) {
    wx.navigateTo({ url: `/pages/order/detail?orderId=${e.currentTarget.dataset.id}` })
  }
})
