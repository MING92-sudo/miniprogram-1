// scripts/verify-m1-sync.js
// M1 三处同步守卫（结构等价取证）：新端点必须同时出现在 services / mock / docs/04
const fs = require('fs')
const path = require('path')
const ROOT = path.join(__dirname, '..')
const ENDPOINTS = [
  ['GET', '/me/menus'],
  ['POST', '/work-orders/:id/signature-refuse'],
  ['POST', '/work-orders/:id/review'],
  ['POST', '/work-orders/:id/sign-request'],
  ['GET', '/work-orders/:id/archive'],
  ['POST', '/unit/records/:id/refuse-by-token'],
  ['POST', '/faults/:id/grab'],
  ['POST', '/faults/:id/arrive'],
  ['POST', '/faults/:id/submit'],
  ['POST', '/faults/:id/confirm'],
  ['POST', '/faults/:id/cancel'],
  ['POST', '/faults/:id/evaluate']
]
const servicesDir = path.join(ROOT, 'services')
const servicesSrc = fs.readdirSync(servicesDir).filter(f => f.endsWith('.js'))
  .map(f => fs.readFileSync(path.join(servicesDir, f), 'utf8')).join('\n')
const mockSrc = fs.readFileSync(path.join(ROOT, 'mock/server.js'), 'utf8')
const docSrc = fs.readFileSync(path.join(ROOT, 'docs/04-接口文档.md'), 'utf8')
const results = []
for (const [method, p] of ENDPOINTS) {
  // services 用模板字符串（变量名不限）：:id → ${任意标识符}
  const svcRe = new RegExp(p.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(':id', '\\$\\{[a-zA-Z]+\\}'))
  const missing = []
  if (!svcRe.test(servicesSrc) && !servicesSrc.includes(p)) missing.push('services')
  if (!mockSrc.includes("'" + p + "'")) missing.push('mock')
  if (!docSrc.includes(p)) missing.push('docs04')
  results.push({ method, path: p, missing })
  if (missing.length) process.exitCode = 1
}
console.log(JSON.stringify({ total: ENDPOINTS.length, gaps: results.filter(r => r.missing.length) }, null, 2))