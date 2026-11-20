#!/usr/bin/env node
/**
 * 静态排查：客户令牌白名单放行，但端点内部只认员工主体。
 *
 * 判据来自 backend/.../auth/AuthInterceptor.java#clientAllowed：
 *   /client/**、/unit/**、若干 /auth/* 精确端点、GET /files/*、GET /elevators*
 *
 * 对每个可达端点，扫描 handler 方法体：
 *   - 员工专属信号：currentUser( / empId( / employeeMapper / SysUserRole / userRoleMapper
 *   - 客户分支信号：CUSTOMER / ATTR_UNIT_ID / ATTR_PRINCIPAL_TYPE
 *   只有员工信号、没有客户信号 → 高风险（客户令牌进来必失败，若抛 401 还会清登录态）
 *
 * 用法：node scripts/e2e/scan-customer-principal.js
 */
const fs = require('fs')
const path = require('path')

const JAVA_ROOT = path.resolve(__dirname, '../../backend/src/main/java/com/cqwlw/maintenance')
const INTERCEPTOR = path.join(JAVA_ROOT, 'auth/AuthInterceptor.java')

// ---------- 1. 从拦截器源码里读出白名单规则（避免手抄漂移） ----------
const interceptorSrc = fs.readFileSync(INTERCEPTOR, 'utf8')
const allowBody = (interceptorSrc.match(/private static boolean clientAllowed\([\s\S]*?\n    \}/) || [''])[0]
if (!allowBody) throw new Error('未能从 AuthInterceptor 提取 clientAllowed，方法签名可能已变更')

// GET 专用段：clientAllowed 末尾 `return "GET".equalsIgnoreCase(method) && (...)`，
// 与「无条件前缀」必须分开，否则写操作（PUT /elevators/{id}/local）会被误判为放行。
const getTailIdx = allowBody.indexOf('"GET".equalsIgnoreCase(method)')
const headPart = getTailIdx >= 0 ? allowBody.slice(0, getTailIdx) : allowBody
const getPart = getTailIdx >= 0 ? allowBody.slice(getTailIdx) : ''

