import http from './client'

/** 认证（docs/09 §3.2）：client=admin 触发后端管理端角色门控（permission-v2 能力码） */
export function login(phone, password) {
  return http.post('/auth/login', { phone, password, client: 'admin' })
}

export function logout() {
  return http.post('/auth/logout', {})
}

// getMenus 已删（2026-11-16 审查清理）：M5 起菜单走 ui/menu-config + permission-v2 本地投影；
// 后端 /auth/menus 旧契约保留（MenuService 已注废弃），前端无调用者。
