// 路由白名单 RouteMeta（M2，规格 §1.5/§3.5）：49 新路由全量登记（M0 映射 §4），
// 蓝图 §4 明确归属的 perm 才登记（可数组 any-of，裁决 R6）；扩展路由只 roles，后端端点级强校验为准。
// built=true 仅已建页（随批次推进）；tab=true 为 custom-tabbar 宿主。守卫 verify-miniapp-routes.js 锁定。
const MAINT = ['MAINT_TECH', 'MAINT_SUP', 'MAINT_MGR']
const MAINT_EXT = MAINT.concat(['MAINT_QUALITY', 'MAINT_BIZ'])
const INST = ['INST_CLERK', 'INST_PM', 'INST_MGR']
const CLIENT = ['CLIENT_ADMIN', 'CLIENT_SIGNER']

function R(path, name, opts) {
  return Object.assign({ path, name, auth: true, batch: 'M2', built: false }, opts || {})
}

const LIST = [
  // ── 主包 17（蓝图 11 + 保留工具 6） ──
  R('/pages/login/index', '登录', { auth: false, built: true }),
  R('/pages/login/agreement', '用户协议与隐私政策', { auth: false, built: true }),
  R('/pages/install/home/index', '安装工作台', { roles: INST, tab: true, built: true }),
  R('/pages/maint/home/index', '维保工作台', { roles: MAINT_EXT, tab: true, built: true }),
  R('/pages/client/home/index', '使用单位首页', { roles: CLIENT, tab: true, built: true }),
  R('/pages/order/list/index', '报修接单', { roles: MAINT, perm: 'order:view_pool', tab: true, batch: 'M3' }),
  R('/pages/order/detail/index', '急修单详情', { roles: MAINT.concat(CLIENT), perm: ['order:view_pool', 'order:view_own'], batch: 'M3' }),
  R('/pages/order/report/index', '一键报修', { roles: CLIENT.concat(MAINT), perm: ['order:create', 'order:create_proxy'], batch: 'M3' }),
  R('/pages/elevator/list/index', '电梯列表', { perm: 'elevator:view', tab: true, built: true }),
  R('/pages/elevator/detail/index', '电梯档案', { perm: 'elevator:view', built: true }),
  R('/pages/profile/index', '我的', { tab: true, built: true }),
  R('/pages/profile/password', '修改密码', { built: true }),
  R('/pages/403/index', '无访问权限', { auth: false, built: true }),
  R('/pages/message/index', '消息中心', { built: true }),
  R('/pages/offline/index', '离线队列', { built: true }),
  R('/pages/common/watermark-camera', '水印相机', { built: true }),
  R('/pages/common/signature', '手写签名', { built: true }),
  // ── pkg-handover 12（蓝图 8 + 外接建档 4） ──
  R('/pkg-handover/pages/list/index', '移交单列表', { roles: INST.concat(['MAINT_SUP', 'MAINT_MGR']), perm: 'handover:view', tab: true, batch: 'M4' }),
  R('/pkg-handover/pages/create/index', '发起移交', { roles: ['INST_PM'], perm: 'handover:create', batch: 'M4' }),
  R('/pkg-handover/pages/detail/index', '移交单详情', { roles: INST.concat(MAINT, CLIENT), perm: 'handover:view', batch: 'M4' }),
  R('/pkg-handover/pages/inspect/index', '现场验收', { roles: ['MAINT_TECH'], perm: 'handover:inspect', batch: 'M4' }),
  R('/pkg-handover/pages/rectify/index', '整改回复', { roles: ['INST_PM', 'INST_CLERK'], perm: 'handover:rectify', batch: 'M4' }),
  R('/pkg-handover/pages/sign/index', '双方签字', { roles: ['INST_MGR', 'MAINT_SUP', 'MAINT_MGR'], perm: ['handover:sign_install', 'handover:sign_maint'], batch: 'M4' }),
  R('/pkg-handover/pages/upload/index', '资料上传', { roles: ['INST_CLERK', 'INST_PM'], perm: 'handover:doc_upload', batch: 'M4' }),
  R('/pkg-handover/pages/review/index', '内部审核', { roles: ['INST_MGR'], perm: 'handover:review', batch: 'M4' }),
  R('/pkg-handover/pages/filing-init/index', '外接建档', { roles: ['INST_PM', 'INST_CLERK'], batch: 'M4' }),
  R('/pkg-handover/pages/filing-detail/index', '外接建档详情', { roles: INST.concat(['MAINT_SUP', 'MAINT_MGR']), batch: 'M4' }),
  R('/pkg-handover/pages/filing-inspect/index', '外接接管查验', { roles: INST.concat(['MAINT_SUP', 'MAINT_MGR']), batch: 'M4' }),
  R('/pkg-handover/pages/filing-confirm/index', '外接建档确认', { roles: INST.concat(['MAINT_SUP', 'MAINT_MGR']), batch: 'M4' }),
  // ── pkg-maintenance 9（蓝图 5 + 合规扩展 4） ──
  R('/pkg-maintenance/pages/orders/index', '我的维保工单', { roles: MAINT_EXT, batch: 'M3' }),
  R('/pkg-maintenance/pages/task/index', '作业执行', { roles: ['MAINT_TECH'], perm: 'task:accept', batch: 'M3' }),
  R('/pkg-maintenance/pages/checkin/index', '签到', { roles: ['MAINT_TECH'], batch: 'M3' }),
  R('/pkg-maintenance/pages/dynamic-code/index', '双人动态码', { roles: ['MAINT_TECH'], batch: 'M3' }),
  R('/pkg-maintenance/pages/checkout/index', '签退', { roles: ['MAINT_TECH'], batch: 'M3' }),
  R('/pkg-maintenance/pages/record-detail/index', '记录单详情', { roles: MAINT_EXT.concat(CLIENT), perm: 'record:view', batch: 'M3' }),
  R('/pkg-maintenance/pages/plan/index', '维保计划', { roles: ['MAINT_SUP', 'MAINT_MGR'], perm: 'plan:view', batch: 'M3' }),
  R('/pkg-maintenance/pages/record-sign/index', '维保双人签字', { roles: ['MAINT_TECH'], perm: 'record:sign_tech', batch: 'M3' }),
  R('/pkg-maintenance/pages/record-archive/index', '归档与导出', { roles: ['MAINT_SUP', 'MAINT_MGR', 'CLIENT_ADMIN', 'PLATFORM_ADMIN'], perm: 'record:export', batch: 'M3' }),
  // ── pkg-client 4 ──
  R('/pkg-client/pages/todo/index', '待办签字', { roles: CLIENT, perm: ['record:sign', 'order:confirm'], tab: true, batch: 'M5' }),
  R('/pkg-client/pages/sign/index', '签署确认', { roles: ['CLIENT_SIGNER'], perm: 'client:sign', batch: 'M5' }),
  R('/pkg-client/pages/orders/index', '本单位工单', { roles: CLIENT, perm: 'order:view_own', batch: 'M5' }),
  R('/pkg-client/pages/evaluate/index', '服务评价', { roles: CLIENT, perm: 'client:evaluate', batch: 'M5' }),
  // ── pkg-admin 7 ──
  R('/pkg-admin/pages/projects/index', '项目台账', { roles: ['MAINT_SUP', 'MAINT_MGR', 'INST_PM', 'INST_MGR'], perm: 'project:view', tab: true, batch: 'M6' }),
  R('/pkg-admin/pages/contracts/index', '合同', { roles: ['MAINT_SUP', 'MAINT_MGR', 'CLIENT_ADMIN'], perm: 'contract:view', tab: true, batch: 'M6' }),
  R('/pkg-admin/pages/users/index', '人员', { roles: ['INST_MGR', 'MAINT_SUP', 'MAINT_MGR', 'CLIENT_ADMIN', 'PLATFORM_ADMIN'], perm: 'user:view', tab: true, batch: 'M6' }),
  R('/pkg-admin/pages/roles/index', '角色', { roles: ['PLATFORM_ADMIN'], perm: 'role:assign', batch: 'M6' }),
  R('/pkg-admin/pages/stats/index', '统计', { roles: ['INST_MGR', 'MAINT_SUP', 'MAINT_MGR', 'CLIENT_ADMIN', 'PLATFORM_ADMIN'], perm: 'stats:view', tab: true, batch: 'M6' }),
  R('/pkg-admin/pages/audit/index', '审计', { roles: ['PLATFORM_ADMIN', 'CLIENT_ADMIN'], perm: 'audit:view', tab: true, batch: 'M6' }),
  R('/pkg-admin/pages/settings/index', '系统配置', { roles: ['PLATFORM_ADMIN'], perm: 'config:manage', tab: true, batch: 'M6' })
]

