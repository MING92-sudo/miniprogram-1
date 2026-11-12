// 全局常量与枚举（详见 docs/01 需求文档、docs/04 接口文档）

// 角色
const ROLE = {
  WORKER: 'WORKER', // 维保人员
  ASSISTANT: 'ASSISTANT', // 配合维保人员
  LEADER: 'LEADER', // 班组长
  UNIT_ADMIN: 'UNIT_ADMIN', // 使用单位安全管理员
  ADMIN: 'ADMIN', // 维保部管理员
  SYS_ADMIN: 'SYS_ADMIN', // 系统管理员
  // docs/14 §3 角色码表：三平级部门岗位（本次仅新增码值，既有码值语义不变）
  INSTALL_MANAGER: 'INSTALL_MANAGER', // 安装项目经理
  INSTALL_CLERK: 'INSTALL_CLERK', // 安装文员
  INSTALL_WORKER: 'INSTALL_WORKER', // 安装员
  MAINT_MANAGER: 'MAINT_MANAGER', // 维保经理
  MAINT_BIZ: 'MAINT_BIZ', // 维保商务
  MAINT_QUALITY: 'MAINT_QUALITY' // 维保质量负责人
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
  FAILED: '上报失败',
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
  1006: '检查项模板未配置，请联系管理员',
  1007: '排班配置冲突，请联系管理员',
  2001: '监管平台 token 获取失败',
  2002: '监管平台上报失败',
  2003: '监管平台上报超时'
}

// 照片压缩约定（docs/04）
const IMAGE_LIMIT = {
  MAX_EDGE: 1200, // 压缩后长边上限 px
  MAX_SIZE: 500 * 1024 // 500KB
}

// 通用提示文案（页面/弹层统一从这里取，AGENTS §3：禁止硬编码）
const TOAST_TEXT = {
  LOAD_FAILED: '加载失败',
  SAVE_FAILED: '保存失败',
  OPERATE_FAILED: '操作失败',
  LOGIN_FAILED: '登录失败'
}

// 空状态文案（列表/汇总无数据时展示）
const EMPTY_TEXT = {
  ORDER: '暂无工单',
  FAULT: '暂无急修单',
  MESSAGE: '暂无消息',
  PHOTO: '暂无照片',
  CACHE: '暂无缓存',
  ELEVATOR: '暂无电梯',
  ELEVATOR_SEARCH: '未找到匹配设备'
}

// 加载中文案
const LOADING_TEXT = '加载中...'

// 移交单状态（W1 安装移交，docs/04 A.9；状态机见 backend HandoverService）
const HANDOVER_STATUS = {
  INIT: 'INIT',
  DOC_SUBMITTING: 'DOC_SUBMITTING',
  INSPECTING: 'INSPECTING',
  RECTIFYING: 'RECTIFYING',
  PENDING_CONFIRM: 'PENDING_CONFIRM',
  ARCHIVED: 'ARCHIVED'
}

const HANDOVER_STATUS_TEXT = {
  INIT: '待发起',
  DOC_SUBMITTING: '资料移交中',
  INSPECTING: '现场查验',
  RECTIFYING: '整改复验中',
  PENDING_CONFIRM: '待确认归档',
  ARCHIVED: '已归档'
}

// 流程节点（详情页进度条；RECTIFYING 是 INSPECTING 的分支，落位在第 3 格）
const HANDOVER_STEPS = [
  { key: 'DOC_SUBMITTING', label: '资料移交' },
  { key: 'INSPECTING', label: '现场查验' },
  { key: 'RECTIFYING', label: '整改复验' },
  { key: 'PENDING_CONFIRM', label: '待确认' },
  { key: 'ARCHIVED', label: '已归档' }
]

// 3 必传资料（缺一后端 422，HandoverService.submitDocs）
const HANDOVER_DOCS = [
  { field: 'supervisionReportFileId', label: '监督检验报告' },
  { field: 'maintenanceManualFileId', label: '维保说明书' },
  { field: 'qualityCertificateFileId', label: '整机质量证明' }
]

