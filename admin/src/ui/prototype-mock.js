/* =====================================================================
 * 博威无纸化 · 管理端 · 原型 mock 基准集（ES 模块移植）
 * 逐字移植原型 UI/admin/assets/mock-data.js（梯维通 LIFT/OPS · 唯一数据源）
 * 契约：docs/16-梯维通设计输入/admin-spec.md §3/§4/§6/§9
 * 时间基准：2026-11-13（周五）。脱敏在服务端语义下按角色出值（mock 层演示）。
 * 用途：概览工作台 / 二期 4 页 / 设计规范自检 / 演示视角；真实业务视图走 api/。
 * ===================================================================== */

const TODAY = '2026-11-13'            // 周五
const NOW = '2026-11-13T09:30:00'     // SLA 计时基准
const VERSION = 'V1.0'

/* ---------- 角色（9，严格取 roles-permissions.md §1） ---------- */
const ROLES = [
  { code: 'INST_CLERK',     name: '安装部资料员',     org: '安装部',   defaultScope: 'PROJECT',  opener: '安装部主管' },
  { code: 'INST_PM',        name: '安装部项目负责人', org: '安装部',   defaultScope: 'PROJECT',  opener: '安装部主管' },
  { code: 'INST_MGR',       name: '安装部主管',       org: '安装部',   defaultScope: 'ORG',      opener: '平台管理员' },
  { code: 'MAINT_TECH',     name: '维保人员',         org: '维保部',   defaultScope: 'TEAM',     opener: '维保主管' },
  { code: 'MAINT_SUP',      name: '维保主管 / 调度',  org: '维保部',   defaultScope: 'PROJECT',  opener: '维保经理 / 平台' },
  { code: 'MAINT_MGR',      name: '维保部经理',       org: '维保部',   defaultScope: 'ALL',      opener: '平台管理员' },
  { code: 'CLIENT_ADMIN',   name: '使用单位管理员',   org: '使用单位', defaultScope: 'ORG_FLAT', opener: '平台管理员 / 维保主管' },
  { code: 'CLIENT_SIGNER',  name: '使用单位签字人',   org: '使用单位', defaultScope: 'ORG_FLAT', opener: '使用单位管理员 / 平台' },
  { code: 'PLATFORM_ADMIN', name: '平台管理员',       org: '平台',     defaultScope: 'ALL',      opener: '平台' }
]
const ROLE_ORDER = ROLES.map(function (r) { return r.code })

/* ---------- 权限码目录（71 + 4 运营，§4.1-4.6）+ Web 落地视图 ---------- */
const PERM_CATALOG = [
  ['handover:view', '查看移交单', 'handover', '#/handovers'],
  ['handover:create', '发起移交单', 'handover', '小程序侧'],
  ['handover:edit', '编辑草稿/基本信息', 'handover', '#/handovers'],
  ['handover:doc_upload', '上传技术资料', 'handover', '#/handovers'],
  ['handover:doc_delete', '删除/替换资料', 'handover', '#/handovers'],
  ['handover:submit', '提交内部审核', 'handover', '小程序侧'],
  ['handover:review', '内部审核通过', 'handover', '#/handovers'],
  ['handover:review_return', '审核退回', 'handover', '#/handovers'],
  ['handover:receive', '接收并指派', 'handover', '#/handovers'],
  ['handover:inspect', '现场验收', 'handover', '小程序侧'],
  ['handover:rectify', '整改回复', 'handover', '#/handovers'],
  ['handover:sign_install', '安装部签字', 'handover', '#/handovers'],
  ['handover:sign_maint', '维保部签字', 'handover', '#/handovers'],
  ['handover:void', '作废移交单', 'handover', '#/handovers'],
  ['handover:export', '导出 PDF/台账', 'handover', '#/handovers'],
  ['handover:notify_client', '知会使用单位', 'handover', '#/handovers'],
  ['project:view', '查看项目', 'ledger', '#/projects'],
  ['project:edit', '新增/编辑维保项目', 'ledger', '#/projects'],
  ['contract:view', '查看合同', 'ledger', '#/contracts'],
  ['contract:edit', '合同续签/变更', 'ledger', '#/contracts'],
  ['elevator:view', '查看电梯档案', 'ledger', '#/elevators'],
  ['elevator:edit', '编辑电梯基础信息', 'ledger', '#/elevators'],
  ['elevator:qr', '生成/打印设备码', 'ledger', '#/elevators'],
  ['elevator:transfer', '移装/停用/报废', 'ledger', '#/elevators'],
  ['elevator:export', '导出电梯台账', 'ledger', '#/elevators'],
  ['plan:view', '查看维保计划', 'maint', '#/plans'],
  ['plan:generate', '生成/调整计划', 'maint', '#/plans'],
  ['task:accept', '接单/开工', 'maint', '小程序侧'],
  ['task:assign_partner', '指定配合人', 'maint', '小程序侧'],
  ['checklist:fill', '填写点检/实测/拍照', 'maint', '小程序侧'],
  ['record:view', '查看记录单', 'maint', '#/records'],
  ['record:submit', '主检签名提交', 'maint', '小程序侧'],
  ['record:sign_tech', '维保双人签字', 'maint', '小程序侧'],
  ['record:review', '复核记录单', 'maint', '#/records'],
  ['record:sign_request', '发起使用单位签字', 'maint', '#/records'],
  ['record:sign', '使用单位签字', 'maint', '小程序侧'],
  ['record:refuse', '拒绝签署/提异议', 'maint', '小程序侧'],
  ['record:archive', '归档', 'maint', '#/records'],
  ['record:amend', '归档后修订', 'maint', '#/records'],
  ['record:export', '导出记录单', 'maint', '#/records'],
  ['order:create', '使用单位报修', 'order', '小程序侧'],
  ['order:create_proxy', '代报修', 'order', '#/dispatch'],
  ['order:view_pool', '查看待接单池', 'order', '#/dispatch'],
  ['order:view_own', '查看本单位工单', 'order', '#/dispatch'],
  ['order:grab', '抢单', 'order', '小程序侧'],
  ['order:dispatch', '派单/改派', 'order', '#/dispatch'],
  ['order:arrive', '到场打卡', 'order', '小程序侧'],
  ['order:process', '处置/更换配件', 'order', '小程序侧'],
  ['order:submit', '提交急修单', 'order', '小程序侧'],
  ['order:confirm', '使用单位确认', 'order', '小程序侧'],
  ['order:close', '关闭工单', 'order', '#/dispatch'],
  ['order:cancel', '取消工单', 'order', '#/dispatch'],
  ['order:escalate', '超时升级', 'order', '#/dispatch'],
  ['order:export', '导出工单', 'order', '#/dispatch'],
  ['client:invite', '生成邀请码/组织码', 'client', '#/grants'],
  ['client:user_manage', '维护本单位人员', 'client', '#/users'],
  ['client:elevator_view', '查看本单位电梯', 'client', '#/elevators'],
  ['client:report', '一键报修', 'client', '小程序侧'],
  ['client:sign', '签署记录单/急修单', 'client', '小程序侧'],
  ['client:evaluate', '服务评价', 'client', '#/reviews'],
  ['client:download', '下载本单位单据', 'client', '#/records'],
  ['user:view', '查看人员', 'org', '#/users'],
  ['user:create', '开通账号', 'org', '#/users'],
  ['user:disable', '停用/离职', 'org', '#/users'],
  ['role:assign', '分配角色', 'org', '#/roles'],
  ['invite:manage', '邀请码管理', 'org', '#/grants'],
  ['stats:view', '查看统计', 'org', '#/stats'],
  ['stats:export', '导出报表', 'org', '#/stats'],
  ['audit:view', '查看审计日志', 'org', '#/audit'],
  ['config:manage', '系统配置', 'org', '#/settings'],
  ['data:export_sensitive', '导出敏感数据', 'org', '#/stats'],
  ['sync:view', '查看监管平台同步', 'ops', '#/sync'],
  ['msg:manage', '消息模板管理', 'ops', '#/msg-templates'],
  ['review:view', '查看服务评价', 'ops', '#/reviews'],
  ['board:view', '大屏调度', 'ops', '#/board']
]

/* ---------- 权限矩阵（75×9，Y/C/-，严格取 roles §4.1-4.6） ----------
 * 列顺序 = ROLE_ORDER：CLERK,PM,INST_MGR,TECH,SUP,MGR,CADMIN,CSIGN,PLAT
 * Y=允许  C=只读/需审批/仅本人或指派  -=禁止
 * P1 T2（DC4）：客户列 CADMIN/CSIGN 对齐根镜像 constants/permission-matrix.js 最小集——
 * client:elevator_view/download/sign=Y（sign 两角色 Y），report/evaluate/user_manage/invite 与全部内部码=- */
