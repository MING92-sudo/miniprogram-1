// 应用入口（M2 单账号多角色，规格 §3.2/§5.3）：单会话 globalData + 菜单驱动导航。
// 云托管初始化/版本更新/离线补传监听沿用；登录态恢复扩展 roles/perms/workspace/home。
const { getToken, getUserInfo, getRole, getRoles, getPerms, getWorkspace, getHome, getScope, clearAuth } = require('./utils/auth')

App({
  globalData: {
    token: '',
    userInfo: null,
    role: '',
    roles: [],
    perms: [],
    workspace: '',
    home: '',
    scope: '',
    tabs: [],
    quickActions: [],
    actions: {},
    badges: { pendingOrders: 0, pendingSigns: 0 },
    lastDeny: null
  },

  onLaunch() {
    this.initCloud()
    this.restoreAuth()
    this.watchNetwork()
    this.watchUpdate()
    if (this.globalData.token) this.loadMenus()
  },

  // 云托管：callContainer 前必须先 wx.cloud.init（环境 ID 来自 config.cloud.env）。
  initCloud() {
    const config = require('./config/index')
    if (!config.cloud || !config.cloud.enabled) return
    if (!wx.cloud || typeof wx.cloud.init !== 'function') {
      console.warn('当前基础库不支持 wx.cloud，接口回退 wx.request')
      return
    }
    wx.cloud.init({ env: config.cloud.env, traceUser: false })
  },

  watchUpdate() {
    try {
      const um = wx.getUpdateManager()
      um.onUpdateReady(() => {
        wx.showModal({
          title: '更新提示',
          content: '新版本已就绪，是否重启应用？',
          success: (res) => { if (res.confirm) um.applyUpdate() }
        })
      })
    } catch (e) { /* 低版本基础库无 UpdateManager */ }
  },

  // 网络恢复自动补传离线队列（docs/02 离线策略，逻辑不动）
  watchNetwork() {
    wx.onNetworkStatusChange((res) => {
      if (!res.isConnected) return
      if (!this.globalData.token) return
      const { flushQueue } = require('./utils/offline')
      flushQueue().then((r) => {
        if (r.success > 0) wx.showToast({ title: `已自动补传 ${r.success} 条离线数据`, icon: 'none' })
      })
    })
  },

  // 从本地存储恢复登录态（单会话；旧门户令牌仅未迁移账号兜底，M6 删除）
  restoreAuth() {
    const g = this.globalData
    g.token = getToken() || ''
    g.userInfo = getUserInfo() || null
    g.role = getRole() || ''
    g.roles = getRoles() || []
    g.perms = getPerms() || []
    g.workspace = getWorkspace() || ''
    g.home = getHome() || ''
    g.scope = getScope() || ''
    if (g.token) require('./services/unit').clearUnitSession()
  },

  // 登录成功写入全局态（saveAuth 已由 services/auth.applyLoginResult 落库）
  setAuth(data) {
    require('./services/unit').clearUnitSession()
    const g = this.globalData
    g.token = data.token
    g.userInfo = data.userInfo || null
    g.role = data.role
    g.roles = data.roles || [data.role]
    g.perms = data.perms || []
    g.workspace = data.activeWorkspace || data.role
    g.home = data.home || ''
    g.scope = data.scope || ''
    this.loadMenus()
  },

  // 拉取菜单（/me/menus 优先，失败兜底镜像）；workspace 缺省用当前
  loadMenus(workspace) {
    if (!this.globalData.token) return Promise.resolve(null)
    const { getMenus } = require('./services/menu')
    return getMenus(workspace || this.globalData.workspace)
  },

  // 切换工作台：重拉菜单 → reLaunch 该工作台首页（权限保持并集，数据范围逐角色判断）
  switchWorkspace(ws) {
    return this.loadMenus(ws).then(() => {
      const { resolveHome } = require('./config/menus')
      const home = this.globalData.home || resolveHome(this.globalData.roles)
      wx.reLaunch({ url: home })
    })
  },

  // 静默刷新角标（不改 workspace）
  refreshBadges() {
    return this.loadMenus()
  },

  logout() {
    clearAuth()
    const g = this.globalData
    g.token = ''; g.userInfo = null; g.role = ''
    g.roles = []; g.perms = []; g.workspace = ''; g.home = ''; g.scope = ''
    g.tabs = []; g.quickActions = []; g.actions = {}
    g.badges = { pendingOrders: 0, pendingSigns: 0 }; g.lastDeny = null
  }
})