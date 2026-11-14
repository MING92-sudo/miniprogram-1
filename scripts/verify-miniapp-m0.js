// scripts/verify-miniapp-m0.js
// M0 映射基线结构守卫（结构等价取证）：集合完整性校验，缺口退出码 1
const fs = require('fs')
const path = require('path')
const ROOT = path.join(__dirname, '..')
const M0 = fs.readFileSync(path.join(ROOT, 'docs/superpowers/specs/2026-11-15-miniapp-redesign-m0-mappings.md'), 'utf8')
const SPEC = fs.readFileSync(path.join(ROOT, 'docs/superpowers/specs/2026-11-15-miniapp-redesign-design.md'), 'utf8')
const results = []
function check(name, missing, total) {
  results.push({ name, total, missingCount: missing.length, missing })
  if (missing.length) process.exitCode = 1
}
function section(n) {
  const re = new RegExp('## ' + n + ' [^\\n]*\\n([\\s\\S]*?)(?=\\n## \\d |$)')
  const m = M0.match(re)
  return m ? m[1] : ''
}
// 1 记录域：旧态全覆盖（严格双形态：status 赋值 或 "X".equals(o.status)；双捕获组归一）
const woSrc = fs.readFileSync(path.join(ROOT, 'backend/src/main/java/com/cqwlw/maintenance/service/WorkOrderService.java'), 'utf8')
const recordStates = [...new Set([...woSrc.matchAll(/status = "([A-Z_]+)"|"([A-Z_]+)"\.equals\(o\.status\)/g)].map(m => m[1] || m[2]))]
check('record-states', recordStates.filter(s => !section(1).includes('| ' + s + ' ')), recordStates.length)
console.log(JSON.stringify(results, null, 2))