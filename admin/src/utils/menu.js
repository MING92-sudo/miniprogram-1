/**
 * 管理端菜单纯函数（docs/14 §9.3、§11 P2）。
 *
 * 菜单有两个来源：
 * 1. 正常态：`GET /auth/menus` 由后端按角色能力过滤后下发（服务端为权威）；
 * 2. 兜底态：接口不可用时用本地 MENUS 表 + `can()` 过滤（离线/旧后端/登录瞬间）。
 *
 * 两条路径都必须过 `filterMenus`：服务端已过滤过，再过滤一次是幂等的，
 * 但能防止「接口返回了超权菜单」时前端把它渲染出来。
 */

/** 按能力过滤：叶子看自身 perm，分组在无可见子项时整组隐藏 */
export function filterMenus(menus, can) {
  if (!Array.isArray(menus)) return []
  return menus
    .map((item) => (item.children
      ? { ...item, children: item.children.filter((child) => can(child.perm)) }
      : item))
    .filter((item) => (item.children ? item.children.length > 0 : can(item.perm)))
}

/**
 * 给菜单挂图标：接口只下发 index/title/perm，图标是前端组件，按 index 从本地表取。
 * 未知 index 用 fallback，避免模板里 `<component :is="undefined">` 报警。
 */
export function attachIcons(menus, icons, fallback) {
  if (!Array.isArray(menus)) return []
  return menus.map((item) => ({ ...item, icon: icons[item.index] || fallback }))
}
