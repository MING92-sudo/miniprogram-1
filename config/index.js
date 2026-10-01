// 全局配置
const ENV = 'dev' // dev | prod

const API_BASE_URL = {
  // dev：自建后端（平台 token 中控 + 业务契约转发）
  // 本地开发：backend/ Spring Boot 默认 8080（开发者工具需开启"不校验合法域名"）
  // 云托管联调：改为云托管域名（容器监听 80），路由与本地完全一致
  // 注意：监管平台凭证（.env 中 REG_*）只存在于后端进程，小程序代码中严禁出现
  dev: 'http://localhost:8080',
  prod: 'https://your-wxcloudrun-domain.example'
}

const config = {
  env: ENV,
  // ★ 前端独立开发开关：true = 全部接口走 mock/ 本地数据（无需后端）
  //   后端就绪联调时改为 false，services 层接口签名不变
  useMock: true,
  apiBaseUrl: API_BASE_URL[ENV],
  requestTimeout: 15000, // 普通请求 15s（接口文档 A.0 约定）
  uploadTimeout: 120000, // 文件上传 120s
  pageSize: 20, // 分页默认 size
  // 作业时长下限（分钟，签到→签退；业主补充规则）。
  // ★ 测试跑通签退流程时临时调小/置 0；回归验收后务必恢复 30。
  minWorkDurationMinutes: 30
}

module.exports = config
