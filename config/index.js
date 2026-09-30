// 全局配置
const ENV = 'dev' // dev | prod

const API_BASE_URL = {
  // dev：监管平台网关（重庆市智慧特种设备安全管理系统），2026-09-28 平台分配
  // 业务根路径（平台确认）：https://tzsb.scjgj.cq.gov.cn:1443/api/wlw/maintenance/
  // 此处按 request.js 拼接约定（url 以 / 开头）去掉尾斜杠
  // 注意：平台 token 凭证（.env 中 REG_*）由后端/脚本持有，小程序代码中严禁出现
  // TODO: 自建后端（Spring Boot，平台 token 中控）部署后替换为 https://api-dev.{domain}/v1
  dev: 'https://tzsb.scjgj.cq.gov.cn:1443/api/wlw/maintenance',
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
  // ★ P2 待办：key 后移到自建后端代理（前端不接触 key，见 services/location.js 注释）
  lbs: {
    provider: 'auto', // auto（按 key 有无自动选）| amap | tencent
    amapKey: '', // 高德 Web服务 key：lbs.amap.com 控制台 → 应用管理 → 创建应用 → 添加 Key（Web服务）
    tencentKey: 'PB7BZ-4A4CZ-VZDXQ-7OMQA-GOZYV-RUFKF' // 腾讯位置服务 key（现有）
  },
  requestTimeout: 15000, // 普通请求 15s（接口文档 A.0 约定）
  uploadTimeout: 120000, // 文件上传 120s
  pageSize: 20 // 分页默认 size
}

module.exports = config
