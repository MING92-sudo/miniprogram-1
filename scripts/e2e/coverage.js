// 端点覆盖核对：扫描后端 Controller 的全部映射，与 E2E smoke/auth/flow 用例里实际打过的路径比对
const fs = require('fs')
const path = require('path')

const ROOT = path.resolve(__dirname, '../../backend/src/main/java/com/cqwlw/maintenance')
const CASES = [__dirname + '/cases.smoke.js', __dirname + '/cases.auth.js', __dirname + '/cases.flow.js']

function walk(dir, out = []) {
  for (const name of fs.readdirSync(dir)) {
    const p = path.join(dir, name)
    const st = fs.statSync(p)
    if (st.isDirectory()) walk(p, out)
    else if (name.endsWith('Controller.java')) out.push(p)
  }
  return out
}

const endpoints = []
for (const file of walk(ROOT)) {
  const src = fs.readFileSync(file, 'utf8')
  const base = (src.match(/@RequestMapping\(\s*"([^"]*)"/) || [, ''])[1]
  const re = /@(Get|Post|Put|Delete|Patch)Mapping\(\s*(?:value\s*=\s*)?"?([^",)]*)"?/g
  let m
  while ((m = re.exec(src))) {
    const method = m[1].toUpperCase()
    const sub = m[2] || ''
    // 上下文路径是 /api；部分 Controller 的类级映射已自带 /api，避免重复前缀
    let full = (base + sub).replace(/\/+/g, '/').replace(/\/$/, '') || '/'
    if (!full.startsWith('/api/') && full !== '/api') full = '/api' + full
    endpoints.push({ method, path: full, file: path.basename(file) })
  }
}

const caseSrc = CASES.map((f) => fs.readFileSync(f, 'utf8')).join('\n')
// 用例里的路径字面量（含 ${...} 模板变量、?query 查询串）
const literals = new Set()
for (const m of caseSrc.matchAll(/['"`](\/[A-Za-z0-9_\-{}$/.?=&%]*)['"`]/g)) literals.add(m[1].split('?')[0])

// 用例里写的是相对上下文路径（post('/auth/login')），端点表里带 /api 前缀 → 两边都剥掉 /api 再比段
const strip = (p) => p.replace(/^\/api(?=\/|$)/, '') || '/'
const seg = (p) => strip(p).split('/').filter(Boolean)

function hit(e) {
  const segs = seg(e.path)
  for (const lit of literals) {
    const ls = seg(lit)
    if (ls.length < segs.length) continue
    let ok = true
    for (let i = 0; i < segs.length; i++) {
      const a = segs[i]
      const b = ls[i]
      if (a.startsWith('{') || a.includes('$')) continue
      if (b === undefined) { ok = false; break }
      if (b.includes('$') || b.startsWith('{') || b.startsWith(':')) continue
      if (a !== b) { ok = false; break }
    }
    if (ok) return true
  }
  return false
}

const miss = endpoints.filter((e) => !hit(e))
const byFile = {}
for (const e of endpoints) byFile[e.file] = (byFile[e.file] || 0) + 1

console.log(`控制器 ${Object.keys(byFile).length} 个，端点 ${endpoints.length} 个，已覆盖 ${endpoints.length - miss.length}`)
if (miss.length) {
  console.log('\n未覆盖：')
  for (const e of miss) console.log(`  ${e.method} ${e.path}   (${e.file})`)
  process.exitCode = 1
} else {
  console.log('覆盖率 100%')
}
