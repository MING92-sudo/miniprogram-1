// 主包/分包依赖边界：仅分包使用的 services/utils 不得留在主包目录。
const fs = require('fs')
const path = require('path')

const ROOT = path.join(__dirname, '..')
const app = require(path.join(ROOT, 'app.json'))
const errors = []
const owners = new Map()

function walkJs(dir) {
  const absolute = path.join(ROOT, dir)
  if (!fs.existsSync(absolute)) return []
  const files = []
  fs.readdirSync(absolute, { withFileTypes: true }).forEach((entry) => {
    const relative = path.join(dir, entry.name)
    if (entry.isDirectory()) files.push(...walkJs(relative))
    if (entry.isFile() && entry.name.endsWith('.js')) files.push(path.join(ROOT, relative))
  })
  return files
}

function resolveModule(importer, request) {
  const base = path.resolve(path.dirname(importer), request)
  const candidates = [base, base + '.js', base + '.json', path.join(base, 'index.js')]
  return candidates.find((candidate) => fs.existsSync(candidate) && fs.statSync(candidate).isFile()) || null
}

function collect(entry, scope) {
  const seen = new Set()
  const queue = [entry]
  while (queue.length) {
    const file = queue.shift()
    if (seen.has(file)) continue
    seen.add(file)
    if (!owners.has(file)) owners.set(file, new Set())
    owners.get(file).add(scope)
    const source = fs.readFileSync(file, 'utf8')
    const pattern = /require\(\s*['"]([^'"]+)['"]\s*\)/g
    let match
    while ((match = pattern.exec(source))) {
      const request = match[1]
      if (!request.startsWith('.')) continue
      const resolved = resolveModule(file, request)
      if (!resolved) {
        errors.push(path.relative(ROOT, file) + ' 无法解析 ' + request)
        continue
      }
      if (resolved.endsWith('.js')) queue.push(resolved)
    }
  }
}

const mainEntries = [path.join(ROOT, 'app.js')]
  .concat(walkJs('pages'), walkJs('components'), walkJs('custom-tab-bar'))
mainEntries.forEach((entry) => collect(entry, 'main'))

;(app.subPackages || []).forEach((pkg) => {
  walkJs(pkg.root).forEach((entry) => collect(entry, pkg.root))
})

const candidates = walkJs('services').concat(walkJs('utils'))
const mainOnlyUnused = candidates.filter((file) => {
  const scopes = owners.get(file)
  if (!scopes || scopes.has('main')) return false
  return Array.from(scopes).some((scope) => scope.startsWith('pkg-'))
})

const mirrors = [
  path.join(ROOT, 'pkg-client/utils/record-view.js'),
  path.join(ROOT, 'pkg-maintenance/utils/record-view.js')
]
if (mirrors.every((file) => fs.existsSync(file)) &&
  fs.readFileSync(mirrors[0], 'utf8') !== fs.readFileSync(mirrors[1], 'utf8')) {
  errors.push('两个分包的 record-view.js 实现不一致')
}

if (mainOnlyUnused.length) {
  console.error('FAIL 主包内存在仅分包使用的 JS：')
  mainOnlyUnused.forEach((file) => console.error('  ' + path.relative(ROOT, file).replace(/\\/g, '/')))
}
if (errors.length) {
  console.error('FAIL 分包依赖或镜像检查：')
  errors.forEach((error) => console.error('  ' + error))
}
if (mainOnlyUnused.length || errors.length) process.exit(1)
console.log('PASS 主包未承载仅分包使用的 services/utils，分包依赖均可解析')