const ROUTES = {}
LIST.forEach((m) => { ROUTES[m.path] = m })
// M3 已建：维保作业域 9 页 + 急修 3 页（随批次推进 built）
LIST.forEach((m) => { if (m.batch === 'M3') m.built = true })
// M4 已建：移交/外接域 12 页
LIST.forEach((m) => { if (m.batch === 'M4') m.built = true })
// M5 已建：使用单位域 4 页
LIST.forEach((m) => { if (m.batch === 'M5') m.built = true })
// M6 已建：管理统计域 7 页（全部 49 路由建成）
LIST.forEach((m) => { if (m.batch === 'M6') m.built = true })

// 过渡期旧 40 页（M0 §4 旧列；各域迁移完即删，M6 清空）
const OLD_PAGES = [
  'pages/home/index', 'pages/order/list', 'pages/message/index', 'pages/mine/index',
  'pages/order/detail', 'pages/order/checkin', 'pages/order/dynamic-code', 'pages/order/checklist',
  'pages/order/item-form', 'pages/order/checkout', 'pages/order/record-preview',
  'pages/common/watermark-camera', 'pages/common/signature',
  'pages/fault/report', 'pages/fault/detail', 'pages/fault/list',
  'pages/elevator/list', 'pages/elevator/detail',
  'pages/handover/list', 'pages/handover/init', 'pages/handover/detail',
  'pages/handover/inspect', 'pages/handover/rectify', 'pages/handover/confirm',
  'pages/filing/init', 'pages/filing/detail', 'pages/filing/inspect', 'pages/filing/confirm',
  'pages/calendar/index', 'pages/mine/offline',
  'pages/unit/sign', 'pages/unit/home', 'pages/unit/mine/index', 'pages/unit/elevators',
  'pages/unit/records', 'pages/unit/faults', 'pages/unit/report',
  'pages/login/index', 'pages/login/agreement', 'pages/mine/password'
]

function metaOf(path) {
  return ROUTES[path] || null
}

module.exports = { ROUTES, OLD_PAGES, metaOf }