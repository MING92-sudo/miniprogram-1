// 全局常量与枚举（详见 docs/01 需求文档、docs/04 接口文档）

// 角色
const ROLE = {
  WORKER: 'WORKER', // 维保人员
  ASSISTANT: 'ASSISTANT', // 配合维保人员
  LEADER: 'LEADER', // 班组长
  UNIT_ADMIN: 'UNIT_ADMIN', // 使用单位安全管理员
  ADMIN: 'ADMIN', // 维保部管理员
  SYS_ADMIN: 'SYS_ADMIN' // 系统管理员
}

// 工单状态
const ORDER_STATUS = {
  PENDING: 'PENDING',
  PROCESSING: 'PROCESSING',
  DONE: 'DONE'
}

// 工单状态文案
const STATUS_TEXT = {
  PENDING: '待执行',
  PROCESSING: '进行中',
  DONE: '已完成'
}

// 检查项结果枚举（docs/04 A.2：NORMAL/ABNORMAL/NA）
const CHECK_RESULT_TEXT = {
  NORMAL: '正常',
  ABNORMAL: '异常',
  NA: '不适用'
}

// 检查项判定方式（docs/04 A.4.2 judgeType）
// NUMERIC 读数型 / STANDARD 标准型 / MANUFACTURER 按本机说明书判定 / QUALITATIVE 定性判定
const JUDGE_TYPES = ['NUMERIC', 'STANDARD', 'MANUFACTURER', 'QUALITATIVE']

// 严重事故隐患码（需求文档 §6.2 / 平台 V1.5 规范 3.2；规范原文 S3 重复且缺 S4，
// 已按连续编码勘误实现，待平台确认 docs/06 #9）
const PROBLEM_CODES = {
  S0: '未发现严重事故隐患',
  S1: '使用非法生产的特种设备',
  S2: '超过特种设备的规定参数范围使用',
  S3: '缺少安全附件、安全装置，或安全附件、安全装置失灵而继续使用',
  S4: '使用应当予以报废或者经检验判定为不合格的特种设备',
  S5: '使用有明显故障、异常情况的特种设备，或使用经责令改正而未予以改正的特种设备',
  S6: '特种设备发生事故不予报告而继续使用',
  S7: '其它严重事故隐患'
}

// 维保类别码（平台 V1.5 规范 3.1：FM按需/HM半月/TM季度/SM半年/OY年度）
const WORK_TYPE_CODES = {
  FM: '按需维保',
  HM: '半月维保',
  TM: '季度维保',
  SM: '半年维保',
  OY: '年度维保'
}

// 记录上报状态（技术架构文档 §5.2 状态机；CONFIRMED 为本地终态，不上报平台）
const REPORT_STATUS_TEXT = {
  DRAFT: '草稿',
  SUBMITTED: '已提交待上报',
  REPORTING: '上报中',
  REPORTED: '平台上报成功',
  RETRY_WAIT: '等待重试',
  FAILED_MAX: '待人工处理',
  CONFIRMED: '已确认（本地归档）'
}

// 业务规则：签到—签退最小作业时长（分钟），前端与后端双重校验。
// 注：需求/接口文档中的「30 分钟」原指 ①双人签到间隔上限（01 §3.7.2）
// ②困人救援抵达时限（TSG 第五条(四)）；作业时长下限为业主 2026-09-30
// 补充的业务规则，上线前须与平台/监管确认口径。
const MIN_WORK_DURATION_MINUTES = 30

// 业务错误码 → 文案（docs/04 A.0）
const ERROR_CODES = {
  401: '登录已过期，请重新登录',
  1001: '签到位置超出允许范围，请提交申诉',
  1002: '该手机号已被其他角色绑定',
  1003: '工单已被锁定，请刷新后重试',
  1004: '监管平台 platform_id 未同步，请联系管理员',
  1005: '电梯位置未登记，已按降级策略放行',
  1006: '不在排班计划内，签到被拦截',
  1007: '排班时间未到，签到被拦截',
  2001: '监管平台 token 获取失败',
  2002: '监管平台上报失败',
  2003: '监管平台上报超时'
}

// 照片压缩约定（docs/04）
const IMAGE_LIMIT = {
  MAX_EDGE: 1200, // 压缩后长边上限 px
  MAX_SIZE: 500 * 1024 // 500KB
}

module.exports = {
  ROLE,
  ORDER_STATUS,
  STATUS_TEXT,
  CHECK_RESULT_TEXT,
  JUDGE_TYPES,
  PROBLEM_CODES,
  WORK_TYPE_CODES,
  REPORT_STATUS_TEXT,
  MIN_WORK_DURATION_MINUTES,
  ERROR_CODES,
  IMAGE_LIMIT
}
