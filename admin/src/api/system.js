import http from './client'

/** 用户权限（SYS_ADMIN 专属，docs/04 A.1）：账号启停 / 重置密码 */
export function setEmployeeEnabled(id, enabled) {
  return http.put(`/admin/employees/${id}/enabled`, { enabled })
}

export function resetEmployeePassword(id, password) {
  return http.put(`/admin/employees/${id}/password`, { password })
}

/** 重置为随机密码（服务端生成，initialPassword 一次性返回） */
export function resetEmployeePasswordRandom(id) {
  return http.put(`/admin/employees/${id}/password`, { password: '' })
}
