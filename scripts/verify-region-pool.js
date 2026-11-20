// 区域抢单池 mock 冒烟（node scripts/verify-region-pool.js）：
// 覆盖 2026-11-20 区域抢单池设计 §4.2/§4.5 小程序链路——池可见性（M9/M11）、签到即认领（M1/D4）、
// 认领落败 1003、无平台ID 422、配合人候选/指派/改选门禁（D3/D8/M10）、签退恒双人门禁 422（D8）。
// 直接驱动 mock/server.js（Node 内 require，wx 桩），断言响应体与错误码；不启 IDE、不发真实请求。
const path = require('path')
const assert = require('assert')
const ROOT = path.join(__dirname, '..')

global.wx = { getStorageSync: () => null, setStorageSync: () => {}, removeStorageSync: () => {} }
const { mockRequest } = require(path.join(ROOT, 'mock/server.js'))
const d = require(path.join(ROOT, 'mock/data.js'))

// 演示账号（mock/data.js）：张伟=rg_yubei+平台ID、陈刚=rg_yubei+平台ID（候选）、
// 李强/李振国=rg_jiangbei+平台ID、INST_PM 王强=无平台ID（D4 门禁演示）
const ACC_ZHANG = '13800000001'
const ACC_CHEN = '13800000002'
const ACC_LI = '13800000004'
const ACC_NO_PLATFORM = '13800000013'
const POOL_ORDER = 'wo_pool_1' // 种子池工单：el_2、regionId=rg_yubei、未认领
// 签到坐标：el_2 档案坐标（mock 不做距离拦截，仅回显）
const GEO = { latitude: 29.719210, longitude: 106.633520, locationAccuracy: 10, photoFileId: 'mock_photo_pool' }

function req(method, url, data) { return mockRequest({ method, url, data }) }
async function login(account) { return req('POST', '/auth/login', { phone: account, password: '123456' }) }
async function expectCode(code, method, url, data) {
  try {
    const r = await req(method, url, data)
    throw new assert.AssertionError({ message: '期望 ' + code + '，实际成功返回 ' + JSON.stringify(r).slice(0, 120) })
  } catch (e) {
    if (e instanceof assert.AssertionError) throw e
    assert.strictEqual(e && e.code, code, '期望 code=' + code + '，实际 ' + JSON.stringify(e))
    return e
  }
}

const results = []
async function check(name, fn) {
  try { await fn(); results.push({ name, ok: true }) } catch (e) { results.push({ name, ok: false, err: e.message }) }
}

