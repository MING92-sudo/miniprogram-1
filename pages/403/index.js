// 403 无权限范式页（M2，屏 12 + 裁决 R3）：中文能力名/允许角色/我的角色/数据范围，
// 不回显权限码原文、路由原文、目标 id（防 403/404 差异探测数据是否存在）。
const { definePage } = require('../../utils/guard')
const { ROUTES } = require('../../config/routes')
const { ROLE_TEXT, resolveHome } = require('../../config/menus')
const { getRoles } = require('../../utils/auth')
const nav = require('../../utils/nav')

definePage(ROUTES['/pages/403/index'], {
  data: { capability: '该功能', allowRoles: '请联系管理员', myRoles: '', scope: '' },
  onLoad() {
    const app = getApp()
    const g = (app && app.globalData) || {}
    const deny = g.lastDeny || {}
    const roles = getRoles()
    this.setData({
      capability: deny.capability || '该功能',
      allowRoles: deny.allowRoles || '请联系管理员',
      myRoles: roles.map((r) => ROLE_TEXT[r] || r).join('、') || '未登录',
      scope: g.scope || '按当前账号角色收敛'
    })
  },
  goHome() {
    const app = getApp()
    const home = (app && app.globalData && app.globalData.home) || resolveHome(getRoles())
    nav.open(home)
  },
  goMessage() { nav.open('/pages/message/index') }
})