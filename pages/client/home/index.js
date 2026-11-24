const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const { gmt8Calendar } = require('../../../utils/util')
const home = require('../../../services/home')
const nav = require('../../../utils/nav')

const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

definePage(ROUTES['/pages/client/home/index'], {
  data: { loading: true, error: '', greeting: {}, signAlerts: [], kpis: [], elevators: [], records: [], notice: '', quickActions: [] },
  onShow() {
    const app = getApp()
    const u = (app.globalData && app.globalData.userInfo) || {}
    const d = gmt8Calendar()
    const roles = (app.globalData && app.globalData.roles) || []
    this.setData({
      greeting: {
        date: d.year + '.' + String(d.month).padStart(2, '0') + '.' + String(d.day).padStart(2, '0'),
        weekday: WEEKDAYS[d.weekday], name: u.name || '',
        org: u.unitName || '', jobTitle: u.jobTitle || '',
        dual: roles.indexOf('CLIENT_ADMIN') > -1 && roles.indexOf('CLIENT_SIGNER') > -1
      },
      quickActions: (app.globalData && app.globalData.quickActions) || []
    })
    app.refreshBadges()
    this.load()
  },
  load(force) {
    // 重显保留旧数据静默刷新，仅首屏/重试显示骨架屏
    this.setData({ loading: !!force || !this.data.kpis.length, error: '' })
    home.getClientHome()
      .then((r) => this.setData({ loading: false, signAlerts: r.signAlerts, kpis: r.kpis, elevators: r.elevators, records: r.records, notice: r.notice }))
      .catch((e) => this.setData({ loading: false, error: (e && e.message) || '加载失败' }))
  },
  onRetry() { this.load(true) },
  onSignAlert() { nav.open('/pkg-client/pages/todo/index') },
  onRecordSign(e) { nav.open('/pkg-client/pages/todo/index') },
  onElevator(e) { nav.open('/pages/elevator/detail/index?id=' + e.currentTarget.dataset.id) },
  onAllElevators() { nav.open('/pages/elevator/list/index') },
  onMoreRecords() { nav.open('/pkg-client/pages/todo/index') },
  onQaAction(e) { nav.open(e.detail.item.path) }
})