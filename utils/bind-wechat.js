// 微信绑定：wx.login 取 code → /auth/bind-wechat，把账号与当前微信关联，之后可一键登录。
// 绑定失败不阻断登录（后端把 code 类失败映射为 422 而非 401，不会清登录态），
// 故调用方需自行决定是否给用户重试入口。
const auth = require('../services/auth')

function wxLoginCode() {
  return new Promise((resolve, reject) => {
    wx.login({ success: (r) => resolve(r.code || ''), fail: reject })
  })
}

async function bindWeChat() {
  const code = await wxLoginCode()
  if (!code) {
    throw new Error('未取得微信登录凭证')
  }
  await auth.bindWeChat(code)
}

module.exports = { bindWeChat }