const fs = require('fs')
const path = require('path')
const ROOT = path.join(__dirname, '..')
const results = []
function check(name, missing, total) {
  results.push({ name, total, missingCount: missing.length, missing })
  if (missing.length) process.exitCode = 1
}
function read(p) { return fs.readFileSync(path.join(ROOT, p), 'utf8') }

const app = JSON.parse(read('app.json'))
const appPages = (app.pages || []).slice()
;(app.subPackages || []).forEach((sp) => (sp.pages || []).forEach((p) => appPages.push(sp.root + '/' + p)))
const menus = require('../config/menus')
const icons = require('../config/icons').ICONS
const routes = require('../config/routes')

// 1 routes-meta：app.json 每页 ∈ ROUTES
check('routes-meta', appPages.filter((p) => !routes.ROUTES['/' + p]), appPages.length)
// 2 routes-built：app.json 每页均已 built
check('routes-built', appPages.filter((p) => routes.ROUTES['/' + p] && !routes.ROUTES['/' + p].built), appPages.length)

// 后端 Java 菜单解析
const java = read('backend/src/main/java/com/cqwlw/maintenance/auth/MiniappMenuService.java')
const canonicalRole = (role) => role === 'SYS_ADMIN' ? 'PLATFORM_ADMIN' : role
function parseJavaTabs() {
  const body = java.slice(java.lastIndexOf('buildTabs()'))
  const clientBlock = (body.match(/client = List\.of\(([\s\S]*?)\)\s*;/) || [])[1] || ''
  const out = {}
  let m
  const reList = /t\.put\("([A-Z_]+)",\s*List\.of\(([\s\S]*?)\)\s*\)\s*;/g
  while ((m = reList.exec(body))) out[canonicalRole(m[1])] = parseTabTuples(m[2])
  const reClient = /t\.put\("([A-Z_]+)",\s*client\)\s*;/g
  while ((m = reClient.exec(body))) out[canonicalRole(m[1])] = parseTabTuples(clientBlock)
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
while ((hm = homeRe.exec(homeBlock))) javaHome[canonicalRole(hm[1])] = hm[2]
Object.keys(menus.ROLE_HOME).forEach((r) => { if (menus.ROLE_HOME[r] !== javaHome[r]) driftBackend.push('HOME:' + r) })
const javaPriority = ((java.match(/ROLE_PRIORITY = List\.of\(([\s\S]*?)\);/) || [])[1] || '')
  .match(/"([A-Z_]+)"/g).map((s) => canonicalRole(s.slice(1, -1)))
  .filter((r, i, all) => all.indexOf(r) === i)
if (JSON.stringify(javaPriority) !== JSON.stringify(menus.ROLE_PRIORITY)) driftBackend.push('PRIORITY')
check('tabs-drift-backend', driftBackend, Object.keys(jsTabsObj).length)

// 5 tabs-paths：tab.path（去 query）∈ ROUTES
const tabPaths = []
Object.keys(menus.ROLE_TABS).forEach((r) => menus.ROLE_TABS[r].forEach((t) => tabPaths.push(t.path.split('?')[0])))
check('tabs-paths', [...new Set(tabPaths)].filter((p) => !routes.ROUTES[p]), new Set(tabPaths).size)

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