// 通用工具函数

// GMT+8 固定偏移（AGENTS §3：时间存取一律 yyyy-MM-dd HH:mm:ss GMT+8）。
// 不依赖设备时区——维保工设备时区非 +08 时，按本地时区格式化会让签到/签退时间整体偏移，
// 直接污染上报监管平台的时间字段。
const TZ_OFFSET_MS = 8 * 60 * 60 * 1000

// 简易 UUID（用于 X-Idempotency-Key）
function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

// 时间格式化 yyyy-MM-dd HH:mm:ss（GMT+8，24小时制）
function formatTime(date) {
  const d = date ? new Date(date) : new Date()
  if (isNaN(d.getTime())) return ''
  const t = new Date(d.getTime() + TZ_OFFSET_MS)
  const pad = function (n) {
    return (n < 10 ? '0' : '') + n
  }
  return (
    t.getUTCFullYear() +
    '-' +
    pad(t.getUTCMonth() + 1) +
    '-' +
    pad(t.getUTCDate()) +
    ' ' +
    pad(t.getUTCHours()) +
    ':' +
    pad(t.getUTCMinutes()) +
    ':' +
    pad(t.getUTCSeconds())
  )
}

// 解析 yyyy-MM-dd[ HH:mm[:ss]] 为时间戳，按 GMT+8 解释（不依赖设备时区，iOS 亦安全）。
// 日期-only（yyyy-MM-dd）按 00:00:00 处理：mock 种子与验收脚本的 lastMaintenanceAt
// 均为 addDays()/formatTime().slice(0,10) 的日期-only 串，若要求时间部分会解析失败返回 0，
// 导致该梯被永久判为"无维保基准"而不再派单（scripts/verify-dispatch.js 即因此变红）。
function parseTime(str) {
  if (!str) return 0
  const m = String(str).trim().match(
    /^(\d{4})-(\d{1,2})-(\d{1,2})(?:[ T](\d{1,2}):(\d{1,2})(?::(\d{1,2}))?)?/
  )
  if (!m) return 0
  return (
    Date.UTC(
      Number(m[1]),
      Number(m[2]) - 1,
      Number(m[3]),
      Number(m[4] || 0),
      Number(m[5] || 0),
      Number(m[6] || 0)
    ) - TZ_OFFSET_MS
  )
}

// 时长格式化 HH:mm:ss（24小时制，如 02:35:00，对齐 docs/04 A.2 duration 格式）
function formatDuration(ms) {
  if (!ms || ms < 0) ms = 0
  const s = Math.floor(ms / 1000)
  const pad = function (n) {
    return (n < 10 ? '0' : '') + n
  }
  return pad(Math.floor(s / 3600)) + ':' + pad(Math.floor((s % 3600) / 60)) + ':' + pad(s % 60)
}

module.exports = {
  uuid,
  formatTime,
  parseTime,
  formatDuration
}
