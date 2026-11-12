// 小程序按角色分权展示（docs/14 §7 小程序权限矩阵）。
// 只做“显示什么”的映射，真正的鉴权在后端：列表数据范围由 EmployeeScopeService 的读写谓词决定，
// 这里藏掉的入口即便被直接跳转，后端仍会 403/1403。
//
// 入口与后端能力码对齐（docs/14 §6）：外接建档=installation/filing:read+write，维保日历=员工令牌只读投影，
// 使用单位门户=UNIT_ADMIN（门户令牌另存 unit_token，见 services/unit.js）。

const TILE = {
  orderMine: { title: '我的工单', iconClass: 'icon-record', url: '/pages/order/list', tab: true },
  orderGroup: { title: '本班组工单', iconClass: 'icon-record', url: '/pages/order/list', tab: true },
  orderAll: { title: '全部工单', iconClass: 'icon-record', url: '/pages/order/list', tab: true },
  orderRead: { title: '工单巡视', iconClass: 'icon-record', url: '/pages/order/list', tab: true },
  faultMine: { title: '现场急修', iconClass: 'icon-emergency', url: '/pages/fault/list' },
  faultAll: { title: '急修单', iconClass: 'icon-emergency', url: '/pages/fault/list' },
  msg: { title: '消息通知', iconClass: 'icon-message', url: '/pages/message/index', tab: true },
  deviceMine: { title: '我的设备', iconClass: 'icon-device', url: '/pages/elevator/list' },
  deviceAll: { title: '设备台账', iconClass: 'icon-device', url: '/pages/elevator/list' },
  // 移交单：列表 → 发起/详情（install 与维保经理/质量负责人在列表内按状态推进）
  handover: { title: '移交单', iconClass: 'icon-record', url: '/pages/handover/list' },
  // 外接建档：小程序仅四页（发起/详情/接管查验/交接确认），无列表页 → 入口直达发起，
  // 历史单据凭 id 打开详情（管理端「外接建档」可查全量）
  filing: { title: '外接建档', iconClass: 'icon-record', url: '/pages/filing/init' },
  // 维保日历：按月分组的只读排期投影（staff 员工令牌）
  calendar: { title: '维保日历', iconClass: 'icon-device', url: '/pages/calendar/index' },
  // 使用单位门户：门户账号（UNIT_ADMIN）首页，令牌存 unit_token
  portal: { title: '使用单位门户', iconClass: 'icon-device', url: '/pages/unit/home' }
}

/**
 * 角色视图：
 * - dept/roleText：头部展示（部门 + 岗位）
 * - scope：一句话说明“你能看到什么”，避免管理员误以为自己是作业身份
 * - canOperate：是否显示「扫码到场签到」（现场作业动作，巡视/管理岗不显示）
 * - tiles：首页宫格
 * - menus：我的页功能入口
 */
const VIEWS = {
  WORKER: {
    dept: '维保部',
    roleText: '维保人员',
    scope: '仅本人负责的电梯与工单',
    canOperate: true,
    tiles: [TILE.orderMine, TILE.faultMine, TILE.calendar, TILE.msg, TILE.deviceMine],
    menus: [TILE.faultMine, TILE.calendar]
  },
  ASSISTANT: {
    dept: '维保部',
    roleText: '配合维保人员',
    scope: '本人参与的工单；双人作业各签各的',
    canOperate: true,
    tiles: [TILE.orderMine, TILE.faultMine, TILE.calendar, TILE.msg, TILE.deviceMine],
    menus: [TILE.faultMine, TILE.calendar]
  },
  LEADER: {
    dept: '维保部',
    roleText: '维保班组长',
    scope: '本班组工单与电梯',
    canOperate: true,
    tiles: [TILE.orderGroup, TILE.faultMine, TILE.calendar, TILE.msg, TILE.deviceMine],
    menus: [TILE.faultMine, TILE.calendar]
  },
  MAINT_MANAGER: {
    dept: '维保部',
    roleText: '维保经理',
    scope: '全维保部数据（巡视）；排班与审批在管理端完成',
    canOperate: false,
    tiles: [TILE.handover, TILE.filing, TILE.calendar, TILE.orderAll, TILE.faultAll, TILE.msg, TILE.deviceAll],
    menus: [TILE.handover, TILE.filing, TILE.calendar, TILE.faultAll]
  },
  MAINT_QUALITY: {
    dept: '维保部',
    roleText: '维保质量负责人',
    scope: '全维保部只读抽查；不可签退、不可重报',
    canOperate: false,
    tiles: [TILE.handover, TILE.filing, TILE.calendar, TILE.orderRead, TILE.faultAll, TILE.msg, TILE.deviceAll],
    menus: [TILE.handover, TILE.filing, TILE.calendar, TILE.faultAll]
  },
  MAINT_BIZ: {
    dept: '维保部',
    roleText: '维保商务',
    scope: '合同与客户在管理端维护；小程序不含现场作业数据',
    canOperate: false,
    tiles: [TILE.msg, TILE.deviceAll],
    menus: []
  },
  INSTALL_MANAGER: {
    dept: '安装部',
    roleText: '安装项目经理',
    scope: '移交单与外接建档的发起、资料上传与查验推进',
    canOperate: false,
    tiles: [TILE.handover, TILE.filing, TILE.msg, TILE.deviceAll],
    menus: [TILE.handover, TILE.filing]
  },
  INSTALL_CLERK: {
    dept: '安装部',
    roleText: '安装文员',
    scope: '移交与外接建档的资料上传、整改照片',
    canOperate: false,
    tiles: [TILE.handover, TILE.filing, TILE.msg],
    menus: [TILE.handover, TILE.filing]
  },
  INSTALL_WORKER: {
    dept: '安装部',
    roleText: '安装员',
    scope: '现场查验与急修登记（本部门被指派单据）',
    canOperate: false,
    tiles: [TILE.handover, TILE.faultMine, TILE.msg, TILE.deviceAll],
    menus: [TILE.handover, TILE.faultMine]
  },
  ADMIN: {
    dept: '平台层',
    roleText: '平台管理员',
    scope: '三部门数据全量可读（巡视）；现场签字请用对应岗位账号',
    canOperate: false,
    tiles: [TILE.orderAll, TILE.filing, TILE.faultAll, TILE.msg, TILE.deviceAll],
    menus: [TILE.filing, TILE.faultAll]
  },
  SYS_ADMIN: {
    dept: '平台层',
    roleText: '系统管理员',
    scope: '平台级账号：数据全量可读（巡视），不代做现场作业与签字',
    canOperate: false,
    tiles: [TILE.orderAll, TILE.filing, TILE.faultAll, TILE.msg, TILE.deviceAll],
    menus: [TILE.filing, TILE.faultAll]
  },
  UNIT_ADMIN: {
    dept: '使用单位',
    roleText: '安全管理员',
    scope: '本单位电梯、维保记录与报修（门户账号，令牌单独存）',
    canOperate: false,
    tiles: [TILE.portal],
    menus: [TILE.portal]
  }
}

const FALLBACK = {
  dept: '',
  roleText: '未登录',
  scope: '',
  canOperate: false,
  tiles: [],
  menus: []
}

function viewOf(role) {
  return VIEWS[role] || FALLBACK
}

module.exports = { viewOf, VIEWS }