const MATRIX_RAW = {
  'handover:view':            'CCY-YY--Y',
  'handover:create':          '-Y-------',
  'handover:edit':            'CY-------',
  'handover:doc_upload':      'YY-------',
  'handover:doc_delete':      'YY-------',
  'handover:submit':          '-Y-------',
  'handover:review':          '--Y------',
  'handover:review_return':   '--Y------',
  'handover:receive':         '----Y----',
  'handover:inspect':         '---C-----',
  'handover:rectify':         'YY-------',
  'handover:sign_install':    '--Y------',
  'handover:sign_maint':      '----YY---',
  'handover:void':            '--Y--Y--Y',
  'handover:export':          '--Y-YY--Y',
  'handover:notify_client':   '----Y----',
  'project:view':             'CCC-YY--Y',
  'project:edit':             '-----Y--Y',
  'contract:view':            '----CY--Y',
  'contract:edit':            '-----Y--Y',
  'elevator:view':            'CCCCCC--C',
  'elevator:edit':            '----Y---Y',
  'elevator:qr':              '---YY----',
  'elevator:transfer':        '-----Y--Y',
  'elevator:export':          '----CY--Y',
  'plan:view':                '---CYY---',
  'plan:generate':            '----YY---',
  'task:accept':              '---Y-----',
  'task:assign_partner':      '---YY----',
  'checklist:fill':           '---Y-----',
  'record:view':              '---CYY--Y',
  'record:submit':            '---Y-----',
  'record:sign_tech':         '---Y-----',
  'record:review':            '----YY---',
  'record:sign_request':      '---YY----',
  'record:sign':              '---------',
  'record:refuse':            '---------',
  'record:archive':           '----Y----',
  'record:amend':             '-----Y--Y',
  'record:export':            '----YY--Y',
  'order:create':             '---------',
  'order:create_proxy':       '----Y----',
  'order:view_pool':          '---CYY---',
  'order:view_own':           '---------',
  'order:grab':               '---Y-----',
  'order:dispatch':           '----YY---',
  'order:arrive':             '---C-----',
  'order:process':            '---C-----',
  'order:submit':             '---Y-----',
  'order:confirm':            '---------',
  'order:close':              '----Y----',
  'order:cancel':             '----Y----',
  'order:escalate':           '-----Y---',
  'order:export':             '----YY--Y',
  'client:invite':            '----Y----',
  'client:user_manage':       '---------',
  'client:elevator_view':     '------YY-',
  'client:report':            '---------',
  'client:sign':              '------YY-',
  'client:evaluate':          '---------',
  'client:download':          '------YY-',
  'user:view':                '--C-CC--Y',
  'user:create':              '--Y-YY--Y',
  'user:disable':             '--Y-YY--Y',
  'role:assign':              '--------Y',
  'invite:manage':            '--Y-YY--Y',
  'stats:view':               '--Y-YY--Y',
  'stats:export':             '--C-CY--Y',
  'audit:view':               '--------Y',
  'config:manage':            '--------Y',
  'data:export_sensitive':    '--------Y',
  'sync:view':                '----YY--Y',
  'msg:manage':               '--------Y',
  'review:view':              '----YY--Y',
  'board:view':               '----YY--Y'
}
const MATRIX = {}
Object.keys(MATRIX_RAW).forEach(function (code) {
  const row = {}
  const s = MATRIX_RAW[code]
  ROLE_ORDER.forEach(function (rc, i) { row[rc] = s.charAt(i) })
  MATRIX[code] = row
})

/* ---------- 可切换 5 视角（spec §3） ---------- */
const SWITCH_ROLES = [
  { role: 'PLATFORM_ADMIN', accountId: 'A10', account: '陈立', empNo: 'T0001', scope: 'ALL',      scopeLabel: '全量（运维）',     orgId: 'O5',  projectIds: ['P1', 'P2', 'P3', 'P4', 'P5'], opsReadonly: ['plan:view', 'order:view_pool'] },
  { role: 'MAINT_MGR',      accountId: 'A07', account: '赵启明', empNo: 'T3000', scope: 'ALL',      scopeLabel: '全部维保项目',     orgId: 'O1',  projectIds: ['P1', 'P2', 'P3', 'P4', 'P5'], opsReadonly: [] },
  { role: 'MAINT_SUP',      accountId: 'A06', account: '孙岚', empNo: 'T3100', scope: 'PROJECT',  scopeLabel: '城东维保项目部',   orgId: 'O11', projectIds: ['P1', 'P2', 'P3'],             opsReadonly: [] },
  { role: 'INST_MGR',       accountId: 'A08', account: '周建国', empNo: 'T2200', scope: 'ORG',      scopeLabel: '中建安装及下级',   orgId: 'O2',  projectIds: ['P4', 'P5'],                   opsReadonly: [] },
  { role: 'CLIENT_ADMIN',   accountId: 'A09', account: '吴敏', empNo: 'T4100', scope: 'ORG_FLAT', scopeLabel: '启元物业（本单位）', orgId: 'O3',  projectIds: ['P1', 'P2', 'P3'],             opsReadonly: [] }
]

/* ---------- 组织树 ---------- */
const ORGS = [
  { id: 'O1',  name: '安捷电梯',       type: '维保',     code: 'AJ',     parentId: null, leader: '赵启明', phone: '13800001111', status: '正常' },
  { id: 'O11', name: '城东维保项目部', type: '维保',     code: 'AJ-CD',  parentId: 'O1', leader: '孙岚',   phone: '13800002222', status: '正常' },
  { id: 'O12', name: '城南维保项目部', type: '维保',     code: 'AJ-CN',  parentId: 'O1', leader: '赵启明', phone: '13800003333', status: '正常' },
  { id: 'O2',  name: '中建安装',       type: '安装',     code: 'ZJ',     parentId: null, leader: '周建国', phone: '13800004444', status: '正常' },
  { id: 'O21', name: '安装一部',       type: '安装',     code: 'ZJ-01',  parentId: 'O2', leader: '王强',   phone: '13800005555', status: '正常' },
  { id: 'O22', name: '安装二部',       type: '安装',     code: 'ZJ-02',  parentId: 'O2', leader: '冯军',   phone: '13800006666', status: '正常' },
  { id: 'O3',  name: '启元物业',       type: '使用单位', code: 'QY',     parentId: null, leader: '吴敏',   phone: '13800007777', administer: '张伟', administerPhone: '13811110005', emergencyPhone: '400-820-8011', status: '正常', flat: true },
  { id: 'O4',  name: '云栖里物业',     type: '使用单位', code: 'YQL',    parentId: null, leader: '郑洁',   phone: '13800008888', administer: '李娜', administerPhone: '13811110016', emergencyPhone: '400-820-8022', status: '正常', flat: true },
  { id: 'O5',  name: '平台运维',       type: '平台',     code: 'PLAT',   parentId: null, leader: '陈立',   phone: '13800009999', status: '正常' }
]

/* ---------- 项目（5；台量 6+4+4+3+3=20） ---------- */
const PROJECTS = [
  { id: 'P1', code: 'XB-2025-01', name: '城东大厦',   clientId: 'O3', maintDeptId: 'O11', instUnit: '中建安装', elevatorCount: 6, contractId: 'HT-2026-0312', status: '在保',   lastWorkDate: '2026-11-10' },
  { id: 'P2', code: 'XB-2025-02', name: '汇金中心',   clientId: 'O3', maintDeptId: 'O11', instUnit: '中建安装', elevatorCount: 4, contractId: 'HT-2026-0313', status: '在保',   lastWorkDate: '2026-11-08' },
  { id: 'P3', code: 'XB-2025-03', name: '城市之光',   clientId: 'O3', maintDeptId: 'O11', instUnit: '中建安装', elevatorCount: 4, contractId: 'HT-2026-0314', status: '在保',   lastWorkDate: '2026-11-05' },
  { id: 'P4', code: 'YQ-2025-08', name: '云栖里',     clientId: 'O4', maintDeptId: 'O12', instUnit: '中建安装', instDeptId: 'O21', elevatorCount: 3, contractId: 'HT-2026-0315', status: '待接管', lastWorkDate: '2026-10-28' },
  { id: 'P5', code: 'YQ-2025-09', name: '华府名邸',   clientId: 'O4', maintDeptId: 'O12', instUnit: '中建安装', instDeptId: 'O22', elevatorCount: 3, contractId: 'HT-2026-0316', status: '待接管', lastWorkDate: '2026-10-20' }
]

