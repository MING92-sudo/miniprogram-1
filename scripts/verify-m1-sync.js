const fs = require('fs')
const path = require('path')
const ROOT = path.join(__dirname, '..')
const ENDPOINTS = [
  ['GET', '/me/menus'],
  ['POST', '/work-orders/:id/signature-refuse'],
  ['POST', '/work-orders/:id/review'],
  ['POST', '/work-orders/:id/sign-request'],
  ['GET', '/work-orders/:id/archive'],
  ['GET', '/work-orders/:id/partner-candidates'],
  ['POST', '/work-orders/:id/assign-partner'],
  ['POST', '/unit/records/:id/refuse-by-token'],
  ['POST', '/faults/:id/grab'],
  ['POST', '/faults/:id/arrive'],
  ['POST', '/faults/:id/submit'],
  ['POST', '/faults/:id/confirm'],
  ['POST', '/faults/:id/cancel'],
  ['POST', '/faults/:id/evaluate'],
  ['GET', '/unit/faults/:id'],
  ['POST', '/unit/faults/:id/confirm'],
  ['POST', '/unit/faults/:id/cancel']
]
const servicesDir = path.join(ROOT, 'services')
const servicesSrc = fs.readdirSync(servicesDir).filter(f => f.endsWith('.js'))
  .map(f => fs.readFileSync(path.join(servicesDir, f), 'utf8')).join('\n')
const mockSrc = fs.readFileSync(path.join(ROOT, 'mock/server.js'), 'utf8')
const controllerDir = path.join(ROOT, 'backend/src/main/java/com/cqwlw/maintenance/controller')
const backendSrc = fs.readdirSync(controllerDir).filter(f => f.endsWith('.java'))
  .map(f => fs.readFileSync(path.join(controllerDir, f), 'utf8')).join('\n')
  + '\n' + fs.readFileSync(path.join(ROOT, 'backend/src/main/java/com/cqwlw/maintenance/auth/MiniappMenuController.java'), 'utf8')
const results = []
for (const [method, p] of ENDPOINTS) {
  const svcRe = new RegExp(p.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(':id', '\\$\\{[a-zA-Z]+\\}'))
  const backendPath = p.replace(':id', '{id}')
  const missing = []
  if (!svcRe.test(servicesSrc) && !servicesSrc.includes(p)) missing.push('services')
  if (!mockSrc.includes("'" + p + "'")) missing.push('mock')
  if (!backendSrc.includes('"' + backendPath + '"')) missing.push('backend')
  results.push({ method, path: p, missing })
  if (missing.length) process.exitCode = 1
}
console.log(JSON.stringify({ total: ENDPOINTS.length, gaps: results.filter(r => r.missing.length) }, null, 2))
