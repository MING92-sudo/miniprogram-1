import http from './client'

/** 写接口幂等键由 client 统一附加； */
export function company() {
  return http.get('/company')
}

/** 保存后自动同步 2.2 主体ID；平台慢时留足 60s，避免浏览器 30s 提前报网络错误 */
export function updateCompany(body) {
  return http.put('/company', body, { timeout: 60000 })
}

export function useUnits() {
  return http.get('/use-units')
}

export function createUseUnit(body) {
  return http.post('/use-units', body)
}

export function updateUseUnit(id, body) {
  return http.put(`/use-units/${id}`, body)
}

export function employees() {
  return http.get('/employees')
}

export function createEmployee(body) {
  return http.post('/employees', body)
}

export function updateEmployee(id, body) {
  return http.put(`/employees/${id}`, body)
}

export function elevatorsAdmin() {
  return http.get('/admin/elevators')
}

export function createElevator(body) {
  return http.post('/elevators', body)
}

export function updateElevator(id, body) {
  return http.put(`/elevators/${id}`, body)
}

/** ①档案增删改查：删除（在途工单/系统管理员/关联电梯时后端 422 拒绝） */
export function deleteEmployee(id) {
  return http.delete(`/employees/${id}`)
}

export function deleteUseUnit(id) {
  return http.delete(`/use-units/${id}`)
}


/** 某单位的客户账号列表 */
export function clientAccounts(useUnitId) {
  return http.get(`/use-units/${useUnitId}/accounts`)
}

/** 开通客户账号（返回 initialPassword 供转交，仅一次） */
export function createClientAccount(useUnitId, body) {
  return http.post(`/use-units/${useUnitId}/accounts`, body)
}

/** 编辑客户账号（姓名/角色/手机号） */
export function updateClientAccount(useUnitId, accountId, body) {
  return http.put(`/use-units/${useUnitId}/accounts/${accountId}`, body)
}

/** 停用 / 启用（不删除，审计留存） */
export function disableClientAccount(useUnitId, accountId, reason) {
  return http.patch(`/use-units/${useUnitId}/accounts/${accountId}/disable`, { reason: reason || '' })
}

export function enableClientAccount(useUnitId, accountId) {
  return http.patch(`/use-units/${useUnitId}/accounts/${accountId}/enable`, {})
}

/** 重置口令（返回 password 供转交，仅一次） */
export function resetClientPassword(useUnitId, accountId, password) {
  return http.post(`/use-units/${useUnitId}/accounts/${accountId}/reset-password`, password ? { password } : {})
}

/** 换绑微信（code 由服务端换取 openid，禁止客户端传 openid） */
export function bindClientWechat(useUnitId, accountId, code) {
  return http.post(`/use-units/${useUnitId}/accounts/${accountId}/bind-wechat`, { code })
}

/** 临时恢复宽限（按账号单条，留审计） */
export function restoreClientGrace(useUnitId, accountId, graceUntil) {
  return http.post(`/use-units/${useUnitId}/accounts/${accountId}/restore-grace`, { graceUntil: graceUntil || '' })
}

export function deleteElevator(id) {
  return http.delete(`/elevators/${id}`)
}

/** 手动派单（首保/补单：无首次维保时间的电梯由管理员触发） */
export function dispatchElevator(id) {
  return http.post(`/elevators/${id}/dispatch`, {})
}

/** 电梯档案详情（一梯一档：本地信息 + 平台同步字段） */
export function elevatorProfile(id) {
  return http.get(`/elevators/${id}/profile`)
}

/** 一梯一档归档目录（按电梯取最近移交归档，7 条 + SHA-256，只读不可篡改） */
export function elevatorArchive(id) {
  return http.get(`/elevators/${id}/archive`)
}


/** 批量导入经纬度：{items:[{code,lng,lat}]}，逐条回显成败 */
export function batchGeo(items) {
  return http.post('/elevators/batch-geo', { items })
}

/** 电梯贴梯二维码 PNG（内容=电梯编码），blob 直传 */
export function elevatorQrPng(id) {
  return http.post(`/elevators/${id}/qrcode`, {}, { responseType: 'blob', timeout: 60000 })
}

/** 重置密码：12 位随机一次性返回 */
export function resetEmployeePassword(id) {
  return http.post(`/employees/${id}/reset-password`, {})
}

/** 2.2 单主体同步：维保单位 */
/** 2.2 单主体同步：使用单位 */
