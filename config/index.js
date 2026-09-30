// 全局配置
const ENV = 'dev' // dev | prod

const API_BASE_URL = {
  // dev：自建后端（Spring Boot，平台 token 中控）。联调时仅将 useMock 改为 false。
  dev: 'http://localhost:8080/api/v1',
  prod: 'https://api.example.com/v1'
}

const config = {
  env: ENV,
  // ★ 前端独立开发开关：true = 全部接口走 mock/ 本地数据（无需后端）
  //   后端就绪联调时改为 false，services 层接口签名不变
  useMock: true,
  apiBaseUrl: API_BASE_URL[ENV],
  // ── 逆地址解析（坐标 → 位置文字）服务商配置 ──
  // 高德 / 腾讯均有个人免费额度（实名认证后每日数千次，覆盖签到+拍照量级）；
  // 自动选择：填了 amapKey 优先用高德，否则用腾讯 key。坐标系均为 gcj02，与 wx.getLocation 直接兼容。
  // 域名加白（小程序后台 request 合法域名）：高德 https://restapi.amap.com ｜ 腾讯 https://apis.map.qq.com
  // 🔴 安全（docs/08 审查 #5）：真实 key 曾提交入库，请到对应控制台**重置/吊销**该 key！
  // key 现改为不入库：本地调试可在开发者工具 Console 执行一次（存 storage，随设备本地保留）：
  //   wx.setStorageSync('lbs_tencent_key', '你的key')   // 或 lbs_amap_key
  // 正式方案（P2）：由自建后端代理 /location/reverse 下发结果，前端彻底不接触 key
  lbs: {
    provider: 'auto', // auto（按 key 有无自动选）| amap | tencent
    amapKey: '',
    tencentKey: ''
  },
  requestTimeout: 15000, // 普通请求 15s（接口文档 A.0 约定）
  uploadTimeout: 120000, // 文件上传 120s
  pageSize: 20 // 分页默认 size
}

module.exports = config
