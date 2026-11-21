// 小程序菜单兜底镜像（M2，规格 §3.4）：逐字移植蓝图 routes-and-menus.md L239-334，
// 与后端 MiniappMenuService.java 同源（漂移守卫 scripts/verify-miniapp-routes.js 双源锁定）。
// 后端 /me/menus 是唯一事实源；本文件仅首屏占位与请求失败兜底。

// 工作台优先级（维保 > 安装 > 使用单位 > 平台）
const ROLE_PRIORITY = [
  'MAINT_MGR', 'MAINT_SUP', 'MAINT_TECH',
  'INST_MGR', 'INST_PM', 'INST_CLERK',
  'CLIENT_ADMIN', 'CLIENT_SIGNER', 'PLATFORM_ADMIN'
]

const ROLE_HOME = {
  INST_CLERK: '/pages/install/home/index',
  INST_PM: '/pages/install/home/index',
  INST_MGR: '/pages/install/home/index',
  MAINT_TECH: '/pages/maint/home/index',
  MAINT_SUP: '/pages/maint/home/index',
  MAINT_MGR: '/pages/maint/home/index',
  CLIENT_ADMIN: '/pages/client/home/index',
  CLIENT_SIGNER: '/pages/client/home/index',
  PLATFORM_ADMIN: '/pkg-admin/pages/users/index'
}

// 扩展角色归属最近工作台只读投影（规格 §3.6，后端 TABS_ALIAS 镜像）
const TABS_ALIAS = {
  MAINT_QUALITY: 'MAINT_SUP',
  MAINT_BIZ: 'MAINT_SUP',
  INSTALLER: 'INST_CLERK'
}

// 角色中文名（页面禁用提示/403/我的页用，D4 中文口径）
const ROLE_TEXT = {
  INST_CLERK: '安装部资料员',
  INST_PM: '安装部项目负责人',
  INST_MGR: '安装部主管',
  MAINT_TECH: '维保人员',
  MAINT_SUP: '维保主管',
  MAINT_MGR: '维保部经理',
  MAINT_QUALITY: '维保质量负责人',
  MAINT_BIZ: '维保商务',
  INSTALLER: '安装员',
  CLIENT_ADMIN: '使用单位管理员',
  CLIENT_SIGNER: '使用单位签字人',
  PLATFORM_ADMIN: '平台管理员',
  // 旧角色码别名（RoleCatalog v2 兼容）
  WORKER: '维保人员', ASSISTANT: '配合维保人员', LEADER: '班组长',
  UNIT_ADMIN: '使用单位管理员', ADMIN: '平台管理员',
  INSTALL_MANAGER: '安装项目经理', INSTALL_CLERK: '安装文员', INSTALL_WORKER: '安装员',
  MAINT_MANAGER: '维保经理'
}

