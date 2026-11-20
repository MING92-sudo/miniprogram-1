import http from './client'

/** 认证（docs/09 §3.2）：client=admin 触发后端管理端角色门控（permission-v2 能力码） */
export function login(phone, password) {
  return http.post('/auth/login', { phone, password, client: 'admin' })
}

export function logout() {
  return http.post('/auth/logout', {})
}

// getMenus 已删（2026-11-16 审查清理）：M5 起菜单走 ui/menu-config + permission-v2 本地投影；
// 后端 GET /auth/menus 旧契约已于 2026-11-20 连同 MenuService 一并删除（客户端与小程序统一只认 /me/menus）。
