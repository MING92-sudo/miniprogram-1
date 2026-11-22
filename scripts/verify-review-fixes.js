// 2026-11-16 审查修复回归断言（P0/P1/P2 前端项）：node scripts/verify-review-fixes.js
// 行为断言走 pkg-maintenance/utils/record-view 纯函数；结构断言锁定导出/入口/映射不再漂移。
const fs = require('fs')
const path = require('path')
const assert = require('assert')
const ROOT = path.join(__dirname, '..')
const C = require(path.join(ROOT, 'constants/index.js'))
const { ICONS } = require(path.join(ROOT, 'config/icons.js'))
const results = []
function check(name, fn) {
  try { fn(); results.push({ name, ok: true }) } catch (e) { results.push({ name, ok: false, err: e.message }) }
}
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')

// P0-1/2：HANDOVER_DOCS 必须导出（sign 页页面级求值、detail 页回退分支依赖）
check('P0-1 HANDOVER_DOCS 导出且结构完整', () => {
  assert(Array.isArray(C.HANDOVER_DOCS) && C.HANDOVER_DOCS.length === 3, 'HANDOVER_DOCS 应为 3 项数组')
  C.HANDOVER_DOCS.forEach((d) => assert(d.field && d.label, '缺 field/label'))
})

// P0-4：记录单结论/统计必须反映异常项（纯函数行为断言）
let rv
try { rv = require(path.join(ROOT, 'pkg-maintenance/utils/record-view.js')) } catch (e) { rv = null }
check('P0-4 pkg-maintenance/utils/record-view 模块存在', () => assert(rv, 'pkg-maintenance/utils/record-view.js 缺失'))
if (!rv) rv = { buildRecordSummary: () => assert(false, '模块缺失'), buildSignState: () => assert(false, '模块缺失') }
check('P0-4 全合格 → 结论合格、遗留 0', () => {
  const s = rv.buildRecordSummary([{ result: 'NORMAL', name: 'A', freqLabel: '半月保' }])
  assert.strictEqual(s.conclusion, C.RECORD_TEXT.CONCLUSION_OK)
  assert.deepStrictEqual(s.stats, { total: 1, ok: 1, fixed: 0, left: 0 })
})
check('P0-4 有异常 → 结论不得为合格、遗留=异常数', () => {
  const s = rv.buildRecordSummary([
    { result: 'NORMAL', name: 'A', freqLabel: '半月保' },
    { result: 'ABNORMAL', name: 'B', abnormalDesc: '松动', freqLabel: '半月保' }
  ])
  assert.notStrictEqual(s.conclusion, C.RECORD_TEXT.CONCLUSION_OK, '异常时结论不能显示合格')
  assert.strictEqual(s.stats.left, 1)
  assert.strictEqual(s.stats.fixed, 0, '无整改数据不得虚报已整改')
  assert(s.abnormalDesc.indexOf('B') > -1 && s.abnormalDesc.indexOf('松动') > -1)
})
check('P0-4 维保项按周期分组（不套用移交 G01—G13）', () => {
  const s = rv.buildRecordSummary([
    { result: 'NORMAL', freqLabel: '半月保' }, { result: 'NORMAL', freqLabel: '季度保' }
  ])
  assert.strictEqual(s.groups.length, 2)
  assert(s.groups.every((g) => !/^G\d\d$/.test(g.label)), '分组标签不得为移交项类码')
})

