// 归档与导出（M3）：GET /work-orders/{id}/archive 投影（PDF + SHA-256 + 归档时间）；非 ARCHIVED 由后端 422。
const { getRecordArchive } = require('../../../services/order')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const nav = require('../../../utils/nav')

definePage(ROUTES['/pkg-maintenance/pages/record-archive/index'], {
  data: { orderId: '', archive: null, loading: true, error: '' },
  onLoad(query) { this.setData({ orderId: (query && query.orderId) || '' }); this.load() },
  onBack() { nav.back() },
  load() {
    this.setData({ loading: true, error: '' })
    getRecordArchive(this.data.orderId)
      .then((a) => this.setData({ archive: a, loading: false }))
      .catch((e) => this.setData({ loading: false, error: (e && e.message) || '暂无归档（记录须已归档）' }))
  },
  onRetry() { this.load() },
  onOpenPdf() {
    const a = this.data.archive
    if (!a || !a.url) return wx.showToast({ title: '暂无归档文件', icon: 'none' })
    wx.showLoading({ title: '加载中', mask: true })
    wx.downloadFile({
      url: a.url,
      success: (res) => {
        wx.hideLoading()
        wx.openDocument({ filePath: res.tempFilePath, fileType: 'pdf', showMenu: true, fail: () => wx.showToast({ title: '打开失败', icon: 'none' }) })
      },
      fail: () => { wx.hideLoading(); wx.showToast({ title: '下载失败', icon: 'none' }) }
    })
  }
})