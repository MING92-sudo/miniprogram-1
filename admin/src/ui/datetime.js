// 后端时间为 GMT+8 墙钟文本（yyyy-MM-dd[ HH:mm[:ss]]），直接 new Date(文本)
// 在 Safari 得 Invalid Date、在非 GMT+8 浏览器按本地时区错位；统一经这里解析。
const DATE_TIME_RE = /^(\d{4})-(\d{2})-(\d{2})(?: (\d{2}):(\d{2})(?::(\d{2}))?)?$/
const GMT8_OFFSET_MS = 8 * 3600 * 1000

export function parseTime(v) {
  if (v == null || v === '') return NaN
  if (v instanceof Date) return v.getTime()
  if (typeof v === 'number') return v
  const m = DATE_TIME_RE.exec(String(v).trim())
  if (!m) return new Date(v).getTime()
  return Date.UTC(+m[1], +m[2] - 1, +m[3], +(m[4] || 0) - 8, +(m[5] || 0), +(m[6] || 0))
}

export function minutesBetween(a, b) {
  return Math.round((parseTime(b) - parseTime(a)) / 60000)
}

// 当前 GMT+8 的 yyyy-MM-dd（天级比较与后端口径对齐，不随浏览器时区漂移）
export function todayGmt8() {
  const d = new Date(Date.now() + GMT8_OFFSET_MS)
  const p = (n) => String(n).padStart(2, '0')
  return d.getUTCFullYear() + '-' + p(d.getUTCMonth() + 1) + '-' + p(d.getUTCDate())
}

// 两个 yyyy-MM-dd（或 GMT+8 墙钟文本）之间的整天数
export function daysBetween(fromDateStr, toDateStr) {
  return Math.round((parseTime(toDateStr) - parseTime(fromDateStr)) / 86400000)
}