// P1-2：签字页状态投影（布尔口径，签后文本/主按钮随状态变化）
check('P1-2 未签 → 未签字文本 + 本机签字按钮', () => {
  const st = rv.buildSignState({ workerName: '张三', assistantName: '李四' }, { name: '张三' })
  assert.strictEqual(st.workerSignText, C.RECORD_TEXT.UNSIGNED)
  assert.strictEqual(st.mySigned, false)
  assert.strictEqual(st.bothSigned, false)
  assert.strictEqual(st.primaryText, C.RECORD_TEXT.SIGN_SELF)
})
check('P1-2 主检已签+配合已签 → 已签字文本 + 查看记录单', () => {
  const st = rv.buildSignState(
    { workerName: '张三', assistantName: '李四', workerSigned: true, assistantSigned: true },
    { name: '张三' })
  assert.strictEqual(st.workerSignText, C.RECORD_TEXT.SIGNED)
  assert.strictEqual(st.assistantSignText, C.RECORD_TEXT.SIGNED)
  assert.strictEqual(st.mySigned, true)
  assert.strictEqual(st.bothSigned, true)
  assert.strictEqual(st.primaryText, C.RECORD_TEXT.VIEW_RECORD)
})
check('P1-2 单人作业主检已签 → bothSigned 成立', () => {
  const st = rv.buildSignState({ workerName: '张三', workerSigned: true }, { name: '张三' })
  assert.strictEqual(st.isDual, false)
  assert.strictEqual(st.bothSigned, true)
})
check('P1-2 不在签字名单 → 主按钮禁用', () => {
  const st = rv.buildSignState({ workerName: '张三' }, { name: '王五' })
  assert.strictEqual(st.primaryDisabled, true)
})
check('P1-2 页面已接线纯函数', () => {
  assert(read('pkg-maintenance/pages/record-sign/index.js').indexOf('buildSignState') > -1)
  assert(read('pkg-maintenance/pages/record-detail/index.js').indexOf('buildRecordSummary') > -1)
})

// P1-1：内部审核状态与入口
check('P1-1 PENDING_INST_REVIEW 状态与中文文案', () => {
  assert.strictEqual(C.HANDOVER_STATUS.PENDING_INST_REVIEW, 'PENDING_INST_REVIEW')
  assert(C.HANDOVER_STATUS_TEXT.PENDING_INST_REVIEW && !/[A-Z_]{4,}/.test(C.HANDOVER_STATUS_TEXT.PENDING_INST_REVIEW), '须为中文文案')
})
check('P1-1 详情页有审核入口、列表筛选含审核态', () => {
  assert(read('pkg-handover/pages/detail/index.js').indexOf('goReview') > -1, 'detail 缺 goReview')
  assert(read('pkg-handover/pages/detail/index.wxml').indexOf('goReview') > -1, 'detail wxml 缺审核按钮')
  assert(read('pkg-handover/pages/list/index.js').indexOf('PENDING_INST_REVIEW') > -1, 'list 筛选缺审核态')
})

// P1-3/7：图标映射与菜单可达
check('P1-7 ICONS 含 bullhorn/calendar-check-r', () => {
  assert(ICONS.bullhorn && ICONS['calendar-check-r'], 'ICONS 缺映射')
})

// P1-4：资料提交恒走后端三平铺字段契约
check('P1-4 submitDocs 不再发 docs 数组分支', () => {
  const src = read('pkg-handover/pages/detail/index.js')
  assert(src.indexOf('payload.docs') < 0, 'payload.docs 与后端契约不符')
})

// P2：文案键与死函数
check('P2 RECORD_TEXT 签字/结论文案键齐全', () => {
  ;['SIGNED', 'UNSIGNED', 'SIGN_SELF', 'VIEW_RECORD', 'CONCLUSION_ABNORMAL'].forEach((k) => assert(C.RECORD_TEXT[k], '缺 ' + k))
})
check('P2 home.js 无死函数 dayPlus', () => assert(read('services/home.js').indexOf('dayPlus') < 0))

const failed = results.filter((r) => !r.ok)
results.forEach((r) => console.log((r.ok ? 'PASS' : 'FAIL') + '  ' + r.name + (r.ok ? '' : '  → ' + r.err)))
console.log(failed.length ? failed.length + '/' + results.length + ' 失败' : '全部 ' + results.length + ' 项通过')
process.exit(failed.length ? 1 : 0)