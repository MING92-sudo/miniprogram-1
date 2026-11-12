// 维保日历（Task 11 GET /maintenance-calendar）：只读前向投影，按月分组展示应做排期
// （日期 / 电梯 / 维保类别）。默认今天起 3 个月，可切 1/3/6 个月窗口；本页不触发派单。
const { getMaintenanceCalendar } = require('../../services/unit')
const { CALENDAR_WINDOWS, CALENDAR_TEXT, WORK_TYPE_CODES, TOAST_TEXT } = require('../../constants/index')
const { ensureLogin } = require('../../utils/guard')

const WEEK = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

function pad(n) {
  return (n < 10 ? '0' : '') + n
}

// yyyy-MM-dd（日历窗口口径，与后端 LocalDate.toString 一致）
function dateText(d) {
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
}

function addMonths(d, n) {
  const x = new Date(d.getTime())
  x.setMonth(x.getMonth() + n)
  return x
}

function monthLabel(month) {
  const parts = String(month).split('-')
  return parts[0] + ' 年 ' + parts[1] + ' 月'
}

// 扁平 entries → 月 → 日 两级分组（后端按日期升序返回，分组保持原顺序）
function groupByMonth(entries) {
  const months = []
  const monthMap = {}
  const dayMap = {}
  ;(entries || []).forEach(function (e) {
    const date = String(e.date || '')
    if (!date) return
    const month = date.slice(0, 7)
    if (!monthMap[month]) {
      monthMap[month] = { month: month, label: monthLabel(month), days: [] }
      months.push(monthMap[month])
    }
    if (!dayMap[date]) {
      dayMap[date] = {
        date: date,
        dayLabel: date.slice(5).replace('-', '/'),
        week: WEEK[new Date(date.replace(/-/g, '/')).getDay()] || '',
        entries: []
      }
      monthMap[month].days.push(dayMap[date])
    }
    dayMap[date].entries.push({
      elevatorCode: e.elevatorCode || '',
      elevatorName: e.elevatorName || e.elevatorCode || '未命名电梯',
      projectName: e.projectName || '',
      workTypeCode: e.workTypeCode || '',
      workType: e.workType || WORK_TYPE_CODES[e.workTypeCode] || e.workTypeCode || ''
    })
  })
  return months
}

Page({
  data: {
    windows: CALENDAR_WINDOWS,
    months: 3,
    groups: [],
    total: 0,
    from: '',
    to: '',
    loading: true,
    failed: false,
    text: CALENDAR_TEXT
  },

  onLoad() {
    if (!ensureLogin()) return
    this.fetch()
  },

  fetch() {
    const start = new Date()
    const end = addMonths(start, this.data.months)
    this.setData({ loading: true, failed: false })
    return getMaintenanceCalendar({ from: dateText(start), to: dateText(end) })
      .then((res) => {
        const entries = (res && res.entries) || []
        this.setData({
          groups: groupByMonth(entries),
          total: entries.length,
          from: (res && res.from) || dateText(start),
          to: (res && res.to) || dateText(end),
          loading: false
        })
      })
      .catch((e) => {
        this.setData({ loading: false, failed: true, groups: [], total: 0 })
        wx.showToast({ title: e.message || TOAST_TEXT.LOAD_FAILED, icon: 'none' })
      })
  },

  onWindowChange(e) {
    const months = Number(e.currentTarget.dataset.months) || 3
    if (months === this.data.months) return
    this.setData({ months: months })
    this.fetch()
  },

  onRetry() {
    this.fetch()
  }
})
