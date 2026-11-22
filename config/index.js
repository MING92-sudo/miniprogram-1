const ENV = 'prod'

const API_BASE_URL = {
  // 真机联调使用局域网 IP；监管平台凭证只能放后端。
  dev: 'http://192.168.1.2:8080',
  prod: 'https://springboot-l1ws-321794-10-1498557567.sh.run.tcloudbase.com'
}

// 上传仍走 wx.uploadFile（callContainer 只收 JSON），该域名需配到「uploadFile 合法域名」。
const CLOUD = {
  enabled: ENV === 'prod', // ★ callContainer 私有链路：免域名、免备案、免配 request 合法域名
  env: 'prod-d3gg6nba2f3160af9',
  service: 'springboot-l1ws'
}

const config = {
  env: ENV,
  // ★ 前端独立开发开关：true = 全部接口走 mock/ 本地数据（无需后端）后端就绪联调时改为 false，services 层接口签名不变
  useMock: false,
  apiBaseUrl: API_BASE_URL[ENV],
  cloud: CLOUD,
  requestTimeout: 15000, // 普通请求 15s（接口文档 A.0 约定）
  uploadTimeout: 120000, // 文件上传 120s
  pageSize: 20, // 分页默认 size
  minWorkDurationMinutes: 30
}

module.exports = config
