// 维保记录签名确认 + 使用单位数据投影（V41 单账号单会话：一律员工令牌 em_token）。
// 2026-11-16 审查清理：unit_token 双令牌登录链已废除（saveUnitSession/unitLogin/bindUnitWechat/
// changeUnitPassword/unitLoginByWechat/uploadUnitPhoto 等删除）；clearUnitSession 仅用于清历史残留。
// 后端 /unit/login* 契约保留至生产清库（docs/12 G 组），前端不再提供入口。
const { get, post } = require('../utils/request')

// 记录完整视图（GET /unit/records/:id，客户门户契约，docs/03 V2.1 §3.3）
// 契约保留、暂无页面调用方：C-24 起员工记录详情走 order.getRecordDetail（GET /records/{id} 内部端点）；
// 本端点后端 getAs 仅客户可达（requireClient + toClientMap 白名单，员工令牌 1403）
function getRecord(id) {
  return get('/unit/records/' + id)
}

// 打开签名链接：校验令牌，返回记录摘要与确认状态（无需登录）
function getSignView(id, token) {
  return get('/unit/records/' + id + '/sign-view?token=' + token, { needAuth: false })
}

// 安全管理员签字确认（签名图 + 满意度）
function confirmByToken(id, token, data) {
  return post('/unit/records/' + id + '/confirm-by-token?token=' + token, data, { needAuth: false })
}

// M1：使用单位拒签（凭分享 token；docs/04 V2.53）。V2.80：refuseCode 分类码（R1—R5，
// R5 其他必须附说明；旧调用不传码时后端按兼容期「原因必填」回退）
function refuseRecordByToken(id, token, reason, refuseCode) {
  return post(`/unit/records/${id}/refuse-by-token?token=${encodeURIComponent(token)}`,
    { reason, refuseCode: refuseCode || '' }, { needAuth: false })
}

// V2.80 批量签字（客户令牌）：同一签名一次确认多条待签记录（≤20），
// 逐条独立门禁、失败不拖累其余 → {ok,signed,failed[{id,message}]}
function batchConfirmRecords(recordIds, data) {
  return post('/unit/records/batch-confirm', Object.assign({}, data, { recordIds }))
}

// ───────────── 客户门户消息（V2.80 签字临期/超期提醒；数据域=本单位锚点） ─────────────
function getUnitMessages() {
  return get('/unit/messages')
}

function getUnitUnreadCount() {
  return get('/unit/messages/unread-count')
}

function readUnitMessage(id) {
  return post('/unit/messages/' + id + '/read', {})
}

function readAllUnitMessages() {
  return post('/unit/messages/read-all', {})
}

// ───────────── 使用单位数据投影（docs/04 A.11；统一令牌 + CLIENT 锚点，后端 UnitPortalController） ─────────────
const UNIT_TOKEN_KEY = 'unit_token'
const UNIT_INFO_KEY = 'unit_info'

// 清历史门户会话残留（V41 前的本地存储；登录/冷启动调用，无写入方）
function clearUnitSession() {
  wx.removeStorageSync(UNIT_TOKEN_KEY)
  wx.removeStorageSync(UNIT_INFO_KEY)
}

// 本单位电梯
function getUnitElevators() {
  return get('/unit/elevators')
}

// 本单位维保记录（契约保留、暂无页面入口；员工记录详情 C-24 起走 order.getRecordDetail，GET /records/{id}）
function getUnitRecords() {
  return get('/unit/records/list')
}

// 本单位待确认签字的记录
function getUnitPendingSigns() {
  return get('/unit/pending-signs')
}

// 本单位急修单
function getUnitFaults() {
  return get('/unit/faults')
}

// 使用单位报修（契约保留、暂无页面入口；报修页走 fault.reportFault 员工链路）
function reportUnitFault(data) {
  return post('/unit/faults', data)
}

// ───────────── 维保日历（Task 11，员工令牌；只读投影不落库） ─────────────
// GET /maintenance-calendar?from=&to= → {from,to,entries:[{date,elevatorCode,elevatorName,projectName,workTypeCode,workType,anchorDate}]}
function getMaintenanceCalendar(params) {
  return get('/maintenance-calendar', params)
}

module.exports = {
  getRecord,
  getSignView,
  confirmByToken,
  refuseRecordByToken,
  batchConfirmRecords,
  getUnitMessages,
  getUnitUnreadCount,
  readUnitMessage,
  readAllUnitMessages,
  clearUnitSession,
  getUnitElevators,
  getUnitRecords,
  getUnitPendingSigns,
  getUnitFaults,
  reportUnitFault,
  getMaintenanceCalendar
}