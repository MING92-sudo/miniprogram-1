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

// 工单状态文案（ORDER_STATUS 枚举死导出已删，2026-11-16 审查清理；状态码见 docs/04）
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
  PENDING_INST_REVIEW: 'PENDING_INST_REVIEW', // C-13 安装部内部审核（后端 HandoverService）
  INSPECTING: 'INSPECTING',
  RECTIFYING: 'RECTIFYING',
  PENDING_CONFIRM: 'PENDING_CONFIRM',
  ARCHIVED: 'ARCHIVED'
}

const HANDOVER_STATUS_TEXT = {
  INIT: '待发起',
  DOC_SUBMITTING: '资料移交中',
  PENDING_INST_REVIEW: '内部审核中',
  INSPECTING: '现场查验',
  RECTIFYING: '整改复验中',
  PENDING_CONFIRM: '待确认归档',
  ARCHIVED: '已归档'
}

// 3 必传资料（缺一后端 422，HandoverService.submitDocs）
const HANDOVER_DOCS = [
  { field: 'supervisionReportFileId', label: '监督检验报告' },
  { field: 'maintenanceManualFileId', label: '维保说明书' },
  { field: 'qualityCertificateFileId', label: '整机质量证明' }
]

// 移交查验 13 项类标签（docs/15 附录 A 分组，G01—G13；详情/查验页共用）
const HANDOVER_FLOW = ['发起并上传', '内部审核', '维保验收', '双方签字']
const HANDOVER_CREATE_TEXT = {
  TITLE: '发起移交',
  PROJECT: '项目信息',
  ELEVATOR: '电梯信息（归档即建电梯档案）',
  SUBMIT: '申请容缺受理 · 提交审核',
  DRAFT: '存草稿',
  TIP: '发起后进入「资料移交中」，需在详情页上传 3 类必传资料',
  PREVIEW_SUB: '安装部移交维保部'
}
const HANDOVER_GROUP_LABEL = {
  G01: '机器空间和滑轮间', G02: '井道', G03: '机器设置在不同位置的要求', G04: '轿厢',
  G05: '悬挂装置和补偿装置', G06: '层门和层站', G07: '电气装置', G08: '紧急报警装置',
  G09: '电梯运行控制', G10: '电梯数据信息输出', G11: '消防员电梯的附加要求', G12: '防爆电梯的附加要求',
  G13: '验收试验项目与试验要求'
}

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
  ROLE.SYS_ADMIN,
  'INST_PM', 'INST_CLERK', 'INST_MGR', 'INSTALLER', 'MAINT_MGR', 'PLATFORM_ADMIN'
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

// 外接建档单状态（W2，docs/04 A.10；状态机见 backend ExternalFilingService）
const FILING_STATUS = {
  INIT: 'INIT',
  DOC_COLLECTING: 'DOC_COLLECTING',
  TAKEOVER_CHECKING: 'TAKEOVER_CHECKING',
  PENDING_CONFIRM: 'PENDING_CONFIRM',
  ARCHIVED: 'ARCHIVED'
}

const FILING_STATUS_TEXT = {
  INIT: '待发起',
  DOC_COLLECTING: '资料收集中',
  TAKEOVER_CHECKING: '接管查验',
  PENDING_CONFIRM: '待交接确认',
  ARCHIVED: '已归档'
}

// 流程节点（详情页进度条；INIT 仅作枚举默认值，与后端一致不进流程）
const FILING_STEPS = [
  { key: 'DOC_COLLECTING', label: '资料收集' },
  { key: 'TAKEOVER_CHECKING', label: '接管查验' },
  { key: 'PENDING_CONFIRM', label: '交接确认' },
  { key: 'ARCHIVED', label: '已归档' }
]

// 建档资料类别（与 ExternalFilingService.DOC_TYPES 逐字一致，缺件可标 missing 待补办）
const FILING_DOC_TYPES = [
  { code: 'USE_REG_CERT', label: '使用登记证' },
  { code: 'INSPECTION_REPORT', label: '检验报告' },
  { code: 'MAINTENANCE_RECORD', label: '既有维保记录' },
  { code: 'MAINTENANCE_MANUAL', label: '维保说明书' },
  { code: 'ELEVATOR_DRAWING', label: '电梯图纸' },
  { code: 'OTHER', label: '其他资料' }
]

// 接管查验不合格项责任归属（ExternalFilingService.RESPONSIBILITIES）
const TAKEOVER_RESPONSIBILITY_TEXT = {
  PREV_MAINT: '原维保单位',
  USE_UNIT: '使用单位',
  OURS_AFTER_TAKEOVER: '接管后我方'
}

const TAKEOVER_RESPONSIBILITY_OPTIONS = ['PREV_MAINT', 'USE_UNIT', 'OURS_AFTER_TAKEOVER']

