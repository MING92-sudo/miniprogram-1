// 服务评价（M5）：已闭环急修单评价（client:evaluate，1–5 星 + 文字，重复评价后端 422）。
const { evaluateFault, getFaultDetail } = require('../../../services/fault')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const nav = require('../../../utils/nav')

definePage(ROUTES['/pkg-client/pages/evaluate/index'], {
  data: { orderId: '', fault: null, score: 0, text: '', submitting: false, loading: true, error: '' },
  onLoad(query) { this.setData({ orderId: (query && query.orderId) || '' }); this.load() },
  onBack() { nav.back() },
  load() {
    getFaultDetail(this.data.orderId).then((fault) => this.setData({ fault, loading: false }))
      .catch((e) => this.setData({ loading: false, error: (e && e.message) || '加载失败' }))
  },
  onRetry() { this.load() },
  onScore(e) { this.setData({ score: Number(e.currentTarget.dataset.score) }) },
  onText(e) { this.setData({ text: e.detail.value }) },
  onSubmit() {
    if (!this.data.score) return wx.showToast({ title: '请选择评分（1–5 星）', icon: 'none' })
    this.setData({ submitting: true })
    evaluateFault(this.data.orderId, this.data.score, this.data.text.trim())
      .then(() => { wx.showToast({ title: '评价已提交', icon: 'success' }); setTimeout(() => nav.back(), 800) })
      .catch((e) => { this.setData({ submitting: false }); wx.showToast({ title: (e && e.message) || '评价失败', icon: 'none' }) })
  }
})