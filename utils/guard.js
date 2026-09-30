// 登录守卫：页面 onShow 时调用，未登录统一跳登录页；401 清态后复用同一跳转
const { isLoggedIn } = require('./auth')

let redirecting = false

// 跳转登录页（带防抖与路由竞态规避）
function redirectToLogin() {
  // 防抖：冷启动时多个 tab 页 onShow 可能并发触发，只允许发起一次 reLaunch
  if (redirecting) return
  // 当前栈顶已是登录页时无需再跳（如 401 清态后 login 页内又触发守卫）
  const pages = getCurrentPages()
  const current = pages.length ? pages[pages.length - 1] : null
  if (current && current.route === 'pages/login/index') return
  redirecting = true
  // 延迟到首屏路由(routeDone)完成后再销毁 webview。setTimeout(0) 仍会早于 routeDone，
  // 触发「routeDone with a webviewId xxx is not found」路由竞态（真机表现为白屏）。
  // Windows 开发者工具渲染较慢，给足 300ms 余量。
  setTimeout(() => {
    wx.reLaunch({
      url: '/pages/login/index',
      complete: () => { redirecting = false }
    })
  }, 300)
}

function ensureLogin() {
  if (isLoggedIn()) return true
  redirectToLogin()
  return false
}

module.exports = { ensureLogin, redirectToLogin }
