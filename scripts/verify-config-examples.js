const fs = require('fs')
const path = require('path')

const ROOT = path.join(__dirname, '..')
const ENV_SAMPLE = '.env.example'
const BACKEND_SAMPLE = 'backend/src/main/resources/application.example.env'
const CI_FILE = '.github/workflows/backend-build.yml'
const APP_YML = 'backend/src/main/resources/application.yml'

const failures = []
function fail(msg) {
  failures.push(msg)
}
function check(name, bad, detail) {
  if (bad) {
    fail(detail || name)
  } else {
    console.log('PASS ' + name)
  }
}
function read(rel) {
  return fs.readFileSync(path.join(ROOT, rel), 'utf8')
}

const samples = [
  { label: ENV_SAMPLE, src: read(ENV_SAMPLE) },
  { label: BACKEND_SAMPLE, src: read(BACKEND_SAMPLE) }
]
const ci = { label: CI_FILE, src: read(CI_FILE) }
const yml = read(APP_YML)
const scoped = samples.concat([ci])

// 1. 旧变量名必须彻底消失（样例与 CI）
const retired = ['APP_JWT_SECRET', 'APP_JWT_TTL_HOURS', 'REG_RETRY_AUTO']
retired.forEach(function (name) {
  const hits = scoped.filter(function (f) { return f.src.includes(name) })
  check('无已废弃变量 ' + name, hits.length > 0,
    '仍残留 ' + name + ': ' + hits.map(function (f) { return f.label }).join(', '))
})

// 2. 样例必须使用与 application.yml 一致的 JWT 变量名
samples.forEach(function (f) {
  const missing = ['JWT_SECRET', 'JWT_EXPIRE_HOURS'].filter(function (name) {
    return !new RegExp('^\\s*' + name + '\\s*=', 'm').test(f.src)
  })
  check('样例包含 JWT 变量 ' + f.label, missing.length > 0,
    f.label + ' 缺少 ' + missing.join(', '))
})
const ymlMissing = ['JWT_SECRET', 'JWT_EXPIRE_HOURS'].filter(function (name) {
  return !yml.includes('${' + name + ':')
})
check('application.yml 引用 JWT 变量', ymlMissing.length > 0,
  APP_YML + ' 未引用 ${' + ymlMissing.join(':, ${') + ':')

// 3. 演示种子开关：仅允许 SEED_DEMO_DATA=false，不得出现 DB_SEED_DEMO
scoped.forEach(function (f) {
  const problems = []
  if (f.src.includes('DB_SEED_DEMO')) {
    problems.push('仍含已废弃的 DB_SEED_DEMO（应改用 SEED_DEMO_DATA=false）')
  }
  const flags = []
  const re = /SEED_DEMO_DATA\s*[:=]\s*["']?([^\s"'#]+)/g
  let m
  while ((m = re.exec(f.src))) flags.push(m[1])
  if (flags.length === 0) problems.push('缺少 SEED_DEMO_DATA=false')
  flags.forEach(function (v) {
    if (v !== 'false') problems.push('SEED_DEMO_DATA 必须为 false，实为 ' + v)
  })
  check('演示种子关闭 ' + f.label, problems.length > 0,
    f.label + ': ' + problems.join('；'))
})

if (failures.length) {
  console.error('\n配置样例检查失败，共 ' + failures.length + ' 项：')
  failures.forEach(function (item) { console.error('  - ' + item) })
  process.exit(1)
}
console.log('\n环境样例与 CI 变量收敛检查通过（JWT_SECRET/JWT_EXPIRE_HOURS，SEED_DEMO_DATA=false）')
