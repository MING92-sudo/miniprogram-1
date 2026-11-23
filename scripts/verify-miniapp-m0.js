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
const routes = require('../config/routes')
const appPages = (app.pages || []).concat((app.subPackages || []).reduce((acc, sp) =>
  acc.concat((sp.pages || []).map((p) => sp.root + '/' + p)), []))
check('routes-meta', appPages.filter((p) => !routes.ROUTES['/' + p]), appPages.length)
check('routes-built', appPages.filter((p) => routes.ROUTES['/' + p] && !routes.ROUTES['/' + p].built), appPages.length)

const woSrc = read('backend/src/main/java/com/cqwlw/maintenance/service/WorkOrderService.java')
for (const state of ['PENDING', 'PROCESSING', 'DONE']) {
  check('work-order-state-' + state, woSrc.includes('"' + state + '"') ? [] : [state], 1)
}
const handoverSrc = read('backend/src/main/java/com/cqwlw/maintenance/service/HandoverService.java')
const handoverStates = ['INIT', 'DOC_SUBMITTING', 'INSPECTING', 'RECTIFYING', 'PENDING_CONFIRM', 'ARCHIVED', 'PENDING_INST_REVIEW', 'VOIDED']
check('handover-states', handoverStates.filter((s) => !handoverSrc.includes('"' + s + '"')), handoverStates.length)
const faultSrc = read('backend/src/main/java/com/cqwlw/maintenance/service/DirectoryService.java')
const faultStates = ['OPEN', 'ASSIGNED', 'PROCESSING', 'PENDING_CONFIRM', 'CLOSED', 'REFUSED', 'CANCELLED']
check('fault-states', faultStates.filter((s) => !faultSrc.includes('"' + s + '"')), faultStates.length)

const pm = require('../constants/permission-matrix')
check('permission-count', pm.canonicalCodes().size === 84 ? [] : ['expect 84 got ' + pm.canonicalCodes().size], 84)
const javaPm = read('backend/src/main/java/com/cqwlw/maintenance/auth/PermissionMatrix.java')
const missingMirror = [...pm.canonicalCodes()].filter((code) => !javaPm.includes('"' + code + '"'))
check('permission-mirror', missingMirror, pm.canonicalCodes().size)

const assets = []
if (!fs.existsSync(path.join(ROOT, 'assets/brand/logo.jpg'))) assets.push('logo.jpg')
const iconCss = fs.existsSync(path.join(ROOT, 'assets/icons/icons.wxss')) ? read('assets/icons/icons.wxss') : ''
const glyphCount = (iconCss.match(/\.i-[a-z0-9-]+:before/g) || []).length
if (glyphCount !== 64) assets.push('icons glyphs=' + glyphCount)
const gapPath = path.join(ROOT, 'assets/fonts/zcool-gap.json')
const gap = fs.existsSync(gapPath) ? JSON.parse(read('assets/fonts/zcool-gap.json')) : { missing: ['gap.json 缺失'] }
if (gap.missing.length && !gap.userApprovedFallback) assets.push('zcool missing=' + gap.missing.join(''))
check('assets', assets, 3)
console.log(JSON.stringify(results, null, 2))
