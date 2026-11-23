// 仅用于「显示」，内部码值（与后端契约一致）保持不变；

const STATUS = {
  // 移交单
  DOC_SUBMITTING: '资料提交', INSPECTING: '查验中', RECTIFYING: '整改中', PENDING_CONFIRM: '待确认', ARCHIVED: '已归档',
  // 工单/急修
  OPEN: '待处理', ASSIGNED: '已派单', PROCESSING: '进行中', DONE: '已完成', SUBMITTED: '已提交',
  REPORTED: '已上报', FAILED: '上报失败', CANCELLED: '已取消', CLOSED: '已关闭', OVERDUE: '已超时',
  REPORTING: '上报中', REUPLOADING: '重报中', LEGACY_UPLOADING: '同步中',
  URGENT: '紧急', TRAPPED: '困人', FAULT: '故障',
  // 记录单
  REVIEWING: '待复核', SIGNING: '待签字', SIGNED: '已签字', CONFIRMED: '已确认', AMENDING: '修订中', REJECTED: '已驳回',
  REFUSED_RETURNED: '已拒签退回',
  // 通用
  ACTIVE: '启用', DISABLED: '停用', INACTIVE: '停保', NORMAL: '正常', PENDING: '待处理', IN_PROGRESS: '进行中',
  APPROVED: '已批准', EXPIRED: '已过期', REVOKED: '已回收', VOID: '已作废', SCRAPPED: '已报废', TRANSFER: '已移交',
  SUCCESS: '成功', BLOCKED: '已拦截', UNKNOWN: '上报待核对',
  // 检查项结果 / 判定结论 / 复验
  PASS: '合格', FAIL: '不合格', NA: '不适用',
  QUALIFIED: '合格', RECTIFY: '整改', DISQUALIFIED: '不合格', CONDITIONAL: '有条件移交'
}
export function statusLabel(v) {
  if (v == null || v === '') return ''
  return STATUS[String(v).toUpperCase()] || String(v)
}

const ROLE = {
  PLATFORM_ADMIN: '平台管理员',
  INST_MGR: '安装部经理', INST_PM: '安装项目经理', INST_CLERK: '安装文员', INSTALLER: '安装技师',
  MAINT_MGR: '维保部经理', MAINT_SUP: '维保主管', MAINT_TECH: '维保技术员', MAINT_BIZ: '维保商务', MAINT_QUALITY: '维保质量负责人',
  CLIENT_ADMIN: '使用单位管理员', CLIENT_SIGNER: '使用单位签字人',
  // 旧码别名
  ADMIN: '平台管理员', LEADER: '维保班组长', WORKER: '维保技术员', ASSISTANT: '维保技术员',
  INSTALL_MANAGER: '安装项目经理', INSTALL_CLERK: '安装文员', INSTALL_WORKER: '安装技师',
  MAINT_MANAGER: '维保部经理', UNIT_ADMIN: '使用单位管理员'
}
export function roleLabel(v) {
  if (v == null || v === '') return ''
  return ROLE[String(v).toUpperCase()] || String(v)
}
// 中文角色名 → 规范码（账号开通表单按中文名录入时使用；也兼容直接填码）
const ROLE_CODE_BY_NAME = {}
Object.keys(ROLE).forEach((code) => {
  const name = ROLE[code]
  if (!ROLE_CODE_BY_NAME[name]) ROLE_CODE_BY_NAME[name] = code
})
// 别名归一：优先规范码
const CANONICAL = {
  ADMIN: 'PLATFORM_ADMIN', WORKER: 'MAINT_TECH', ASSISTANT: 'MAINT_TECH',
  INSTALL_MANAGER: 'INST_PM', INSTALL_CLERK: 'INST_CLERK', INSTALL_WORKER: 'INSTALLER',
  MAINT_MANAGER: 'MAINT_MGR', UNIT_ADMIN: 'CLIENT_ADMIN'
}
export function roleCodeFromName(input) {
  const s = String(input || '').trim()
  if (!s) return ''
  const upper = s.toUpperCase()
  if (ROLE[upper]) return CANONICAL[upper] || upper
  return ROLE_CODE_BY_NAME[s] || s
}

