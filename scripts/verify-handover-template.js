// 校验移交查验 74 项模板 mock ↔ 后端一致性（GB/T 10060—2023 表1）。
// 后端事实源：backend HandoverChecklistTemplate.items()（new Item(no, group, clause, name, requirement, important)）
// mock 事实源：mock/server.js 的 HANDOVER_TEMPLATE（[no, group, clause, name, requirement]）+ HANDOVER_IMPORTANT（☆项集合）
// 任一端增删/改条目而另一端未同步时本脚本退出码 1（已挂 CI 门禁 backend-build.yml）
const fs = require('fs')
const path = require('path')

const ROOT = path.join(__dirname, '..')
const JAVA_FILE = path.join(ROOT, 'backend/src/main/java/com/cqwlw/maintenance/service/HandoverChecklistTemplate.java')
const MOCK_FILE = path.join(ROOT, 'mock/server.js')
const EXPECTED_TOTAL = 74

let failed = false
function fail(msg) {
  console.error(msg)
  failed = true
}

// 解析后端 Java 模板：new Item(1, "G01", "5.1.1", "通道", "…", false)
function parseBackend() {
  const src = fs.readFileSync(JAVA_FILE, 'utf8')
  const re = /new Item\((\d+), "([^"]+)", "([^"]+)", "([^"]+)", "([^"]+)", (true|false)\)/g
  const items = []
  let m
  while ((m = re.exec(src))) {
    items.push({ no: Number(m[1]), group: m[2], clause: m[3], name: m[4], requirement: m[5], important: m[6] === 'true' })
  }
  return items
}

// 解析 mock 模板：HANDOVER_TEMPLATE 为纯数据字面量（数字 + 单引号字符串），直接求值
function parseMock() {
  const src = fs.readFileSync(MOCK_FILE, 'utf8')
  const tplMatch = src.match(/const HANDOVER_TEMPLATE = (\[[\s\S]*?\n\])/)
  const impMatch = src.match(/const HANDOVER_IMPORTANT = new Set\(\[([\d,\s]+)\]\)/)
  if (!tplMatch) { fail('mock/server.js 未找到 HANDOVER_TEMPLATE'); return null }
  if (!impMatch) { fail('mock/server.js 未找到 HANDOVER_IMPORTANT'); return null }
  const tpl = Function('return ' + tplMatch[1])() // eslint-disable-line no-new-func
  const important = new Set(impMatch[1].split(',').map(function (s) { return Number(s.trim()) }).filter(Boolean))
  return tpl.map(function (t) {
    return { no: t[0], group: t[1], clause: t[2], name: t[3], requirement: t[4], important: important.has(t[0]) }
  })
}

const backend = parseBackend()
const mock = parseMock()
if (!mock) process.exit(1)

if (backend.length !== EXPECTED_TOTAL) fail('后端模板项数错误: ' + backend.length + ' 应 ' + EXPECTED_TOTAL)
if (mock.length !== EXPECTED_TOTAL) fail('mock 模板项数错误: ' + mock.length + ' 应 ' + EXPECTED_TOTAL)

const backendByNo = {}
backend.forEach(function (it) {
  if (backendByNo[it.no]) fail('后端模板序号重复: ' + it.no)
  backendByNo[it.no] = it
})
for (let no = 1; no <= EXPECTED_TOTAL; no++) {
  if (!backendByNo[no]) fail('后端模板缺序号: ' + no)
}

let compared = 0
mock.forEach(function (m) {
  const b = backendByNo[m.no]
  if (!b) { fail('mock 序号在后端不存在: ' + m.no); return }
  compared++
  ;['group', 'clause', 'name', 'requirement', 'important'].forEach(function (field) {
    if (m[field] !== b[field]) {
      fail('第 ' + m.no + ' 项 ' + field + ' 不一致:\n  mock: ' + JSON.stringify(m[field]) + '\n  后端: ' + JSON.stringify(b[field]))
    }
  })
})

const impBackend = backend.filter(function (it) { return it.important }).length
const impMock = mock.filter(function (it) { return it.important }).length
if (impBackend !== 49) fail('后端 ☆重要项数量错误: ' + impBackend + ' 应 49')
if (impMock !== 49) fail('mock ☆重要项数量错误: ' + impMock + ' 应 49')

console.log('后端模板:', backend.length, '项 | mock 模板:', mock.length, '项 | 比对:', compared, '项 | ☆重要项:', impBackend)
if (failed) process.exit(1)
console.log('移交查验 74 项模板 mock ↔ 后端一致')
