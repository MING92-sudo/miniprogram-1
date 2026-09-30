// 消息中心模块
const { get, post } = require('../utils/request')

// 消息列表（分页）
function getMessageList(params) {
  return get('/messages', params)
}

// 未读数
function getUnreadCount() {
  return get('/messages/unread-count')
}

// 标记已读
function markRead(id) {
  return post(`/messages/${id}/read`, {})
}

// 全部已读
function markAllRead() {
  return post('/messages/read-all', {})
}

module.exports = {
  getMessageList,
  getUnreadCount,
  markRead,
  markAllRead
}
