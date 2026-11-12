// 消息中心
const { getMessageList, markRead, markAllRead } = require('../../services/message')
const { isLoggedIn } = require('../../utils/auth')
const { refreshUnreadBadge } = require('../../utils/badge')
const { TOAST_TEXT, EMPTY_TEXT } = require('../../constants/index')

Page({
  data: {
    guest: false,
    list: [],
    page: 1,
    size: 20,
    total: 0,
    unreadCount: 0,
    expandedId: '',
    emptyText: EMPTY_TEXT
  },

  onShow() {
    if (!this._authReady) return
    if (!this.guard()) return
    this.fetchList(true)
    this.syncUnread()
  },

  onReady() {
    this._authReady = true
    if (!this.guard()) return
    this.fetchList(true)
    this.syncUnread()
  },

  // 未登录：展示登录引导空状态，不强制跳转（登录规范：先浏览后登录）
  guard() {
    if (isLoggedIn()) {
      this.setData({ guest: false })
      return true
    }
    this.setData({ guest: true })
    return false
  },

  goLogin() {
    wx.navigateTo({ url: '/pages/login/index' })
  },

  onPullDownRefresh() {
    this.fetchList(true).then(() => wx.stopPullDownRefresh())
  },

  onReachBottom() {
    if (this.data.list.length < this.data.total) {
      this.fetchList(false)
    }
  },

  async fetchList(reset) {
    const page = reset ? 1 : this.data.page + 1
    try {
      const data = await getMessageList({ page, size: this.data.size })
      const list = (reset ? data.list || [] : this.data.list.concat(data.list || []))
      this.setData({
        list,
        total: data.total || 0,
        page
      })
    } catch (e) {
      wx.showToast({ title: e.message || TOAST_TEXT.LOAD_FAILED, icon: 'none' })
    }
  },

  // 同步未读数：tabBar 角标 + 页面"全部已读"入口文案
  syncUnread() {
    refreshUnreadBadge().then((count) => this.setData({ unreadCount: count }))
  },

  async onItemTap(e) {
    const id = e.currentTarget.dataset.id
    const item = this.data.list.find((m) => m.id === id)
    if (!item) return
    // 点按消息：展开/收起详细信息；未读则顺带标记已读
    this.setData({ expandedId: this.data.expandedId === id ? '' : id })
    if (item.read) return
    const idx = this.data.list.findIndex((m) => m.id === id)
    markRead(id)
      .then(() => {
        this.setData({ ['list[' + idx + '].read']: true, unreadCount: Math.max(0, this.data.unreadCount - 1) })
      })
      .catch(() => {})
  },

  async onMarkAll() {
    try {
      await markAllRead()
      this.fetchList(true)
      this.syncUnread()
    } catch (e) {
      wx.showToast({ title: e.message || TOAST_TEXT.OPERATE_FAILED, icon: 'none' })
    }
  }
})
