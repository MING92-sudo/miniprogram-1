import http from './client'

/** 认证（docs/09 §3.2）：client=admin 触发后端管理端角色白名单（LEADER/ADMIN/SYS_ADMIN） */
export function login(phone, password) {
  return http.post('/auth/login', { phone, password, client: 'admin' })
}

export function logout() {
  return http.post('/auth/logout', {})
}

/**
 * 管理端菜单（docs/14 §9.3）：后端按登录角色能力过滤后下发，前端只渲染下发的项。
 * 返回 { role, menus: [{ index, title, perm?, children? }] }；不可登录管理端的角色 menus 为空。
 */
export function getMenus() {
  return http.get('/auth/menus')
}
