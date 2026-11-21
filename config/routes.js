// 路由白名单 RouteMeta（M2，规格 §1.5/§3.5）：49 新路由全量登记（M0 映射 §4），
// 蓝图 §4 明确归属的 perm 才登记（可数组 any-of，裁决 R6）；扩展路由只 roles，后端端点级强校验为准。
// built=true 仅已建页（随批次推进）；tab=true 为 custom-tabbar 宿主。守卫 verify-miniapp-routes.js 锁定。
const MAINT = ['MAINT_TECH', 'MAINT_SUP', 'MAINT_MGR']
const MAINT_EXT = MAINT.concat(['MAINT_QUALITY', 'MAINT_BIZ'])
const INST = ['INST_CLERK', 'INST_PM', 'INST_MGR']
const CLIENT = ['CLIENT_ADMIN', 'CLIENT_SIGNER']
const PLATFORM = ['PLATFORM_ADMIN']
const MAINT_FILING = ['MAINT_MGR', 'MAINT_SUP', 'MAINT_BIZ'].concat(PLATFORM)

function R(path, name, opts) {
  return Object.assign({ path, name, auth: true, batch: 'M2', built: false }, opts || {})
}

const LIST = [
  // ── 主包 17（蓝图 11 + 保留工具 6） ──
  R('/pages/login/index', '登录', { auth: false, built: true }),
  R('/pages/login/agreement', '用户协议与隐私政策', { auth: false, built: true }),
  R('/pages/login/forgot', '忘记密码', { auth: false, built: true }),
  R('/pages/install/home/index', '安装工作台', { roles: INST.concat(['INSTALLER']), tab: true, built: true }),
  R('/pages/maint/home/index', '维保工作台', { roles: MAINT_EXT, tab: true, built: true }),
  R('/pages/client/home/index', '使用单位首页', { roles: CLIENT, tab: true, built: true }),
  // P3 客户账号：选单位页（登录链中途，凭证已验但尚未签发令牌 → auth:false）
  R('/pages/client/select-unit/index', '选择使用单位', { auth: false, built: true }),
  R('/pages/order/list/index', '报修接单', { roles: MAINT, perm: 'order:view_pool', tab: true, batch: 'M3' }),
  R('/pages/order/detail/index', '急修单详情', { roles: MAINT.concat(CLIENT), perm: ['order:view_pool', 'order:view_own'], batch: 'M3' }),
  // 2026-11-21 裁决：客户报修重开（client:report CA/CS 双 Y，页内按主体分流 /unit/faults）；维保主管代报修保留
  R('/pages/order/report/index', '一键报修', { roles: ['MAINT_SUP'].concat(CLIENT), perm: ['order:create', 'order:create_proxy', 'client:report'], batch: 'M3' }),
  // P1 T8-B4：电梯页为客户 tab（client-elevators），perm any-of 补客户码（T2 后客户仅持 client:*，内部码不再命中）
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
  // ── pkg-handover 12（蓝图 8 + 外接建档 4） ──
  R('/pkg-handover/pages/list/index', '移交单列表', { roles: INST.concat(['MAINT_TECH', 'MAINT_SUP', 'MAINT_MGR', 'INSTALLER']).concat(PLATFORM), perm: 'handover:view', tab: true, batch: 'M4' }),
  // 2026-11-21 裁决：handover:create 扩 INST_CLERK（docs/14 V2.8，资料员可起草）
  R('/pkg-handover/pages/create/index', '发起移交', { roles: ['INST_PM', 'INST_CLERK'], perm: 'handover:create', batch: 'M4' }),
  // P1 终审 M6：客户去列（T8 漏做）——T2 收回后客户不持 handover:view，roles 含 CLIENT 为死配置
  R('/pkg-handover/pages/detail/index', '移交单详情', { roles: INST.concat(MAINT).concat(['INSTALLER']).concat(PLATFORM), perm: 'handover:view', batch: 'M4' }),
  R('/pkg-handover/pages/inspect/index', '现场验收', { roles: ['MAINT_TECH', 'INSTALLER'], perm: 'handover:inspect', batch: 'M4' }),
  R('/pkg-handover/pages/rectify/index', '整改回复', { roles: ['INST_PM', 'INST_CLERK'], perm: 'handover:rectify', batch: 'M4' }),
  R('/pkg-handover/pages/sign/index', '双方签字', { roles: ['INST_MGR', 'MAINT_SUP', 'MAINT_MGR'], perm: ['handover:sign_install', 'handover:sign_maint'], batch: 'M4' }),
  R('/pkg-handover/pages/upload/index', '资料上传', { roles: ['INST_CLERK', 'INST_PM'], perm: 'handover:doc_upload', batch: 'M4' }),
  R('/pkg-handover/pages/review/index', '内部审核', { roles: ['INST_MGR'], perm: 'handover:review', batch: 'M4' }),
  R('/pkg-handover/pages/filing-init/index', '外接建档', { roles: MAINT_FILING, perm: 'filing:write', batch: 'M4' }),
  R('/pkg-handover/pages/filing-detail/index', '外接建档详情', { roles: MAINT_FILING.concat(INST), perm: 'filing:view', batch: 'M4' }),
  R('/pkg-handover/pages/filing-inspect/index', '外接接管查验', { roles: MAINT_FILING, perm: 'filing:write', batch: 'M4' }),
  R('/pkg-handover/pages/filing-confirm/index', '外接建档确认', { roles: MAINT_FILING, perm: 'filing:write', batch: 'M4' }),
  // ── pkg-maintenance 7（2026-11-16 审查清理：dynamic-code 已内联 checkin、record-archive 已内联 record-detail，删不可达页） ──
  R('/pkg-maintenance/pages/orders/index', '我的维保工单', { roles: MAINT_EXT, batch: 'M3' }),
  R('/pkg-maintenance/pages/task/index', '作业执行', { roles: ['MAINT_TECH'], perm: 'task:accept', batch: 'M3' }),
  R('/pkg-maintenance/pages/checkin/index', '签到', { roles: ['MAINT_TECH'], batch: 'M3' }),
  R('/pkg-maintenance/pages/checkout/index', '签退', { roles: ['MAINT_TECH'], batch: 'M3' }),
  // P1 T8-B4：内部记录页保持内部码（员工用），客户不可达——客户读记录走 pkg-client/sign token 链路
  R('/pkg-maintenance/pages/record-detail/index', '记录单详情', { roles: MAINT_EXT, perm: 'record:view', batch: 'M3' }),
  R('/pkg-maintenance/pages/plan/index', '维保计划', { roles: ['MAINT_SUP', 'MAINT_MGR'], perm: 'plan:view', batch: 'M3' }),
  R('/pkg-maintenance/pages/record-sign/index', '维保双人签字', { roles: ['MAINT_TECH'], perm: 'record:sign_tech', batch: 'M3' }),
  // ── pkg-client 4（P1 T8-B4：客户 perm 收敛为 client:* 三码；工单/评价入口下线，页面文件保留 P3 复用）──
  R('/pkg-client/pages/todo/index', '待办签字', { roles: CLIENT, perm: 'client:sign', tab: true, batch: 'M5' }),
  // client:sign 为 CA/CS 双 Y（docs/14 §5.4 两角色能力相同）；C-25④：免登录签署链路以 token 即凭证（taskId+token，无会话回退），
  // auth:false 使守卫直接放行（docs/14 §4.6 规则 8 P1/P2 兼容期匿名链路保留）；roles/perm 仅留登记参考，auth:false 优先生效
  R('/pkg-client/pages/sign/index', '签署确认', { auth: false, roles: CLIENT, perm: 'client:sign', batch: 'M5' }),
  // 客户只看记录不看内部工单：roles 置空 = 客户入口下线（报修/工单域关闭，重开由 P3 裁决）
  R('/pkg-client/pages/orders/index', '本单位工单', { roles: [], perm: 'order:view_own', batch: 'M5' }),
  // 评价关闭（docs/14 §4.7，T3-Important#1）：roles 置空断入口，页面保留 P3 复用
  R('/pkg-client/pages/evaluate/index', '服务评价', { roles: [], perm: 'client:evaluate', batch: 'M5' }),
  // ── pkg-admin 7 ──
  R('/pkg-admin/pages/projects/index', '项目台账', { roles: ['MAINT_SUP', 'MAINT_MGR', 'INST_PM', 'INST_MGR'].concat(PLATFORM), perm: 'project:view', tab: true, batch: 'M6' }),
  // C-25⑤：T2 收回后客户不持 contract:view/user:view/stats:view/audit:view 四码（perm 层已挡），
  // contracts/users/stats/audit 四页 roles 中 CLIENT_ADMIN 为死配置，删除（先例 P1 M6 handover detail 去 CLIENT）
  R('/pkg-admin/pages/contracts/index', '合同', { roles: ['MAINT_SUP', 'MAINT_MGR'].concat(PLATFORM), perm: 'contract:view', tab: true, batch: 'M6' }),
  R('/pkg-admin/pages/users/index', '人员', { roles: ['INST_MGR', 'MAINT_SUP', 'MAINT_MGR'].concat(PLATFORM), perm: 'user:view', tab: true, batch: 'M6' }),
  R('/pkg-admin/pages/roles/index', '角色', { roles: PLATFORM, perm: 'role:assign', batch: 'M6' }),
  R('/pkg-admin/pages/stats/index', '统计', { roles: ['INST_MGR', 'MAINT_SUP', 'MAINT_MGR'].concat(PLATFORM), perm: 'stats:view', tab: true, batch: 'M6' }),
  R('/pkg-admin/pages/audit/index', '审计', { roles: PLATFORM, perm: 'audit:view', tab: true, batch: 'M6' }),
  R('/pkg-admin/pages/settings/index', '系统配置', { roles: PLATFORM, perm: 'config:manage', tab: true, batch: 'M6' })
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

// OLD_PAGES 过渡表已删（2026-11-16 审查清理：M6 旧页全删后零消费者）

function metaOf(path) {
  return ROUTES[path] || null
}

module.exports = { ROUTES, metaOf }