// 接管查验 6 项（与移交现场查验同口径，便于两端对账；判定枚举复用 HANDOVER_RESULTS）
const TAKEOVER_CHECK_ITEMS = HANDOVER_CHECK_ITEMS.map(function (i) {
  return { itemType: i.itemType, label: i.label }
})

// 可推进外接建档单的岗位（PermissionMatrix filing:write=PA/MM/MS/MB，SA 全权限；2026-11-15 裁决写归维保部）
const FILING_WRITE_ROLES = [
  ROLE.MAINT_MANAGER,
  ROLE.LEADER,
  ROLE.ADMIN,
  ROLE.SYS_ADMIN,
  'MAINT_MGR', 'MAINT_SUP', 'MAINT_BIZ', 'PLATFORM_ADMIN'
]

// 可读外接建档单的岗位（filing:view：写岗位 Y + MQ/IM/IP/IC 条件读；安装员无读权）
const FILING_READ_ROLES = FILING_WRITE_ROLES.concat([
  ROLE.MAINT_QUALITY,
  ROLE.INSTALL_MANAGER,
  ROLE.INSTALL_CLERK,
  'MAINT_QUALITY', 'INST_MGR', 'INST_PM', 'INST_CLERK'
])

// 外接建档页提示与校验文案
const FILING_TEXT = {
  UNIT_REQUIRED: '请选择使用单位',
  CONTRACT_REQUIRED: '请选择已生效合同',
  LOAD_INVALID: '额定载重须为数字',
  SPEED_INVALID: '额定速度须为数字',
  USEUNIT_UNAVAILABLE: '当前账号无使用单位列表读取权限，请手工填写使用单位 ID',
  CONTRACT_UNAVAILABLE: '当前账号无合同列表读取权限（合同维护仅平台层），请手工填写合同 ID',
  DOCS_REQUIRED: '每项资料须上传文件或标记缺件',
  DOCS_EMPTY: '至少提交一项资料',
  ITEMS_REQUIRED: '请完成 6 项接管查验结果',
  RESPONSIBILITY_REQUIRED: '不合格项必须选择责任归属',
  SIGN_NAME_REQUIRED: '请填写对方签字人姓名',
  SIGN_FILE_REQUIRED: '请上传对方手写签名',
  CONFIRM_TITLE: '提交交接确认',
  CONFIRM_CONTENT: '确认后本单归档：自动建电梯档案（来源=外接建档）并预建 3 日后的首保工单，且不可撤销。是否继续？',
  DOC_TIP: '缺件可标「缺件待补办」，不阻断进入接管查验；补齐后请重新提交资料',
  READONLY_TIP: '当前岗位对外接建档单只读；发起/推进请用维保部管理岗位（经理/班组长/商务）或平台管理员账号',
  DETAIL_GAP_TIP: '查验明细与交接确认单在本页按最近一次提交展示；完整归档记录请在管理端「外接建档」查阅'
}

// UNIT_PORTAL_TEXT/UNIT_FAULT_TYPES/UNIT_FAULT_STATUS_TEXT/UNIT_CONFIRM_STATUS_TEXT 已删
// （2026-11-16 审查清理：V41 门户登录链废除后零引用；状态文案见 docs/04 A.7/A.11）

// 维保日历（Task 11 GET /maintenance-calendar）：窗口月数可切 1/3/6
const CALENDAR_WINDOWS = [
  { months: 1, label: '1 个月' },
  { months: 3, label: '3 个月' },
  { months: 6, label: '6 个月' }
]

const CALENDAR_TEXT = {
  TITLE: '维保日历',
  TIP: '按维保类别前向投影的应做日期（半月/季度/半年/年度），只读参考，不代表已派单',
  EMPTY: '所选窗口内暂无排期',
  FAILED: '排期加载失败，请稍后重试',
  SCOPE_TIP: '可见范围：本人名下电梯；班组/区域扩大随管理端权限收敛'
}


