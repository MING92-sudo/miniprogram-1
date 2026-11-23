// 离线队列回归：稳定签退幂等键 + flush 合并新增任务 + 单 flight + 账号属主隔离。
const assert = require('assert')
const path = require('path')
const Module = require('module')

const root = path.resolve(__dirname, '..')
const offlinePath = path.join(root, 'utils/offline.js')
const pagePath = path.join(root, 'pkg-maintenance/pages/checkout/index.js')
const store = { offline_queue: [], em_user: { id: 'user-A' } }
let requests = []
let requestImpl = () => Promise.resolve({})
let uploadImpl = () => Promise.resolve({ fileId: 'file_default' })
let toasts = []

global.wx = {
  getStorageSync: (key) => store[key] === undefined ? '' : store[key],
  setStorageSync: (key, value) => { store[key] = value },
  removeStorageSync: (key) => { delete store[key] },
  getStorageInfoSync: () => ({ keys: Object.keys(store) }),
  showToast: (options) => { toasts.push((options && options.title) || '') },
  reLaunch: () => {},
  redirectTo: () => {}
}

const originalLoad = Module._load
Module._load = function (request, parent, isMain) {
  if (parent && parent.filename === offlinePath && request === './request') {
    return { request: (options) => { requests.push(options); return requestImpl(options) } }
  }
  if (parent && parent.filename === offlinePath && request === '../services/upload') {
    return { uploadImage: (path) => uploadImpl(path) }
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

function deferred() {
  let resolve
  const promise = new Promise((resolvePromise) => { resolve = resolvePromise })
  return { promise, resolve }
}

function reset() {
  store.offline_queue = []
  store.em_user = { id: 'user-A' }
  requests = []
  toasts = []
  uploadImpl = () => Promise.resolve({ fileId: 'file_default' })
  requestImpl = () => Promise.resolve({})
}

// 真实 utils/auth 从 em_user 取稳定账号 id，这里只切换登录账号模拟 A/B。
function loginAs(id) {
  store.em_user = { id }
}

function putLegacyTask(task) {
  store.offline_queue = (store.offline_queue || []).concat([task])
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
  first.stopTicker()
  await first.onSubmit()
  const firstKey = offline.getQueue()[0].idempotencyKey
  const second = createCheckoutPage()
  await second.fetchOrder()
  second.stopTicker()
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

async function testAccountBCannotReplayAccountATask() {
  reset()
  offline.enqueue({ id: 'task-a', url: '/a', method: 'POST', data: {}, idempotencyKey: 'co_a' })
  loginAs('user-B')
  const result = await offline.flushQueue()
  assert.strictEqual(requests.length, 0, 'B 的会话重放了 A 的任务')
  assert.strictEqual(result.success, 0)
  assert.strictEqual(offline.getQueue().length, 0, 'B 不应看到 A 的任务')
  assert.strictEqual(store.offline_queue.length, 1, 'A 的任务应原样保留')
  assert.strictEqual(store.offline_queue[0].ownerId, 'user-A')
  console.log('PASS B 登录后不会重放 A 的任务，任务归属不变')
}

async function testOwnerCanResumeAfterRelogin() {
  reset()
  offline.enqueue({ id: 'task-resume', url: '/resume', method: 'POST', data: {}, idempotencyKey: 'co_resume' })
  loginAs('user-B')
  await offline.flushQueue()
  assert.strictEqual(requests.length, 0)
  loginAs('user-A')
  const result = await offline.flushQueue()
  assert.strictEqual(result.success, 1)
  assert.strictEqual(requests.length, 1)
  assert.strictEqual(requests[0].idempotencyKey, 'co_resume', '重新登录后应复用原幂等键')
  assert.deepStrictEqual(offline.getQueue(), [])
  console.log('PASS 属主重新登录后复用原幂等键补传')
}

async function testLegacyTaskWithoutOwnerNeverFlushes() {
  reset()
  putLegacyTask({ id: 'legacy-1', url: '/legacy', method: 'POST', data: {}, createdAt: Date.now() })
  const result = await offline.flushQueue()
  assert.strictEqual(requests.length, 0, '无属主旧任务不得自动上传')
  assert.strictEqual(result.success, 0)
  assert.strictEqual(store.offline_queue.length, 1, '无属主旧任务不得被删除')
  assert.strictEqual(offline.getQueue().length, 0)
  assert.strictEqual(offline.getOrphanQueue().length, 1, '无属主旧任务应单独列出待人工处理')
  console.log('PASS 无 ownerId 旧任务不上传、不删除且单独列出')
}

async function testAccountSwitchMidFlushStopsReplay() {
  reset()
  const pending = []
  requestImpl = () => new Promise((resolve) => { pending.push(resolve) })
  offline.enqueue({ id: 'mid-1', url: '/mid-1', method: 'POST', data: {} })
  offline.enqueue({ id: 'mid-2', url: '/mid-2', method: 'POST', data: {} })
  const flushing = offline.flushQueue()
  await new Promise((resolve) => setImmediate(resolve))
  assert.strictEqual(requests.length, 1, '第一条任务应在 flush 中')
  loginAs('user-B')
  pending.shift()()
  await new Promise((resolve) => setImmediate(resolve))
  if (pending.length) pending.shift()() // 有缺陷时让第二条请求落地，避免用例挂起
  await flushing
  assert.strictEqual(requests.length, 1, '换号后不得再用 B 的会话重放 A 的剩余任务')
  const remaining = store.offline_queue.filter((t) => t.ownerId === 'user-A')
  assert.deepStrictEqual(remaining.map((t) => t.id), ['mid-2'], 'A 的剩余任务应原样保留')
  requestImpl = () => Promise.resolve({})
  console.log('PASS flush 中途换号后不再重放剩余任务')
}

async function testAccountBEnqueueDoesNotOverwriteAccountATask() {
  reset()
  offline.enqueue({ url: '/same', method: 'POST', data: { who: 'A' } })
  const aTaskId = store.offline_queue[0].id
  loginAs('user-B')
  offline.enqueue({ url: '/same', method: 'POST', data: { who: 'B' } })
  assert.strictEqual(store.offline_queue.length, 2, 'B 入队覆盖了 A 的同 URL 任务')
  const aTask = store.offline_queue.find((t) => t.ownerId === 'user-A')
  assert.strictEqual(aTask.id, aTaskId)
  assert.deepStrictEqual(aTask.data, { who: 'A' })
  console.log('PASS B 入队同名任务不会覆盖 A 的待补传任务')
}

async function testOrphanTaskListedAndManuallyDeletable() {
  reset()
  putLegacyTask({ id: 'legacy-1', url: '/legacy', method: 'POST', data: {}, createdAt: Date.now() })
  offline.enqueue({ id: 'owned-1', url: '/owned', method: 'POST', data: {} })
  const orphans = offline.getOrphanQueue()
  assert.deepStrictEqual(orphans.map((t) => t.id), ['legacy-1'])
  offline.removeOrphanTask('owned-1')
  assert.strictEqual(store.offline_queue.some((t) => t.id === 'owned-1'), true, '不得删除有属主任务')
  offline.removeOrphanTask('legacy-1')
  assert.deepStrictEqual(store.offline_queue.map((t) => t.id), ['owned-1'])
  assert.deepStrictEqual(offline.getQueue().map((t) => t.id), ['owned-1'])
  console.log('PASS 无属主旧任务可人工删除且不影响有属主任务')
}

async function testOfflineEnqueueRequiresLogin() {
  reset()
  loginAs('')

  const size = offline.enqueue({ id: 'anonymous', url: '/anonymous', method: 'POST', data: {} })

  assert.strictEqual(size, 0)
  assert.deepStrictEqual(store.offline_queue, [])
  assert.strictEqual(toasts.some((text) => text.includes('请先登录')), true, '未登录入件缺少明确提示')
  console.log('PASS 未登录离线入队被拒绝并提示')
}

async function testAccountSwitchDuringUploadKeepsTaskWithoutRequest() {
  reset()
  const upload = deferred()
  uploadImpl = () => upload.promise
  const task = {
    id: 'upload-owner',
    url: '/upload-owner',
    method: 'POST',
    data: {},
    uploads: [{ field: 'signatureFileId', path: '/tmp/sign.png' }]
  }
  offline.enqueue(task)
  const flushing = offline.flushQueue()
  await new Promise((resolve) => setImmediate(resolve))
  loginAs('user-B')
  upload.resolve({ fileId: 'file_owner_a' })
  await flushing

  assert.strictEqual(requests.length, 0, '上传期间换号后仍发送了原账号业务请求')
  assert.strictEqual(store.offline_queue.length, 1, '换号失败时任务不得丢失')
  assert.strictEqual(store.offline_queue[0].ownerId, 'user-A')
  console.log('PASS 上传期间换号会保留任务且不发送业务请求')
}

async function testMixedAccountsFlushOnlyCurrentOwner() {
  reset()
  offline.enqueue({ id: 'task-a', url: '/a', method: 'POST', data: {} })
  loginAs('user-B')
  offline.enqueue({ id: 'task-b', url: '/b', method: 'POST', data: {} })
  await offline.flushQueue()

  assert.deepStrictEqual(requests.map((r) => r.url), ['/b'])
  assert.deepStrictEqual(store.offline_queue.map((t) => t.id), ['task-a'])
  console.log('PASS A/B 混合队列仅补传当前账号任务')
}

;(async () => {
  await testStableCheckoutKey()
  await testReworkAttemptKeysDiffer()
  await testEnqueueDuringFlush()
  await testSingleFlight()
  await testAccountBCannotReplayAccountATask()
  await testOwnerCanResumeAfterRelogin()
  await testLegacyTaskWithoutOwnerNeverFlushes()
  await testAccountSwitchMidFlushStopsReplay()
  await testAccountBEnqueueDoesNotOverwriteAccountATask()
  await testOrphanTaskListedAndManuallyDeletable()
  await testAccountSwitchDuringUploadKeepsTaskWithoutRequest()
  await testOfflineEnqueueRequiresLogin()
  await testMixedAccountsFlushOnlyCurrentOwner()
})().catch((e) => { console.error(e); process.exit(1) })