/* ---------- 电梯（20） ---------- */
function elev(id, code, projectId, name, model, fld, reg, install, next, status, addr) {
  return { id: id, code: code, projectId: projectId, name: name, model: model, floors: fld[0], stations: fld[1], doors: fld[2], regCode: reg, installDate: install, lastMaintDate: null, nextMaintDue: next, status: status, address: addr }
}
const ELEVATORS = [
  elev('E01', 'DT-CB-0318', 'P1', '2# 客梯', 'NEXWAY-S', [18, 18, 2], '310101010120260001', '2025-03-18', '2026-11-25', '在保', '城东大厦 2# 楼 · 机房 A'),
  elev('E02', 'DT-CB-0319', 'P1', '1# 客梯', 'NEXWAY-S', [18, 18, 2], '310101010120260002', '2025-03-18', '2026-11-25', '在保', '城东大厦 1# 楼 · 机房 A'),
  elev('E03', 'DT-CB-0320', 'P1', '3# 客梯', 'NEXWAY-S', [18, 18, 2], '310101010120260003', '2025-03-20', '2026-11-27', '在保', '城东大厦 3# 楼 · 机房 B'),
  elev('E04', 'DT-CB-0321', 'P1', '1# 货梯', 'NEXWAY-F', [18, 18, 2], '310101010120260004', '2025-03-20', '2026-11-27', '在保', '城东大厦 1# 楼 · 机房 B'),
  elev('E05', 'DT-CB-0322', 'P1', '2# 货梯', 'NEXWAY-F', [18, 18, 2], '310101010120260005', '2025-03-22', '2026-11-29', '在保', '城东大厦 2# 楼 · 机房 B'),
  elev('E06', 'DT-CB-0323', 'P1', '1# 扶梯', 'FT-800',   [3, 3, 1],  '310101010120260006', '2025-03-22', '2026-11-29', '在保', '城东大厦 裙楼 · 中庭'),
  elev('E07', 'DT-HJ-0101', 'P2', '1# 客梯', 'NEXWAY-S', [22, 22, 2], '310101010120260007', '2025-05-10', '2026-11-23', '在保', '汇金中心 1# 楼 · 机房 A'),
  elev('E08', 'DT-HJ-0102', 'P2', '2# 客梯', 'NEXWAY-S', [22, 22, 2], '310101010120260008', '2025-05-10', '2026-11-23', '在保', '汇金中心 2# 楼 · 机房 A'),
  elev('E09', 'DT-HJ-0103', 'P2', '1# 货梯', 'NEXWAY-F', [22, 22, 2], '310101010120260009', '2025-05-12', '2026-11-25', '在保', '汇金中心 1# 楼 · 机房 B'),
  elev('E10', 'DT-HJ-0104', 'P2', '1# 扶梯', 'FT-800',   [4, 4, 1],  '310101010120260010', '2025-05-12', '2026-11-25', '在保', '汇金中心 裙楼 · 中庭'),
  elev('E11', 'DT-CG-0201', 'P3', '1# 客梯', 'NEXWAY-S', [26, 26, 2], '310101010120260011', '2025-07-01', '2026-11-20', '在保', '城市之光 1# 楼 · 机房 A'),
  elev('E12', 'DT-CG-0202', 'P3', '2# 客梯', 'NEXWAY-S', [26, 26, 2], '310101010120260012', '2025-07-01', '2026-11-20', '在保', '城市之光 2# 楼 · 机房 A'),
  elev('E13', 'DT-CG-0203', 'P3', '3# 客梯', 'NEXWAY-S', [26, 26, 2], '310101010120260013', '2025-07-03', '2026-11-22', '在保', '城市之光 3# 楼 · 机房 B'),
  elev('E14', 'DT-CG-0204', 'P3', '1# 货梯', 'NEXWAY-F', [26, 26, 2], '310101010120260014', '2025-07-03', '2026-11-22', '在保', '城市之光 1# 楼 · 机房 B'),
  elev('E15', 'DT-YQ-0301', 'P4', '1# 客梯', 'NEXWAY-S', [11, 11, 2], '310101010120260015', '2026-06-15', '2026-11-28', '待接管', '云栖里 1# 楼 · 机房 A'),
  elev('E16', 'DT-YQ-0302', 'P4', '2# 客梯', 'NEXWAY-S', [11, 11, 2], '310101010120260016', '2026-06-15', '2026-11-28', '待接管', '云栖里 2# 楼 · 机房 A'),
  elev('E17', 'DT-YQ-0303', 'P4', '1# 货梯', 'NEXWAY-F', [11, 11, 2], '310101010120260017', '2026-06-18', '2026-11-30', '待接管', '云栖里 1# 楼 · 机房 B'),
  elev('E18', 'DT-HF-0401', 'P5', '1# 客梯', 'NEXWAY-S', [9, 9, 2],  '310101010120260018', '2026-08-01', '2026-12-02', '待接管', '华府名邸 1# 楼 · 机房 A'),
  elev('E19', 'DT-HF-0402', 'P5', '2# 客梯', 'NEXWAY-S', [9, 9, 2],  '310101010120260019', '2026-08-01', '2026-12-02', '待接管', '华府名邸 2# 楼 · 机房 A'),
  elev('E20', 'DT-HF-0403', 'P5', '1# 扶梯', 'FT-800',   [2, 2, 1],  '310101010120260020', '2026-08-05', '2026-12-05', '待接管', '华府名邸 裙楼 · 入口')
]

/* ---------- 合同（6；金额分级脱敏） ---------- */
const CONTRACTS = [
  { id: 'HT-2026-0312', projectId: 'P1', maintUnit: '安捷电梯', clientId: 'O3', cycle: '半月保', units: 6, start: '2026-01-01', end: '2026-12-31', amount: 138000, status: '履行中' },
  { id: 'HT-2026-0313', projectId: 'P2', maintUnit: '安捷电梯', clientId: 'O3', cycle: '半月保', units: 4, start: '2026-02-01', end: '2026-12-31', amount: 96000,  status: '履行中' },
  { id: 'HT-2026-0314', projectId: 'P3', maintUnit: '安捷电梯', clientId: 'O3', cycle: '半月保', units: 4, start: '2026-03-01', end: '2026-11-30', amount: 88000,  status: '临期' },
  { id: 'HT-2026-0315', projectId: 'P4', maintUnit: '安捷电梯', clientId: 'O4', cycle: '季度保', units: 3, start: '2026-06-01', end: '2026-12-31', amount: 45000,  status: '待续签' },
  { id: 'HT-2026-0316', projectId: 'P5', maintUnit: '安捷电梯', clientId: 'O4', cycle: '半月保', units: 3, start: '2025-12-01', end: '2026-11-20', amount: 52000,  status: '临期' },
  { id: 'HT-2026-0317', projectId: 'P1', maintUnit: '安捷电梯', clientId: 'O3', cycle: '半月保', units: 6, start: '2025-01-01', end: '2025-12-31', amount: 130000, status: '已到期' }
]

/* ---------- 账号（18，覆盖 4 状态） ---------- */
function acct(id, name, empNo, phone, orgId, roles, scope, status, lastLogin, openOrders) {
  return { id: id, name: name, empNo: empNo, phone: phone, orgId: orgId, roles: roles, scope: scope, status: status, lastLogin: lastLogin, openOrders: openOrders }
}
const ACCOUNTS = [
  acct('A01', '李振国', 'T3101', '13811110001', 'O11', ['MAINT_TECH'], 'TEAM', '正常', '2026-11-13 08:12', 2),
  acct('A02', '王海涛', 'T3122', '13811110002', 'O11', ['MAINT_TECH'], 'TEAM', '正常', '2026-11-13 08:20', 1),
  acct('A03', '王强',   'T2201', '13811110003', 'O21', ['INST_PM'], 'PROJECT', '正常', '2026-11-12 17:40', 0),
  acct('A04', '刘倩',   'T2202', '13811110004', 'O21', ['INST_CLERK'], 'PROJECT', '正常', '2026-11-12 16:05', 0),
  acct('A05', '张伟',   'T4101', '13811110005', 'O3', ['CLIENT_SIGNER'], 'ORG_FLAT', '正常', '2026-11-13 09:01', 0),
  acct('A06', '孙岚',   'T3100', '13811110006', 'O11', ['MAINT_SUP', 'MAINT_TECH'], 'PROJECT', '正常', '2026-11-13 07:55', 3),
  acct('A07', '赵启明', 'T3000', '13811110007', 'O1', ['MAINT_MGR'], 'ALL', '正常', '2026-11-13 08:30', 0),
  acct('A08', '周建国', 'T2200', '13811110008', 'O2', ['INST_MGR', 'INST_PM'], 'ORG', '正常', '2026-11-12 18:10', 0),
  acct('A09', '吴敏',   'T4100', '13811110009', 'O3', ['CLIENT_ADMIN', 'CLIENT_SIGNER'], 'ORG_FLAT', '正常', '2026-11-13 08:45', 0),
  acct('A10', '陈立',   'T0001', '13811110010', 'O5', ['PLATFORM_ADMIN'], 'ALL', '正常', '2026-11-13 09:10', 0),
  acct('A11', '陈志远', 'T3123', '13811110011', 'O11', ['MAINT_TECH'], 'TEAM', '正常', '2026-11-12 20:15', 1),
  acct('A12', '赵磊',   'T3124', '13811110012', 'O12', ['MAINT_TECH'], 'TEAM', '待激活', '—', 0),
  acct('A13', '周涛',   'T3125', '13811110013', 'O12', ['MAINT_TECH'], 'TEAM', '正常', '2026-11-11 19:22', 1),
  acct('A14', '冯军',   'T2203', '13811110014', 'O22', ['INST_PM'], 'PROJECT', '正常', '2026-11-10 15:30', 0),
  acct('A15', '许静',   'T2204', '13811110015', 'O22', ['INST_CLERK'], 'PROJECT', '停用', '2026-10-30 11:00', 0),
  acct('A16', '李娜',   'T4201', '13811110016', 'O4', ['CLIENT_SIGNER'], 'ORG_FLAT', '正常', '2026-11-12 14:18', 0),
  acct('A17', '吴刚',   'T3126', '13811110017', 'O11', ['MAINT_TECH'], 'TEAM', '离职', '2026-09-30 18:00', 0),
  acct('A18', '郑洁',   'T4200', '13811110018', 'O4', ['CLIENT_ADMIN'], 'ORG_FLAT', '正常', '2026-11-11 10:40', 0)
]

/* ---------- 移交单（12，覆盖 10 态） ---------- */
const HANDOVER_STATES = ['草稿', '待安装部审核', '退回整改', '待维保接收', '验收中', '待双方签字', '待维保签字', '已生效', '已归档', '已作废']
function ho(no, projectId, devices, initiator, status, created, extra) {
  const o = { no: no, projectId: projectId, deviceCodes: devices, initiator: initiator, instUnit: '中建安装', maintUnit: '安捷电梯', elevatorCount: devices.length, createdAt: created, status: status, docs: 6, missing: 0, tolerant: false, timeline: [] }
  if (extra) { Object.keys(extra).forEach(function (k) { o[k] = extra[k] }) }
  return o
}
const HANDOVERS = [
  ho('YJ-2026-0117', 'P4', ['DT-YQ-0301', 'DT-YQ-0302', 'DT-YQ-0303'], 'A03', '待维保接收', '2026-10-28T10:00:00', { missing: 1, docs: 5 }),
  ho('YJ-2026-0118', 'P5', ['DT-HF-0401', 'DT-HF-0402', 'DT-HF-0403'], 'A14', '验收中', '2026-10-20T09:30:00'),
  ho('YJ-2026-0101', 'P1', ['DT-CB-0318'], 'A03', '已归档', '2025-03-18T09:00:00'),
  ho('YJ-2026-0102', 'P1', ['DT-CB-0319', 'DT-CB-0320'], 'A03', '已归档', '2025-03-18T09:20:00'),
  ho('YJ-2026-0103', 'P2', ['DT-HJ-0101', 'DT-HJ-0102'], 'A03', '已生效', '2025-05-10T10:00:00'),
  ho('YJ-2026-0104', 'P3', ['DT-CG-0201'], 'A14', '待双方签字', '2026-09-15T14:00:00'),
  ho('YJ-2026-0105', 'P3', ['DT-CG-0202', 'DT-CG-0203'], 'A14', '待维保签字', '2026-09-20T11:00:00'),
  ho('YJ-2026-0106', 'P4', ['DT-YQ-0301'], 'A03', '草稿', '2026-11-01T16:00:00'),
  ho('YJ-2026-0107', 'P5', ['DT-HF-0401'], 'A14', '待安装部审核', '2026-11-05T10:30:00', { missing: 1, docs: 5, tolerant: true }),
  ho('YJ-2026-0108', 'P2', ['DT-HJ-0103'], 'A03', '退回整改', '2026-10-12T15:00:00'),
  ho('YJ-2026-0109', 'P3', ['DT-CG-0204'], 'A14', '已作废', '2026-08-01T09:00:00', { voidReason: '项目规划调整' }),
  ho('YJ-2026-0110', 'P1', ['DT-CB-0321', 'DT-CB-0322', 'DT-CB-0323'], 'A03', '已归档', '2025-03-22T09:00:00')
]

