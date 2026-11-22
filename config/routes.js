const MAINT = ['MAINT_TECH', 'MAINT_SUP', 'MAINT_MGR']
const MAINT_EXT = MAINT.concat(['MAINT_QUALITY', 'MAINT_BIZ'])
const INST = ['INST_CLERK', 'INST_PM', 'INST_MGR']
const CLIENT = ['CLIENT_ADMIN', 'CLIENT_SIGNER']
const PLATFORM = ['PLATFORM_ADMIN']

function R(path, name, opts) {
  return Object.assign({ path, name, auth: true, batch: 'M2', built: false }, opts || {})
}

const LIST = [
  // ── 主包 17（蓝图 11 + 保留工具 6） ──
  R('/pages/login/index', '登录', { auth: false, built: true }),
  R('/pages/login/agreement', '用户协议与隐私政策', { auth: false, built: true }),
  R('/pages/login/forgot', '忘记密码', { auth: false, built: true }),
  R('/pages/preview/index', '功能预览', { auth: false, built: true }),
  R('/pages/install/home/index', '安装工作台', { roles: INST.concat(['INSTALLER']), tab: true, built: true }),
  R('/pages/maint/home/index', '维保工作台', { roles: MAINT_EXT, tab: true, built: true }),
  R('/pages/client/home/index', '使用单位首页', { roles: CLIENT, tab: true, built: true }),
  R('/pages/client/select-unit/index', '选择使用单位', { auth: false, built: true }),
  R('/pages/order/list/index', '报修接单', { roles: MAINT, perm: 'order:view_pool', tab: true, batch: 'M3' }),
  R('/pages/order/detail/index', '急修单详情', { roles: MAINT.concat(CLIENT), perm: ['order:view_pool', 'order:view_own'], batch: 'M3' }),
  R('/pages/order/report/index', '一键报修', { roles: ['MAINT_SUP'].concat(CLIENT), perm: ['order:create', 'order:create_proxy', 'client:report'], batch: 'M3' }),
  R('/pages/elevator/list/index', '电梯列表', { perm: ['elevator:view', 'client:elevator_view'], tab: true, built: true }),
  R('/pages/elevator/detail/index', '电梯档案', { perm: ['elevator:view', 'client:elevator_view'], built: true }),
  R('/pages/profile/index', '我的', { tab: true, built: true }),
  R('/pages/profile/password', '修改密码', { built: true }),
  R('/pages/profile/employee', '人员档案', { built: true }),
  R('/pages/403/index', '无访问权限', { auth: false, built: true }),
  R('/pages/message/index', '消息中心', { built: true }),
  R('/pages/offline/index', '离线队列', { built: true }),
  R('/pages/common/watermark-camera', '水印相机', { built: true }),
  R('/pages/common/signature', '手写签名', { built: true }),
  // ── pkg-handover 8（安装移交） ──
  R('/pkg-handover/pages/list/index', '移交单列表', { roles: INST.concat(['MAINT_TECH', 'MAINT_SUP', 'MAINT_MGR', 'INSTALLER']).concat(PLATFORM), perm: 'handover:view', tab: true, batch: 'M4' }),
  R('/pkg-handover/pages/create/index', '发起移交', { roles: ['INST_PM', 'INST_CLERK'], perm: 'handover:create', batch: 'M4' }),
  R('/pkg-handover/pages/detail/index', '移交单详情', { roles: INST.concat(MAINT).concat(['INSTALLER']).concat(PLATFORM), perm: 'handover:view', batch: 'M4' }),
  R('/pkg-handover/pages/inspect/index', '现场验收', { roles: ['MAINT_TECH', 'INSTALLER'], perm: 'handover:inspect', batch: 'M4' }),
  R('/pkg-handover/pages/rectify/index', '整改回复', { roles: ['INST_PM', 'INST_CLERK'], perm: 'handover:rectify', batch: 'M4' }),
  R('/pkg-handover/pages/sign/index', '双方签字', { roles: ['INST_MGR', 'MAINT_SUP', 'MAINT_MGR'], perm: ['handover:sign_install', 'handover:sign_maint'], batch: 'M4' }),
  R('/pkg-handover/pages/upload/index', '资料上传', { roles: ['INST_CLERK', 'INST_PM'], perm: 'handover:doc_upload', batch: 'M4' }),
  R('/pkg-handover/pages/review/index', '内部审核', { roles: ['INST_MGR'], perm: 'handover:review', batch: 'M4' }),
  R('/pkg-maintenance/pages/orders/index', '我的维保工单', { roles: MAINT_EXT, batch: 'M3' }),
  R('/pkg-maintenance/pages/task/index', '作业执行', { roles: ['MAINT_TECH'], perm: 'task:accept', batch: 'M3' }),
  R('/pkg-maintenance/pages/checkin/index', '签到', { roles: ['MAINT_TECH'], batch: 'M3' }),
  R('/pkg-maintenance/pages/checkout/index', '签退', { roles: ['MAINT_TECH'], batch: 'M3' }),
  R('/pkg-maintenance/pages/record-detail/index', '记录单详情', { roles: MAINT_EXT, perm: 'record:view', batch: 'M3' }),
  R('/pkg-maintenance/pages/plan/index', '维保计划', { roles: ['MAINT_SUP', 'MAINT_MGR'], perm: 'plan:view', batch: 'M3' }),
  R('/pkg-maintenance/pages/record-sign/index', '维保双人签字', { roles: ['MAINT_TECH'], perm: 'record:sign_tech', batch: 'M3' }),
  R('/pkg-client/pages/todo/index', '待办签字', { roles: CLIENT, perm: 'client:sign', tab: true, batch: 'M5' }),
  R('/pkg-client/pages/sign/index', '签署确认', { auth: false, roles: CLIENT, perm: 'client:sign', batch: 'M5' }),
  R('/pkg-client/pages/orders/index', '本单位工单', { roles: [], perm: 'order:view_own', batch: 'M5' }),
  R('/pkg-client/pages/evaluate/index', '服务评价', { roles: [], perm: 'client:evaluate', batch: 'M5' }),
  // ── pkg-admin 7 ──
  R('/pkg-admin/pages/projects/index', '项目台账', { roles: ['MAINT_SUP', 'MAINT_MGR', 'INST_PM', 'INST_MGR'].concat(PLATFORM), perm: 'project:view', tab: true, batch: 'M6' }),
  R('/pkg-admin/pages/contracts/index', '合同', { roles: ['MAINT_SUP', 'MAINT_MGR'].concat(PLATFORM), perm: 'contract:view', tab: true, batch: 'M6' }),
  R('/pkg-admin/pages/users/index', '人员', { roles: ['INST_MGR', 'MAINT_SUP', 'MAINT_MGR'].concat(PLATFORM), perm: 'user:view', tab: true, batch: 'M6' }),
  R('/pkg-admin/pages/roles/index', '角色', { roles: PLATFORM, perm: 'role:assign', batch: 'M6' }),
  R('/pkg-admin/pages/stats/index', '统计', { roles: ['INST_MGR', 'MAINT_SUP', 'MAINT_MGR'].concat(PLATFORM), perm: 'stats:view', tab: true, batch: 'M6' }),
  R('/pkg-admin/pages/audit/index', '审计', { roles: PLATFORM, perm: 'audit:view', tab: true, batch: 'M6' }),
  R('/pkg-admin/pages/settings/index', '系统配置', { roles: PLATFORM, perm: 'config:manage', tab: true, batch: 'M6' })
]

const ROUTES = {}
LIST.forEach((m) => { ROUTES[m.path] = m })
LIST.forEach((m) => { if (m.batch === 'M3') m.built = true })
LIST.forEach((m) => { if (m.batch === 'M4') m.built = true })
LIST.forEach((m) => { if (m.batch === 'M5') m.built = true })
LIST.forEach((m) => { if (m.batch === 'M6') m.built = true })


function metaOf(path) {
  return ROUTES[path] || null
}

module.exports = { ROUTES, metaOf }