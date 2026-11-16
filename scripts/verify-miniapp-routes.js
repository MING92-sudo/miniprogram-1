// scripts/verify-miniapp-routes.js
// M2 路由/菜单/导航结构守卫（结构等价取证）：10 检查，缺口退出码 1，JSON 计数输出。
// 过渡期口径：app.json 新旧页并存（old∪new）；built 随批次推进；Task 9 收口后 10 检查全绿。
const fs = require('fs')
const path = require('path')
const ROOT = path.join(__dirname, '..')
const results = []
function check(name, missing, total) {
  results.push({ name, total, missingCount: missing.length, missing })
  if (missing.length) process.exitCode = 1
}
function read(p) { return fs.readFileSync(path.join(ROOT, p), 'utf8') }
function section4() {
  const m0 = read('docs/superpowers/specs/2026-11-15-miniapp-redesign-m0-mappings.md')
  const m = m0.match(/## 4 [^\n]*\n([\s\S]*?)(?=\n## \d |$)/)
  return m ? m[1] : ''
}

const app = JSON.parse(read('app.json'))
const appPages = (app.pages || []).slice()
;(app.subPackages || []).forEach((sp) => (sp.pages || []).forEach((p) => appPages.push(sp.root + '/' + p)))
const menus = require('../config/menus')
const icons = require('../config/icons').ICONS
const routes = require('../config/routes')

// M0 §4 新旧路由集合（单元格精确解析）
const oldCol = new Set()
const newCol = new Set()
section4().split('\n').forEach((line) => {
  const cells = line.split('|').map((c) => c.trim())
  if (cells.length >= 4 && cells[1] && cells[1] !== '新路由' && !cells[1].startsWith('---')) {
    newCol.add(cells[1])
    cells[2].split('+').forEach((x) => oldCol.add(x.trim()))
  }
})

// 1 routes-meta：app.json 每页 ∈ ROUTES（OLD_PAGES 过渡表已删，2026-11-16 审查清理）
check('routes-meta', appPages.filter((p) => !routes.ROUTES['/' + p]), appPages.length)
// 2 routes-coverage：49 新路由 ⊆ ROUTES
check('routes-coverage', [...newCol].filter((p) => !routes.ROUTES['/' + p]), newCol.size)
// 3 routes-built：built:true ⊆ app.json；app.json 新页 ⊆ built
const builtPaths = Object.keys(routes.ROUTES).filter((p) => routes.ROUTES[p].built).map((p) => p.slice(1))
const builtMissing = builtPaths.filter((p) => !appPages.includes(p))
const newRegisteredNotBuilt = appPages.filter((p) => newCol.has(p) && !routes.ROUTES['/' + p].built)
check('routes-built', builtMissing.concat(newRegisteredNotBuilt), builtPaths.length)

// 后端 Java 菜单解析
const java = read('backend/src/main/java/com/cqwlw/maintenance/auth/MiniappMenuService.java')
function parseJavaTabs() {
  const body = java.slice(java.lastIndexOf('buildTabs()'))
  const clientBlock = (body.match(/client = List\.of\(([\s\S]*?)\)\s*;/) || [])[1] || ''
  const out = {}
  let m
  const reList = /t\.put\("([A-Z_]+)",\s*List\.of\(([\s\S]*?)\)\s*\)\s*;/g
  while ((m = reList.exec(body))) out[m[1]] = parseTabTuples(m[2])
  const reClient = /t\.put\("([A-Z_]+)",\s*client\)\s*;/g
  while ((m = reClient.exec(body))) out[m[1]] = parseTabTuples(clientBlock)
  return out
}
function parseTabTuples(s) {
  const re = /tab\("([^"]+)",\s*"([^"]+)",\s*"([^"]+)",\s*"([^"]+)"(?:,\s*"([^"]+)")?\)/g
  const list = []
  let m
  while ((m = re.exec(s))) list.push([m[1], m[2], m[3], m[4], m[5] || ''])
  return list
}
function jsTabs(roleTabs) {
  const out = {}
  Object.keys(roleTabs).forEach((r) => {
    out[r] = roleTabs[r].map((t) => [t.key, t.text, t.icon, t.path, t.badgeKey || ''])
  })
  return out
}
function diffTabs(a, b) {
  const missing = []
  const roles = new Set(Object.keys(a).concat(Object.keys(b)))
  roles.forEach((r) => {
    const sa = JSON.stringify(a[r] || [])
    const sb = JSON.stringify(b[r] || [])
    if (sa !== sb) missing.push(r)
  })
  return missing
}
const javaTabs = parseJavaTabs()
const jsTabsObj = jsTabs(menus.ROLE_TABS)
// 4 tabs-drift-backend
const driftBackend = diffTabs(jsTabsObj, javaTabs)
const javaHome = {}
const homeBlock = (java.match(/ROLE_HOME = Map\.of\(([\s\S]*?)\);/) || [])[1] || ''
let hm
const homeRe = /"([A-Z_]+)",\s*"(\/[^"]+)"/g
while ((hm = homeRe.exec(homeBlock))) javaHome[hm[1]] = hm[2]
Object.keys(menus.ROLE_HOME).forEach((r) => { if (menus.ROLE_HOME[r] !== javaHome[r]) driftBackend.push('HOME:' + r) })
const javaPriority = ((java.match(/ROLE_PRIORITY = List\.of\(([\s\S]*?)\);/) || [])[1] || '').match(/"([A-Z_]+)"/g).map((s) => s.slice(1, -1))
if (JSON.stringify(javaPriority) !== JSON.stringify(menus.ROLE_PRIORITY)) driftBackend.push('PRIORITY')
check('tabs-drift-backend', driftBackend, Object.keys(jsTabsObj).length)