/* ---------- 记录单（14，覆盖 7 态 + 修订） ---------- */
const RECORD_STATES = ['作业中', '待配合人签字', '待复核', '待使用单位签署', '已签署', '待整改', '已归档']
function rec(no, device, projectId, primary, partner, status, work, pass, abn, extra) {
  const o = { no: no, deviceCode: device, projectId: projectId, primaryId: primary, partnerId: partner, status: status, workDate: work, checkPass: pass, checkAbnormal: abn, version: 'v1', hash8: '', primarySignAt: null, partnerSignAt: null, clientSignAt: null, signRequestAt: null, sampled: false, versions: [] }
  if (extra) { Object.keys(extra).forEach(function (k) { o[k] = extra[k] }) }
  return o
}
const RECORDS = [
  rec('WB-2026-1113-0231', 'DT-CB-0318', 'P1', 'A01', 'A02', '待复核', '2026-11-13', 26, 2, { primarySignAt: '2026-11-13T08:40:00', partnerSignAt: '2026-11-13T08:55:00', hash8: 'a3f19c2e', sampled: true }),
  rec('WB-2026-1110-0228', 'DT-CB-0318', 'P1', 'A01', 'A02', '已归档', '2026-11-10', 28, 0, { primarySignAt: '2026-11-10T09:10:00', partnerSignAt: '2026-11-10T09:25:00', clientSignAt: '2026-11-10T10:24:00', hash8: 'b7e20d41' }),
  rec('WB-2026-1028-0210', 'DT-CB-0318', 'P1', 'A01', 'A11', '已归档', '2026-10-28', 28, 0, { primarySignAt: '2026-10-28T09:00:00', partnerSignAt: '2026-10-28T09:18:00', clientSignAt: '2026-10-28T11:02:00', hash8: 'c1d43e56' }),
  rec('WB-2026-1108-0225', 'DT-HJ-0101', 'P2', 'A01', 'A02', '已归档', '2026-11-08', 27, 1, { primarySignAt: '2026-11-08T09:05:00', partnerSignAt: '2026-11-08T09:22:00', clientSignAt: '2026-11-08T10:40:00', hash8: 'd9a87f03' }),
  rec('WB-2026-1105-0220', 'DT-CG-0201', 'P3', 'A11', 'A02', '已归档', '2026-11-05', 28, 0, { primarySignAt: '2026-11-05T08:50:00', partnerSignAt: '2026-11-05T09:08:00', clientSignAt: '2026-11-05T10:15:00', hash8: 'e2b61a98' }),
  rec('WB-2026-1113-0232', 'DT-CB-0319', 'P1', 'A01', 'A02', '待配合人签字', '2026-11-13', 24, 0, { primarySignAt: '2026-11-13T09:00:00', hash8: 'f4c02b7d' }),
  rec('WB-2026-1113-0233', 'DT-CB-0320', 'P1', 'A11', 'A02', '作业中', '2026-11-13', 12, 0),
  rec('WB-2026-1112-0230', 'DT-HJ-0102', 'P2', 'A01', 'A02', '待使用单位签署', '2026-11-12', 28, 0, { primarySignAt: '2026-11-12T09:00:00', partnerSignAt: '2026-11-12T09:20:00', signRequestAt: '2026-11-12T09:30:00', hash8: '0a9d3e6f', sampled: true }),
  rec('WB-2026-1111-0229', 'DT-CG-0202', 'P3', 'A11', 'A02', '已签署', '2026-11-11', 28, 0, { primarySignAt: '2026-11-11T08:45:00', partnerSignAt: '2026-11-11T09:02:00', clientSignAt: '2026-11-11T10:30:00', hash8: '1b8e4f70' }),
  rec('WB-2026-1110-0227', 'DT-CG-0203', 'P3', 'A01', 'A11', '待整改', '2026-11-10', 25, 3, { primarySignAt: '2026-11-10T09:00:00', partnerSignAt: '2026-11-10T09:15:00', hash8: '2c7f5081' }),
  rec('WB-2026-1028-0209', 'DT-YQ-0301', 'P4', 'A13', 'A12', '已归档', '2026-10-28', 28, 0, { primarySignAt: '2026-10-28T10:00:00', partnerSignAt: '2026-10-28T10:18:00', clientSignAt: '2026-10-28T11:30:00', hash8: '3d606192' }),
  rec('WB-2026-1020-0200', 'DT-HF-0401', 'P5', 'A13', 'A12', '已归档', '2026-10-20', 28, 0, { primarySignAt: '2026-10-20T09:30:00', partnerSignAt: '2026-10-20T09:48:00', clientSignAt: '2026-10-20T11:00:00', hash8: '4e5172a3' }),
  rec('WB-2026-1101-0215', 'DT-CB-0321', 'P1', 'A01', 'A02', '已归档', '2026-11-01', 28, 0, { primarySignAt: '2026-11-01T09:00:00', partnerSignAt: '2026-11-01T09:16:00', clientSignAt: '2026-11-01T10:20:00', hash8: '5f4283b4', amended: true, versions: ['v1', 'v2'] }),
  rec('WB-2026-1103-0218', 'DT-HJ-0103', 'P2', 'A01', 'A11', '已归档', '2026-11-03', 27, 1, { primarySignAt: '2026-11-03T09:10:00', partnerSignAt: '2026-11-03T09:28:00', clientSignAt: '2026-11-03T10:45:00', hash8: '603394c5', sampled: true })
]

/* ---------- 工单（16，覆盖 7 态） ---------- */
const ORDER_STATES = ['待接单', '已接单', '处置中', '待使用单位确认', '待整改', '已闭环', '已取消']
function ord(no, type, level, device, projectId, reporter, phone, status, sla, created, owner, source, extra) {
  const o = { no: no, type: type, level: level, deviceCode: device, projectId: projectId, reporter: reporter, phone: phone, status: status, slaMinutes: sla, createdAt: created, owner: owner, source: source, timeline: [], elapsed: 0, overtime: false }
  if (extra) { Object.keys(extra).forEach(function (k) { o[k] = extra[k] }) }
  return o
}
const ORDERS = [
  ord('JX-2026-1113-007', '急修', 'P1', 'DT-CB-0318', 'P1', '张伟', '13811110005', '处置中', 30, '2026-11-13T09:05:00', 'A01', '小程序', { fault: '困人 · 2# 客梯停在 8-9 层间', gps: '31.2304,121.4737' }),
  ord('RX-11013', '报修', 'P2', 'DT-CB-0319', 'P1', '吴敏', '13811110009', '待接单', 240, '2026-11-13T08:40:00', null, '小程序', { fault: '1# 客梯门机异响' }),
  ord('JX-2026-1113-008', '急修', 'P1', 'DT-HJ-0101', 'P2', '张伟', '13811110005', '待接单', 30, '2026-11-13T09:12:00', null, '电话代报修', { fault: '困人 · 汇金 1# 客梯', recNo: 'REC-20261' }),
  ord('RX-11014', '报修', 'P3', 'DT-CG-0201', 'P3', '吴敏', '13811110009', '已接单', 240, '2026-11-13T07:50:00', 'A11', '小程序', { fault: '城市之光 1# 客梯平层偏差' }),
  ord('JX-2026-1112-006', '急修', 'P2', 'DT-CB-0321', 'P1', '张伟', '13811110005', '待使用单位确认', 120, '2026-11-12T14:20:00', 'A01', '小程序', { fault: '货梯门锁触点故障' }),
  ord('RX-11012', '报修', 'P3', 'DT-HJ-0102', 'P2', '吴敏', '13811110009', '已闭环', 240, '2026-11-11T10:00:00', 'A02', '小程序', { fault: '2# 客梯按钮失灵' }),
  ord('RX-11011', '报修', 'P2', 'DT-CG-0202', 'P3', '吴敏', '13811110009', '待整改', 240, '2026-11-10T16:30:00', 'A11', '小程序', { fault: '2# 客梯运行抖动' }),
  ord('JX-2026-1110-005', '急修', 'P1', 'DT-YQ-0301', 'P4', '李娜', '13811110016', '已闭环', 30, '2026-11-10T09:00:00', 'A13', '电话代报修', { fault: '困人 · 云栖里 1# 客梯', recNo: 'REC-20258' }),
  ord('RX-11010', '报修', 'P3', 'DT-HF-0401', 'P5', '李娜', '13811110016', '已取消', 240, '2026-11-09T11:20:00', null, '小程序', { fault: '误报 · 已自行恢复', cancelReason: '使用单位误报' }),
  ord('RX-11009', '报修', 'P2', 'DT-CB-0320', 'P1', '吴敏', '13811110009', '已闭环', 240, '2026-11-08T09:40:00', 'A01', '小程序', { fault: '3# 客梯照明故障' }),
  ord('JX-2026-1108-004', '急修', 'P2', 'DT-HJ-0103', 'P2', '张伟', '13811110005', '已闭环', 120, '2026-11-08T15:10:00', 'A02', '小程序', { fault: '货梯超载报警' }),
  ord('RX-11008', '报修', 'P3', 'DT-CG-0203', 'P3', '吴敏', '13811110009', '待接单', 240, '2026-11-13T08:10:00', null, '小程序', { fault: '3# 客梯显示异常' }),
  ord('RX-11007', '报修', 'P2', 'DT-CB-0322', 'P1', '吴敏', '13811110009', '处置中', 240, '2026-11-12T13:00:00', 'A11', '小程序', { fault: '2# 货梯门滑块磨损' }),
  ord('JX-2026-1111-003', '急修', 'P1', 'DT-CG-0204', 'P3', '张伟', '13811110005', '待使用单位确认', 30, '2026-11-11T17:45:00', 'A11', '小程序', { fault: '货梯急停' }),
  ord('RX-11006', '报修', 'P3', 'DT-YQ-0302', 'P4', '李娜', '13811110016', '已接单', 240, '2026-11-12T09:00:00', 'A13', '小程序', { fault: '云栖里 2# 客梯异响' }),
  ord('RX-11005', '报修', 'P2', 'DT-HF-0402', 'P5', '李娜', '13811110016', '待接单', 240, '2026-11-13T07:30:00', null, '小程序', { fault: '华府名邸 2# 客梯按钮卡滞' })
]

