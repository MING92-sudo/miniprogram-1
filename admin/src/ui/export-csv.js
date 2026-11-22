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
  const safe = /^[\s]*[=+\-@]/.test(s) ? "'" + s : s
  return /[",\r\n]/.test(safe) ? '"' + safe.replace(/"/g, '""') + '"' : safe
}
