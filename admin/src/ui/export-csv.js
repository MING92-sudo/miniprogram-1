/** 前端 CSV 导出：把页面已加载的真实数据导出为 CSV（UTF-8 BOM，Excel 可直接打开）。
 *  数据取自后端已脱敏的接口响应，前端不再二次加工；服务端「导出台账 + 写审计」端点未接入前，本工具不产生审计记录。
 *  @param filename 文件名（自动补 .csv）
 *  @param columns  [{ key, label, value? }]，value 可传函数自定义取值
 *  @param rows     数据行
 *  @returns 导出行数
 */
export function exportCsv(filename, columns, rows) {
  const list = rows || []
  const head = columns.map((c) => cell(c.label)).join(',')
  const body = list
    .map((r) => columns.map((c) => cell(typeof c.value === 'function' ? c.value(r) : r[c.key])).join(','))
    .join('\r\n')
  const csv = '\ufeff' + head + (body ? '\r\n' + body : '')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = /\.csv$/i.test(filename) ? filename : filename + '.csv'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
  return list.length
}

function cell(v) {
  if (v === null || v === undefined) return ''
  const s = String(v)
  return /[",\r\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s
}
