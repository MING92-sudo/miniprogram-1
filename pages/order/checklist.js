// 作业清单：按项目逐项检查，全部填写完成后才能签退
const { getChecklist } = require('../../services/order')
const { CHECK_RESULT_TEXT } = require('../../constants/index')

const RESULT_TEXT = CHECK_RESULT_TEXT // NORMAL/ABNORMAL/NA（docs/04 A.2 枚举）

Page({
  data: {
    orderId: '',
    items: [],
    doneCount: 0,
    total: 0,
    progress: 0,
    loading: true
  },

  onLoad(query) {
    this.setData({ orderId: query.orderId || '' })
  },

  // 从检查项填写页返回后自动刷新
  onShow() {
    if (this.data.orderId) {
      this.fetchChecklist()
    }
  },

  async fetchChecklist() {
    try {
      const checklist = await getChecklist(this.data.orderId)
      const items = ((checklist && checklist.items) || []).map((i) =>
        Object.assign({}, i, { resultText: i.result ? RESULT_TEXT[i.result] : '' })
      )
      const doneCount = items.filter((i) => i.result).length
      this.setData({
        items,
        doneCount,
        total: items.length,
        progress: items.length ? Math.round((doneCount / items.length) * 100) : 0,
        loading: false
      })
    } catch (e) {
      this.setData({ loading: false })
      wx.showToast({ title: e.message || '加载失败', icon: 'none' })
    }
  },

  // 进入检查项填写页
  goItemForm(e) {
    const itemId = e.currentTarget.dataset.id
    wx.navigateTo({
      url: `/pages/order/item-form?orderId=${this.data.orderId}&itemId=${itemId}`
    })
  },

  // 全部完成后进入签退
  goCheckout() {
    if (this.data.doneCount < this.data.total) {
      wx.showToast({ title: `还有 ${this.data.total - this.data.doneCount} 项未填写`, icon: 'none' })
      return
    }
    wx.navigateTo({ url: `/pages/order/checkout?orderId=${this.data.orderId}` })
  }
})
