// 维保计划（M3 迁自 pages/calendar，逻辑保留）：GET /maintenance-calendar 只读前向投影，按月/日分组；不触发派单。
const { getMaintenanceCalendar } = require('../../../services/unit')
const { CALENDAR_WINDOWS, CALENDAR_TEXT, WORK_TYPE_CODES, TOAST_TEXT } = require('../../../constants/index')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const nav = require('../../../utils/nav')

const WEEK = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
function pad(n) { return (n < 10 ? '0' : '') + n }
function dateText(d) { return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) }
function addMonths(d, n) { const x = new Date(d.getTime()); x.setMonth(x.getMonth() + n); return x }
function monthLabel(month) { const p = String(month).split('-'); return p[0] + ' 年 ' + p[1] + ' 月' }

function groupByMonth(entries) {
  const months = []; const monthMap = {}; const dayMap = {}
  ;(entries || []).forEach(function (e) {
    const date = String(e.date || '')
    if (!date) return
    const month = date.slice(0, 7)
    if (!monthMap[month]) { monthMap[month] = { month, label: monthLabel(month), days: [] }; months.push(monthMap[month]) }
    if (!dayMap[date]) {
      dayMap[date] = { date, dayLabel: date.slice(5).replace('-', '/'), week: WEEK[new Date(date.replace(/-/g, '/')).getDay()] || '', entries: [] }
      monthMap[month].days.push(dayMap[date])
    }
    dayMap[date].entries.push({
      elevatorCode: e.elevatorCode || '', elevatorName: e.elevatorName || e.elevatorCode || '未命名电梯',
      projectName: e.projectName || '', workTypeCode: e.workTypeCode || '',
      workType: e.workType || WORK_TYPE_CODES[e.workTypeCode] || e.workTypeCode || ''
    })
  })
  return months
}

definePage(ROUTES['/pkg-maintenance/pages/plan/index'], {
  data: { windows: CALENDAR_WINDOWS, months: 3, groups: [], total: 0, from: '', to: '', loading: true, error: '', text: CALENDAR_TEXT },
  onLoad() { this.fetch() },
  onBack() { nav.back() },
  fetch() {
    const start = new Date()
    const end = addMonths(start, this.data.months)
    this.setData({ loading: true, error: '' })
    return getMaintenanceCalendar({ from: dateText(start), to: dateText(end) })
      .then((res) => {
        const entries = (res && res.entries) || []
        this.setData({ groups: groupByMonth(entries), total: entries.length, from: (res && res.from) || dateText(start), to: (res && res.to) || dateText(end), loading: false })
      })
      .catch((e) => this.setData({ loading: false, error: (e && e.message) || TOAST_TEXT.LOAD_FAILED, groups: [], total: 0 }))
  },
  onWindowChange(e) {
    const months = Number(e.currentTarget.dataset.months) || 3
    if (months === this.data.months) return
    this.setData({ months })
    this.fetch()
  },
  onRetry() { this.fetch() }
})