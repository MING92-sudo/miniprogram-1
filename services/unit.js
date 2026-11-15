// 维保记录签名确认（取消独立使用单位端：链接分享远程签字 / 本机代签）
const { get, post } = require('../utils/request')
const config = require('../config/index')
const { uploadImage } = require('./upload')
const { UNIT_PORTAL_TEXT } = require('../constants/index')

// 记录完整视图（GET /unit/records/:id，与维保记录预览页/使用单位确认同源，docs/03 V2.1 §3.3）
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

// ───────────── 使用单位门户（W3，docs/04 A.11；Task 13） ─────────────
// 门户令牌与员工令牌分开存：员工登录态在 utils/auth.js（em_token），门户令牌存 unit_token，
// 两套身份互不覆盖（门户身份 subject=use_unit.id，后端 AuthInterceptor 按 ut=1 分流）。
const UNIT_TOKEN_KEY = 'unit_token'
const UNIT_INFO_KEY = 'unit_info'

// 登录成功后的门户会话（token + 单位信息，页面头部展示与守卫都用它）
function saveUnitSession(data) {
  // 单身份登录：门户态与员工态互斥，进入门户即清掉员工登录态，避免两个账号同时在线（用户需求）
  require('../utils/auth').clearAuth()
  wx.setStorageSync(UNIT_TOKEN_KEY, data.token)
  wx.setStorageSync(UNIT_INFO_KEY, {
    useUnitId: data.useUnitId,
    account: data.account || '',
    unitName: data.unitName,
    role: data.role || 'UNIT_ADMIN',
    wechatBound: !!data.wechatBound
  })
}

function getUnitSession() {
  return wx.getStorageSync(UNIT_INFO_KEY) || null
}

function getUnitToken() {
  return wx.getStorageSync(UNIT_TOKEN_KEY) || ''
}

function clearUnitSession() {
  wx.removeStorageSync(UNIT_TOKEN_KEY)
  wx.removeStorageSync(UNIT_INFO_KEY)
}

// 门户 JWT 过期时间（只读 payload，不做签名校验——签名由后端负责）。
// 为什么要本地判过期：过期令牌发请求会走 utils/request.js 的 401 分支（清员工登录态 + 跳员工登录页），
// 门户账号在员工登录页没有入口，会变成死路；本地先判过期即可直接引导重新登录。
function jwtExpiresAt(token) {
  try {
    const parts = String(token || '').split('.')
    if (parts.length < 2) return 0
    let b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    while (b64.length % 4) b64 += '='
    const bytes = new Uint8Array(wx.base64ToArrayBuffer(b64))
    let json = ''
    for (let i = 0; i < bytes.length; i++) json += String.fromCharCode(bytes[i])
    const payload = JSON.parse(json)
    return payload && payload.exp ? payload.exp * 1000 : 0
  } catch (e) {
    return 0
  }
}

// 门户登录态是否可用（无令牌或令牌已过期 → false，并清掉过期会话）
function isUnitLoggedIn() {
  const token = getUnitToken()
  if (!token) return false
  const exp = jwtExpiresAt(token)
  if (exp && exp <= Date.now()) {
    clearUnitSession()
    return false
  }
  return true
}

// 门户请求选项：needAuth=false + 显式注入门户令牌。
// 不能沿用 request.js 的 needAuth 分支——它会用员工令牌写 Authorization，门户账号没有员工令牌。
function portalOptions() {
  return { needAuth: false, header: { Authorization: 'Bearer ' + getUnitToken() } }
}

function noSession() {
  return Promise.reject({ code: 401, message: UNIT_PORTAL_TEXT.NOT_LOGGED_IN })
}

// M1：统一令牌优先（单账号多角色）；旧门户令牌仅未迁移账号兜底（裁决 R4）
function portalGet(url, data) {
  const auth = require('../utils/auth')
  if (auth.isLoggedIn()) return get(url, data)
  if (!isUnitLoggedIn()) return noSession()
  return get(url, data, portalOptions())
}

function portalPost(url, data) {
  const auth = require('../utils/auth')
  if (auth.isLoggedIn()) return post(url, data)
  if (!isUnitLoggedIn()) return noSession()
  return post(url, data, portalOptions())
}

// 账号口令登录 → {token, useUnitId, unitName, role, wechatBound}（开放接口，不带任何令牌）
function unitLogin(data) {
  return post('/unit/login', data, { needAuth: false })
}

// 邀请码绑定微信（openid 只由服务端用 wx.login code 换取，AGENTS §2.3）
function bindUnitWechat(data) {
  return post('/unit/bind-wechat', data, { needAuth: false })
}

// 自助改口令（门户令牌）：旧口令校验由后端负责，422 = 原口令不正确
function changeUnitPassword(data) {
  return portalPost('/unit/account/password', data)
}

// M1：使用单位拒签（凭分享 token，原因必填；docs/04 V2.53）
function refuseRecordByToken(id, token, reason) {
  return post(`/unit/records/${id}/refuse-by-token?token=${encodeURIComponent(token)}`, { reason }, { needAuth: false })
}

// 本单位电梯
function getUnitElevators() {
  return portalGet('/unit/elevators')
}

// 本单位维保记录（只读；签字仍走分享链接，见 pages/unit/sign）
function getUnitRecords() {
  return portalGet('/unit/records/list')
}

// 本单位待确认签字的记录
function getUnitPendingSigns() {
  return portalGet('/unit/pending-signs')
}

// 本单位急修单
function getUnitFaults() {
  return portalGet('/unit/faults')
}

// 报修（电梯必须属本单位，越权由后端 1403 拦截）
function reportUnitFault(data) {
  return portalPost('/unit/faults', data)
}

// 门户照片上传：复用 services/upload.js，只把令牌换成门户令牌（unit_token）
function uploadUnitPhoto(filePath) {
  if (!isUnitLoggedIn()) return noSession()
  return uploadImage(filePath, null, getUnitToken())
}

// ───────────── 维保日历（Task 11，员工令牌；只读投影不落库） ─────────────
// GET /maintenance-calendar?from=&to= → {from,to,entries:[{date,elevatorCode,elevatorName,projectName,workTypeCode,workType,anchorDate}]}
function getMaintenanceCalendar(params) {
  return get('/maintenance-calendar', params)
}


// 微信免密登录（已绑定邀请码）：wx.login 的 code → 门户令牌；未绑定由后端 422 提示
function unitLoginByWechat(data) {
  return post('/unit/login-by-wechat', data, { needAuth: false })
}

module.exports = {
  getRecord,
  getSignView,
  confirmByToken,
  // ── 使用单位门户（W3，docs/04 A.11；Task 13）──
  unitLogin,
  unitLoginByWechat,
  bindUnitWechat,
  changeUnitPassword,
  refuseRecordByToken,
  getUnitElevators,
  getUnitRecords,
  getUnitPendingSigns,
  getUnitFaults,
  reportUnitFault,
  uploadUnitPhoto,
  saveUnitSession,
  getUnitSession,
  getUnitToken,
  isUnitLoggedIn,
  clearUnitSession,
  // ── 维保日历（Task 11，员工令牌） ──
  getMaintenanceCalendar
}
