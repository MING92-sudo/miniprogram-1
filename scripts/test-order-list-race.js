// 工单列表竞态回归：仅最新请求可落地（含错误），翻页不拼接过期页。
const assert = require('assert')
const path = require('path')
const Module = require('module')

const root = path.resolve(__dirname, '..')
const pagePath = path.join(root, 'pkg-maintenance/pages/orders/index.js')
let definition
let pending = []
let stopped = 0

global.wx = {
  getStorageSync: () => '',
  removeStorageSync: () => {},
  stopPullDownRefresh: () => { stopped++ },
  showToast: () => {},
  showModal: () => {},
  scanCode: () => {}
}

const originalLoad = Module._load
Module._load = function (request, parent, isMain) {
  if (parent && parent.filename === pagePath) {
    if (request === '../../../utils/guard') return { definePage: (route, page) => { definition = page } }
    if (request === '../../../config/routes') return { ROUTES: {} }
    if (request === '../../../utils/nav') return { open: () => {}, back: () => {} }
    if (request === '../../../services/order') {
      return {
        getOrderList: (params) => new Promise((resolve, reject) => { pending.push({ params, resolve, reject }) }),
        resolveByElevatorCode: () => Promise.resolve({})
      }
    }
    if (request === '../../../constants/index') {
      return {
        STATUS_TEXT: { PENDING: '待执行', PROCESSING: '进行中', DONE: '已完成' },
        REPORT_STATUS_TEXT: { REPORTED: '已上报平台', FAILED: '上报异常', PENDING: '待上报' },
        TOAST_TEXT: { LOAD_FAILED: '加载失败' },
        EMPTY_TEXT: { ORDER: '暂无工单' },
        POOL_TEXT: {}
      }
    }
  }
  return originalLoad.call(this, request, parent, isMain)
}
require(pagePath)
Module._load = originalLoad

function createPage() {
  return Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch) { this.data = Object.assign({}, this.data, patch) }
  })
}

function reset() {
  pending = []
  stopped = 0
  global.wx.getStorageSync = () => ''
}

function flush() { return new Promise((resolve) => setImmediate(resolve)) }

function order(id, status, extra) {
  return Object.assign({
    id, status, planTime: '2026-01-02 09:00:00', reportStatus: 'REPORTED', workerName: '维保员'
  }, extra || {})
}

async function testTabSwitchSlowEarlierIgnored() {
  reset()
  const page = createPage()
  page.onTabChange({ currentTarget: { dataset: { key: '' } } })
  page.onTabChange({ currentTarget: { dataset: { key: 'DONE' } } })
  assert.strictEqual(pending.length, 2)
  pending[1].resolve({ list: [order('done-1', 'DONE')], total: 1 })
  await flush()
  pending[0].resolve({ list: [order('all-1', 'PENDING')], total: 9 })
  await flush()
  assert.deepStrictEqual(page.data.list.map((o) => o.id), ['done-1'], '慢的旧响应覆盖了较新的筛选结果')
  assert.strictEqual(page.data.total, 1)
  assert.strictEqual(page.data.page, 1)
  assert.strictEqual(page.data.loading, false)
  assert.strictEqual(page.data.status, 'DONE')
  console.log('PASS 切 Tab 后慢响应不覆盖新结果')
}

async function testSearchSlowEarlierIgnored() {
  reset()
  const page = createPage()
  page.onSearch({ detail: { value: 'a' } })
  page.onSearch({ detail: { value: 'ab' } })
  pending[1].resolve({ list: [order('ab-1', 'DONE')], total: 1 })
  await flush()
  pending[0].resolve({ list: [order('a-1', 'DONE')], total: 1 })
  await flush()
  assert.deepStrictEqual(page.data.list.map((o) => o.id), ['ab-1'], '慢的旧响应覆盖了新关键字结果')
  assert.strictEqual(page.data.emptyMode, 'filtered')
  assert.strictEqual(page.data.page, 1)
  console.log('PASS 搜索后慢响应不覆盖新关键字结果')
}

async function testOnShowOnlyLatestWins() {
  reset()
  const page = createPage()
  page.onShow()
  global.wx.getStorageSync = (key) => (key === 'order_due_filter' ? 'today' : '')
  page.onShow()
  global.wx.getStorageSync = () => ''
  pending[1].resolve({ list: [order('due-1', 'DONE')], total: 1 })
  await flush()
  pending[0].resolve({ list: [order('all-2', 'DONE')], total: 2 })
  await flush()
  assert.deepStrictEqual(page.data.list.map((o) => o.id), ['due-1'], 'onShow 旧请求覆盖了新请求')
  assert.strictEqual(page.data.due, 'today')
  assert.strictEqual(page.data.total, 1)
  console.log('PASS onShow 重复进入仅保留最新请求结果')
}

