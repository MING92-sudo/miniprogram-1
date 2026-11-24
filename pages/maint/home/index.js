const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const { greetPeriod, gmt8Calendar } = require('../../../utils/util')
const home = require('../../../services/home')
const nav = require('../../../utils/nav')

const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

definePage(ROUTES['/pages/maint/home/index'], {
  data: {
    loading: true, error: '', greeting: {},
    kpis: [], tasks: [], sos: null, week: [], sync: {}, quickActions: []
  },
  onShow() {
    const app = getApp()
    const u = (app.globalData && app.globalData.userInfo) || {}
    const d = gmt8Calendar()
    this.setData({
      greeting: {
        date: d.year + '.' + String(d.month).padStart(2, '0') + '.' + String(d.day).padStart(2, '0'),
        weekday: WEEKDAYS[d.weekday],
        period: greetPeriod(),
        name: u.name || '',
        org: (u.groupName || u.unitName || ''),
        jobNo: u.jobNo || '',
        shift: '08:00–18:00'
      },
      quickActions: (app.globalData && app.globalData.quickActions) || []
    })
    app.refreshBadges()
    this.load()
  },
  load(force) {
    // 重显保留旧数据静默刷新，仅首屏/重试显示骨架屏
    this.setData({ loading: !!force || !this.data.kpis.length, error: '' })
    home.getMaintHome()
      .then((r) => this.setData({ loading: false, kpis: r.kpis, tasks: r.tasks, sos: r.sos, week: r.week, sync: r.sync }))
      .catch((e) => this.setData({ loading: false, error: (e && e.message) || '加载失败' }))
  },
  onRetry() { this.load(true) },
  onTaskTap(e) {
    const t = e.currentTarget.dataset.item
    if (t.kind === 'fault') nav.open('/pages/order/detail/index?id=' + t.id)
    else nav.open('/pkg-maintenance/pages/task/index?id=' + t.id)
  },
  onSosTap() { if (this.data.sos) nav.open('/pages/order/detail/index?id=' + this.data.sos.id) },
  onAllTasks() { nav.open('/pkg-maintenance/pages/orders/index') },
  onKpiTap(e) { const item = e.detail.item; if (item && item.path) nav.open(item.path) },
  onQaAction(e) { nav.open(e.detail.item.path) }
})