/* ---------- 维保计划（12） ---------- */
const PLANS = [
  { no: 'JH-2026-1113-01', projectId: 'P1', deviceCode: 'DT-CB-0318', type: '半月保', planDate: '2026-11-25', team: '城东一班', primaryId: 'A01', status: '待执行', source: '合同周期自动' },
  { no: 'JH-2026-1113-02', projectId: 'P1', deviceCode: 'DT-CB-0319', type: '半月保', planDate: '2026-11-25', team: '城东一班', primaryId: 'A01', status: '待执行', source: '合同周期自动' },
  { no: 'JH-2026-1113-03', projectId: 'P1', deviceCode: 'DT-CB-0320', type: '半月保', planDate: '2026-11-27', team: '城东一班', primaryId: 'A11', status: '执行中', source: '合同周期自动' },
  { no: 'JH-2026-1110-04', projectId: 'P1', deviceCode: 'DT-CB-0318', type: '半月保', planDate: '2026-11-10', team: '城东一班', primaryId: 'A01', status: '已完成', source: '合同周期自动' },
  { no: 'JH-2026-1108-05', projectId: 'P2', deviceCode: 'DT-HJ-0101', type: '半月保', planDate: '2026-11-08', team: '城东二班', primaryId: 'A01', status: '已完成', source: '合同周期自动' },
  { no: 'JH-2026-1123-06', projectId: 'P2', deviceCode: 'DT-HJ-0102', type: '半月保', planDate: '2026-11-23', team: '城东二班', primaryId: 'A02', status: '待执行', source: '合同周期自动' },
  { no: 'JH-2026-1105-07', projectId: 'P3', deviceCode: 'DT-CG-0201', type: '半月保', planDate: '2026-11-05', team: '城东二班', primaryId: 'A11', status: '已完成', source: '合同周期自动' },
  { no: 'JH-2026-1120-08', projectId: 'P3', deviceCode: 'DT-CG-0202', type: '半月保', planDate: '2026-11-20', team: '城东二班', primaryId: 'A11', status: '待执行', source: '手动调整' },
  { no: 'JH-2026-1101-09', projectId: 'P3', deviceCode: 'DT-CG-0203', type: '半月保', planDate: '2026-11-01', team: '城东二班', primaryId: 'A01', status: '已逾期', source: '合同周期自动' },
  { no: 'JH-2026-1128-10', projectId: 'P4', deviceCode: 'DT-YQ-0301', type: '季度保', planDate: '2026-11-28', team: '城南一班', primaryId: 'A13', status: '待生成', source: '合同周期自动' },
  { no: 'JH-2026-1202-11', projectId: 'P5', deviceCode: 'DT-HF-0401', type: '半月保', planDate: '2026-12-02', team: '城南一班', primaryId: 'A13', status: '待生成', source: '合同周期自动' },
  { no: 'JH-2026-1113-12', projectId: 'P1', deviceCode: 'DT-CB-0321', type: '半月保', planDate: '2026-11-13', team: '城东一班', primaryId: 'A01', status: '执行中', source: '手动调整' }
]

/* ---------- 审计（24，覆盖 6 类动作；只读不可删） ---------- */
const AUDIT_ACTIONS = ['登录', '授权', '签字', '退回', '导出', '作废']
function aud(time, actor, role, action, object, scope, ip, result, hash8) {
  return { time: time, actor: actor, role: role, action: action, object: object, scope: scope, ip: ip, result: result, hash8: hash8 }
}
const AUDIT = [
  aud('2026-11-13 09:10', '陈立', 'PLATFORM_ADMIN', '登录', '后台登录', 'ALL', '10.0.0.1', '成功', '9a01b2c3'),
  aud('2026-11-13 08:45', '吴敏', 'CLIENT_ADMIN', '登录', '后台登录', 'ORG_FLAT', '10.0.1.9', '成功', '9a02b2c3'),
  aud('2026-11-13 08:30', '赵启明', 'MAINT_MGR', '登录', '后台登录', 'ALL', '10.0.1.7', '成功', '9a03b2c3'),
  aud('2026-11-13 08:55', '王海涛', 'MAINT_TECH', '签字', 'WB-2026-1113-0231', 'TEAM', '10.0.2.2', '成功', 'a3f19c2e'),
  aud('2026-11-13 08:40', '李振国', 'MAINT_TECH', '签字', 'WB-2026-1113-0231', 'TEAM', '10.0.2.1', '成功', 'a3f19c2e'),
  aud('2026-11-13 09:05', '张伟', 'CLIENT_SIGNER', '签字', 'JX-2026-1113-007', 'ORG_FLAT', '10.0.1.5', '成功', '7b12c3d4'),
  aud('2026-11-12 09:30', '李振国', 'MAINT_TECH', '签字', 'WB-2026-1112-0230', 'TEAM', '10.0.2.1', '成功', '0a9d3e6f'),
  aud('2026-11-12 10:15', '孙岚', 'MAINT_SUP', '授权', '限时授权 GR-003', 'PROJECT', '10.0.1.6', '成功', '8c23d4e5'),
  aud('2026-11-11 14:00', '陈立', 'PLATFORM_ADMIN', '授权', '角色分配 A12', 'ALL', '10.0.0.1', '成功', '8c24d4e5'),
  aud('2026-11-10 16:40', '孙岚', 'MAINT_SUP', '退回', 'WB-2026-1110-0227', 'PROJECT', '10.0.1.6', '成功', '2c7f5081'),
  aud('2026-11-10 11:20', '周建国', 'INST_MGR', '退回', 'YJ-2026-0108', 'ORG', '10.0.3.8', '成功', '6d34e5f6'),
  aud('2026-11-09 15:00', '赵启明', 'MAINT_MGR', '导出', '电梯台账（脱敏）', 'ALL', '10.0.1.7', '成功', '5e45f607'),
  aud('2026-11-08 10:30', '陈立', 'PLATFORM_ADMIN', '导出', '统计报表', 'ALL', '10.0.0.1', '成功', '5e46f607'),
  aud('2026-11-08 09:00', '吴敏', 'CLIENT_ADMIN', '导出', '敏感数据申请', 'ORG_FLAT', '10.0.1.9', '被拦截', '5e47f607'),
  aud('2026-11-07 16:00', '周建国', 'INST_MGR', '作废', 'YJ-2026-0109', 'ORG', '10.0.3.8', '成功', '4f560718'),
  aud('2026-11-07 16:05', '赵启明', 'MAINT_MGR', '作废', 'YJ-2026-0109 确认', 'ALL', '10.0.1.7', '成功', '4f560718'),
  aud('2026-11-06 11:00', '陈立', 'PLATFORM_ADMIN', '授权', '邀请码 INV-005 撤回', 'ALL', '10.0.0.1', '成功', '8c25d4e5'),
  aud('2026-11-05 09:20', '李振国', 'MAINT_TECH', '签字', 'WB-2026-1105-0220', 'TEAM', '10.0.2.1', '成功', 'e2b61a98'),
  aud('2026-11-04 14:30', '孙岚', 'MAINT_SUP', '登录', '后台登录', 'PROJECT', '10.0.1.6', '成功', '9a04b2c3'),
  aud('2026-11-03 10:50', '王海涛', 'MAINT_TECH', '签字', 'WB-2026-1103-0218', 'TEAM', '10.0.2.2', '成功', '603394c5'),
  aud('2026-11-02 16:20', '赵启明', 'MAINT_MGR', '导出', '合同台账', 'ALL', '10.0.1.7', '成功', '5e48f607'),
  aud('2026-11-01 09:15', '周建国', 'INST_MGR', '登录', '后台登录', 'ORG', '10.0.3.8', '成功', '9a05b2c3'),
  aud('2026-10-30 11:05', '陈立', 'PLATFORM_ADMIN', '授权', '账号停用 A15', 'ALL', '10.0.0.1', '成功', '8c26d4e5'),
  aud('2026-10-28 10:20', '李娜', 'CLIENT_SIGNER', '签字', 'WB-2026-1028-0209', 'ORG_FLAT', '10.0.4.16', '成功', '3d606192')
]

