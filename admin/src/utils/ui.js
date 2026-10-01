import { ElMessage, ElMessageBox } from 'element-plus'
import { PROBLEM_CODES } from '../constants'

/** 统一错误提示；1002 手机号互斥时展示具体冲突角色对（docs/09 决策 #3） */
export function showErr(e) {
  if (e && e.code === 1002 && e.data && Array.isArray(e.data.conflicts)) {
    const lines = e.data.conflicts.map((c) => {
      const roles = (c.roles || []).map((r) => `${r.role}(${r.owner || '待补充归属'})`).join(' ↔ ')
      return `${c.phone}：${roles}`
    })
    ElMessageBox.alert(lines.join('\n'), '手机号互斥冲突（1002）', {
      type: 'warning',
      customStyle: { whiteSpace: 'pre-line' }
    }).catch(() => {})
    return
  }
  ElMessage.error((e && e.message) || '操作失败')
}

export function ok(msg) {
  ElMessage.success(msg || '操作成功')
}

/** 隐患码 → 短文案（完整文案见 constants PROBLEM_CODES） */
export function hazardShort(code) {
  const full = PROBLEM_CODES[code]
  if (!full) return code
  return `${code} ${full.length > 12 ? full.slice(0, 12) + '…' : full}`
}

/** 通用下载（PDF 导出等 blob 响应） */
export function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}
