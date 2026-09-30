// 消息中心
const { getMessageList, markRead, markAllRead } = require('../../services/message')
const { ensureLogin } = require('../../utils/guard')
const { refreshUnreadBadge } = require('../../utils/badge')

Page({
  data: {
    list: [],
    page: 1,
    size: 20,
    total: 0,
    unreadCount: 0
  },

  onShow() {
    // 首次 onShow 发生在 tab 路由进行中，守卫 reLaunch 会与其竞态
    // （routeDone with a webviewId xxx is not found），首次守卫延迟到 onReady
    if (!this._authReady) return
    if (!ensureLogin()) return
    this.fetchList(true)
    this.syncUnread()
  },

  onReady() {
    this._authReady = true
    if (!ensureLogin()) return
    this.fetchList(true)
    this.syncUnread()
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
      wx.showToast({ title: e.message || '加载失败', icon: 'none' })
    }
  },

  // 同步未读数：tabBar 角标 + 页面"全部已读"入口文案
  syncUnread() {
    refreshUnreadBadge().then((count) => this.setData({ unreadCount: count }))
  },

  async onItemTap(e) {
    const id = e.currentTarget.dataset.id
    const item = this.data.list.find((m) => m.id === id)
    if (!item || item.read) return
    try {
      await markRead(id)
      this.fetchList(true)
      this.syncUnread()
    } catch (err) {
      wx.showToast({ title: err.message || '操作失败', icon: 'none' })
    }
  },

  async onMarkAll() {
    try {
      await markAllRead()
      this.fetchList(true)
      this.syncUnread()
    } catch (e) {
      wx.showToast({ title: e.message || '操作失败', icon: 'none' })
    }
  }
})