/* ---------- 邀请码（8） / 限时授权（5） ---------- */
const INVITES = [
  { code: 'INV-001', type: '邀请码', targetOrg: 'O11', creator: '孙岚', validDays: 7, status: '有效', joined: 2, createdAt: '2026-11-10' },
  { code: 'INV-002', type: '组织二维码', targetOrg: 'O3', creator: '吴敏', validDays: 7, status: '有效', joined: 1, createdAt: '2026-11-09' },
  { code: 'INV-003', type: '邀请码', targetOrg: 'O21', creator: '周建国', validDays: 7, status: '已使用', joined: 1, createdAt: '2026-11-01' },
  { code: 'INV-004', type: '邀请码', targetOrg: 'O12', creator: '赵启明', validDays: 7, status: '已过期', joined: 0, createdAt: '2026-10-20' },
  { code: 'INV-005', type: '组织二维码', targetOrg: 'O4', creator: '郑洁', validDays: 7, status: '已撤回', joined: 0, createdAt: '2026-11-02' },
  { code: 'INV-006', type: '邀请码', targetOrg: 'O11', creator: '孙岚', validDays: 14, status: '有效', joined: 0, createdAt: '2026-11-12' },
  { code: 'INV-007', type: '邀请码', targetOrg: 'O22', creator: '周建国', validDays: 7, status: '已使用', joined: 1, createdAt: '2026-10-28' },
  { code: 'INV-008', type: '组织二维码', targetOrg: 'O3', creator: '吴敏', validDays: 30, status: '有效', joined: 3, createdAt: '2026-11-05' }
]
const GRANTS = [
  { id: 'GR-001', grantee: 'A11', originalRole: 'MAINT_TECH', addedPerms: ['order:dispatch'], scope: 'PROJECT', start: '2026-11-10', end: '2026-11-17', status: '生效中' },
  { id: 'GR-002', grantee: 'A13', originalRole: 'MAINT_TECH', addedPerms: ['handover:receive'], scope: 'PROJECT', start: '2026-11-15', end: '2026-11-22', status: '待生效' },
  { id: 'GR-003', grantee: 'A02', originalRole: 'MAINT_TECH', addedPerms: ['record:review'], scope: 'PROJECT', start: '2026-11-12', end: '2026-11-13', status: '生效中' },
  { id: 'GR-004', grantee: 'A12', originalRole: 'MAINT_TECH', addedPerms: ['elevator:qr'], scope: 'PROJECT', start: '2026-10-20', end: '2026-10-27', status: '已回收' },
  { id: 'GR-005', grantee: 'A14', originalRole: 'INST_PM', addedPerms: ['handover:export'], scope: 'ORG', start: '2026-11-01', end: '2026-11-08', status: '已撤回' }
]

/* ---------- 系统配置默认值 ---------- */
const CONFIG = {
  sla: { p1: 30, urgent: 120, report: 240 },
  cycle: { half: 15, quarter: 90 },
  sampleRate: 20,
  masking: { contactPhone: true, staffPhone: true, idReg: true, amount: true, photo: true, address: true },
  templates: [
    { id: 'TPL-01', name: '半月保记录单标准模板', enabled: true },
    { id: 'TPL-02', name: '季度保记录单模板', enabled: true },
    { id: 'TPL-03', name: '急修处置单模板', enabled: false }
  ]
}

/* ---------- 脱敏矩阵（spec §6） ---------- */
const MASK_MATRIX = {
  contactPhone: { PLATFORM_ADMIN: 'full', MAINT_MGR: 'full', MAINT_SUP: 'full', INST_MGR: 'mask', CLIENT_ADMIN: 'full' },
  staffPhone:   { PLATFORM_ADMIN: 'full', MAINT_MGR: 'full', MAINT_SUP: 'full', INST_MGR: 'full', CLIENT_ADMIN: 'mask' },
  idReg:        { PLATFORM_ADMIN: 'full', MAINT_MGR: 'mask', MAINT_SUP: 'mask', INST_MGR: 'mask', CLIENT_ADMIN: 'mask' },
  amount:       { PLATFORM_ADMIN: 'full', MAINT_MGR: 'full', MAINT_SUP: 'mask', INST_MGR: 'hidden', CLIENT_ADMIN: 'full' },
  photo:        { PLATFORM_ADMIN: 'full', MAINT_MGR: 'thumb', MAINT_SUP: 'full', INST_MGR: 'thumb', CLIENT_ADMIN: 'full' },
  address:      { PLATFORM_ADMIN: 'full', MAINT_MGR: 'full', MAINT_SUP: 'partial', INST_MGR: 'partial', CLIENT_ADMIN: 'partial' }
}

/* ---------- 二期：监管平台上报（接口规范 V1.5） ---------- */
const ELEVATOR_REGULATOR = {
  'DT-CB-0318': '130421', 'DT-CB-0319': '130422', 'DT-CB-0320': '130423', 'DT-CB-0321': '130424',
  'DT-CB-0322': '130425', 'DT-CB-0323': '130426', 'DT-HJ-0101': '130427', 'DT-HJ-0102': '130428',
  'DT-HJ-0103': '130429', 'DT-HJ-0104': '130430', 'DT-CG-0201': '130431', 'DT-CG-0202': '130432',
  'DT-CG-0203': '130433', 'DT-CG-0204': '130434', 'DT-YQ-0301': '130435', 'DT-YQ-0302': '130436',
  'DT-YQ-0303': '130437', 'DT-HF-0401': '130438', 'DT-HF-0402': '130439', 'DT-HF-0403': '130440'
}
function regulatorCode(code) { return ELEVATOR_REGULATOR[code] || '' }

/* 码表（接口规范 3.1 / 3.2） */
const WORK_TYPE_MAP = [
  { code: 'FM', name: '按需维保' }, { code: 'HM', name: '半月' }, { code: 'TM', name: '季度' },
  { code: 'SM', name: '半年' }, { code: 'OY', name: '年度' }
]
const PROBLEM_CODE_MAP = [
  { code: 'S0', name: '未发现严重事故隐患' },
  { code: 'S1', name: '使用非法生产的特种设备' },
  { code: 'S2', name: '超过特种设备的规定参数范围使用' },
  { code: 'S3', name: '缺少安全附件、安全装置，或者安全附件、安全装置失灵而继续使用' },
  { code: 'S4', name: '使用应当予以报废或者经检验判定为不合格的特种设备' },
  { code: 'S5', name: '使用有明显故障、异常情况的特种设备，或者使用经责令改正而未予以改正的特种设备' },
  { code: 'S6', name: '特种设备发生事故不予报告而继续使用' },
  { code: 'S7', name: '其它严重事故隐患' }
]
function workTypeCode(cycle) { if (cycle === '半月保') return 'HM'; if (cycle === '季度保') return 'TM'; return 'FM' }
function problemCode(serious) { return serious || 'S0' }

/* 监管同步记录（记录单归档后自动上报；覆盖 5 态） */
const SYNC_STATES = ['待上报', '上报中', '成功', '失败', '已重试']
function sync(id, recordNo, status, code, message, retry, syncAt, api) {
  return { id: id, recordNo: recordNo, status: status, code: code, message: message, retry: retry, syncAt: syncAt, api: api || 'maintenanceRecord' }
}
const SYNC_RECORDS = [
  sync('SY-001', 'WB-2026-1103-0218', '成功', 200, '操作成功', 0, '2026-11-03 11:02:00'),
  sync('SY-002', 'WB-2026-1101-0215', '成功', 200, '操作成功', 1, '2026-11-01 10:30:00'),
  sync('SY-003', 'WB-2026-1110-0228', '失败', 500, 'access_token 已过期，请重新获取', 1, '2026-11-10 10:40:00'),
  sync('SY-004', 'WB-2026-1028-0210', '成功', 200, '操作成功', 0, '2026-10-28 11:20:00'),
  sync('SY-005', 'WB-2026-1108-0225', '已重试', 200, '操作成功', 2, '2026-11-08 11:05:00'),
  sync('SY-006', 'WB-2026-1105-0220', '成功', 200, '操作成功', 0, '2026-11-05 10:40:00'),
  sync('SY-007', 'WB-2026-1028-0209', '成功', 200, '操作成功', 0, '2026-10-28 11:45:00'),
  sync('SY-008', 'WB-2026-1020-0200', '失败', 502, '上游网关超时，请稍后重试', 0, '2026-10-20 11:10:00'),
  sync('SY-009', 'WB-2026-1111-0229', '待上报', null, '', 0, '')
]

/* 服务评价（使用单位对闭环工单，闭环后 7 天内可评价） */
const REVIEW_STATES = ['待评价', '已评价', '已过期']
function rev(id, orderNo, type, projectId, deviceCode, reviewer, stars, comment, closedAt, status, ratedAt) {
  return { id: id, orderNo: orderNo, type: type, projectId: projectId, deviceCode: deviceCode, reviewer: reviewer, stars: stars, comment: comment, closedAt: closedAt, status: status, ratedAt: ratedAt || null }
}
const REVIEWS = [
  rev('RV-001', 'RX-11012', '报修', 'P2', 'DT-HJ-0102', '吴敏', 5, '响应及时，师傅很专业', '2026-11-11', '已评价', '2026-11-12 10:20:00'),
  rev('RV-002', 'JX-2026-1110-005', '急修', 'P1', 'DT-CB-0321', '张伟', 4, '处理干净，但到场略晚', '2026-11-10', '已评价', '2026-11-11 09:40:00'),
  rev('RV-003', 'RX-11009', '报修', 'P1', 'DT-CB-0320', '吴敏', 5, '很满意', '2026-11-08', '已评价', '2026-11-09 15:10:00'),
  rev('RV-004', 'JX-2026-1108-004', '急修', 'P2', 'DT-HJ-0103', '张伟', null, '', '2026-11-08', '待评价', null),
  rev('RV-005', 'JX-2026-1028-001', '急修', 'P3', 'DT-CG-0201', '吴敏', 3, '问题解决，但沟通可以更好', '2026-10-28', '已评价', '2026-10-29 08:50:00'),
  rev('RV-006', 'RX-10021', '报修', 'P1', 'DT-CB-0318', '张伟', null, '', '2026-10-25', '已过期', null),
  rev('RV-007', 'RX-10018', '报修', 'P2', 'DT-HJ-0101', '吴敏', null, '', '2026-10-18', '已过期', null)
]