const SCOPE = { ALL: '全部', ORG: '本部门', PROJECT: '本项目', SELF: '本人', TEAM: '本班组', ORG_FLAT: '本单位' }
export function scopeLabel(v) { return SCOPE[String(v || '').toUpperCase()] || String(v || '') }

// 员工 department 码 → 中文（使用单位是外部客户主体，不进内部组织树，故单独标注）
const DEPT = { PLATFORM: '平台', INSTALL: '安装部', MAINT: '维保部', UNIT: '使用单位（客户）' }
export function deptLabel(v) {
  if (v == null || v === '') return ''
  return DEPT[String(v).toUpperCase()] || String(v)
}

export function roleDept(v) {
  const r = String(v || '').toUpperCase()
  if (!r) return ''
  if (r.startsWith('CLIENT') || r.startsWith('UNIT')) return ''
  if (r.startsWith('INST') || r === 'INSTALLER') return 'INSTALL'
  if (r.startsWith('MAINT') || r === 'LEADER') return 'MAINT'
  return 'PLATFORM'
}

const PERM = {
  'handover:view': '查看移交', 'handover:create': '发起移交', 'handover:edit': '编辑移交',
  'handover:doc_upload': '上传移交资料', 'handover:doc_delete': '删除移交资料', 'handover:submit': '提交移交',
  'handover:review': '审核移交', 'handover:review_return': '驳回移交', 'handover:receive': '接收移交',
  'handover:inspect': '现场查验', 'handover:rectify': '整改', 'handover:sign_install': '安装签字',
  'handover:sign_maint': '维保签字', 'handover:void': '作废移交', 'handover:export': '导出移交', 'handover:notify_client': '通知使用单位',
  'project:view': '查看项目', 'project:edit': '编辑项目',
  'contract:view': '查看合同', 'contract:edit': '编辑合同',
  'elevator:view': '查看电梯', 'elevator:edit': '编辑电梯', 'elevator:qr': '电梯二维码', 'elevator:transfer': '电梯移交', 'elevator:export': '导出电梯',
  'plan:view': '查看计划', 'plan:generate': '生成计划',
  'task:accept': '接单', 'task:assign_partner': '指派配合人员', 'checklist:fill': '填写检查项',
  'record:view': '查看记录', 'record:submit': '提交记录', 'record:sign_tech': '维保签字', 'record:review': '复核记录',
  'record:sign_request': '请求签字', 'record:sign': '使用单位签字', 'record:refuse': '拒签', 'record:archive': '归档记录',
  'record:amend': '修订记录', 'record:export': '导出记录', 'record:spotcheck': '记录抽查',
  'order:create': '报修', 'order:create_proxy': '代报修', 'order:view_pool': '查看工单池', 'order:view_own': '查看本人工单',
  'order:grab': '抢单', 'order:dispatch': '派单', 'order:arrive': '到场', 'order:process': '处理工单', 'order:submit': '提交工单',
  'order:confirm': '确认工单', 'order:close': '关闭工单', 'order:cancel': '取消工单', 'order:escalate': '升级工单', 'order:export': '导出工单',
  'client:invite': '邀请使用单位', 'client:user_manage': '使用单位账号管理', 'client:elevator_view': '查看本单位电梯',
  'client:report': '报修', 'client:sign': '签字', 'client:evaluate': '评价', 'client:download': '下载', 'client:edit': '编辑使用单位',
  'user:view': '查看账号', 'user:create': '开通账号', 'user:disable': '停用账号', 'user:reset_password': '重置口令',
  'role:assign': '分配角色', 'invite:manage': '邀请管理',
  'org:create': '新建组织', 'org:edit': '编辑组织',
  'stats:view': '查看统计', 'stats:export': '导出统计', 'audit:view': '查看审计', 'config:manage': '系统配置', 'data:export_sensitive': '敏感数据导出',
  'report:view': '查看上报', 'report:retry': '人工重报',
  'region:view': '查看区域', 'region:write': '编辑区域',
  'template:view': '查看模板', 'template:write': '编辑模板', 'template:toggle': '启停模板',
  'platform:worker_view': '查看平台人员', 'platform:worker_register': '平台人员登记', 'platform:sync': '平台同步'
}
export function permLabel(v) { return PERM[String(v || '')] || String(v || '') }

