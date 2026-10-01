import http from './client'

/** 认证：client=admin 触发后端管理端角色白名单（LEADER/ADMIN/SYS_ADMIN） */
export function login(phone, password) {
  return http.post('/auth/login', { phone, password, client: 'admin' })
}

export function logout() {
  return http.post('/auth/logout', {})
}
