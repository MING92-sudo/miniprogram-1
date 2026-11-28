// 小程序版本角标：优先使用后台上传的版本号，开发者工具/未上传时回退环境标识
function miniProgramVersionBadge() {
  try {
    const mp = wx.getAccountInfoSync().miniProgram || {}
    if (mp.version) return 'V' + mp.version
    if (mp.envVersion === 'trial') return '体验版'
    if (mp.envVersion === 'release') return '正式版'
    return '开发版'
  } catch (e) {
    return ''
  }
}

module.exports = { miniProgramVersionBadge }