// 品牌标识（程序名/公司名/徽标口径不变，D4/D5；页面与 nav-bar 统一引用，禁止散落硬编码）
const BRAND = {
  APP_NAME: '博威无纸化',
  APP_SUB: '电梯无纸化维保系统',
  COMPANY: '重庆博威电梯公司',
  VERSION_BADGE: 'V1.4 / 2026',
  LOGIN_FOOT: '该小程序为重庆博威电梯公司内部相关人员登录使用 · 账号由所属单位管理员开通 · 首次使用请联系项目负责人'
}
// ── 原型 V1.4 界面文案（屏 00/02/05/06/08/09/11/20/24 等）：页面一律经 data.text 绑定，禁止 WXML 硬编码 ──
const LOGIN_TEXT = {
  EYEBROW: 'ELEVATOR MAINTENANCE OS',
  BRAND_SUB: '电梯无纸化维保系统',
  TITLE_1: '让每一台电梯',
  TITLE_2: '都有据可查',
  CARD_TITLE: '登录工作台',
  CARD_TAG: '统一入口',
  ACCOUNT: '账号',
  ACCOUNT_PH: '手机号 / 单位分配的账号',
  PASSWORD: '密码',
  PASSWORD_PH: '请输入密码',
  SHOW: '显示',
  HIDE: '隐藏',
  REMEMBER: '记住账号',
  FORGOT: '忘记密码',
  SUBMIT: '登录工作台',
  SUBMITTING: '登录中…',
  WECHAT: '微信一键登录',
  AGREE_PRE: '已阅读并同意',
  AGREE_MID: '与',
  POINTS: ['电子签名固化', '弱网自动续传', '作业全程留痕'],
  STATS: [
    { n: '24/7', label: '应急响应', tone: 'amber' },
    { n: '15天', label: '半月保周期', tone: '' },
    { n: '100%', label: '签字留痕', tone: 'ok' }
  ],
  FLOORS: ['12F', '09F', '06F', '03F', '01F']
}
const RECORD_TEXT = {
  KICKER: '电梯维护保养记录',
  TITLE: '电梯维护保养记录单',
  USE_UNIT: '使用单位',
  DEVICE_CODE: '设备代码',
  MAINT_UNIT: '维保单位',
  MAINT_DATE: '维保日期',
  WORKER: '维保人',
  PROBLEM: '隐患码',
  STAT_TOTAL: '应检项',
  STAT_OK: '合格',
  STAT_FIXED: '异常已整改',
  STAT_LEFT: '遗留项',
  ABNORMAL: '异常说明',
  CONCLUSION: '维保结论',
  CONCLUSION_OK: '合格 · 可正常运行',
  SIGN_MAIN: '主检人签字',
  SIGN_ASSIST: '配合人签字',
  SIGN_CLIENT: '使用单位签字确认',
  SIGN_PENDING: '待签署',
  VERIFY: 'VERIFY / 版本与哈希',
  VERIFY_MATCH: '与签署页一致',
  VER_VERSION: '记录单版本号',
  VER_HASH: 'SHA-256 前 8 位',
  VER_TIME: '版本生成时刻',
  VERIFY_NOTE: '双签完成即冻结 v1 并生成校验值；使用单位签署页（屏 11）展示同一组「版本号 + 哈希前 8 位」供签署前核对。签署后再生成整单 PDF 哈希并同步监管平台。',
  CONCLUSION_ABNORMAL: '存在异常项 · 详见异常说明',
  SIGNED: '已签字',
  UNSIGNED: '未签字',
  SIGN_SELF: '本机签字',
  VIEW_RECORD: '查看记录单',
  ROLE_MAIN: '主检人',
  ROLE_ASSIST: '配合人',
  ROLE_NONE: '当前账号不在本工单签字名单'
}
const ORDER_TEXT = {
  TITLE: '急修处置',
  RESCUE_DONE: '救援完成 · 已闭环',
  TRAPPED_UNIT: '人',
  CONFIRMED_SHORT: '使用单位已确认',
  RESPONSE: 'RESPONSE',
  TL_REPORT: '接警',
  TL_ARRIVE: '到场',
  TL_RESCUE: '救出',
  TL_DONE: '完工',
  FAULT: 'FAULT / 故障信息',
  FAULT_PHEN: '故障现象',
  FAULT_TRAPPED: '困人情况',
  FAULT_SOURCE: '报修来源',
  REPAIR: 'REPAIR / 处置记录',
  PARTS: '更换配件',
  STOCK: '库存扣减',
  CONFIRMED: '使用单位已确认 · 工单已闭环',
  SIGN_CLIENT: '使用单位签字',
  SIGNED: '已签署',
  SIGN_NOTE: '电子签名固化',
  SIGN_GPS: '签署后写入时间戳与 GPS',
  GRAB: '抢单',
  CANCEL: '取消急修单',
  ARRIVE: '到场签到',
  SUBMIT: '提交处置',
  CONFIRM: '确认闭环'
}
const ELEVATOR_TEXT = {
  TITLE: '电梯档案',
  IN_SERVICE: '在保',
  USE_UNIT: 'USE UNIT',
  CONTRACT: 'MAINT. CONTRACT',
  NEXT_SERVICE: 'NEXT SERVICE',
  INSPECTION: 'INSPECTION',
  SERVICE_LOG: 'SERVICE LOG / 维保记录',
  ALL: '全部',
  STAT_FAULT: '近 30 天故障',
  STAT_RESPONSE: '平均响应',
  STAT_INSPECT: '距年检 / 天',
  CONTACT: 'CONTACT / 联系与合同',
  C_UNIT: '使用单位联系人',
  C_MAIN: '维保负责人',
  C_ASSIST: '配合人电话',
  C_ADDR: '设备地址',
  C_CONTRACT: '合同编号',
  QR: '设备二维码',
  LIST_TITLE: '电梯',
  SEARCH_PH: '搜索名称 / 编号 / 项目',
  COUNT_PRE: '在管电梯',
  COUNT_UNIT: '台',
  NORMAL: '正常',
  NEXT_LABEL: '下次维保',
  QR_TITLE: 'QR / 设备码',
  QR_SUB: '扫码进入档案 / 开工',
  QR_NOTE: '维保人员到场扫码开工，自动关联本次维保记录。',
  LIST_NOTE: '点电梯行进入「电梯档案」；使用单位仅见本单位电梯（ORG_FLAT 单级，不含下级）。',
  LOCAL: '本地维护（点按可现场更正）',
  PLATFORM: '平台登记（监管平台同步）'
}
const PROFILE_TEXT = {
  TITLE: '我的',
  ROLES: 'ROLES / 我的角色',
  ROLES_SUB: '一人可多角色 · 登录不选',
  CURRENT: '当前',
  DUAL: '兼任',
  WORKBENCH: 'WORKBENCH / 切换工作台',
  WORKBENCH_SUB: '仅展示有权限的端',
  NO_PERM: '无权限',
  NOTE: '账号不选角色、后台定角色：登录后进入默认工作台，可在本页切换。兼任签字人角色后方可代签，签名独立留痕。',
  MASK: '字段脱敏说明',
  MASK_CONTENT: '手机号、openid、平台密钥等敏感字段按权限脱敏展示：列表与详情默认掩码，完整值仅授权岗位经审计后调阅；签名与哈希固化留痕。',
  MESSAGE: '消息中心',
  OFFLINE: '离线队列',
  PASSWORD: '修改密码',
  WECHAT: '微信绑定',
  WECHAT_BOUND: '（已绑定）',
  ABOUT: '关于博威无纸化',
  LOGOUT: '退出登录',
  MASKED_PHONE: '（脱敏 · 屏 16）',
  ADMIN: '管理入口（按权限渲染）'
}
const ROLE_DESC = {
  MAINT_TECH: '本班组 · 点检 / 签到 / 签退 / 双签',
  MAINT_SUP: '本班组 · 派单 / 计划 / 复核 / 归档',
  MAINT_MGR: '维保部 · 台账 / 合同 / 统计 / 审核',
  MAINT_QUALITY: '维保部 · 质量抽查 / 记录复核',
  MAINT_BIZ: '维保部 · 合同 / 项目商务',
  INST_PM: '安装部项目 · 发起移交 / 整改',
  INST_CLERK: '安装部 · 资料上传 / 移交单据',
  INST_MGR: '安装部 · 容缺受理 / 内部审核',
  INSTALLER: '安装部 · 现场整改 / 自检',
  CLIENT_ADMIN: 'ORG_FLAT · 本单位台账 / 报修 / 评价',
  CLIENT_SIGNER: '记录单 / 急修单电子签署',
  PLATFORM_ADMIN: '全平台 · 组织 / 审计 / 配置',
  SYS_ADMIN: '全平台 · 系统管理'
}

