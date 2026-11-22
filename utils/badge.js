// Tab 角标由 custom-tabbar 的 g.badges 驱动，本模块只供消息页未读数）。
const { getUnreadCount } = require('../services/message')

// 拉取未读数；resolve 未读数（供页面同步展示），失败归零
function refreshUnreadBadge() {
  return getUnreadCount()
    .then((data) => (data && data.count) || 0)
    .catch(() => 0)
}

module.exports = { refreshUnreadBadge }