// 原型 md ROLE_TABS 解析
function parseProtoTabs() {
  const md = read('docs/16-梯维通设计输入/routes-and-menus.md')
  const block = (md.match(/ROLE_TABS: Record<RoleCode, TabItem\[\]> = \{([\s\S]*?)\n\};/) || [])[1] || ''
  const out = {}
  const roleRe = /([A-Z_]+):\s*\[([\s\S]*?)\]/g
  let m
  while ((m = roleRe.exec(block))) {
    const items = []
    const itemRe = /\{\s*key:\s*'([^']+)',\s*text:\s*'([^']+)',\s*icon:\s*'([^']+)',\s*path:\s*'([^']+)'(?:,\s*badgeKey:\s*'([^']+)')?\s*\}/g
    let it
    while ((it = itemRe.exec(m[2]))) items.push([it[1], it[2], it[3], it[4], it[5] || ''])
    out[m[1]] = items
  }
  return out
}
// 5 tabs-drift-proto
check('tabs-drift-proto', diffTabs(jsTabsObj, parseProtoTabs()), Object.keys(jsTabsObj).length)
// 6 tabs-paths：tab.path（去 query）∈ ROUTES
const tabPaths = []
Object.keys(menus.ROLE_TABS).forEach((r) => menus.ROLE_TABS[r].forEach((t) => tabPaths.push(t.path.split('?')[0])))
check('tabs-paths', [...new Set(tabPaths)].filter((p) => !routes.ROUTES[p]), new Set(tabPaths).size)

// 7 nav-transition：M6 后全部 tab 目标必须已建（TRANSITION_MAP 过渡映射已删，2026-11-16 审查清理）
const navMissing = [...new Set(tabPaths)].filter((p) => routes.ROUTES[p] && !routes.ROUTES[p].built)
check('nav-transition', navMissing, [...new Set(tabPaths)].length)

// 8 no-switchtab：pages/components/utils（除 nav.js）无 wx.switchTab 调用
function walk(dir, out) {
  fs.readdirSync(dir, { withFileTypes: true }).forEach((e) => {
    const fp = path.join(dir, e.name)
    if (e.isDirectory()) walk(fp, out)
    else if (e.name.endsWith('.js')) out.push(fp)
  })
  return out
}
const switchTabHits = []
;['pages', 'components', 'utils'].forEach((dir) => {
  const full = path.join(ROOT, dir)
  if (!fs.existsSync(full)) return
  walk(full, []).forEach((fp) => {
    if (fp.endsWith(path.join('utils', 'nav.js'))) return
    const src = fs.readFileSync(fp, 'utf8')
    if (/wx\.switchTab\s*\(/.test(src)) switchTabHits.push(path.relative(ROOT, fp))
  })
})
check('no-switchtab', switchTabHits, appPages.length)

// 9 appjson-shape：无 tabBar + custom 导航 + lazyCodeLoading
const shapeMissing = []
if (app.tabBar) shapeMissing.push('tabBar 未删除')
if (!app.window || app.window.navigationStyle !== 'custom') shapeMissing.push('navigationStyle!=custom')
if (app.lazyCodeLoading !== 'requiredComponents') shapeMissing.push('lazyCodeLoading!=requiredComponents')
check('appjson-shape', shapeMissing, 3)

// 10 icons-map：ICONS 值 ∈ 既有 .i- 类；ROLE_TABS icon key ∈ ICONS 键
const iconCss = read('assets/icons/icons.wxss')
const iconClasses = new Set((iconCss.match(/\.i-[a-z0-9-]+(?=:before)/g) || []).map((s) => s.slice(1)))
const iconMissing = Object.keys(icons).filter((k) => !iconClasses.has(icons[k])).map((k) => k + '→' + icons[k])
const tabIconKeys = new Set()
Object.keys(menus.ROLE_TABS).forEach((r) => menus.ROLE_TABS[r].forEach((t) => tabIconKeys.add(t.icon)))
tabIconKeys.forEach((k) => { if (!icons[k]) iconMissing.push('tabKey:' + k) })
check('icons-map', iconMissing, Object.keys(icons).length)

console.log(JSON.stringify(results, null, 2))