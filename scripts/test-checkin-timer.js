// 主维保动态码：从水印相机返回后必须立即刷新并恢复轮换。
const assert = require('assert')
const path = require('path')
const Module = require('module')

const pagePath = path.resolve(__dirname, '../pkg-maintenance/pages/checkin/index.js')
let definition
let issueCount = 0
let issueImpl = () => { issueCount++; return Promise.resolve({ code: String(100000 + issueCount), stepSeconds: 5 }) }
const originalLoad = Module._load
Module._load = function (request, parent, isMain) {
  if (parent && parent.filename === pagePath) {
    if (request === '../../../utils/guard') return { definePage: (route, page) => { definition = page } }
    if (request === '../../../config/routes') return { ROUTES: {} }
    if (request === '../../../services/order') {
      return {
        issueDynamicCode: () => issueImpl()
      }
    }
    if (request === '../../../services/upload') return { uploadImage: () => Promise.resolve({}) }
    if (request === '../../../services/location') return { reverseGeocode: () => Promise.resolve('') }
  }
  return originalLoad.call(this, request, parent, isMain)
}
require(pagePath)
Module._load = originalLoad

const page = Object.assign({}, definition, {
  data: JSON.parse(JSON.stringify(definition.data)),
  setData(patch) { this.data = Object.assign({}, this.data, patch) }
})
page.setData({ orderId: 'wo_1', role: 'PRINCIPAL', isDual: true, roleLocked: true })

async function main() {
  await page.startCodeTimer()
  assert.strictEqual(issueCount, 1)
  page.onHide()
  page.onShow()
  await new Promise((resolve) => setImmediate(resolve))
  assert.strictEqual(issueCount, 2, 'onShow 未重新获取动态码')
  assert.strictEqual(page.data.issuedCode, '100002')
  assert(page._codeTimer, 'onShow 未恢复轮换定时器')

  const resolvers = []
  issueImpl = () => new Promise((resolve, reject) => { resolvers.push({ resolve, reject }) })
  page.startCodeTimer() // A pending
  page.onHide()
  page.onShow() // B pending
  resolvers[1].resolve({ code: 'NEW', stepSeconds: 5 })
  await new Promise((resolve) => setImmediate(resolve))
  resolvers[0].resolve({ code: 'OLD', stepSeconds: 5 })
  await new Promise((resolve) => setImmediate(resolve))
  assert.strictEqual(page.data.issuedCode, 'NEW', '迟到的 A 覆盖了 B 的动态码')
  resolvers[0].reject(new Error('late failure'))
  await new Promise((resolve) => setImmediate(resolve))
  assert(page._codeTimer, '迟到的失败停止了新定时器')
  assert.strictEqual(page.data.issuedCode, 'NEW')
  page.stopCodeTimer()
  console.log('PASS 水印相机返回后立即刷新动态码并恢复轮换')
}

main().catch((e) => { console.error(e); process.exit(1) })