/* 消息模板（微信订阅消息；与「签字模板」区分，此为通知消息） */
const MSG_TEMPLATES = [
  { id: 'MSG-01', name: '使用单位签字提醒', scene: '签字提醒', channel: '微信订阅消息', content: '【博威无纸化】{{unit}} 您好，{{project}} {{elevator}} 的维保记录单待您签署（单号 {{recordNo}}），请于 {{deadline}} 前完成签署。', enabled: true },
  { id: 'MSG-02', name: '工单派单通知', scene: '工单派单', channel: '微信订阅消息', content: '【博威无纸化】您有新的 {{type}} 工单 {{orderNo}}：{{project}} {{elevator}} {{fault}}，请尽快接单处理。', enabled: true },
  { id: 'MSG-03', name: '合同临期提醒', scene: '合同临期', channel: '微信订阅消息', content: '【博威无纸化】{{project}} 维保合同将于 {{endDate}} 到期（剩余 {{daysLeft}} 天），请安排续签。', enabled: true },
  { id: 'MSG-04', name: 'SLA 超时升级提醒', scene: '超时升级', channel: '微信订阅消息', content: '【博威无纸化】工单 {{orderNo}} 已超出 SLA 时限 {{overtimeMin}} 分钟，请立即处理或升级。', enabled: false },
  { id: 'MSG-05', name: '维保计划到期提醒', scene: '计划提醒', channel: '微信订阅消息', content: '【博威无纸化】{{project}} {{elevator}} 将于 {{planDate}} 进行 {{type}}，请提前准备。', enabled: true }
]

/* ===================== 工具函数 ===================== */
function toDate(s) { return new Date(String(s).replace(' ', 'T')) }
function daysBetween(a, b) { return Math.round((toDate(b) - toDate(a)) / 86400000) }
function minutesBetween(a, b) { return Math.round((toDate(b) - toDate(a)) / 60000) }
function maskPhone(p) { return p ? p.slice(0, 3) + '****' + p.slice(-4) : '' }
function maskId(v) { return v ? v.slice(0, 3) + '***********' + v.slice(-4) : '' }
function maskAmount() { return '¥ *** 万' }
function fmtAmount(n) { return '¥ ' + n.toLocaleString('en-US') + '.00' }
function shortAddr(a) { return a ? a.split(' · ')[0] : '' }

function projectById(id) { for (let i = 0; i < PROJECTS.length; i++) if (PROJECTS[i].id === id) return PROJECTS[i]; return null }
function elevatorByCode(code) { for (let i = 0; i < ELEVATORS.length; i++) if (ELEVATORS[i].code === code) return ELEVATORS[i]; return null }
function orgById(id) { for (let i = 0; i < ORGS.length; i++) if (ORGS[i].id === id) return ORGS[i]; return null }
function accountById(id) { for (let i = 0; i < ACCOUNTS.length; i++) if (ACCOUNTS[i].id === id) return ACCOUNTS[i]; return null }
function switchByRole(role) { for (let i = 0; i < SWITCH_ROLES.length; i++) if (SWITCH_ROLES[i].role === role) return SWITCH_ROLES[i]; return null }

/* 派生：电梯 lastMaintDate = 该设备最近一条已归档记录单作业日期（inv2） */
ELEVATORS.forEach(function (e) {
  let latest = null
  RECORDS.forEach(function (r) {
    if (r.deviceCode === e.code && r.status === '已归档') {
      if (!latest || r.workDate > latest) latest = r.workDate
    }
  })
  e.lastMaintDate = latest
})

/* 派生：合同剩余天数（inv6） */
CONTRACTS.forEach(function (c) { c.daysLeft = daysBetween(TODAY, c.end) })

/* 派生：工单 SLA 计时（inv7） */
function recomputeOrders() {
  ORDERS.forEach(function (o) {
    o.elapsed = minutesBetween(o.createdAt, NOW)
    o.overtime = o.elapsed > o.slaMinutes
    o.remaining = o.slaMinutes - o.elapsed
  })
}
recomputeOrders()

/* 数据范围解析 → 项目集合 */
function scopeProjects(role) {
  const sw = switchByRole(role)
  if (!sw) return []
  return sw.projectIds.slice()
}
function scopeElevators(role) {
  const pids = scopeProjects(role)
  return ELEVATORS.filter(function (e) { return pids.indexOf(e.projectId) >= 0 })
}

/* 字段脱敏出值 */
function fieldView(kind, value, role) {
  let mode = (MASK_MATRIX[kind] && MASK_MATRIX[kind][role]) || 'mask'
  if (!CONFIG.masking[kind]) {
    mode = 'full' // 配置关闭脱敏 → 明文（演示；生产在服务端）
  }
  if (mode === 'full') return value
  if (mode === 'hidden') return null
  if (mode === 'thumb') return value
  if (mode === 'partial') return shortAddr(value)
  if (kind === 'contactPhone' || kind === 'staffPhone') return maskPhone(value)
  if (kind === 'idReg') return maskId(value)
  if (kind === 'amount') return maskAmount()
  return value
}

/* 权限判定（原型 9 角色矩阵；真实系统以 permission-v2 / 后端为准） */
function perm(role, code) { return (MATRIX[code] && MATRIX[code][role]) || '-' }
function canView(role, code) {
  const p = perm(role, code)
  if (p !== '-') return true
  const sw = switchByRole(role)
  return !!(sw && sw.opsReadonly.indexOf(code) >= 0) // D3 运维只读
}
function canOperate(role, code) { return perm(role, code) === 'Y' }

/* 导航视图定义（侧边栏；工单详情为详情路由不计入） */
const NAV_VIEWS = [
  { id: 'dashboard', name: '概览工作台', group: '概览', perm: null },
  { id: 'org', name: '组织架构', group: '组织与权限', perm: 'user:view' },
  { id: 'users', name: '账号管理', group: '组织与权限', perm: 'user:view' },
  { id: 'roles', name: '角色权限矩阵', group: '组织与权限', perm: 'role:assign' },
  { id: 'grants', name: '授权管理', group: '组织与权限', perm: 'invite:manage' },
  { id: 'projects', name: '项目台账', group: '台账中心', perm: 'project:view' },
  { id: 'contracts', name: '合同管理', group: '台账中心', perm: 'contract:view' },
  { id: 'elevators', name: '电梯台账', group: '台账中心', perm: 'elevator:view' },
  { id: 'handovers', name: '移交管理', group: '安装移交', perm: 'handover:view' },
  { id: 'plans', name: '维保计划', group: '维保作业', perm: 'plan:view' },
  { id: 'records', name: '记录单复核', group: '维保作业', perm: 'record:view' },
  { id: 'dispatch', name: '工单调度', group: '急修报修', perm: 'order:view_pool', permAlt: 'order:view_own' },
  { id: 'stats', name: '统计报表', group: '数据与系统', perm: 'stats:view' },
  { id: 'audit', name: '审计日志', group: '数据与系统', perm: 'audit:view' },
  { id: 'settings', name: '系统配置', group: '数据与系统', perm: 'config:manage' },
  { id: 'spec', name: '设计规范', group: '数据与系统', perm: null },
  { id: 'sync', name: '监管平台同步', group: '监管与运营', perm: 'sync:view' },
  { id: 'templates', name: '消息模板', group: '监管与运营', perm: 'msg:manage' },
  { id: 'reviews', name: '服务评价', group: '监管与运营', perm: 'review:view' },
  { id: 'board', name: '大屏调度', group: '监管与运营', perm: 'board:view' }
]
function visibleNav(role) {
  return NAV_VIEWS.filter(function (v) {
    if (!v.perm) return true
    if (canView(role, v.perm)) return true
    if (v.permAlt && canView(role, v.permAlt)) return true
    return false
  })
}

/* KPI（由 mock 计算，分项闭合） */
function computeKPIs(role) {
  const pids = scopeProjects(role)
  const elevs = scopeElevators(role)
  const inProject = function (pid) { return pids.indexOf(pid) >= 0 }
  const handovers = HANDOVERS.filter(function (h) { return inProject(h.projectId) })
  const records = RECORDS.filter(function (r) { return inProject(r.projectId) })
  const orders = ORDERS.filter(function (o) { return inProject(o.projectId) })
  const contracts = CONTRACTS.filter(function (c) { return inProject(c.projectId) })
  return {
    projects: pids.length,
    elevators: elevs.length,
    handoverPendingReview: handovers.filter(function (h) { return h.status === '待安装部审核' }).length,
    recordPendingReview: records.filter(function (r) { return r.status === '待复核' }).length,
    orderOvertime: orders.filter(function (o) { return o.overtime && o.status !== '已闭环' && o.status !== '已取消' }).length,
    contractExpiring: contracts.filter(function (c) { return c.daysLeft > 0 && c.daysLeft <= 90 }).length,
    dualSignPending: records.filter(function (r) { return r.status === '待配合人签字' }).length
  }
}