const exactAllow = new Set()
for (const m of headPart.matchAll(/uri\.equals\("([^"]+)"\)/g)) exactAllow.add(m[1])
const prefixAllow = []
for (const m of headPart.matchAll(/uri\.startsWith\("([^"]+)"\)/g)) prefixAllow.push(m[1])

const getExactAllow = new Set()
for (const m of getPart.matchAll(/uri\.equals\("([^"]+)"\)/g)) getExactAllow.add(m[1])
const getPrefixAllow = []
for (const m of getPart.matchAll(/uri\.startsWith\("([^"]+)"\)/g)) getPrefixAllow.push(m[1])
const getRegexAllow = []
for (const m of getPart.matchAll(/uri\.matches\("([^"]+)"\)/g)) getRegexAllow.push(new RegExp('^' + m[1] + '$'))

function inWhitelist(uri, method) {
  if (prefixAllow.some((p) => uri.startsWith(p))) return true
  if (exactAllow.has(uri)) return true
  if (method === 'GET' && (getExactAllow.has(uri) || getPrefixAllow.some((p) => uri.startsWith(p)))) return true
  if (method === 'GET' && getRegexAllow.some((re) => re.test(uri))) return true
  return false
}

// ---------- 2. 解析所有 Controller 端点 ----------
function listJava(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) listJava(p, out)
    else if (e.name.endsWith('.java')) out.push(p)
  }
  return out
}

/** 从 start（指向 '{'）开始，配平大括号，返回方法体字符串 */
function methodBody(src, start) {
  let depth = 0
  for (let i = start; i < src.length; i++) {
    if (src[i] === '{') depth++
    else if (src[i] === '}') {
      depth--
      if (depth === 0) return src.slice(start, i + 1)
    }
  }
  return src.slice(start)
}

const MAPPING_RE = /@(Get|Post|Put|Delete|Patch)Mapping(?:\(([^)]*)\))?\s*[\r\n]?\s*(?:@[\w.]+(?:\([^)]*\))?\s*)*public\s+[^;{]+?\{/g
const CLASS_MAPPING_RE = /@RequestMapping\(([^)]*)\)/

const endpoints = []
// 不能只扫 controller/ 包：AuthController、MiniappMenuController 在 auth/ 包，
// 漏扫会让「白名单里的 /auth/* 全是死规则」这类假结论出现在报告里。
const controllerFiles = listJava(JAVA_ROOT).filter((f) => {
  const s = fs.readFileSync(f, 'utf8')
  return /@RestController/.test(s)
})
for (const file of controllerFiles) {
  const src = fs.readFileSync(file, 'utf8')
  const cls = (src.match(CLASS_MAPPING_RE) || [, ''])[1]
  const base = (cls.match(/"([^"]*)"/) || [, ''])[1]
  MAPPING_RE.lastIndex = 0
  let m
  while ((m = MAPPING_RE.exec(src))) {
    const method = m[1].toUpperCase()
    const args = m[2] || ''
    const sub = (args.match(/"([^"]*)"/) || [, ''])[1]
    const uri = ('/' + base + '/' + sub).replace(/\/+/g, '/').replace(/\/$/, '') || '/'
    const openBrace = m.index + m[0].length - 1
    const body = methodBody(src, openBrace)
    // 方法名
    const sig = src.slice(m.index, openBrace)
    const name = (sig.match(/(\w+)\s*\([^)]*\)\s*$/) || [])[1] ||
      (sig.match(/(\w+)\s*\(\s*$/m) || [])[1] || '?'
    endpoints.push({ file: path.basename(file), uri, method, body, name })
  }
}

// ---------- 3. 信号判定 ----------
const EMPLOYEE_SIGNALS = [
  ['currentUser(', 'currentUser() 只查员工表'],
  ['empId(', 'empId() 取员工 id'],
  ['employeeMapper', '直接查员工表'],
  ['SysUserRole', '旧 sys_user_role 锚点口径'],
  ['userRoleMapper', '旧 sys_user_role 锚点口径']
]
const CUSTOMER_SIGNALS = ['CUSTOMER', 'ATTR_UNIT_ID', 'ATTR_PRINCIPAL_TYPE', 'clientAccount', 'ClientAccount',
  'portalRoles', 'isClient', 'isCustomerRequest']

/**
 * 已人工复核为安全的端点：不进「高风险」，但要在输出里保留结论，避免每轮重复判断。
 * 新增条目必须写清理由——不能只写「已确认」。
 */
const ACKNOWLEDGED = {
  'POST /auth/logout': '本端点免鉴权，主体从 Authorization 自解析；ut=2 分支走 ClientLoginService.revokeSession，'
    + '同文件 employeeMapper 分支只服务员工令牌。',
  'POST /auth/bind-wechat': '已按 isCustomerRequest 分流到 ClientAccountService.bindWechat（2026-11-20 修）。',
  'POST /auth/change-password': '已按 isCustomerRequest 分流到 ClientAccountService.changePassword（2026-11-20 修）。'
}

const rows = []
for (const e of endpoints) {
  const hitEmployee = EMPLOYEE_SIGNALS.filter(([s]) => e.body.includes(s)).map(([, d]) => d)
  const hitCustomer = CUSTOMER_SIGNALS.filter((s) => e.body.includes(s))
  const allowed = inWhitelist(e.uri, e.method)
  rows.push({ ...e, allowed, hitEmployee, hitCustomer })
}

// ---------- 4. 输出 ----------
const reachable = rows.filter((r) => r.allowed)
const key = (r) => `${r.method} ${r.uri}`
const acknowledged = reachable.filter((r) => ACKNOWLEDGED[key(r)])
const risky = reachable.filter((r) => !ACKNOWLEDGED[key(r)] && r.hitEmployee.length > 0 && r.hitCustomer.length === 0)
const mixed = reachable.filter((r) => !ACKNOWLEDGED[key(r)] && r.hitEmployee.length > 0 && r.hitCustomer.length > 0)
const clean = reachable.filter((r) => !ACKNOWLEDGED[key(r)] && r.hitEmployee.length === 0)

const pad = (s, n) => (s + ' '.repeat(n)).slice(0, n)
console.log(`白名单可达端点：${reachable.length} 个（全部端点 ${rows.length} 个）\n`)

console.log('=== 高风险：客户可达 + 只认员工主体 + 无客户分支 ===')
if (!risky.length) console.log('  （无）')
for (const r of risky) {
  console.log(`  ${pad(r.method, 6)} ${pad(r.uri, 34)} ${r.file}#${r.name}`)
  console.log(`         员工信号: ${[...new Set(r.hitEmployee)].join(' / ')}`)
}

console.log('\n=== 需人工确认：含客户分支但也依赖员工口径（注意分支是否覆盖全部路径） ===')
if (!mixed.length) console.log('  （无）')
for (const r of mixed) {
  console.log(`  ${pad(r.method, 6)} ${pad(r.uri, 34)} ${r.file}#${r.name}`)
  console.log(`         客户信号: ${r.hitCustomer.join(', ')} | 员工信号: ${[...new Set(r.hitEmployee)].join(' / ')}`)
}

console.log('\n=== 已人工复核（有结论，不再重复判定） ===')
if (!acknowledged.length) console.log('  （无）')
for (const r of acknowledged) {
  console.log(`  ${pad(r.method, 6)} ${pad(r.uri, 34)} ${ACKNOWLEDGED[key(r)]}`)
}

console.log('\n=== 安全：不依赖员工主体 ===')
for (const r of clean) console.log(`  ${pad(r.method, 6)} ${r.uri}`)

// 白名单里的死规则：放行了但没有对应端点
const declaredUris = new Set(rows.map((r) => r.uri))
const dead = [...exactAllow].filter((u) => !declaredUris.has(u))
console.log('\n=== 白名单死规则（无对应端点，属遗留/无效放行） ===')
if (!dead.length) console.log('  （无）')
for (const u of dead) console.log(`  ${u}`)

fs.writeFileSync(path.join(__dirname, 'reports/customer-principal-scan.json'),
  JSON.stringify({ generatedAt: new Date().toISOString(), reachable, risky, mixed, acknowledged, dead }, null, 2))
console.log('\n明细已写入 scripts/e2e/reports/customer-principal-scan.json')
process.exit(risky.length ? 1 : 0)
