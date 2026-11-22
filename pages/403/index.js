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