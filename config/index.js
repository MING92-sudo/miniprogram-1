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
  // 腾讯位置服务 key（逆地址解析用，lbs.qq.com 控制台 → 应用管理 → 我的应用 → 添加 Key，勾选 WebService API）
  // ★ 在下面引号内粘贴你申请的 KEY 即可生效；同时需在小程序后台把 https://apis.map.qq.com 加入 request 合法域名
  lbsKey: 'PB7BZ-4A4CZ-VZDXQ-7OMQA-GOZYV-RUFKF',
  requestTimeout: 15000, // 普通请求 15s（接口文档 A.0 约定）
  uploadTimeout: 120000, // 文件上传 120s
  pageSize: 20 // 分页默认 size
}

module.exports = config