async function testPullDownRefreshStaleIgnored() {
  reset()
  const page = createPage()
  const done = page.onPullDownRefresh()
  page.onSearch({ detail: { value: 'x' } })
  pending[1].resolve({ list: [order('search-1', 'DONE')], total: 1 })
  await flush()
  pending[0].resolve({ list: [order('pull-1', 'DONE')], total: 1 })
  await flush()
  await done
  assert.deepStrictEqual(page.data.list.map((o) => o.id), ['search-1'], '下拉刷新慢响应覆盖了后发搜索')
  assert.strictEqual(stopped, 1, '下拉刷新未结束')
  console.log('PASS 下拉刷新慢响应不覆盖后发搜索')
}

async function testReachBottomAppendsCapturedPage() {
  reset()
  const page = createPage()
  page.onTabChange({ currentTarget: { dataset: { key: '' } } })
  pending[0].resolve({ list: [order('p1-1', 'DONE')], total: 2 })
  await flush()
  assert.strictEqual(page.data.page, 1)
  page.onReachBottom()
  assert.strictEqual(pending[1].params.page, 2, '翻页请求未按当前页 +1')
  pending[1].resolve({ list: [order('p2-1', 'DONE')], total: 2 })
  await flush()
  assert.deepStrictEqual(page.data.list.map((o) => o.id), ['p1-1', 'p2-1'])
  assert.strictEqual(page.data.page, 2)
  console.log('PASS 触底翻页按捕获页拼接')
}

async function testStalePageNeverAppended() {
  reset()
  const page = createPage()
  page.onTabChange({ currentTarget: { dataset: { key: '' } } })
  pending[0].resolve({ list: [order('old-1', 'DONE')], total: 40 })
  await flush()
  page.onReachBottom()
  assert.strictEqual(pending[1].params.page, 2)
  page.onSearch({ detail: { value: 'k' } })
  pending[2].resolve({ list: [order('new-1', 'DONE')], total: 1 })
  await flush()
  pending[1].resolve({ list: [order('stale-p2', 'DONE')], total: 40 })
  await flush()
  assert.deepStrictEqual(page.data.list.map((o) => o.id), ['new-1'], '过期翻页结果被拼接进列表')
  assert.strictEqual(page.data.page, 1)
  console.log('PASS 过期翻页结果不拼接')
}

async function testLateErrorIgnoredAfterSuccess() {
  reset()
  const page = createPage()
  page.onTabChange({ currentTarget: { dataset: { key: 'DONE' } } })
  page.onTabChange({ currentTarget: { dataset: { key: 'PROCESSING' } } })
  pending[1].resolve({ list: [order('proc-1', 'PROCESSING', { checkinTime: '2026-01-02 09:00:00' })], total: 1 })
  await flush()
  pending[0].reject(new Error('stale failure'))
  await flush()
  assert.strictEqual(page.data.error, '', '迟到的旧错误覆盖了新结果')
  assert.deepStrictEqual(page.data.list.map((o) => o.id), ['proc-1'])
  assert.strictEqual(page.data.loading, false)
  console.log('PASS 迟到的旧错误不覆盖新成功结果')
}

async function testStaleSuccessIgnoredAfterNewerError() {
  reset()
  const page = createPage()
  page.onTabChange({ currentTarget: { dataset: { key: 'DONE' } } })
  page.onTabChange({ currentTarget: { dataset: { key: 'PENDING' } } })
  pending[1].reject(new Error('latest failed'))
  await flush()
  pending[0].resolve({ list: [order('old', 'DONE')], total: 1 })
  await flush()
  assert.strictEqual(page.data.error, 'latest failed')
  assert.deepStrictEqual(page.data.list, [], '迟到的旧成功覆盖了最新错误')
  console.log('PASS 迟到的旧成功不覆盖最新错误')
}

async function testLatestErrorSurfaces() {
  reset()
  const page = createPage()
  page.onSearch({ detail: { value: 'z' } })
  pending[0].reject(new Error('boom'))
  await flush()
  assert.strictEqual(page.data.loading, false)
  assert.strictEqual(page.data.error, 'boom')
  console.log('PASS 最新请求错误正常展示')
}

;(async () => {
  await testTabSwitchSlowEarlierIgnored()
  await testSearchSlowEarlierIgnored()
  await testOnShowOnlyLatestWins()
  await testPullDownRefreshStaleIgnored()
  await testReachBottomAppendsCapturedPage()
  await testStalePageNeverAppended()
  await testLateErrorIgnoredAfterSuccess()
  await testStaleSuccessIgnoredAfterNewerError()
  await testLatestErrorSurfaces()
})().catch((e) => { console.error(e); process.exit(1) })
