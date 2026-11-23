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

const CLIENT_ACCOUNT = '13800000012' // 客户 = 张伟（uu_1）
const OWN_RECORD = 'ur_1' // EM-2024-001 → el_1 → uu_1（本单位，可签）
const ANCHOR_ORDER = 'wo_5' // DONE 工单（originalRecordId=19480012609000005），核对同步锚点

function req(method, url, data) {
  return mockRequest({ method, url, data })
}
async function login(account) {
  return req('POST', '/auth/login', { phone: account, password: '123456' })
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

// 注入核对链路记录（mock 数据为进程内共享单例，断言后统一清理）
const INJECTED_IDS = []
function injectRecord(id, reportStatus, extra) {
  const rec = Object.assign({
    id: id,
    elevatorName: '世纪大厦 1# 客梯',
    elevatorCode: 'EM-2024-001',
    workType: '半月维保',
    workTypeCode: 'HM',
    workerName: '张伟',
    reportStatus: reportStatus,
    retryCount: 0,
    confirmStatus: 'PENDING',
    lifecycleStatus: 'PENDING_CLIENT_SIGN',
    originalRecordId: 'rc-' + id
  }, extra || {})
  d.db.unitRecords.push(rec)
  INJECTED_IDS.push(id)
  return rec
}

;(async () => {
  try {
    await check('1 reconcile REPORTING→REPORTED：响应回执 + 工单锚点 reportStatus 同步', async () => {
      injectRecord('ur_rc_reporting', 'REPORTING', { originalRecordId: '19480012609000005' })
      const order = d.db.orders.find((o) => o.id === ANCHOR_ORDER)
      assert(order, '锚点工单缺失: ' + ANCHOR_ORDER)
      const savedStatus = order.reportStatus
      order.reportStatus = 'REPORTING'
      try {
        const r = await req('POST', '/platform/records/ur_rc_reporting/reconcile', { outcome: 'REPORTED', note: '平台控制台已核实' })
        assert.strictEqual(r.ok, true)
        assert.strictEqual(r.reportStatus, 'REPORTED', JSON.stringify(r))
        assert.strictEqual(order.reportStatus, 'REPORTED', '工单 reportStatus 未同步: ' + order.reportStatus)
      } finally {
        order.reportStatus = savedStatus
      }
    })
    await check('2 reconcile 非法 outcome（FOO）→ 422', async () => {
      await expectCode(422, 'POST', '/platform/records/ur_rc_reporting/reconcile', { outcome: 'FOO' })
    })
    await check('3 reconcile FAILED 状态记录 → 1003（FAILED 不属待核对集合）', async () => {
      injectRecord('ur_rc_failed', 'FAILED')
      await expectCode(1003, 'POST', '/platform/records/ur_rc_failed/reconcile', { outcome: 'REPORTED' })
    })
    await check('4 reconcile UNKNOWN→FAILED 后同记录 reupload → REPORTED（契约串联）', async () => {
      injectRecord('ur_rc_unknown', 'UNKNOWN')
      const r = await req('POST', '/platform/records/ur_rc_unknown/reconcile', { outcome: 'FAILED', note: '平台无此记录' })
      assert.strictEqual(r.reportStatus, 'FAILED', JSON.stringify(r))
      const up = await req('POST', '/platform/records/ur_rc_unknown/reupload')
      assert.strictEqual(up.ok, true)
      assert.strictEqual(up.reportStatus, 'REPORTED', JSON.stringify(up))
    })
    await check('5 /admin/records?reportStatus=REPORTING,UNKNOWN 逗号多值过滤出停留态记录', async () => {
      injectRecord('ur_rc_s_reporting', 'REPORTING')
      injectRecord('ur_rc_s_unknown', 'UNKNOWN')
      const res = await req('GET', '/admin/records', { reportStatus: 'REPORTING,UNKNOWN' })
      const ids = (res.list || []).map((r) => r.id)
      assert(ids.indexOf('ur_rc_s_reporting') > -1, '过滤结果缺 REPORTING 记录: ' + JSON.stringify(ids))
      assert(ids.indexOf('ur_rc_s_unknown') > -1, '过滤结果缺 UNKNOWN 记录: ' + JSON.stringify(ids))
      assert(ids.indexOf('ur_rc_failed') < 0, 'FAILED 记录混入多值过滤结果')
    })
    await check('6 /admin/records 行 uploadStatus 投影：REPORTING/REUPLOADING/LEGACY_UPLOADING → UNKNOWN', async () => {
      injectRecord('ur_rc_m_reuploading', 'REUPLOADING')
      injectRecord('ur_rc_m_legacy', 'LEGACY_UPLOADING')
      const res = await req('GET', '/admin/records', { reportStatus: 'REPORTING,REUPLOADING,LEGACY_UPLOADING', size: 100 })
      const byId = {}
      ;(res.list || []).forEach((r) => { byId[r.id] = r })
      ;['ur_rc_s_reporting', 'ur_rc_m_reuploading', 'ur_rc_m_legacy'].forEach((id) => {
        assert(byId[id], '过滤结果缺记录: ' + id)
        assert.strictEqual(byId[id].uploadStatus, 'UNKNOWN', id + ' uploadStatus=' + byId[id].uploadStatus)
      })
    })
    await login(CLIENT_ACCOUNT)
    await check('7 客户签字响应含 archived 键（mock 归档恒成功 → archived:true）', async () => {
      await withRecord(OWN_RECORD, { lifecycleStatus: 'PENDING_CLIENT_SIGNATURE', confirmStatus: 'PENDING', signatureUrl: '', signatureFileId: '' }, async () => {
        const ok = await req('POST', '/unit/records/' + OWN_RECORD + '/confirm', { signatureUrl: 'https://mock/s.png' })
        assert.strictEqual(ok.ok, true)
        assert(Object.prototype.hasOwnProperty.call(ok, 'archived'), '签字响应缺 archived 键')
        assert.strictEqual(ok.archived, true, 'archived=' + ok.archived)
      })
    })
    await check('8 constants 含 REPORT_STATUS_TEXT.UNKNOWN 与 REPORT_UNKNOWN_HINT（Task 5 落地后转绿）', async () => {
      const C = require(path.join(ROOT, 'constants/index.js'))
      assert(C.REPORT_STATUS_TEXT && Object.prototype.hasOwnProperty.call(C.REPORT_STATUS_TEXT, 'UNKNOWN'),
        '缺 REPORT_STATUS_TEXT.UNKNOWN')
      assert(typeof C.REPORT_UNKNOWN_HINT === 'string' && C.REPORT_UNKNOWN_HINT.length > 0, '缺 REPORT_UNKNOWN_HINT')
    })
    await check('9 /admin/records 行投影含 archiveFileId 键（待归档标签契约）', async () => {
      injectRecord('ur_rc_archive_key', 'REPORTED', { archiveFileId: 'file_mock_1' })
      injectRecord('ur_rc_archive_empty', 'FAILED', {})
      const res = await req('GET', '/admin/records', { reportStatus: 'REPORTED,FAILED', size: 100 })
      const byId = {}
      ;(res.list || []).forEach((r) => { byId[r.id] = r })
      ;['ur_rc_archive_key', 'ur_rc_archive_empty'].forEach((id) => {
        assert(byId[id], '过滤结果缺记录: ' + id)
        assert(Object.prototype.hasOwnProperty.call(byId[id], 'archiveFileId'), id + ' 行投影缺 archiveFileId 键')
      })
      assert.strictEqual(byId.ur_rc_archive_key.archiveFileId, 'file_mock_1')
      assert.strictEqual(byId.ur_rc_archive_empty.archiveFileId, '')
    })
  } finally {
    d.db.unitRecords = d.db.unitRecords.filter((r) => INJECTED_IDS.indexOf(r.id) < 0)
  }

  const failed = results.filter((r) => !r.ok)
  results.forEach((r) => console.log((r.ok ? 'PASS' : 'FAIL') + '  ' + r.name + (r.ok ? '' : '  → ' + r.err)))
  console.log(failed.length ? failed.length + '/' + results.length + ' 失败' : '全部 ' + results.length + ' 项通过')
  process.exit(failed.length ? 1 : 0)
})().catch((e) => { console.error('脚本异常', e); process.exit(1) })
