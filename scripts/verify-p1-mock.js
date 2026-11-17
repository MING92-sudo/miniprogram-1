// P1 T7 mock 客户路由契约断言：node scripts/verify-p1-mock.js
// 逐条对齐后端 P1 门禁/白名单（UnitRecordService.toClientMap/requireSignable/requireViewable、
// RecordLifecycleService.refuseByToken、UnitPortalService 本单位谓词、RoleCatalog.isCustomerPrincipal）。
// 直接驱动 mock/server.js（Node 内 require，wx 桩），断言响应体与错误码；不启 IDE、不发真实请求。
const path = require('path')
const assert = require('assert')
const ROOT = path.join(__dirname, '..')

// mock/server.js 模块加载即读 storage（已 try/catch），此处给最小 wx 桩
global.wx = {
  getStorageSync: () => null,
  setStorageSync: () => {},
  removeStorageSync: () => {}
}
const { mockRequest } = require(path.join(ROOT, 'mock/server.js'))
const d = require(path.join(ROOT, 'mock/data.js'))

// 演示账号（mock/data.js employees）：客户 = 张伟（uu_1）、员工 = 李振国、旧码客户 = 王芳（UNIT_ADMIN）
const CLIENT_ACCOUNT = '13800000012'
const TECH_ACCOUNT = '13800000011'
const LEGACY_UNIT_ACCOUNT = '13800000003'
const OWN_RECORD = 'ur_1' // EM-2024-001 → el_1 → uu_1（本单位，可签）
const OTHER_RECORD = 'ur_2' // EM-2024-003 → el_3 → uu_2（跨单位）
const OWN_TOKEN = 'sg-mock-1'

// 客户路径禁返字段（后端 toClientMap「明确不返回」+ satisfaction，docs/14 §4.7）
const FORBIDDEN = ['context', 'previewContext', 'reportPayload', 'shareToken', 'originalRecordId',
  'workerPlatformId', 'assistantPlatformId', 'refuseReason', 'reportStatus', 'uploadStatus',
  'satisfaction', 'retryCount']

function req(method, url, data) {
  return mockRequest({ method, url, data })
}
async function login(account) {
  const r = await req('POST', '/auth/login', { phone: account, password: '123456' })
  return r
}
/** 断言请求以指定业务码失败（mock 与后端同口径：throw {code,message}） */
async function expectCode(code, method, url, data) {
  try {
    const r = await req(method, url, data)
    throw new assert.AssertionError({ message: '期望 ' + code + '，实际成功返回 ' + JSON.stringify(r).slice(0, 120) })
  } catch (e) {
    if (e instanceof assert.AssertionError) throw e
    assert.strictEqual(e && e.code, code, '期望 code=' + code + '，实际 ' + JSON.stringify(e))
  }
}
// except：裁决明文允许的兼容期例外字段（仅 T7-C2 的 pending-signs.shareToken 一处使用）
function noForbidden(obj, label, except) {
  FORBIDDEN.filter((k) => (except || []).indexOf(k) < 0)
    .forEach((k) => assert(!Object.prototype.hasOwnProperty.call(obj, k), (label || '响应') + ' 含禁返字段 ' + k))
}
/** 临时改写记录字段跑断言，跑完还原（mock 数据为进程内共享单例） */
async function withRecord(id, patch, fn) {
  const r = d.db.unitRecords.find((x) => x.id === id)
  assert(r, '种子记录缺失: ' + id)
  const saved = {}
  Object.keys(patch).forEach((k) => { saved[k] = r[k] })
  Object.assign(r, patch)
  try { return await fn(r) } finally { Object.keys(saved).forEach((k) => { r[k] = saved[k] }) }
}

const results = []
async function check(name, fn) {
  try { await fn(); results.push({ name, ok: true }) } catch (e) { results.push({ name, ok: false, err: e.message }) }
}

