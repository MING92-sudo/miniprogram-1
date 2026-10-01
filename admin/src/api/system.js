import http from './client'

/** 用户权限（SYS_ADMIN 专属）：账号启停 / 重置密码 */
export function setEmployeeEnabled(id, enabled) {
  return http.put(`/admin/employees/${id}/enabled`, { enabled })
}

export function resetEmployeePassword(id, password) {
  return http.put(`/admin/employees/${id}/password`, { password })
}
