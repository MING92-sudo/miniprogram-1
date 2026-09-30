// 逆地址解析：经纬度 → 具体位置文字（省市区 + 道路/POI）
// 真实环境走腾讯位置服务（需在小程序后台把 https://apis.map.qq.com 加入 request 合法域名）
const config = require('../config/index')

function reverseGeocode(lat, lng) {
  // 未配置 key：返回演示地址，保证前端流程可走通
  // （逆地址解析独立于后端接口，配置 lbsKey 后即使 useMock=true 也走真实解析）
  if (!config.lbsKey) {
    return Promise.resolve('重庆市渝北区龙山一路 88 号世纪大厦')
  }

  // 永不 reject：任何失败（含超时）都回退为坐标展示，避免签到流程卡死
  // 注意：开发者工具在"校验合法域名"开启且域名未加白时会拦截请求且不触发
  // success/fail 回调，因此必须自带超时兜底，不能依赖 wx.request 的 fail
  const fallback = `${lat.toFixed(5)}, ${lng.toFixed(5)}`
  return new Promise((resolve) => {
    let done = false
    const finish = (text) => {
      if (done) return
      done = true
      clearTimeout(timer)
      resolve(text)
    }
    const timer = setTimeout(() => {
      console.warn('[location] 逆地址解析超时（10s），已回退坐标。若反复出现：请检查开发者工具"详情→本地设置→不校验合法域名"，或后台 request 合法域名是否包含 https://apis.map.qq.com')
      finish(fallback)
    }, 10000)
    wx.request({
      url: 'https://apis.map.qq.com/ws/geocoder/v1/',
      data: { location: `${lat},${lng}`, key: config.lbsKey },
      success(res) {
        const b = res.data || {}
        if (b.status === 0 && b.result) {
          const fa = b.result.formatted_addresses || {}
          finish(fa.recommend || b.result.address || fallback)
        } else {
          console.warn('[location] 逆地址解析接口返回异常，已回退坐标：', b)
          finish(fallback)
        }
      },
      fail(err) {
        console.warn('[location] 逆地址解析请求失败，已回退坐标：', err && err.errMsg)
        finish(fallback)
      }
    })
  })
}

module.exports = { reverseGeocode }
