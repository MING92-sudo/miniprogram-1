const { get, post } = require('../utils/request')

// 本端点后端 getAs 仅客户可达（requireClient + toClientMap 白名单，员工令牌 1403）
function getRecord(id) {
  return get('/unit/records/' + id)
}

// 打开签名链接：校验令牌，返回记录摘要与确认状态（无需登录）；token 走 X-Share-Token 头
function getSignView(id, token) {
  return get('/unit/records/' + id + '/sign-view', undefined, { needAuth: false, header: { 'X-Share-Token': token } })
}

// 安全管理员签字确认（签名图 + 满意度）
function confirmByToken(id, token, data) {
  return post('/unit/records/' + id + '/confirm-by-token', data, { needAuth: false, header: { 'X-Share-Token': token } })
}

function refuseRecordByToken(id, token, reason, refuseCode) {
  return post(`/unit/records/${id}/refuse-by-token`,
    { reason, refuseCode: refuseCode || '' }, { needAuth: false, header: { 'X-Share-Token': token } })
}

function batchConfirmRecords(recordIds, data) {
  return post('/unit/records/batch-confirm', Object.assign({}, data, { recordIds }))
}

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

const UNIT_TOKEN_KEY = 'unit_token'
const UNIT_INFO_KEY = 'unit_info'

function clearUnitSession() {
  wx.removeStorageSync(UNIT_TOKEN_KEY)
  wx.removeStorageSync(UNIT_INFO_KEY)
}

// 本单位电梯
function getUnitElevators() {
  return get('/unit/elevators')
}

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

function getUnitFaultDetail(id) {
  return get(`/unit/faults/${id}`)
}

function confirmUnitFault(id, pass, reason, signature) {
  return post(`/unit/faults/${id}/confirm`, { pass, reason, signature })
}

function cancelUnitFault(id, reason) {
  return post(`/unit/faults/${id}/cancel`, { reason })
}

function reportUnitFault(data) {
  return post('/unit/faults', data)
}

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
  getUnitFaultDetail,
  confirmUnitFault,
  cancelUnitFault,
  reportUnitFault,
  getMaintenanceCalendar
}