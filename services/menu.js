// 小程序菜单下发（M1 建，M2 兜底接线，规格 §3.4）：后端 /me/menus 是唯一事实源；
// 请求失败用 config/menus 镜像兜底（首屏占位），写入 globalData 供 custom-tabbar/工作台消费。
const { get } = require('../utils/request')
const menus = require('../config/menus')
const { getRoles, getPerms } = require('../utils/auth')

// 后端 BUTTON_CODES 镜像（MiniappMenuService.java），兜底 actions 投影用；P1 T6 删 client:evaluate（评价入口下线）；
// 2026-11-21 裁决：client:report 重开恢复下发
const BUTTON_CODES = [
  'order:grab', 'order:arrive', 'order:submit', 'order:confirm', 'order:cancel',
  'record:sign_tech', 'record:sign_request', 'record:review', 'record:export',
  'handover:create', 'handover:inspect', 'handover:rectify', 'handover:review',
  'client:sign', 'client:report', 'project:edit', 'elevator:export',
  'checklist:fill', 'task:accept'
]

// 客户工作台快捷动作（2026-11-21 裁决：一键报修重开；与后端 MiniappMenuService.quickActions CLIENT 分支同口径）。
// 客户主体不走 /me/menus（见 getMenus 注释），由兜底投影这里下发；报修项按登录 perms 的 client:report 门控。
function clientQuickActions(perms) {
  const list = []
  if (perms.indexOf('client:report') > -1) {
    list.push({ key: 'report', text: '一键报修', icon: 'wrench', path: '/pages/order/report/index' })
  }
  list.push({ key: 'elevators', text: '我的电梯', icon: 'elevator', path: '/pages/elevator/list/index' })
  list.push({ key: 'todo', text: '待办签字', icon: 'file-signature', path: '/pkg-client/pages/todo/index' })
  return list
}

// 兜底投影：与后端 menusOf 同形状（user/home/activeWorkspace/tabs/quickActions/actions/badges）
function fallbackMenus(workspace) {
  const roles = getRoles()
  const effective = workspace && roles.indexOf(workspace) > -1 ? [workspace] : roles
  const perms = getPerms()
  const actions = {}
  BUTTON_CODES.forEach((c) => { actions[c] = perms.indexOf(c) > -1 })
  const { isCustomerPrincipal } = require('../utils/auth')
  return {
    user: { roles: effective, activeWorkspace: menus.activeWorkspace(effective) },
    home: menus.resolveHome(effective),
    activeWorkspace: menus.activeWorkspace(effective),
    tabs: menus.resolveTabs(effective),
    quickActions: isCustomerPrincipal() ? clientQuickActions(perms) : [],
    actions,
    badges: { pendingOrders: 0, pendingSigns: 0 }
  }
}

function applyToGlobal(data) {
  const app = getApp()
  if (!app || !app.globalData) return data
  const g = app.globalData
  // 登录态下空 tabs 恒为非法（旧空 stub/异常载荷），禁止覆盖已渲染的底栏
  if (data.tabs && data.tabs.length) g.tabs = data.tabs
  g.home = data.home || g.home
  g.quickActions = data.quickActions || []
  g.actions = data.actions || {}
  g.badges = data.badges || { pendingOrders: 0, pendingSigns: 0 }
  if (data.activeWorkspace) g.workspace = data.activeWorkspace
  // 菜单晚到时通知已渲染的底栏/工作台补渲染（组件只在 attached/show 时 sync，不通知永远不更新）
  if (typeof app._notifyMenusUpdated === 'function') app._notifyMenusUpdated()
  return data
}

// 按当前会话（可指定 workspace 切换工作台）拉取菜单；失败兜底镜像
function getMenus(workspace) {
  // 客户主体没有员工菜单：/me/menus 只服务员工目录，客户令牌调它会被判「登录已过期」，
  // 而 request 层把 401 当登录失效 → clearAuth + 跳登录页，表现为「登录成功后立刻被踢回登录页」。
  // 客户工作台用固定 tab（config/menus 镜像），这里直接走兜底，不发起请求。
  const { isCustomerPrincipal } = require('../utils/auth')
  if (isCustomerPrincipal()) {
    return Promise.resolve(applyToGlobal(fallbackMenus(workspace)))
  }
  return get('/me/menus', workspace ? { workspace } : {})
    .then(applyToGlobal)
    .catch((err) => {
      console.warn('菜单下发失败，使用前端兜底镜像', err && err.message)
      return applyToGlobal(fallbackMenus(workspace))
    })
}

module.exports = { getMenus, fallbackMenus, BUTTON_CODES }