const ROLE_TABS = {
  INST_CLERK: [
    { key: 'install-home', text: '工作台', icon: 'gauge', path: '/pages/install/home/index' },
    { key: 'handover-list', text: '移交单', icon: 'file-signature', path: '/pkg-handover/pages/list/index' },
    { key: 'handover-docs', text: '资料', icon: 'folder', path: '/pkg-handover/pages/list/index?tab=docs' },
    { key: 'profile', text: '我的', icon: 'user', path: '/pages/profile/index' }
  ],
  INST_PM: [
    { key: 'install-home', text: '工作台', icon: 'gauge', path: '/pages/install/home/index' },
    { key: 'handover-list', text: '移交单', icon: 'file-signature', path: '/pkg-handover/pages/list/index' },
    { key: 'install-projects', text: '项目', icon: 'building', path: '/pkg-admin/pages/projects/index' },
    { key: 'profile', text: '我的', icon: 'user', path: '/pages/profile/index' }
  ],
  INST_MGR: [
    { key: 'install-home', text: '工作台', icon: 'gauge', path: '/pages/install/home/index' },
    { key: 'handover-list', text: '移交单', icon: 'file-signature', path: '/pkg-handover/pages/list/index' },
    { key: 'handover-review', text: '审核', icon: 'file-check', path: '/pkg-handover/pages/list/index?tab=review' },
    { key: 'stats', text: '统计', icon: 'chart', path: '/pkg-admin/pages/stats/index' },
    { key: 'profile', text: '我的', icon: 'user', path: '/pages/profile/index' }
  ],
  MAINT_TECH: [
    { key: 'maint-home', text: '工作台', icon: 'gauge', path: '/pages/maint/home/index' },
    { key: 'maint-orders', text: '工单', icon: 'clipboard', path: '/pkg-maintenance/pages/orders/index', badgeKey: 'pendingOrders' },
    { key: 'maint-elevators', text: '电梯', icon: 'elevator', path: '/pages/elevator/list/index' },
    { key: 'profile', text: '我的', icon: 'user', path: '/pages/profile/index' }
  ],
  MAINT_SUP: [
    { key: 'maint-home', text: '工作台', icon: 'gauge', path: '/pages/maint/home/index' },
    { key: 'maint-orders', text: '工单', icon: 'clipboard', path: '/pkg-maintenance/pages/orders/index', badgeKey: 'pendingOrders' },
    { key: 'maint-projects', text: '项目', icon: 'building', path: '/pkg-admin/pages/projects/index' },
    { key: 'stats', text: '统计', icon: 'chart', path: '/pkg-admin/pages/stats/index' },
    { key: 'profile', text: '我的', icon: 'user', path: '/pages/profile/index' }
  ],
  MAINT_MGR: [
    { key: 'maint-home', text: '工作台', icon: 'gauge', path: '/pages/maint/home/index' },
    { key: 'maint-projects', text: '项目', icon: 'building', path: '/pkg-admin/pages/projects/index' },
    { key: 'maint-contracts', text: '合同', icon: 'file-text', path: '/pkg-admin/pages/contracts/index' },
    { key: 'stats', text: '统计', icon: 'chart', path: '/pkg-admin/pages/stats/index' },
    { key: 'profile', text: '我的', icon: 'user', path: '/pages/profile/index' }
  ],
  CLIENT_ADMIN: [
    { key: 'client-home', text: '首页', icon: 'home', path: '/pages/client/home/index' },
    { key: 'client-todo', text: '待办', icon: 'file-signature', path: '/pkg-client/pages/todo/index', badgeKey: 'pendingSigns' },
    { key: 'client-elevators', text: '电梯', icon: 'elevator', path: '/pages/elevator/list/index' },
    { key: 'profile', text: '我的', icon: 'user', path: '/pages/profile/index' }
  ],
  CLIENT_SIGNER: [
    { key: 'client-home', text: '首页', icon: 'home', path: '/pages/client/home/index' },
    { key: 'client-todo', text: '待办', icon: 'file-signature', path: '/pkg-client/pages/todo/index', badgeKey: 'pendingSigns' },
    { key: 'client-elevators', text: '电梯', icon: 'elevator', path: '/pages/elevator/list/index' },
    { key: 'profile', text: '我的', icon: 'user', path: '/pages/profile/index' }
  ],
  PLATFORM_ADMIN: [
    { key: 'admin-org', text: '组织', icon: 'users', path: '/pkg-admin/pages/users/index' },
    { key: 'stats', text: '统计', icon: 'chart', path: '/pkg-admin/pages/stats/index' },
    { key: 'admin-audit', text: '审计', icon: 'shield', path: '/pkg-admin/pages/audit/index' },
    { key: 'admin-settings', text: '配置', icon: 'settings', path: '/pkg-admin/pages/settings/index' },
    { key: 'profile', text: '我的', icon: 'user', path: '/pages/profile/index' }
  ],
}

function resolveHome(roles) {
  const list = roles || []
  const role = ROLE_PRIORITY.find((r) => list.includes(r))
  if (role) return ROLE_HOME[role]
  for (let i = 0; i < list.length; i++) {
    const alias = TABS_ALIAS[list[i]]
    if (alias) return ROLE_HOME[alias]
  }
  return '/pages/profile/index'
}

// 多角色并集：按 ROLE_PRIORITY 取并集、按 key 去重、最多 5 项；扩展角色别名投影追加在后
function resolveTabs(roles) {
  const list = roles || []
  const seen = {}
  const tabs = []
  ROLE_PRIORITY.forEach((role) => {
    if (!list.includes(role)) return
    ROLE_TABS[role].forEach((tab) => {
      if (seen[tab.key]) return
      seen[tab.key] = true
      tabs.push(tab)
    })
  })
  list.forEach((role) => {
    const alias = TABS_ALIAS[role]
    if (!alias) return
    ROLE_TABS[alias].forEach((tab) => {
      if (seen[tab.key]) return
      seen[tab.key] = true
      tabs.push(tab)
    })
  })
  return tabs.slice(0, 5)
}

function activeWorkspace(roles) {
  const list = roles || []
  const role = ROLE_PRIORITY.find((r) => list.includes(r))
  if (role) return role
  for (let i = 0; i < list.length; i++) {
    const alias = TABS_ALIAS[list[i]]
    if (alias) return alias
  }
  return null
}

module.exports = {
  ROLE_PRIORITY, ROLE_HOME, ROLE_TABS, TABS_ALIAS, ROLE_TEXT,
  resolveHome, resolveTabs, activeWorkspace
}