;(async () => {
  // ── A. 池可见性（M9/M11）：同区域+持平台ID 才见池工单 ──
  await login(ACC_ZHANG)
  await check('A1 张伟（rg_yubei）可见同区域未认领池工单，不可见跨区池工单', async () => {
    const r = await req('GET', '/work-orders', { page: 1, size: 50 })
    const ids = (r.list || []).map((o) => o.id)
    assert(ids.indexOf(POOL_ORDER) > -1, '同区域池工单不可见: ' + JSON.stringify(ids))
    const crossRegion = (r.list || []).filter((o) => o.regionId === 'rg_jiangbei' && o.status === 'PENDING' && !o.workerPlatformId)
    assert.strictEqual(crossRegion.length, 0, '跨区（rg_jiangbei）池工单泄漏')
    const pool = (r.list || []).find((o) => o.id === POOL_ORDER)
    assert.strictEqual(pool.workerName, '', '池工单 workerName 应为空')
    assert.strictEqual(pool.workerPlatformId, '', '池工单 workerPlatformId 应为空')
    assert.strictEqual(pool.regionId, 'rg_yubei', '池工单缺 regionId 快照')
  })
  await check('A2 无平台ID账号（INST_PM）：池谓词 fail-closed，工单列表为空', async () => {
    await login(ACC_NO_PLATFORM)
    const r = await req('GET', '/work-orders', { page: 1, size: 50 })
    assert.strictEqual(r.total, 0, '无平台ID安装岗不应见任何工单: ' + JSON.stringify(r.list && r.list.map((o) => o.id)))
  })

  // ── B. 认领（M1/D4）：签到即认领 / 落败 1003 / 无平台ID 422 ──
  await check('B1 认领前 partner-candidates 仅主维保本人可调（1403）', async () => {
    await login(ACC_ZHANG)
    await expectCode(1403, 'GET', '/work-orders/' + POOL_ORDER + '/partner-candidates')
  })
  await check('B2 张伟签到未认领池工单 → 原子认领成功（worker*=张伟、PROCESSING、claimedViaPool 打标）', async () => {
    await login(ACC_ZHANG)
    const r = await req('POST', '/work-orders/' + POOL_ORDER + '/checkin',
      Object.assign({ role: 'PRINCIPAL' }, GEO))
    assert.strictEqual(r.passed, true)
    const o = d.db.orders.find((x) => x.id === POOL_ORDER)
    assert.strictEqual(o.workerName, '张伟')
    assert.strictEqual(o.workerPlatformId, '6901282369105174537')
    assert.strictEqual(o.status, 'PROCESSING')
    assert(o.checkinExtra && o.checkinExtra.claimedViaPool === true, '缺 claimedViaPool 打标（D8 门禁信号）')
  })
  await check('B3 李强再签到同一单 → 1003 并回显认领人「张伟」（落败零副作用）', async () => {
    await login(ACC_LI)
    const e = await expectCode(1003, 'POST', '/work-orders/' + POOL_ORDER + '/checkin',
      Object.assign({ role: 'PRINCIPAL' }, GEO))
    assert((e.message || '').indexOf('张伟') > -1, '落败文案未回显认领人: ' + e.message)
    const o = d.db.orders.find((x) => x.id === POOL_ORDER)
    assert(!o.checkinExtra || o.checkinExtra.latitude !== GEO.latitude || o.workerName === '张伟', '落败不应改写留痕')
    const r = await req('GET', '/work-orders', { page: 1, size: 50 })
    assert((r.list || []).every((x) => x.id !== POOL_ORDER), '落败者列表仍可见他区域已认领工单')
  })
  await check('B4 无平台ID签到未认领池工单 → 422「请先在管理端完成平台人员登记」（零副作用）', async () => {
    // 独立池工单，避免污染主线
    d.db.orders.push({ id: 'wo_pool_np', orderNo: 'WO-NP', elevatorId: 'el_1', workType: '半月维保', workTypeCode: 'HM',
      planTime: '2026-11-21 09:00:00', status: 'PENDING', workerName: '', assistantName: '', workerPlatformId: '',
      assistantPlatformId: '', regionId: 'rg_yubei', checkinTime: '', checkoutTime: '', duration: '',
      originalRecordId: '', reportStatus: '', checklist: [] })
    await login(ACC_NO_PLATFORM)
    const e = await expectCode(422, 'POST', '/work-orders/wo_pool_np/checkin',
      Object.assign({ role: 'PRINCIPAL' }, GEO))
    assert((e.message || '').indexOf('平台人员登记') > -1, '文案不符: ' + e.message)
    const o = d.db.orders.find((x) => x.id === 'wo_pool_np')
    assert.strictEqual(o.workerName, '', '422 后仍被认领（有副作用）')
    assert.strictEqual(o.status, 'PENDING')
  })

  // ── C. 配合人候选与指派（D3/D4/M10）──
  await check('C1 候选=同区域+持平台ID+排除本人（含 陈刚；不含 张伟/李强/李振国）', async () => {
    await login(ACC_ZHANG)
    const r = await req('GET', '/work-orders/' + POOL_ORDER + '/partner-candidates')
    const ids = (r.list || []).map((c) => c.id)
    assert(ids.indexOf('emp_2') > -1, '同区域陈刚不在候选: ' + JSON.stringify(ids))
    assert(ids.indexOf('emp_1') < 0 && ids.indexOf('emp_lzg') < 0, '主维保本人（张伟/同平台ID李振国）未排除')
    assert(ids.indexOf('emp_3') < 0, '跨区李强混入候选')
    assert(ids.every((id) => id !== 'emp_wq'), '无平台ID员工混入候选')
    r.list.forEach((c) => assert(c.id && c.name && c.platformId && c.phone, '候选行缺字段: ' + JSON.stringify(c)))
  })
  await check('C2 非主维保（陈刚）查候选/指派均 1403', async () => {
    await login(ACC_CHEN)
    await expectCode(1403, 'GET', '/work-orders/' + POOL_ORDER + '/partner-candidates')
    await expectCode(1403, 'POST', '/work-orders/' + POOL_ORDER + '/assign-partner', { employeeId: 'emp_2' })
  })
  await check('C3 指派跨区员工（李强）→ 422；指派同区陈刚 → ok 落 assistantName/PlatformId', async () => {
    await login(ACC_ZHANG)
    await expectCode(422, 'POST', '/work-orders/' + POOL_ORDER + '/assign-partner', { employeeId: 'emp_3' })
    const ok = await req('POST', '/work-orders/' + POOL_ORDER + '/assign-partner', { employeeId: 'emp_2' })
    assert.strictEqual(ok.ok, true)
    assert.strictEqual(ok.assistantName, '陈刚')
    const o = d.db.orders.find((x) => x.id === POOL_ORDER)
    assert.strictEqual(o.assistantName, '陈刚')
    assert.strictEqual(o.assistantPlatformId, '990002')
  })
  await check('C4 配合人未签到前可改选；签到留痕后改选 1003', async () => {
    await login(ACC_ZHANG)
    const again = await req('POST', '/work-orders/' + POOL_ORDER + '/assign-partner', { employeeId: 'emp_2' })
    assert.strictEqual(again.ok, true, '未签到前改选被拦')
    const o = d.db.orders.find((x) => x.id === POOL_ORDER)
    o.assistantCheckinExtra = { role: 'ASSISTANT', checkedAt: '2026-11-21 09:30:00' } // 模拟配合人已签到
    try {
      await expectCode(1003, 'POST', '/work-orders/' + POOL_ORDER + '/assign-partner', { employeeId: 'emp_2' })
    } finally {
      o.assistantCheckinExtra = null
    }
  })

  // ── D. 签退恒双人门禁（D8，池化新单 422 文案）──
  await check('D1 未选配合人签退 → 422「请选择配合人员」', async () => {
    // 李强认领 rg_jiangbei 池工单（el_4 自动派单件），不选配合人直接签退
    await login(ACC_LI)
    const r = await req('GET', '/work-orders', { page: 1, size: 50 })
    const pool = (r.list || []).find((o) => o.regionId === 'rg_jiangbei' && o.status === 'PENDING' && !o.workerPlatformId)
    assert(pool, '缺 rg_jiangbei 池工单（el_4 到期自动派单件）')
    const el = d.getElevator(pool.elevatorId) || {}
    await req('POST', '/work-orders/' + pool.id + '/checkin',
      { role: 'PRINCIPAL', latitude: el.lat || 29.588660, longitude: el.lng || 106.574210, locationAccuracy: 10, photoFileId: 'mock_photo_pool2' })
    const e = await expectCode(422, 'POST', '/work-orders/' + pool.id + '/checkout', {})
    assert((e.message || '').indexOf('请选择配合人员') > -1, '文案不符: ' + e.message)
    const o = d.db.orders.find((x) => x.id === pool.id)
    assert.strictEqual(o.status, 'PROCESSING', '422 后状态被改写')
    assert(!o.originalRecordId, '422 后生成了记录（有副作用）')
  })
  await check('D2 已选配合人但配合人未签到 → 422「配合人员未签到」；签到后门禁放行进入后续校验', async () => {
    await login(ACC_ZHANG)
    const e = await expectCode(422, 'POST', '/work-orders/' + POOL_ORDER + '/checkout', {})
    assert((e.message || '').indexOf('配合人员未签到') > -1, '文案不符: ' + e.message)
    const o = d.db.orders.find((x) => x.id === POOL_ORDER)
    o.assistantCheckinExtra = { role: 'ASSISTANT', checkedAt: '2026-11-21 09:30:00' } // 模拟配合人已签到
    try {
      const e2 = await expectCode(422, 'POST', '/work-orders/' + POOL_ORDER + '/checkout', {})
      assert((e2.message || '').indexOf('配合人员未签到') < 0, '配合人已签到仍被 D8 门禁拦截')
      assert((e2.message || '').indexOf('主维保签字') > -1, '应进入签字校验环节: ' + e2.message)
    } finally {
      o.assistantCheckinExtra = null
    }
  })

  const failed = results.filter((r) => !r.ok)
  results.forEach((r) => console.log((r.ok ? 'PASS' : 'FAIL') + '  ' + r.name + (r.ok ? '' : '  → ' + r.err)))
  console.log(failed.length ? failed.length + '/' + results.length + ' 失败' : '全部 ' + results.length + ' 项通过')
  process.exit(failed.length ? 1 : 0)
})().catch((e) => { console.error('脚本异常', e); process.exit(1) })
