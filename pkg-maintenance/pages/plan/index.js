const { getMaintenanceCalendar } = require('../../../services/unit')
const { CALENDAR_WINDOWS, CALENDAR_TEXT, CALENDAR_STATUS, WORK_TYPE_CODES, TOAST_TEXT } = require('../../../constants/index')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const { formatTime } = require('../../../utils/util')
const nav = require('../../../utils/nav')

const WEEK = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
function pad(n) { return (n < 10 ? '0' : '') + n }
// 日历窗口按 GMT+8「今日」起算，月加法做末日钳位（10-31 + 1 月 → 11-30）
function addMonthsText(dateStr, n) {
  const p = dateStr.split('-').map(Number)
  let y = p[0]
  let m = p[1] + n
  y += Math.floor((m - 1) / 12)
  m = ((m - 1) % 12) + 1
  const dim = new Date(Date.UTC(y, m, 0)).getUTCDate()
  return y + '-' + pad(m) + '-' + pad(Math.min(p[2], dim))
}
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
      workType: e.workType || WORK_TYPE_CODES[e.workTypeCode] || e.workTypeCode || '',
      status: e.status || 'PLANNED',
      statusText: e.statusText || CALENDAR_STATUS[e.status] || '',
      orderId: e.orderId || '', orderNo: e.orderNo || ''
    })
  })
  return months
}

definePage(ROUTES['/pkg-maintenance/pages/plan/index'], {
  data: { windows: CALENDAR_WINDOWS, months: 3, groups: [], total: 0, from: '', to: '', loading: true, error: '', text: CALENDAR_TEXT },
  onLoad() { this.fetch() },
  onBack() { nav.back() },
  fetch() {
    const from = formatTime().slice(0, 10)
    const to = addMonthsText(from, this.data.months)
    this.setData({ loading: true, error: '' })
    return getMaintenanceCalendar({ from: from, to: to })
      .then((res) => {
        const entries = (res && res.entries) || []
        this.setData({ groups: groupByMonth(entries), total: entries.length, from: (res && res.from) || from, to: (res && res.to) || to, loading: false })
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