module.exports = {
  HANDOVER_FLOW,
  HANDOVER_CREATE_TEXT,
  BRAND,
  LOGIN_TEXT,
  RECORD_TEXT,
  ORDER_TEXT,
  ELEVATOR_TEXT,
  PROFILE_TEXT,
  ROLE_DESC,
  ROLE,
  STATUS_TEXT,
  CHECK_RESULT_TEXT,
  PROBLEM_CODES,
  WORK_TYPE_CODES,
  MIN_WORK_DURATION_MINUTES,
  ERROR_CODES,
  IMAGE_LIMIT,
  TOAST_TEXT,
  EMPTY_TEXT,
  LOADING_TEXT,
  HANDOVER_STATUS,
  HANDOVER_STATUS_TEXT,
  HANDOVER_GROUP_LABEL,
  HANDOVER_DOCS,
  HANDOVER_RESULTS,
  HANDOVER_RESULT_TEXT,
  ELEVATOR_CATEGORIES,
  HANDOVER_WRITE_ROLES,
  HANDOVER_TEXT,
  FILING_STATUS,
  FILING_STATUS_TEXT,
  FILING_STEPS,
  FILING_DOC_TYPES,
  TAKEOVER_RESPONSIBILITY_TEXT,
  TAKEOVER_RESPONSIBILITY_OPTIONS,
  TAKEOVER_CHECK_ITEMS,
  FILING_WRITE_ROLES,
  FILING_READ_ROLES,
  FILING_TEXT,
  CALENDAR_WINDOWS,
  CALENDAR_TEXT
}

