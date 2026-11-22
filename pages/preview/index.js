// 游客预览页：未登录可浏览的静态功能介绍（禁止引用 services/*，不发任何请求）
const nav = require('../../utils/nav')
const { PREVIEW_TEXT, BRAND } = require('../../constants/index')

Page({
  data: {
    text: PREVIEW_TEXT,
    appName: BRAND.APP_NAME,
    appSub: BRAND.APP_SUB,
    foot: BRAND.LOGIN_FOOT
  },

  goLogin() {
    nav.open('/pages/login/index')
  }
})