// 现场查验 6 类（顺序固定，HandoverService.ITEM_TYPES）；rectify=true 的项任一 FAIL 强制进整改
const HANDOVER_CHECK_ITEMS = [
  { itemType: 'RUNNING', label: '运行', rectify: false },
  { itemType: 'SAFETY_DEVICE', label: '安全装置', rectify: true },
  { itemType: 'BRAKE', label: '制动器', rectify: true },
  { itemType: 'EMERGENCY_OPERATION', label: '紧急操作', rectify: false },
  { itemType: 'MACHINE_ROOM_SHAFT', label: '机房井道', rectify: false },
  { itemType: 'ACCESSORIES', label: '随附物品', rectify: false }
]

// 查验/复验结果（PASS/FAIL/NA，HandoverService 常量）
const HANDOVER_RESULTS = ['PASS', 'FAIL', 'NA']
const HANDOVER_RESULT_TEXT = {
  PASS: '合格',
  FAIL: '不合格',
  NA: '不适用'
}

// 电梯品种四类（TSG T5002 第八条；与后端 ChecklistTemplateSeeder.APPENDIX_SCOPE 一致）
const ELEVATOR_CATEGORIES = ['曳引与强制驱动电梯', '液压驱动电梯', '杂物电梯', '自动扶梯与自动人行道']

// 可发起/推进移交单的岗位（docs/14 §5 handover:write）
const HANDOVER_WRITE_ROLES = [
  ROLE.INSTALL_MANAGER,
  ROLE.INSTALL_CLERK,
  ROLE.INSTALL_WORKER,
  ROLE.MAINT_MANAGER,
  ROLE.ADMIN,
  ROLE.SYS_ADMIN
]

// 移交页提示与校验文案（AGENTS §4.1：页面不硬编码文案）
const HANDOVER_TEXT = {
  PROJECT_REQUIRED: '请填写项目名称',
  LOAD_INVALID: '额定载重须为数字',
  SPEED_INVALID: '额定速度须为数字',
  DOCS_REQUIRED: '监督检验报告、维保说明书、整机质量证明缺一不可',
  RECEIVER_REQUIRED: '请选择接收工程师',
  ITEMS_REQUIRED: '请完成 6 项查验结果',
  RECTIFY_REQUIRED: '请选择需整改项',
  PHOTO_REQUIRED: '请上传整改前/后照片',
  CONFIRMER_REQUIRED: '请填写确认人姓名',
  CONFIRM_TITLE: '确认归档',
  CONFIRM_CONTENT: '确认后移交单归档并自动建电梯档案、预建首保工单，且不可撤销。是否继续？',
  REJECT_TITLE: '复验不通过',
  REJECT_CONTENT: '复验不通过将保持整改中，由安装部重新上传整改照片。是否继续？',
  RECEIVER_MANUAL_TIP: '当前账号无人员目录读取权限，请手工填写接收工程师工号与姓名',
  INSPECT_FAIL_TIP: '不合格项须拍照留证：请在「整改」页上传整改前后照片',
  DETAIL_GAP_TIP: '查验明细与整改记录由后端在归档时汇总，小程序端仅驱动状态流转；完整记录请在管理端「移交单」查阅',
  READONLY_TIP: '当前岗位对移交单只读，可查看列表与详情，发起/推进请用安装部或维保经理账号',
  LIST_EMPTY: '暂无移交单',
  LIST_FAILED: '移交单列表暂不可用，可按 ID 打开详情'
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
  IMAGE_LIMIT,
  TOAST_TEXT,
  EMPTY_TEXT,
  LOADING_TEXT,
  HANDOVER_STATUS,
  HANDOVER_STATUS_TEXT,
  HANDOVER_STEPS,
  HANDOVER_DOCS,
  HANDOVER_CHECK_ITEMS,
  HANDOVER_RESULTS,
  HANDOVER_RESULT_TEXT,
  ELEVATOR_CATEGORIES,
  HANDOVER_WRITE_ROLES,
  HANDOVER_TEXT
}
