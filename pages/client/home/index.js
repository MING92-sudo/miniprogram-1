// 使用单位工作台（M2，屏 10）：问候 + 待签/待评价 + 快捷动作 + KPI + 我的电梯 + 最近记录 + 年检提醒。
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const home = require('../../../services/home')
const nav = require('../../../utils/nav')

const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

definePage(ROUTES['/pages/client/home/index'], {
  data: { loading: true, error: '', greeting: {}, signAlerts: [], kpis: [], elevators: [], records: [], notice: '', quickActions: [] },
  onShow() {
    const app = getApp()
    const u = (app.globalData && app.globalData.userInfo) || {}
    const d = new Date()
    const roles = (app.globalData && app.globalData.roles) || []
    this.setData({
      greeting: {
        date: d.getFullYear() + '.' + String(d.getMonth() + 1).padStart(2, '0') + '.' + String(d.getDate()).padStart(2, '0'),
        weekday: WEEKDAYS[d.getDay()], name: u.name || '',
        org: u.unitName || '', jobTitle: u.jobTitle || '',
        dual: roles.indexOf('CLIENT_ADMIN') > -1 && roles.indexOf('CLIENT_SIGNER') > -1
      },
      quickActions: (app.globalData && app.globalData.quickActions) || []
    })
    app.refreshBadges()
    this.load()
  },
  load() {
    this.setData({ loading: true, error: '' })
    home.getClientHome()
      .then((r) => this.setData({ loading: false, signAlerts: r.signAlerts, kpis: r.kpis, elevators: r.elevators, records: r.records, notice: r.notice }))
      .catch((e) => this.setData({ loading: false, error: (e && e.message) || '加载失败' }))
  },
  onRetry() { this.load() },
  onSignAlert(e) {
    const item = e.currentTarget.dataset.item
    if (item.kind === 'fault') nav.open('/pkg-client/pages/evaluate/index?orderId=' + item.id)
    else nav.open('/pkg-client/pages/todo/index')
  },
  onRecordSign(e) { nav.open('/pkg-client/pages/todo/index') },
  onElevator(e) { nav.open('/pages/elevator/detail/index?id=' + e.currentTarget.dataset.id) },
  onAllElevators() { nav.open('/pages/elevator/list/index') },
  onMoreRecords() { nav.open('/pkg-client/pages/todo/index') },
  onQaAction(e) { nav.open(e.detail.item.path) }
})