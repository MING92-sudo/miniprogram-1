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
// 2 移交域：旧态全覆盖（基准六值取自 HandoverService.java:48–53 状态常量块）
const hoSrc = fs.readFileSync(path.join(ROOT, 'backend/src/main/java/com/cqwlw/maintenance/service/HandoverService.java'), 'utf8')
const hoBlock = hoSrc.split('\n').slice(47, 53).join('')
const handoverStates = [...new Set((hoBlock.match(/"([A-Z_]+)"/g) || []).map(s => s.slice(1, -1)))]
check('handover-states', handoverStates.filter(s => !section(2).includes('| ' + s + ' ')), handoverStates.length)
check('handover-baseline', handoverStates.length === 6 ? [] : ['expect 6 got ' + handoverStates.length], 6)
// 3 急修域：旧态全覆盖 + 漂移检测（严格双形态，排除 BWJX 单号前缀等大写串误报）
const faultStates = ['OPEN', 'ASSIGNED', 'CLOSED']
const faultSrc = fs.readFileSync(path.join(ROOT, 'backend/src/main/java/com/cqwlw/maintenance/service/DirectoryService.java'), 'utf8')
const faultFound = [...new Set([...faultSrc.matchAll(/status = "([A-Z_]+)"|"([A-Z_]+)"\.equals\(f\.status\)/g)].map(m => m[1] || m[2]))]
check('fault-states', faultStates.filter(s => !section(3).includes('| ' + s + ' ')), faultStates.length)
check('fault-drift', faultFound.filter(s => !faultStates.includes(s)), faultFound.length)
// 4 路由：旧页全覆盖 + 新路由全覆盖（单元格精确解析，防子串误匹配）
const app = JSON.parse(fs.readFileSync(path.join(ROOT, 'app.json'), 'utf8'))
const s4 = section(4)
const oldCol = new Set()
const newCol = new Set()
for (const line of s4.split('\n')) {
  const cells = line.split('|').map(c => c.trim())
  if (cells.length >= 4 && cells[1] && cells[1] !== '新路由' && !cells[1].startsWith('---')) {
    newCol.add(cells[1])
    cells[2].split('+').forEach(x => oldCol.add(x.trim()))
  }
}
check('routes-old', app.pages.filter(p => !oldCol.has(p)), app.pages.length)
const spec14 = SPEC.split('### 1.4')[1].split('### 1.5')[0]
const specRoutes = new Set()
for (const line of spec14.split('\n')) {
  const cells = line.split('|').map(c => c.trim())
  if (cells.length >= 5 && cells[3] && cells[3] !== '新路由' && !cells[3].startsWith('---')) {
    cells[3].split('+').forEach(part => {
      const p = part.split('（')[0].trim()
      const brace = p.match(/\{([^}]+)\}/)
      if (brace) brace[1].split(',').forEach(x => specRoutes.add(p.replace(brace[0], x)))
      else if (/^(pages|pkg-[a-z]+)\//.test(p)) specRoutes.add(p)
    })
  }
}
check('routes-new', [...specRoutes].filter(p => !newCol.has(p)), specRoutes.size)
check('routes-count', specRoutes.size === 49 && newCol.size === 49 ? [] : ['spec=' + specRoutes.size + ' m0=' + newCol.size], 49)
console.log(JSON.stringify(results, null, 2))