/* ===================== 12 条不变量自检（spec §9 + 二期 3 条） ===================== */
function selfCheck() {
  const results = []
  function add(id, name, pass, detail) { results.push({ id: id, name: name, pass: !!pass, detail: detail }) }

  /* 1 台量闭合 */
  let total = 0, ok1 = true
  const d1 = []
  PROJECTS.forEach(function (p) {
    const n = ELEVATORS.filter(function (e) { return e.projectId === p.id }).length
    total += n
    if (n !== p.elevatorCount) { ok1 = false }
    d1.push(p.name + ':' + n)
  })
  add(1, '项目台量 = 电梯行数，总数 = 分项之和', ok1 && total === ELEVATORS.length, d1.join(' / ') + ' = ' + total)

  /* 2 lastMaintDate = 最近已归档记录单作业日期 */
  let ok2 = true
  const d2 = []
  ELEVATORS.forEach(function (e) {
    let latest = null
    RECORDS.forEach(function (r) { if (r.deviceCode === e.code && r.status === '已归档' && (!latest || r.workDate > latest)) latest = r.workDate })
    if (e.lastMaintDate !== latest) { ok2 = false; d2.push(e.code) }
  })
  add(2, '电梯上次维保 = 最近已归档记录单作业日期', ok2, ok2 ? '20 台全部一致' : '不一致: ' + d2.join(','))

  /* 3 引用存在 */
  let ok3 = true
  const d3 = []
  function chkRef(pid, code, oid, tag) {
    if (pid && !projectById(pid)) { ok3 = false; d3.push(tag + ':project ' + pid) }
    if (code && !elevatorByCode(code)) { ok3 = false; d3.push(tag + ':device ' + code) }
    if (oid && !orgById(oid)) { ok3 = false; d3.push(tag + ':org ' + oid) }
  }
  RECORDS.forEach(function (r) { chkRef(r.projectId, r.deviceCode, null, r.no) })
  ORDERS.forEach(function (o) { chkRef(o.projectId, o.deviceCode, null, o.no) })
  HANDOVERS.forEach(function (h) { chkRef(h.projectId, null, null, h.no); h.deviceCodes.forEach(function (c) { if (!elevatorByCode(c)) { ok3 = false; d3.push(h.no + ':device ' + c) } }) })
  CONTRACTS.forEach(function (c) { chkRef(c.projectId, null, c.clientId, c.id) })
  add(3, '记录/工单/移交/合同引用的项目·设备·组织存在', ok3, ok3 ? '全部引用有效' : d3.join(','))

  /* 4 状态机合法 */
  let ok4 = true
  const d4 = []
  HANDOVERS.forEach(function (h) { if (HANDOVER_STATES.indexOf(h.status) < 0) { ok4 = false; d4.push(h.no) } })
  RECORDS.forEach(function (r) { if (RECORD_STATES.indexOf(r.status) < 0) { ok4 = false; d4.push(r.no) } })
  ORDERS.forEach(function (o) { if (ORDER_STATES.indexOf(o.status) < 0) { ok4 = false; d4.push(o.no) } })
  add(4, '各单据状态 ∈ 对应状态集', ok4, ok4 ? '移交10态/记录7态/工单7态 全部合法' : d4.join(','))

  /* 5 双签 */
  let ok5 = true
  const d5 = []
  RECORDS.forEach(function (r) {
    if (r.primaryId === r.partnerId) { ok5 = false; d5.push(r.no + ':同账号') }
    if (r.primarySignAt && r.partnerSignAt && r.primarySignAt > r.partnerSignAt) { ok5 = false; d5.push(r.no + ':时序') }
  })
  add(5, '双签：主检≠配合人，主检先签', ok5, ok5 ? '14 单双签规则一致' : d5.join(','))

  /* 6 临期与 KPI 闭合 */
  let ok6 = true
  const d6 = []
  CONTRACTS.forEach(function (c) { if (c.daysLeft !== daysBetween(TODAY, c.end)) { ok6 = false; d6.push(c.id) } })
  const kpi = computeKPIs('PLATFORM_ADMIN')
  const actualExpiring = CONTRACTS.filter(function (c) { return c.daysLeft > 0 && c.daysLeft <= 90 }).length
  if (kpi.contractExpiring !== actualExpiring) { ok6 = false; d6.push('KPI临期') }
  add(6, '合同剩余天数 = end-TODAY，KPI 计数 = 实际行数', ok6, ok6 ? '临期 ' + actualExpiring + ' 份，KPI 闭合' : d6.join(','))

  /* 7 SLA */
  let ok7 = true
  const d7 = []
  ORDERS.forEach(function (o) {
    const el = minutesBetween(o.createdAt, NOW)
    if (o.elapsed !== el || o.overtime !== (el > o.slaMinutes)) { ok7 = false; d7.push(o.no) }
  })
  add(7, 'SLA：elapsed=now-createdAt，超时=elapsed>时限', ok7, ok7 ? '16 单计时一致' : d7.join(','))

  /* 8 数据范围台量 */
  const expect = { PLATFORM_ADMIN: 20, MAINT_MGR: 20, MAINT_SUP: 14, INST_MGR: 6, CLIENT_ADMIN: 14 }
  let ok8 = true
  const d8 = []
  Object.keys(expect).forEach(function (role) {
    const n = scopeElevators(role).length
    d8.push(role + ':' + n)
    if (n !== expect[role]) ok8 = false
  })
  add(8, '范围解析台量 ALL20/MGR20/SUP14/INST6/CLIENT14', ok8, d8.join(' / '))

  /* 9 日期范围 + 周五 */
  let ok9 = true
  const d9 = []
  const lo = '2025-01-01', hi = '2026-12-31'
  function chkDate(s, tag) {
    if (!s) return
    const day = String(s).slice(0, 10)
    if (day < lo || day > hi) { ok9 = false; d9.push(tag + ':' + day) }
  }
  ELEVATORS.forEach(function (e) { chkDate(e.installDate, e.code); chkDate(e.nextMaintDue, e.code) })
  RECORDS.forEach(function (r) { chkDate(r.workDate, r.no) })
  CONTRACTS.forEach(function (c) { chkDate(c.start, c.id); chkDate(c.end, c.id) })
  const weekday = toDate(TODAY).getDay() // 5 = Friday
  if (weekday !== 5) { ok9 = false; d9.push('TODAY非周五') }
  add(9, '日期 ∈ 2025-01-01~2026-12-31，2026-11-13=周五', ok9, ok9 ? '全部日期合规，基准日周五' : d9.join(','))

  /* 10 监管同步记录：recordNo 引用已归档记录单，状态合法 */
  let ok10 = true
  const d10 = []
  SYNC_RECORDS.forEach(function (s) {
    if (SYNC_STATES.indexOf(s.status) < 0) { ok10 = false; d10.push(s.id + ':状态') }
    if (s.recordNo) {
      let r = null
      RECORDS.forEach(function (x) { if (x.no === s.recordNo) r = x })
      if (!r) { ok10 = false; d10.push(s.id + ':记录单不存在') }
    }
  })
  add(10, '监管同步记录引用存在的记录单，状态 ∈ 5 态', ok10, ok10 ? SYNC_RECORDS.length + ' 条同步记录合法' : d10.join(','))

  /* 11 服务评价：评分 1-5，状态合法 */
  let ok11 = true
  const d11 = []
  REVIEWS.forEach(function (r) {
    if (REVIEW_STATES.indexOf(r.status) < 0) { ok11 = false; d11.push(r.id + ':状态') }
    if (r.stars != null && (r.stars < 1 || r.stars > 5)) { ok11 = false; d11.push(r.id + ':评分') }
  })
  add(11, '服务评价评分 1–5，状态 ∈ 3 态', ok11, ok11 ? REVIEWS.length + ' 条评价合法' : d11.join(','))

  /* 12 电梯监管编码：20 台唯一且非空 */
  let ok12 = true
  const d12 = []
  const seen = {}
  ELEVATORS.forEach(function (e) {
    const c = regulatorCode(e.code)
    if (!c) { ok12 = false; d12.push(e.code + ':缺编码') }
    else if (seen[c]) { ok12 = false; d12.push(e.code + ':重复 ' + c) }
    else seen[c] = true
  })
  add(12, '电梯监管编码 20 台唯一且非空', ok12, ok12 ? '20 台监管编码唯一' : d12.join(','))

  return results
}

/* ===================== 挂载（原型 window.LIFT.mock 的 ES 模块等价） ===================== */
export const M = {
  meta: { TODAY: TODAY, NOW: NOW, VERSION: VERSION, weekday: '周五' },
  ROLES: ROLES, ROLE_ORDER: ROLE_ORDER,
  PERM_CATALOG: PERM_CATALOG, MATRIX: MATRIX,
  SWITCH_ROLES: SWITCH_ROLES,
  ORGS: ORGS, PROJECTS: PROJECTS, ELEVATORS: ELEVATORS, CONTRACTS: CONTRACTS,
  ACCOUNTS: ACCOUNTS, HANDOVERS: HANDOVERS, RECORDS: RECORDS, ORDERS: ORDERS,
  PLANS: PLANS, AUDIT: AUDIT, INVITES: INVITES, GRANTS: GRANTS, CONFIG: CONFIG,
  MASK_MATRIX: MASK_MATRIX, NAV_VIEWS: NAV_VIEWS,
  HANDOVER_STATES: HANDOVER_STATES, RECORD_STATES: RECORD_STATES, ORDER_STATES: ORDER_STATES, AUDIT_ACTIONS: AUDIT_ACTIONS,
  SYNC_STATES: SYNC_STATES, REVIEW_STATES: REVIEW_STATES,
  SYNC_RECORDS: SYNC_RECORDS, REVIEWS: REVIEWS, MSG_TEMPLATES: MSG_TEMPLATES,
  WORK_TYPE_MAP: WORK_TYPE_MAP, PROBLEM_CODE_MAP: PROBLEM_CODE_MAP,
  regulatorCode: regulatorCode, workTypeCode: workTypeCode, problemCode: problemCode,
  projectById: projectById, elevatorByCode: elevatorByCode, orgById: orgById, accountById: accountById, switchByRole: switchByRole,
  scopeProjects: scopeProjects, scopeElevators: scopeElevators,
  fieldView: fieldView, maskPhone: maskPhone, maskId: maskId, fmtAmount: fmtAmount, maskAmount: maskAmount,
  perm: perm, canView: canView, canOperate: canOperate, visibleNav: visibleNav,
  computeKPIs: computeKPIs, daysBetween: daysBetween, recomputeOrders: recomputeOrders,
  selfCheck: selfCheck
}
export { TODAY, NOW }
export default M