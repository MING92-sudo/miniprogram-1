const config = require('../../config/index')
const offline = require('../../utils/offline')
const { formatTime } = require('../../utils/util')
const { definePage } = require('../../utils/guard')
const { ROUTES } = require('../../config/routes')
const nav = require('../../utils/nav')

definePage(ROUTES['/pages/offline/index'], {
  data: { useMock: config.useMock, cacheList: [], queueCount: 0, syncing: false },
  onShow() { this.refresh() },
  onBack() { nav.back() },
  refresh() {
    const cacheList = offline.listCachedOrders().map((c) =>
      Object.assign({}, c, { cachedAtText: c.cachedAt ? formatTime(new Date(c.cachedAt)) : '' }))
    this.setData({ cacheList, queueCount: offline.getQueue().length })
  },
  onClearCache(e) {
    const key = e.currentTarget.dataset.key
    const orderId = e.currentTarget.dataset.id || key.replace(/^em_order_/, '')
    wx.showModal({
      title: '提示', content: '确认清除该工单的本地缓存？（不影响待补传队列）',
      success: (res) => { if (res.confirm) { offline.clearOrderCache(orderId); this.refresh() } }
    })
  },
  async onSyncQueue() {
    if (this.data.syncing) return
    if (this.data.queueCount === 0) return wx.showToast({ title: '队列为空，无需补传', icon: 'none' })
    this.setData({ syncing: true })
    wx.showLoading({ title: '补传中...', mask: true })
    try {
      const result = await offline.flushQueue()
      wx.hideLoading()
      if (result.fail > 0) {
        wx.showModal({ title: '补传完成', content: `成功 ${result.success} 条，失败 ${result.fail} 条已保留在队列，可稍后重试`, showCancel: false })
      } else {
        wx.showToast({ title: `已补传 ${result.success} 条`, icon: 'success' })
      }
    } catch (e) {
      wx.hideLoading()
      wx.showToast({ title: e.message || '补传失败', icon: 'none' })
    }
    this.setData({ syncing: false })
    this.refresh()
  },
  onDemoEnqueue() {
    offline.cacheOrderSnapshot({
      id: 'wo_2', orderNo: 'WO20260929-002', elevatorName: '蓝湾国际 A 座货梯',
      checklist: [{ id: 'ci_A-1-01', name: '机房、滑轮间环境', result: 'NORMAL', abnormalDesc: '', skipReason: '', photos: [] }]
    })
    offline.enqueue({
      url: '/work-orders/wo_2/checklist/ci_A-1-01', method: 'POST',
      data: { clientItemId: 'ci_A-1-01', result: 'NORMAL', value: null, valueText: '', abnormalDesc: '', problemCode: '', skipReason: '', photoFileIds: [], photoUrls: [] },
      desc: '机房、滑轮间环境（A-1-01）'
    })
    wx.showToast({ title: '已模拟写入 1 条', icon: 'success' })
    this.refresh()
  }
})