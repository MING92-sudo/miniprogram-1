// 小程序菜单下发（M1，规格 §3.4）：后端 /me/menus 是唯一事实源，前端兜底镜像在 M2 落地
const { get } = require('../utils/request')

// 按当前会话（可指定 workspace 切换工作台）拉取 home/tabs/quickActions/actions/badges
function getMenus(workspace) {
  return get('/me/menus', workspace ? { workspace } : {})
}

module.exports = {
  getMenus
}