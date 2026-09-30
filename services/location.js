// 逆地址解析：经纬度 → 具体位置文字（省市区 + 道路/POI）
// 支持高德（amap）/ 腾讯（tencent）双服务商，按 config.lbs 自动选择；坐标系均为 gcj02
// ⚠ key 暂存于前端配置仅为联调便利；P2 起应后移到自建后端代理（/location/reverse），前端不接触 key
const config = require('../config/index')

function pickProvider() {
  const lbs = config.lbs || {}
  if (lbs.provider === 'amap' && lbs.amapKey) return { name: 'amap', key: lbs.amapKey }
  if (lbs.provider === 'tencent' && lbs.tencentKey) return { name: 'tencent', key: lbs.tencentKey }
  if (lbs.amapKey) return { name: 'amap', key: lbs.amapKey }
  if (lbs.tencentKey) return { name: 'tencent', key: lbs.tencentKey }
  return null
}

// 高德逆地理：GET https://restapi.amap.com/v3/geocode/regeo?key=&location=lng,lat
function requestAmap(key, lat, lng, finish, fallback) {
  wx.request({
    url: 'https://restapi.amap.com/v3/geocode/regeo',
    data: { key: key, location: `${lng},${lat}`, extensions: 'base' },
    success(res) {
      const b = res.data || {}
      // amap: status '1' 成功；infocode 10000 正常
      if (b.status === '1' && b.regeocode) {
        finish(b.regeocode.formatted_address || fallback)
      } else {
        console.warn('[location] 高德逆地址解析返回异常，已回退坐标：', b)
        finish(fallback)
      }
    },
    fail(err) {
      console.warn('[location] 高德逆地址解析请求失败，已回退坐标：', err && err.errMsg)
      finish(fallback)
    }
  })
}

// 腾讯逆地理：GET https://apis.map.qq.com/ws/geocoder/v1/?location=lat,lng
function requestTencent(key, lat, lng, finish, fallback) {
  wx.request({
    url: 'https://apis.map.qq.com/ws/geocoder/v1/',
    data: { location: `${lat},${lng}`, key: key },
    success(res) {
      const b = res.data || {}
      if (b.status === 0 && b.result) {
        const fa = b.result.formatted_addresses || {}
        finish(fa.recommend || b.result.address || fallback)
      } else {
        console.warn('[location] 腾讯逆地址解析返回异常，已回退坐标：', b)
        finish(fallback)
      }
    },
    fail(err) {
      console.warn('[location] 腾讯逆地址解析请求失败，已回退坐标：', err && err.errMsg)
      finish(fallback)
    }
  })
}

function reverseGeocode(lat, lng) {
  const provider = pickProvider()
  // 未配置任何 key：返回演示地址，保证前端流程可走通
  if (!provider) {
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
    if (provider.name === 'amap') requestAmap(provider.key, lat, lng, finish, fallback)
    else requestTencent(provider.key, lat, lng, finish, fallback)
  })
}

module.exports = { reverseGeocode }
