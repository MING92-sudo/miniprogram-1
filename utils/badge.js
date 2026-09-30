// tabBar 消息未读角标（消息 tab 固定第 3 个，index = 2）
const { getUnreadCount } = require('../services/message')

const MESSAGE_TAB_INDEX = 2

function applyBadge(count) {
  if (count > 0) {
    wx.setTabBarBadge({
      index: MESSAGE_TAB_INDEX,
      text: count > 99 ? '99+' : String(count),
      fail: () => {} // tabBar 尚未就绪等场景忽略
    })
  } else {
    wx.removeTabBarBadge({ index: MESSAGE_TAB_INDEX, fail: () => {} })
  }
}

// 拉取未读数并刷新角标；resolve 未读数（供页面同步展示）
function refreshUnreadBadge() {
  return getUnreadCount()
    .then((data) => {
      const count = (data && data.count) || 0
      applyBadge(count)
      return count
    })
    .catch(() => {
      applyBadge(0)
      return 0
    })
}

module.exports = { refreshUnreadBadge, MESSAGE_TAB_INDEX }
