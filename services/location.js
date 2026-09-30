// 逆地址解析：经纬度 → 具体位置文字（省市区 + 道路/POI）
// P2 起由自建后端代理（/location/reverse，docs/08 审查 #5 收口）：
// 高德/腾讯 key 只在后端环境变量，前端彻底不接触 key；坐标系均为 gcj02
const { get } = require('../utils/request')
const config = require('../config/index')

function reverseGeocode(lat, lng) {
  // mock 模式：不依赖后端，直接返回演示地址（原 mock 行为）
  if (config.useMock) {
    return Promise.resolve('重庆市渝北区龙山一路 88 号世纪大厦')
  }
  // 永不 reject：任何失败（含超时）都回退为坐标展示，避免签到流程卡死
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
      console.warn('[location] 逆地址解析超时（10s），已回退坐标。若反复出现：请检查开发者工具"详情→本地设置→不校验合法域名"，或 request 合法域名是否包含后端域名')
      finish(fallback)
    }, 10000)
    get('/location/reverse', { lng: String(lng), lat: String(lat) }, { needAuth: false })
      .then((data) => finish((data && data.address) || fallback))
      .catch((err) => {
        console.warn('[location] 后端逆地址解析失败，已回退坐标：', err && err.message)
        finish(fallback)
      })
  })
}

module.exports = { reverseGeocode }
