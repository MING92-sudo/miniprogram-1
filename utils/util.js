// 通用工具函数

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
  const pad = function (n) {
    return (n < 10 ? '0' : '') + n
  }
  return (
    d.getFullYear() +
    '-' +
    pad(d.getMonth() + 1) +
    '-' +
    pad(d.getDate()) +
    ' ' +
    pad(d.getHours()) +
    ':' +
    pad(d.getMinutes()) +
    ':' +
    pad(d.getSeconds())
  )
}

// 解析 yyyy-MM-dd HH:mm:ss 为时间戳；iOS 不认 '-' 分隔的日期串，统一替换为 '/'
function parseTime(str) {
  if (!str) return 0
  return new Date(String(str).replace(/-/g, '/')).getTime()
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
