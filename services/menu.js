// 小程序菜单下发（M1 建，M2 兜底接线，规格 §3.4）：后端 /me/menus 是唯一事实源；
// 请求失败用 config/menus 镜像兜底（首屏占位），写入 globalData 供 custom-tabbar/工作台消费。
const { get } = require('../utils/request')
const menus = require('../config/menus')
const { getRoles, getPerms } = require('../utils/auth')

// 后端 BUTTON_CODES 镜像（MiniappMenuService.java），兜底 actions 投影用；P1 T6 删 client:evaluate（评价入口下线）
const BUTTON_CODES = [
  'order:grab', 'order:arrive', 'order:submit', 'order:confirm', 'order:cancel',
  'record:sign_tech', 'record:sign_request', 'record:review', 'record:export',
  'handover:create', 'handover:inspect', 'handover:rectify', 'handover:review',
  'client:sign', 'project:edit', 'elevator:export',
  'checklist:fill', 'task:accept'
]

// 兜底投影：与后端 menusOf 同形状（user/home/activeWorkspace/tabs/quickActions/actions/badges）
function fallbackMenus(workspace) {
  const roles = getRoles()
  const effective = workspace && roles.indexOf(workspace) > -1 ? [workspace] : roles
  const perms = getPerms()
  const actions = {}
  BUTTON_CODES.forEach((c) => { actions[c] = perms.indexOf(c) > -1 })
  return {
    user: { roles: effective, activeWorkspace: menus.activeWorkspace(effective) },
    home: menus.resolveHome(effective),
    activeWorkspace: menus.activeWorkspace(effective),
    tabs: menus.resolveTabs(effective),
    quickActions: [],
    actions,
    badges: { pendingOrders: 0, pendingSigns: 0 }
  }
}

function applyToGlobal(data) {
  const app = getApp()
  if (!app || !app.globalData) return data
  const g = app.globalData
  g.tabs = data.tabs || []
  g.home = data.home || g.home
  g.quickActions = data.quickActions || []
  g.actions = data.actions || {}
  g.badges = data.badges || { pendingOrders: 0, pendingSigns: 0 }
  if (data.activeWorkspace) g.workspace = data.activeWorkspace
  return data
}

// 按当前会话（可指定 workspace 切换工作台）拉取菜单；失败兜底镜像
function getMenus(workspace) {
  return get('/me/menus', workspace ? { workspace } : {})
    .then(applyToGlobal)
    .catch((err) => {
      console.warn('菜单下发失败，使用前端兜底镜像', err && err.message)
      return applyToGlobal(fallbackMenus(workspace))
    })
}

module.exports = { getMenus, fallbackMenus, BUTTON_CODES }