;(async () => {
  // ── A. loginPayload.principalType（T1 RoleCatalog.isCustomerPrincipal 口径）+ 别名表引用根 ALIAS ──
  await check('A1 客户登录 principalType=CUSTOMER', async () => {
    const p = await login(CLIENT_ACCOUNT)
    assert.strictEqual(p.principalType, 'CUSTOMER', JSON.stringify({ pt: p.principalType, roles: p.roles }))
    assert(p.roles.indexOf('CLIENT_ADMIN') > -1)
  })
  await check('A2 员工登录 principalType=EMPLOYEE', async () => {
    const p = await login(TECH_ACCOUNT)
    assert.strictEqual(p.principalType, 'EMPLOYEE')
  })
  await check('A3 旧码 UNIT_ADMIN 归一 CLIENT_ADMIN → CUSTOMER（别名走根 ALIAS）', async () => {
    const p = await login(LEGACY_UNIT_ACCOUNT)
    assert.deepStrictEqual(p.roles, ['CLIENT_ADMIN'], JSON.stringify(p.roles))
    assert.strictEqual(p.principalType, 'CUSTOMER')
  })
  await check('A4 旧码 WORKER 归一 MAINT_TECH → EMPLOYEE（canonMap 不再手写第三份）', async () => {
    const p = await login('13800000001')
    assert.deepStrictEqual(p.roles, ['MAINT_TECH'], JSON.stringify(p.roles))
    assert.strictEqual(p.principalType, 'EMPLOYEE')
    const src = require('fs').readFileSync(path.join(ROOT, 'mock/server.js'), 'utf8')
    assert(src.indexOf('WORKER: \'MAINT_TECH\'') < 0, 'mock 仍手写别名表（应引用 constants/permission-matrix ALIAS）')
  })
  await check('A5 登录体不含 password', async () => {
    const p = await login(CLIENT_ACCOUNT)
    assert(!Object.prototype.hasOwnProperty.call(p.userInfo, 'password'))
  })

  // ── B. 记录详情：客户白名单 + 归属/可见性门禁 ──
  await login(CLIENT_ACCOUNT)
  await check('B1 客户读本单位记录不含禁返字段', async () => {
    const r = await req('GET', '/unit/records/' + OWN_RECORD)
    noForbidden(r, 'GET /unit/records/:id')
    assert.strictEqual(r.id, OWN_RECORD)
    assert(r.lifecycleStatus, '缺 lifecycleStatus')
    assert(r.version != null, '缺 version')
    assert(Array.isArray(r.items), '缺 items')
  })
  await check('B2 客户读跨单位记录 → 1403', async () => {
    await expectCode(1403, 'GET', '/unit/records/' + OTHER_RECORD)
  })
  await check('B3 客户读不可见状态（PENDING_CLIENT_SIGN）→ 422', async () => {
    await withRecord(OWN_RECORD, { lifecycleStatus: 'PENDING_CLIENT_SIGN' },
      () => expectCode(422, 'GET', '/unit/records/' + OWN_RECORD))
  })
  await check('B4 客户读 PENDING_REVIEW/NULL → 422（历史行不外显）', async () => {
    await withRecord(OWN_RECORD, { lifecycleStatus: 'PENDING_REVIEW' },
      () => expectCode(422, 'GET', '/unit/records/' + OWN_RECORD))
    await withRecord(OWN_RECORD, { lifecycleStatus: null },
      () => expectCode(422, 'GET', '/unit/records/' + OWN_RECORD))
  })
  await check('B5 客户读旧版本 → 422（存在 prevVersionId=本记录 的后续版本）', async () => {
    d.db.unitRecords.push({ id: 'ur_tmp_v2', prevVersionId: OWN_RECORD, elevatorCode: 'EM-2024-001', version: 2 })
    try { await expectCode(422, 'GET', '/unit/records/' + OWN_RECORD) } finally {
      d.db.unitRecords = d.db.unitRecords.filter((x) => x.id !== 'ur_tmp_v2')
    }
  })
  // C-24：员工读记录详情走内部端点 GET /records/:id（后端 RecordController 同口径：
  // record:view + V7 班组谓词 + 内部全量投影）；/unit/records/:id 还原客户专用契约（后端 getAs）
  await check('B7 员工读记录详情走内部端点保留内部投影（C-24 GET /records/:id）', async () => {
    await login(TECH_ACCOUNT)
    const r = await req('GET', '/records/' + OWN_RECORD)
    assert(r.reportPayload, '员工投影缺 reportPayload（内部全量投影）')
    assert(r.shareToken, '员工投影缺 shareToken（发起签署/分享链接依赖）')
    assert((r.items || []).some((i) => i.abnormalDesc || i.freqLabel), '员工投影 items 明细被裁剪')
    assert(r.lifecycleStatus, '员工投影缺 lifecycleStatus（record-detail 生命周期状态条依赖）')
    assert(r.version != null, '员工投影缺 version（record-detail 版本核对依赖）')
  })
  await check('B8 内部端点班组谓词镜像：他班组记录 1403；/unit/records/:id 员工不可达（客户专用）', async () => {
    await login(TECH_ACCOUNT)
    // 李振国（MAINT_TECH，platformId=张伟）：把记录主/配合人员改成他人 → 越权 1403 无副作用
    await withRecord(OWN_RECORD, { workerPlatformId: 'P_OTHER', assistantPlatformId: 'P_OTHER2' },
      () => expectCode(1403, 'GET', '/records/' + OWN_RECORD))
    // 门户端点客户专用（后端 getAs requireClient）：员工令牌 1403
    await expectCode(1403, 'GET', '/unit/records/' + OWN_RECORD)
  })
  await login(CLIENT_ACCOUNT)
  await check('B6 只读可见集合 SIGNED/ARCHIVED/REFUSED_RETURNED 可查看但不可签', async () => {
    for (const lc of ['SIGNED', 'ARCHIVED', 'REFUSED_RETURNED']) {
      await withRecord(OWN_RECORD, { lifecycleStatus: lc }, async () => {
        const r = await req('GET', '/unit/records/' + OWN_RECORD)
        noForbidden(r, lc)
        assert.strictEqual(r.lifecycleStatus, lc)
        await expectCode(422, 'POST', '/unit/records/' + OWN_RECORD + '/confirm', { signatureUrl: 'https://mock/s.png' })
      })
    }
  })

  // ── C. 签字（会话路径）：门禁链 + 先签为准，无 already 幂等体，不收 satisfaction ──
  await check('C1 非待签状态签字 → 422 且无副作用', async () => {
    await withRecord(OWN_RECORD, { lifecycleStatus: 'PENDING_CLIENT_SIGN' }, async (r) => {
      await expectCode(422, 'POST', '/unit/records/' + OWN_RECORD + '/confirm', { signatureUrl: 'https://mock/s.png' })
      assert.strictEqual(r.confirmStatus, 'PENDING', '状态被改写')
      assert(!r.signatureUrl, '签字字段被改写')
    })
  })
  await check('C2 旧版本签字 → 422', async () => {
    d.db.unitRecords.push({ id: 'ur_tmp_v2', prevVersionId: OWN_RECORD, elevatorCode: 'EM-2024-001', version: 2 })
    try { await expectCode(422, 'POST', '/unit/records/' + OWN_RECORD + '/confirm', { signatureUrl: 'https://mock/s.png' }) } finally {
      d.db.unitRecords = d.db.unitRecords.filter((x) => x.id !== 'ur_tmp_v2')
    }
  })
  await check('C3 缺签名 → 422', async () => {
    await expectCode(422, 'POST', '/unit/records/' + OWN_RECORD + '/confirm', {})
  })
  await check('C4 跨单位签字 → 1403', async () => {
    await expectCode(1403, 'POST', '/unit/records/' + OTHER_RECORD + '/confirm', { signatureUrl: 'https://mock/s.png' })
  })
  await check('C5 签字成功 → 重复签字 422（先签为准，无 already 幂等体，不写 satisfaction）', async () => {
    await withRecord(OWN_RECORD, { lifecycleStatus: 'PENDING_CLIENT_SIGNATURE', confirmStatus: 'PENDING', signatureUrl: '' }, async (r) => {
      const ok = await req('POST', '/unit/records/' + OWN_RECORD + '/confirm', { signatureUrl: 'https://mock/s.png', satisfaction: 5 })
      assert.strictEqual(ok.ok, true)
      assert(!Object.prototype.hasOwnProperty.call(ok, 'already'), '不应返回 already 幂等体')
      assert.strictEqual(r.confirmStatus, 'CONFIRMED')
      assert.strictEqual(r.lifecycleStatus, 'SIGNED', '签字后应进入 SIGNED')
      assert.strictEqual(r.satisfaction, null, 'satisfaction 不应被签字端点写入')
      const again = await req('POST', '/unit/records/' + OWN_RECORD + '/confirm', { signatureUrl: 'https://mock/other.png' })
        .then(() => null).catch((e) => e)
      assert(again && again.code === 422, '重复签字应 422，实际 ' + JSON.stringify(again))
      assert(!again.already, '重复签字不应回 already')
      assert.strictEqual(r.signatureUrl, 'https://mock/s.png', '首签被覆盖')
    })
  })
  await check('C6 员工会话读门户 pending → 1403（仅使用单位可访问）', async () => {
    await login(TECH_ACCOUNT)
    await expectCode(1403, 'GET', '/unit/records/pending')
  })

  // ── D. 待签列表 / 门户列表：本单位 + 可签集合 + 最新版本 ──
  await login(CLIENT_ACCOUNT)
  await check('D1 /unit/records/pending 仅本单位可签记录且走白名单', async () => {
    const res = await req('GET', '/unit/records/pending')
    const list = res.list || []
    assert(list.length > 0, '本单位待签列表为空（种子 ur_1 应可签）')
    list.forEach((r) => {
      noForbidden(r, 'pending 行')
      assert(!Object.prototype.hasOwnProperty.call(r, 'shareToken'), 'records/pending 不应下发签字凭证')
      assert.strictEqual(r.lifecycleStatus, 'PENDING_CLIENT_SIGNATURE')
      assert.strictEqual(r.confirmStatus, 'PENDING')
      assert.strictEqual(r.id, OWN_RECORD, '混入非本单位/非链末端记录: ' + r.id)
    })
  })
  // 裁决 T7-C2（docs/14 §4.6 规则 8 兼容期）：待办行保留 shareToken 维持「去签字」链路；
  // 其余客户投影（records/list、records/pending、详情）一律不含签字凭证
  await check('D2 /unit/pending-signs 按可签集合 + 本单位，兼容期含 shareToken', async () => {
    const list = await req('GET', '/unit/pending-signs')
    assert(Array.isArray(list) && list.length === 1, '应仅 ur_1，实际 ' + JSON.stringify(list))
    assert.strictEqual(list[0].id, OWN_RECORD)
    assert.strictEqual(list[0].lifecycleStatus, 'PENDING_CLIENT_SIGNATURE')
    assert(list[0].version != null, '缺 version')
    const seed = d.db.unitRecords.find((x) => x.id === OWN_RECORD)
    assert.strictEqual(list[0].shareToken, seed.shareToken, '待办行缺 shareToken（客户签字链路断裂）')
    noForbidden(list[0], 'pending-signs 行', ['shareToken']) // 仅此一处按裁决 T7-C2 放行
  })
  await check('D3 /unit/records/list 按可见集合 + 本单位，且不含 shareToken', async () => {
    const list = await req('GET', '/unit/records/list')
    assert(Array.isArray(list), 'list 应为数组')
    assert(list.every((r) => r.id !== OTHER_RECORD), '跨单位记录泄漏')
    list.forEach((r) => {
      noForbidden(r, 'records/list 行')
      assert(!Object.prototype.hasOwnProperty.call(r, 'shareToken'), 'records/list 不应下发签字凭证')
      assert(['PENDING_CLIENT_SIGNATURE', 'SIGNED', 'ARCHIVED', 'REFUSED_RETURNED'].indexOf(r.lifecycleStatus) > -1,
        '不可见状态外显: ' + r.lifecycleStatus)
    })
  })
  await check('D4 已签记录不再出现在待签列表', async () => {
    await withRecord(OWN_RECORD, { lifecycleStatus: 'SIGNED', confirmStatus: 'CONFIRMED' }, async () => {
      const res = await req('GET', '/unit/records/pending')
      assert.strictEqual((res.list || []).length, 0)
      const signs = await req('GET', '/unit/pending-signs')
      assert.strictEqual(signs.length, 0)
    })
  })

  // ── E. 门户数据域：电梯/急修按会话锚点本单位收敛（非 useUnits[0]/全量） ──
  await check('E1 /unit/elevators 仅本单位电梯，下次维保/检验日期非空', async () => {
    const list = await req('GET', '/unit/elevators')
    const codes = list.map((e) => e.elevatorCode)
    assert(codes.indexOf('EM-2024-001') > -1 && codes.indexOf('EM-2024-002') > -1, JSON.stringify(codes))
    assert(codes.indexOf('EM-2024-003') < 0 && codes.indexOf('EM-2024-004') < 0, '跨单位电梯泄漏: ' + JSON.stringify(codes))
    // 投影字段不得恒空（客户工作台「下次维保」依赖）
    assert(/^\d{4}-\d{2}-\d{2}$/.test(list[0].nextMaintenanceDate || ''), 'nextMaintenanceDate 空: ' + JSON.stringify(list[0]))
    assert(/^\d{4}-\d{2}-\d{2}$/.test(list[0].nextCheckDate || ''), 'nextCheckDate 空')
  })
  await check('E4 POST /unit/faults 报修门禁：缺电梯/缺描述 422、跨单位 1403 且不落单', async () => {
    await login(CLIENT_ACCOUNT)
    const before = d.db.faults.length
    await expectCode(422, 'POST', '/unit/faults', { desc: '异响' })
    await expectCode(422, 'POST', '/unit/faults', { elevatorCode: 'EM-2024-001' })
    await expectCode(1403, 'POST', '/unit/faults', { elevatorCode: 'EM-2024-003', desc: '跨单位报修' })
    assert.strictEqual(d.db.faults.length, before, '越权/校验失败仍落单（有副作用）')
    const created = await req('POST', '/unit/faults', { elevatorCode: 'EM-2024-001', desc: '本单位报修（断言用）' })
    assert(created && created.id, '本单位报修未建单')
    d.db.faults = d.db.faults.filter((f) => f.id !== created.id) // 清理断言数据
    assert.strictEqual(d.db.faults.length, before)
  })
  await check('E2 /unit/faults 仅本单位电梯急修单', async () => {
    d.db.faults.push({ id: 'ft_tmp_other', elevatorCode: 'EM-2024-003', desc: '跨单位急修', status: 'OPEN', createdAt: '2026-11-16 10:00:00' })
    try {
      const list = await req('GET', '/unit/faults')
      assert(list.every((f) => f.id !== 'ft_tmp_other'), '跨单位急修单泄漏')
      assert(list.length > 0, '本单位急修单为空')
    } finally {
      d.db.faults = d.db.faults.filter((f) => f.id !== 'ft_tmp_other')
    }
  })
  await check('E3 员工会话访问门户数据域 → 403（后端 UnitPortalController.unitId 同口径）', async () => {
    await login(TECH_ACCOUNT)
    await expectCode(403, 'GET', '/unit/elevators')
    await expectCode(403, 'GET', '/unit/faults')
  })

  // ── F. sign-view / confirm-by-token / refuse-by-token（token 免登录链路） ──
  await login(CLIENT_ACCOUNT)
  await check('F1 sign-view 含 recordVersion/hashPrefix，不含 satisfaction', async () => {
    const v = await req('GET', '/unit/records/' + OWN_RECORD + '/sign-view?token=' + OWN_TOKEN)
    assert(v.recordVersion, '缺 recordVersion')
    assert(v.hashPrefix, '缺 hashPrefix')
    assert(!Object.prototype.hasOwnProperty.call(v, 'satisfaction'), 'sign-view 不应返回 satisfaction')
    assert(!Object.prototype.hasOwnProperty.call(v, 'shareToken'), 'sign-view 不应回显 shareToken')
  })
  await check('F2 sign-view token 不符 → 401', async () => {
    await expectCode(401, 'GET', '/unit/records/' + OWN_RECORD + '/sign-view?token=bad')
    await expectCode(401, 'GET', '/unit/records/' + OWN_RECORD + '/sign-view')
  })
  await check('F3 confirm-by-token token 401 先于状态披露', async () => {
    await withRecord(OWN_RECORD, { lifecycleStatus: 'SIGNED', confirmStatus: 'CONFIRMED' },
      () => expectCode(401, 'POST', '/unit/records/' + OWN_RECORD + '/confirm-by-token?token=bad', { signatureUrl: 'https://mock/s.png' }))
  })
  await check('F4 confirm-by-token 非待签 → 422（不再是无门禁 stub）', async () => {
    await withRecord(OWN_RECORD, { lifecycleStatus: 'PENDING_CLIENT_SIGN', confirmStatus: 'PENDING' },
      () => expectCode(422, 'POST', '/unit/records/' + OWN_RECORD + '/confirm-by-token?token=' + OWN_TOKEN, { signatureUrl: 'https://mock/s.png' }))
  })
  await check('F5 confirm-by-token 旧版本 → 422', async () => {
    d.db.unitRecords.push({ id: 'ur_tmp_v2', prevVersionId: OWN_RECORD, elevatorCode: 'EM-2024-001', version: 2 })
    try {
      await expectCode(422, 'POST', '/unit/records/' + OWN_RECORD + '/confirm-by-token?token=' + OWN_TOKEN, { signatureUrl: 'https://mock/s.png' })
    } finally { d.db.unitRecords = d.db.unitRecords.filter((x) => x.id !== 'ur_tmp_v2') }
  })
  await check('F6 confirm-by-token 缺签名 → 422', async () => {
    await expectCode(422, 'POST', '/unit/records/' + OWN_RECORD + '/confirm-by-token?token=' + OWN_TOKEN, {})
  })
  await check('F7 confirm-by-token 成功不写 satisfaction，重复签字 422 且无 already', async () => {
    await withRecord(OWN_RECORD, { lifecycleStatus: 'PENDING_CLIENT_SIGNATURE', confirmStatus: 'PENDING', signatureUrl: '' }, async (r) => {
      const ok = await req('POST', '/unit/records/' + OWN_RECORD + '/confirm-by-token?token=' + OWN_TOKEN,
        { signatureUrl: 'https://mock/s.png', satisfaction: 5 })
      assert.strictEqual(ok.ok, true)
      assert(!Object.prototype.hasOwnProperty.call(ok, 'already'), '不应返回 already 幂等体')
      assert.strictEqual(r.satisfaction, null, 'satisfaction 不应被签字端点写入')
      assert.strictEqual(r.lifecycleStatus, 'SIGNED')
      await expectCode(422, 'POST', '/unit/records/' + OWN_RECORD + '/confirm-by-token?token=' + OWN_TOKEN, { signatureUrl: 'https://mock/x.png' })
      assert.strictEqual(r.signatureUrl, 'https://mock/s.png', '首签被覆盖')
    })
  })
  await check('F8 refuse-by-token token 不符 → 401（不再是 ()=>({ok:true}) stub）', async () => {
    await expectCode(401, 'POST', '/unit/records/' + OWN_RECORD + '/refuse-by-token?token=bad', { reason: '数据不符' })
    await expectCode(401, 'POST', '/unit/records/' + OWN_RECORD + '/refuse-by-token', { reason: '数据不符' })
  })
  await check('F9 refuse-by-token reason 空 → 422', async () => {
    await expectCode(422, 'POST', '/unit/records/' + OWN_RECORD + '/refuse-by-token?token=' + OWN_TOKEN, { reason: '  ' })
    await expectCode(422, 'POST', '/unit/records/' + OWN_RECORD + '/refuse-by-token?token=' + OWN_TOKEN, {})
  })
  await check('F10 refuse-by-token 非待签 → 422', async () => {
    await withRecord(OWN_RECORD, { lifecycleStatus: 'PENDING_CLIENT_SIGN' },
      () => expectCode(422, 'POST', '/unit/records/' + OWN_RECORD + '/refuse-by-token?token=' + OWN_TOKEN, { reason: '数据不符' }))
    await withRecord(OWN_RECORD, { lifecycleStatus: 'PENDING_CLIENT_SIGNATURE', confirmStatus: 'CONFIRMED' },
      () => expectCode(422, 'POST', '/unit/records/' + OWN_RECORD + '/refuse-by-token?token=' + OWN_TOKEN, { reason: '数据不符' }))
  })
  await check('F11 refuse-by-token 成功 → REFUSED_RETURNED，客户只读可见且不可再签', async () => {
    await withRecord(OWN_RECORD, { lifecycleStatus: 'PENDING_CLIENT_SIGNATURE', confirmStatus: 'PENDING' }, async (r) => {
      try {
        const ok = await req('POST', '/unit/records/' + OWN_RECORD + '/refuse-by-token?token=' + OWN_TOKEN, { reason: '检查项照片缺失' })
        assert.strictEqual(ok.ok, true)
        assert.strictEqual(r.lifecycleStatus, 'REFUSED_RETURNED')
        assert.strictEqual(r.refuseReason, '检查项照片缺失')
        const view = await req('GET', '/unit/records/' + OWN_RECORD)
        noForbidden(view, '拒签后详情')
        assert(!Object.prototype.hasOwnProperty.call(view, 'refuseReason'), '内部拒签原因不得回显客户')
        await expectCode(422, 'POST', '/unit/records/' + OWN_RECORD + '/confirm-by-token?token=' + OWN_TOKEN, { signatureUrl: 'https://mock/s.png' })
      } finally {
        delete r.refuseReason // 拒签写入的内部字段不在 withRecord 还原范围，避免残留共享 db
      }
    })
    const seed = d.db.unitRecords.find((x) => x.id === OWN_RECORD)
    assert(!Object.prototype.hasOwnProperty.call(seed, 'refuseReason'), '断言后 refuseReason 残留')
  })

  // ── G. 菜单角标与 T6 保留项 ──
  await login(CLIENT_ACCOUNT)
  await check('G1 客户 badges.pendingSigns 按本单位可签口径（不计跨单位/不可签）', async () => {
    const m = await req('GET', '/me/menus')
    assert.strictEqual(m.badges.pendingSigns, 1, JSON.stringify(m.badges))
    await withRecord(OWN_RECORD, { lifecycleStatus: 'PENDING_CLIENT_SIGN' }, async () => {
      const m2 = await req('GET', '/me/menus')
      assert.strictEqual(m2.badges.pendingSigns, 0, '不可签状态仍计数: ' + JSON.stringify(m2.badges))
    })
  })
  await check('G2 T6 客户 quickActions 保留（todo/elevators，无 report/emergency）', async () => {
    const m = await req('GET', '/me/menus')
    const keys = (m.quickActions || []).map((q) => q.key)
    assert(keys.indexOf('todo') > -1 && keys.indexOf('elevators') > -1, JSON.stringify(keys))
    assert(keys.indexOf('report') < 0 && keys.indexOf('emergency') < 0, JSON.stringify(keys))
    assert(m.actions['client:sign'] === true, 'client:sign 应为 Y')
    assert(!m.actions['client:report'] && !m.actions['client:evaluate'], 'T2 收回的按钮码不应下发')
  })

  // ── H. 生命周期链路（签退 → 发起使用单位签字 → 客户可见可签）──
  const wo = d.db.orders.find((o) => o.id === 'wo_5')
  const woSaved = Object.assign({}, wo)
  let newRecordId = ''
  await check('H1 签退生成记录初值 PENDING_CLIENT_SIGN → 客户不可见（422）、不入待签列表', async () => {
    const rec = d.markCheckout('wo_5', { signatureUrl: 'https://mock/w.png' })
    assert(rec, 'markCheckout 未生成记录')
    newRecordId = rec.id
    assert.strictEqual(rec.lifecycleStatus, 'PENDING_CLIENT_SIGN', JSON.stringify(rec.lifecycleStatus))
    assert.strictEqual(rec.version, 1)
    assert.strictEqual(rec.prevVersionId, '')
    await login(CLIENT_ACCOUNT)
    await expectCode(422, 'GET', '/unit/records/' + rec.id)
    const signs = await req('GET', '/unit/pending-signs')
    assert(signs.every((x) => x.id !== rec.id), '未发起签署即进入客户待签列表')
  })
  await check('H2 发起使用单位签字 → PENDING_CLIENT_SIGNATURE + 回传真实 shareToken，客户随即可见可签', async () => {
    await login(TECH_ACCOUNT)
    const sr = await req('POST', '/work-orders/wo_5/sign-request', {}) // 工单 id 定位（小程序历史用法）
    assert.strictEqual(sr.ok, true)
    const rec = d.db.unitRecords.find((x) => x.id === newRecordId)
    assert.strictEqual(sr.shareToken, rec.shareToken, '未回传真实 shareToken（分享链接无法签字）')
    assert.strictEqual(rec.lifecycleStatus, 'PENDING_CLIENT_SIGNATURE')
    // 记录 id 定位（后端 RecordLifecycleService.signRequest 口径）：已推进 → 422
    await expectCode(422, 'POST', '/work-orders/' + newRecordId + '/sign-request', {})
    await login(CLIENT_ACCOUNT)
    const view = await req('GET', '/unit/records/' + newRecordId)
    noForbidden(view, '发起签署后客户详情')
    const signs = await req('GET', '/unit/pending-signs')
    assert(signs.some((x) => x.id === newRecordId), '发起签署后未进入待签列表')
    const m = await req('GET', '/me/menus')
    assert.strictEqual(m.badges.pendingSigns, 2, JSON.stringify(m.badges))
  })
  // 还原 H 组对 mock 单例数据的改写（脚本内其他断言不受影响，仍保持数据洁净）
  d.db.unitRecords = d.db.unitRecords.filter((x) => x.id !== newRecordId)
  Object.keys(woSaved).forEach((k) => { wo[k] = woSaved[k] })

  // ── I. fix round 1：门禁顺序 / 员工读门禁 / sign-request 角色 / 无锚点 fail-closed ──
  const INST_PM_ACCOUNT = '13800000013' // INST_PM：无 record:view
  const SUP_ACCOUNT = '13800000002' // LEADER → MAINT_SUP：可发起签署
  await check('I1 门禁顺序：无 record:view 的会话读记录（含不存在 id）一律 1403，不以 1404 泄漏存在性', async () => {
    await login(INST_PM_ACCOUNT)
    await expectCode(1403, 'GET', '/unit/records/' + OWN_RECORD)
    await expectCode(1403, 'GET', '/unit/records/不存在的记录id')
    // C-24 内部端点同口径：门禁最先，无 record:view（含不存在 id）1403 不泄漏存在性
    await expectCode(1403, 'GET', '/records/' + OWN_RECORD)
    await expectCode(1403, 'GET', '/records/不存在的记录id')
  })
  await check('I2 员工读记录详情须 record:view，且返回浅拷贝不外泄 db 单例（C-24 内部端点）', async () => {
    await login(TECH_ACCOUNT)
    const r1 = await req('GET', '/records/' + OWN_RECORD)
    assert.strictEqual(r1.id, OWN_RECORD)
    const seed = d.db.unitRecords.find((x) => x.id === OWN_RECORD)
    assert(r1 !== seed, '返回了 db 共享引用（调用方可改写演示数据）')
    r1.elevatorName = '被调用方改写'
    r1.confirmStatus = 'HACKED'
    const r2 = await req('GET', '/records/' + OWN_RECORD)
    assert.notStrictEqual(r2.elevatorName, '被调用方改写', '浅拷贝未生效')
    assert.strictEqual(seed.confirmStatus, 'PENDING', 'db 记录被外部改写: ' + seed.confirmStatus)
  })
  await check('I3 sign-request 角色门禁：客户/安装部 1403，MAINT_SUP 通过（后端 signRequest 同口径）', async () => {
    await login(CLIENT_ACCOUNT)
    await expectCode(1403, 'POST', '/work-orders/' + OWN_RECORD + '/sign-request', {})
    await login(INST_PM_ACCOUNT)
    await expectCode(1403, 'POST', '/work-orders/' + OWN_RECORD + '/sign-request', {})
    await login(SUP_ACCOUNT)
    // 角色门禁通过后才是记录定位：不存在的 id → 1404（证明未被 1403 拦）
    await expectCode(1404, 'POST', '/work-orders/不存在的id/sign-request', {})
  })
  await check('I4 客户角色无单位锚点 → fail-closed（门户 403 / 记录 1403 / 角标 0 / 电梯空）', async () => {
    d.db.employees.CLIENT_NOANCHOR = { id: 'emp_noanchor', name: '无锚点客户', phone: '13800000090',
      account: '13800000090', password: '123456', role: 'CLIENT_ADMIN', roles: ['CLIENT_ADMIN'],
      roleText: '使用单位管理员（缺 use_unit 锚点）', platformId: '' }
    try {
      const p = await login('13800000090')
      assert.strictEqual(p.principalType, 'CUSTOMER')
      await expectCode(403, 'GET', '/unit/elevators')
      await expectCode(403, 'GET', '/unit/faults')
      await expectCode(403, 'GET', '/unit/pending-signs')
      await expectCode(1403, 'GET', '/unit/records/pending')
      await expectCode(1403, 'GET', '/unit/records/' + OWN_RECORD)
      const m = await req('GET', '/me/menus')
      assert.strictEqual(m.badges.pendingSigns, 0, '无锚点仍计数: ' + JSON.stringify(m.badges))
      assert.deepStrictEqual(await req('GET', '/elevators'), [], '无锚点电梯列表应为空（fail-closed）')
    } finally {
      delete d.db.employees.CLIENT_NOANCHOR
    }
    await login(CLIENT_ACCOUNT)
  })

  const failed = results.filter((r) => !r.ok)
  results.forEach((r) => console.log((r.ok ? 'PASS' : 'FAIL') + '  ' + r.name + (r.ok ? '' : '  → ' + r.err)))
  console.log(failed.length ? failed.length + '/' + results.length + ' 失败' : '全部 ' + results.length + ' 项通过')
  process.exit(failed.length ? 1 : 0)
})().catch((e) => { console.error('脚本异常', e); process.exit(1) })
