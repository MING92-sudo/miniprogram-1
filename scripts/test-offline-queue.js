// 离线队列回归：稳定签退幂等键 + flush 合并新增任务 + 单 flight。
const assert = require('assert')
const path = require('path')
const Module = require('module')

const root = path.resolve(__dirname, '..')
const offlinePath = path.join(root, 'utils/offline.js')
const pagePath = path.join(root, 'pkg-maintenance/pages/checkout/index.js')
const store = { offline_queue: [] }
let requests = []
let requestImpl = () => Promise.resolve({})

global.wx = {
  getStorageSync: (key) => store[key] === undefined ? '' : store[key],
  setStorageSync: (key, value) => { store[key] = value },
  removeStorageSync: (key) => { delete store[key] },
  getStorageInfoSync: () => ({ keys: Object.keys(store) }),
  showToast: () => {},
  reLaunch: () => {},
  redirectTo: () => {}
}

const originalLoad = Module._load
Module._load = function (request, parent, isMain) {
  if (parent && parent.filename === offlinePath && request === './request') {
    return { request: (options) => { requests.push(options); return requestImpl(options) } }
  }
  if (parent && parent.filename === pagePath) {
    if (request === '../../../utils/guard') return { definePage: (route, page) => { global.pageDef = page } }
    if (request === '../../../config/routes') return { ROUTES: {} }
    if (request === '../../../services/order') {
      return { checkout: () => Promise.reject({ code: -1, message: 'response lost' }),
        getOrderDetail: () => Promise.resolve({ originalRecordId: 'OR1', elevator: {}, workerSigned: true, assistantSigned: true }),
        getChecklist: () => Promise.resolve({ items: [] }) }
    }
  }
  return originalLoad.call(this, request, parent, isMain)
}

const offline = require(offlinePath)
require(pagePath)
Module._load = originalLoad

function reset() {
  store.offline_queue = []
  requests = []
  requestImpl = () => Promise.resolve({})
}

function createCheckoutPage() {
  const page = Object.assign({}, global.pageDef, {
    data: JSON.parse(JSON.stringify(global.pageDef.data)),
    setData(patch) { this.data = Object.assign({}, this.data, patch) }
  })
  page.setData({
    orderId: 'wo_1',
    selfChecks: page.data.selfChecks.map((x) => ({ ...x, checked: true })),
    safety: page.data.safety.map((x) => ({ ...x, checked: true })),
    workerSigned: true,
    hasAssistant: false,
    durationOk: true
  })
  return page
}

async function testStableCheckoutKey() {
  reset()
  const page = createCheckoutPage()
  await page.onSubmit()
  const queued = offline.getQueue()
  assert.strictEqual(queued.length, 1)
  assert.strictEqual(queued[0].idempotencyKey, 'co_wo_1')
  await offline.flushQueue()
  assert.strictEqual(requests.length, 1)
  assert.strictEqual(requests[0].idempotencyKey, 'co_wo_1')
  assert.deepStrictEqual(offline.getQueue(), [])
  console.log('PASS 首次响应丢失后补传复用 co_<orderId>')
}

async function testReworkAttemptKeysDiffer() {
  reset()
  const first = createCheckoutPage()
  await first.fetchOrder()
  await first.onSubmit()
  const firstKey = offline.getQueue()[0].idempotencyKey
  const second = createCheckoutPage()
  await second.fetchOrder()
  await second.onSubmit()
  const secondKey = offline.getQueue()[0].idempotencyKey
  assert.notStrictEqual(firstKey, secondKey, '不同整改尝试复用了首次幂等键')
  assert.notStrictEqual(firstKey, 'co_wo_1')
  console.log('PASS 每次整改使用独立且稳定的尝试键')
}

async function testEnqueueDuringFlush() {
  reset()
  let release
  requestImpl = () => new Promise((resolve) => { release = resolve })
  offline.enqueue({ id: 'first', url: '/first', method: 'POST', data: {} })
  const flushing = offline.flushQueue()
  await new Promise((resolve) => setImmediate(resolve))
  offline.enqueue({ id: 'second', url: '/second', method: 'POST', data: {} })
  release()
  await flushing
  assert.strictEqual(offline.getQueue().some((t) => t.id === 'second'), true, 'flush 旧快照覆盖了新任务')
  requestImpl = () => Promise.resolve({})
  await offline.flushQueue()
  assert.deepStrictEqual(requests.map((r) => r.url), ['/first', '/second'])
  assert.deepStrictEqual(offline.getQueue(), [])
  console.log('PASS flush 期间新入队任务最终恰好发送一次')
}

async function testSingleFlight() {
  reset()
  let release
  requestImpl = () => new Promise((resolve) => { release = resolve })
  offline.enqueue({ id: 'only', url: '/only', method: 'POST', data: {} })
  const first = offline.flushQueue()
  const second = offline.flushQueue()
  await new Promise((resolve) => setImmediate(resolve))
  assert.strictEqual(requests.length, 1, '自动+手动 flush 同时发送了重复请求')
  release()
  await Promise.all([first, second])
  assert.deepStrictEqual(offline.getQueue(), [])
  console.log('PASS 自动+手动 flush 共用单 flight')
}

;(async () => {
  await testStableCheckoutKey()
  await testReworkAttemptKeysDiffer()
  await testEnqueueDuringFlush()
  await testSingleFlight()
})().catch((e) => { console.error(e); process.exit(1) })