const ORG_TYPE = { COMPANY: '公司', DEPT: '部门', TEAM: '班组', UNIT: '使用单位' }
export function orgTypeLabel(v) { return ORG_TYPE[String(v || '').toUpperCase()] || String(v || '') }

const CHANNEL = { PHONE: '电话', WECHAT: '微信', ONSITE: '现场' }
export function channelLabel(v) { return CHANNEL[String(v || '').toUpperCase()] || String(v || '') }

// 维保类别（与小程序 constants WORK_TYPE_CODES 一致：FM按需/HM半月/TM季度/SM半年/OY年度）
const WORK_TYPE = { FM: '按需维保', HM: '半月维保', TM: '季度维保', SM: '半年维保', OY: '年度维保' }
export function workTypeLabel(v) { return WORK_TYPE[String(v || '').toUpperCase()] || String(v || '') }

// 与后端 RecordLifecycleService.REFUSE_CODES、小程序 constantsREFUSE_REASON_OPTIONS 三方镜像同步，改动必须三侧同改；
const REFUSE_CODE = {
  R1: '记录内容与现场不符', R2: '维保项目漏项或未按单执行', R3: '现场照片不清晰或与实际不符',
  R4: '时间或人员信息有误', R5: '其他'
}
export function refuseCodeLabel(v) {
  if (v == null || v === '') return ''
  return REFUSE_CODE[String(v).toUpperCase()] || String(v)
}

export function enabledLabel(v) {
  if (v === true || v === 'true' || v === 1 || v === '1') return '启用'
  if (v === false || v === 'false' || v === 0 || v === '0') return '停用'
  return String(v ?? '')
}

const INVITE_TYPE = { INVITE: '邀请码', EMPLOYEE: '员工', UNIT: '使用单位', ROLE: '角色' }
export function inviteTypeLabel(v) { return INVITE_TYPE[String(v || '').toUpperCase()] || String(v || '') }

const ELEVATOR_ACTION = { TRANSFER: '移装', DISABLE: '停保', SCRAP: '报废' }
export function elevatorActionLabel(v) { return ELEVATOR_ACTION[String(v || '').toUpperCase()] || String(v || '') }

// 权限三态：文字与紧凑符号（矩阵用符号，避免拉丁字母）
const TRI_STATE = { Y: '可操作', C: '需审批', DENY: '禁止', '-': '禁止' }
const TRI_SYMBOL = { Y: '✓', C: '◐', DENY: '✕', '-': '✕' }
export function triStateLabel(v) { return TRI_STATE[String(v || '')] || String(v || '') }
export function triStateSymbol(v) { return TRI_SYMBOL[String(v || '')] || String(v || '') }

// DataTable 单元格默认中文化：常见枚举列自动映射；其余列可用 col.format 自定义。
export function formatCell(col, value) {
  if (typeof col.format === 'function') return col.format(value)
  const key = col.key
  if (key === 'status' || key === 'reportStatus' || key === 'confirmStatus' || key === 'result' || key === 'faultType') return statusLabel(value)
  if (key === 'role') return roleLabel(value)
  if (key === 'roles') {
    const arr = Array.isArray(value) ? value : String(value || '').split(',')
    return arr.map((x) => roleLabel(String(x).trim())).filter(Boolean).join('、')
  }
  if (key === 'workTypeCode' || key === 'workType') return workTypeLabel(value)
  if (key === 'permCode') return permLabel(value)
  if (key === 'enabled') return enabledLabel(value)
  return value
}