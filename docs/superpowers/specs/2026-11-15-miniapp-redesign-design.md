# 博威无纸化小程序重构设计规格

> 版本 V1.0 · 2026-11-15 · 状态：§1–§7 已逐节获用户确认，待规格审阅
> 原型来源：`C:\Users\18223\Desktop\UI` V1.3（index.html 17 屏设计板 / routes-and-menus.md V1.3 / roles-permissions.md）
> 关联：docs/14（账号权限）、docs/15（移交清单）、docs/12（待办唯一清单）、AGENTS.md、2026-11-14-admin-redesign-design.md

---

## §0 背景、目标与范围

**现状基线（2026-11-15 机审）**：小程序 40 页（原生 tabBar 4）；services 11 模块；mock 83 路由；后端 Flyway V1—V40、399 单测、182 映射；管理端 23 视图（自建组件库）。后端权限底座 M1—M5 已落地（13 规范角色 / 86 能力码三态 / JWT scopeVersion / MultiRoleGate / ScopeResolver 读写双谓词）。

**目标**：按原型 V1.3 重构小程序——主包 + 4 子包路由拓扑、自定义底部导航、三类工作台 + 平台管理、单账号多角色统一登录、深色工业风设计语言、界面全中文、品牌「博威无纸化」。合规主干（签到/动态码/清单/签退/各签各的/离线上报/上报快照）逐字保留。

**范围**：49 页（蓝图 35 路由 + 合规/保留扩展 14）；后端账号统一（V41）+ 记录生命周期（V42）+ 菜单下发 + 评价端点 + 门户端点改鉴权；mock 与 docs/04 三处同步；旧 40 页最终全部删除。

**非目标**：不引入新 UI 框架；不开任何自动重试（AGENTS §2.4）；不改平台调用链路 2.1—2.8；订阅消息真实推送留 C-01；生产清库仍人工门禁（docs/12 G 组）；管理端本期不改（仅复用其端点并验证放行）。

### §0.1 决策记录（用户确认）

| 编号 | 决策 | 口径 |
|---|---|---|
| D1 | 身份模型 | **完全按蓝图单账号多角色**：单位账号并入员工账号体系，CLIENT_* 为同账号角色，支持工作台切换；V41 迁移；AGENTS §3.9 重写 |
| D2 | 上报时机 | **签退即上报 2.6 不变**（AGENTS §3.5）；蓝图 §6.2 状态机作为签退后记录生命周期叠加；归档/修订不触发重复上报 |
| D3 | 执行策略 | **底座先行分批 M0—M6**；每批门禁全绿后提交并合入 master；旧页随域迁移删除 |
| D4 | 品牌与文案 | 品牌 = **博威无纸化**（原型「梯维通 / LIFT/OPS」全部替换）；**所有页面不允许出现英文**：装饰性英文微标改中文；禁用提示用中文能力名 + 允许角色（权限码只留代码层）；单号/电梯编号等业务标识属数据编码不在禁令内；代码标识符仍按 AGENTS 编码约定 |
| D5 | 公司 logo | 页面仅使用**圆形徽标裁切件**（BOWEI 字母不渲染上页）；原图存 `assets/brand/` 备查；M2 裁切入包 |

---

## §1 总体架构与路由拓扑

### 1.1 包结构

主包 + 4 子包，共 49 页：主包 17（蓝图 11 + 保留工具 6）、pkg-handover 12（蓝图 8 + 外接建档 4）、pkg-maintenance 9（蓝图 5 + 合规扩展 4）、pkg-client 4、pkg-admin 7。

### 1.2 导航形态（M2 一次性结构切换）

- 删除原生 `tabBar`，改 `custom-tabbar` 组件 + `wx.reLaunch` 切 Tab；详情页 `navigateTo`；登录/403 `reLaunch`。
- 全局 `navigationStyle: "custom"` + 共享 `nav-bar` 组件（状态栏高度自适应、胶囊避让、返回/品牌区），还原原型 40/44 顶部区。
- Tab 数据源：后端 `GET /me/menus` 下发优先，前端 `ROLE_TABS` 兜底（逐字镜像蓝图 routes-and-menus.md L257–315）；4–5 项自适应等宽、角标、safe-area 底部留白。
- `subpackages` + `preloadRule`（维保工作台预载 pkg-maintenance）。
- 过渡期（M2—M5）：旧页暂留可达，旧 `wx.switchTab` 调用统一 shim 为 `reLaunch`/`navigateTo`；各域迁移完成后删除旧页。

### 1.3 语义口径

沿用管理端已确认的反转约定：`order/*` = 急修/报修（Fault）；维保工单全部在 `pkg-maintenance`，**绝不套急修七态状态机**。

### 1.4 旧 40 页 → 新路由映射

| 域 | 旧页 | 新路由 | 批次 |
|---|---|---|---|
| 工作台 | pages/home/index | pages/maint/home/index（重建） | M2 |
| 工作台 | （新） | pages/install/home/index | M2 |
| 工作台 | pages/unit/home | pages/client/home/index（重建，屏 10） | M2 |
| 登录 | pages/login/index | pages/login/index（重建：单入口、不选角色） | M2 |
| 登录 | pages/login/agreement | pages/login/agreement（保留） | M2 |
| 登录 | （新） | pages/login/forgot（V2.19 忘记密码·微信验证重置） | V2.19 |
| 我的 | pages/mine/index + pages/unit/mine/index | pages/profile/index（合并：单身份卡 + 切换工作台） | M2 |
| 我的 | pages/mine/password | pages/profile/password | M2 |
| 我的 | pages/mine/offline | pages/offline/index（保留非 Tab） | M2 |
| 范式 | （新） | pages/403/index（屏 12，不回显目标 id） | M2 |
| 公共 | pages/message/index | pages/message/index（保留非 Tab，工作台/我的入口） | M2 |
| 公共 | pages/common/watermark-camera | pages/common/watermark-camera（主包共享） | M2 |
| 公共 | pages/common/signature | pages/common/signature（主包共享画布） | M2 |
| 维保 | pages/order/list | pkg-maintenance/pages/orders/index（保留扩展：我的维保工单，工作台「全部」入口） | M3 |
| 维保 | pages/order/detail | pkg-maintenance/pages/task/index + pkg-maintenance/pages/record-detail/index | M3 |
| 维保 | pages/order/checklist + item-form | pkg-maintenance/pages/task/index | M3 |
| 维保 | pages/order/checkin | pkg-maintenance/pages/checkin/index（保留迁包） | M3 |
| 维保 | pages/order/dynamic-code | pkg-maintenance/pages/dynamic-code/index（保留迁包） | M3 |
| 维保 | pages/order/checkout | pkg-maintenance/pages/checkout/index（保留迁包） | M3 |
| 维保 | pages/order/record-preview | pkg-maintenance/pages/record-detail/index（屏 05） | M3 |
| 维保 | pages/calendar/index | pkg-maintenance/pages/plan/index（SUP/MGR 月视图） | M3 |
| 维保 | （新） | pkg-maintenance/pages/record-sign/index | M3 |
| 维保 | （新） | pkg-maintenance/pages/record-archive/index（归档/PDF） | M3 |
| 急修 | pages/fault/list | pages/order/list/index（屏 07 报修接单池） | M3 |
| 急修 | pages/fault/detail | pages/order/detail/index（屏 06） | M3 |
| 急修 | pages/fault/report + pages/unit/report | pages/order/report/index（一键报修 + 员工代报修合并） | M3 |
| 电梯 | pages/elevator/list + pages/unit/elevators | pages/elevator/list/index（scope 由后端谓词收敛） | M2 |
| 电梯 | pages/elevator/detail | pages/elevator/detail/index（屏 08） | M2 |
| 移交 | pages/handover/list | pkg-handover/pages/list/index（含 tab=docs/review 参数） | M4 |
| 移交 | pages/handover/init | pkg-handover/pages/create/index（屏 09） | M4 |
| 移交 | pages/handover/detail | pkg-handover/pages/detail/index（屏 02 + C-12 74 项留证） | M4 |
| 移交 | pages/handover/inspect | pkg-handover/pages/inspect/index（现场验收逐项留证） | M4 |
| 移交 | pages/handover/rectify | pkg-handover/pages/rectify/index（1:1 逐条整改复验） | M4 |
| 移交 | pages/handover/confirm | pkg-handover/pages/sign/index（双方签字） | M4 |
| 移交 | （新） | pkg-handover/pages/upload/index（资料员 D1—D20 上传） | M4 |
| 移交 | （新） | pkg-handover/pages/review/index（主管内部审核） | M4 |
| 外接 | pages/filing/init | pkg-handover/pages/filing-init/index（保留扩展） | M4 |
| 外接 | pages/filing/detail | pkg-handover/pages/filing-detail/index | M4 |
| 外接 | pages/filing/inspect | pkg-handover/pages/filing-inspect/index | M4 |
| 外接 | pages/filing/confirm | pkg-handover/pages/filing-confirm/index | M4 |
| 单位 | pages/unit/records | pkg-client/pages/todo/index + pkg-maintenance/pages/record-detail/index | M5 |
| 单位 | pages/unit/sign | pkg-client/pages/sign/index（屏 11，taskId&token） | M5 |
| 单位 | pages/unit/faults | pkg-client/pages/orders/index（本单位工单只读） | M5 |
| 单位 | （新） | pkg-client/pages/evaluate/index（服务评价，新端点） | M5 |
| 管理 | （新） | pkg-admin/pages/{projects,contracts,users,roles,stats,audit,settings}/index（屏 03；复用管理端端点） | M6 |

### 1.5 不变量

页面只调 services；路由白名单 `RouteMeta` 全量登记（含扩展路由）；页面只传 id/token，进页重拉；所有跳转路径来自白名单（扫码/订阅消息深链同样走白名单）。

---

## §2 设计系统（深色工业风）

### 2.1 色彩令牌（逐字取自原型 :root，落 styles/tokens.wxss 全局 CSS 变量）

| 令牌 | 值 | 用途 |
|---|---|---|
| --ink / --ink-2 | #0A0F12 / #0D1418 | 主背景 / 次背景 |
| --panel / --panel-2 | #131C21 / #172228 | 卡面 / 抬升卡面 |
| --line / --line-2 | #26343B / #2E3E46 | 边界线 / 高亮线 |
| --text / --muted / --dim | #E8EEF0 / #A3B4BB / #7E929B | 正文 / 次级 / 弱文字 |
| --amber / --amber-soft | #FFB020 / #FFC85C | 主操作 / 彩底提亮 |
| --signal / --signal-soft | #FF4B2B / #FF8F7A | 急修困人 / 提亮 |
| --ok / --ok-soft | #2FC48D / #5FE0B2 | 合格完成 / 提亮 |
| --paper / --paper-ink / --paper-line | #EDE5D3 / #22201A / #C6BA9F | 签字单据纸面三件套 |

规则：正文对比度 ≥4.5:1；彩色底次级文字用同色系 soft 提亮不灰化；状态色映射——橙红=困人急修、安全黄=进行中、绿=合格/已验收、灰=待办。

### 2.2 单位与布局

原型 375 画布 → 750rpx，全部 px×2。安全边距 28rpx（登录 40rpx）；卡片圆角 8rpx、按钮 10rpx、标签 4/6rpx、chip 40rpx（金属硬边）；间距三级 56/104、30/32/36/40、8–28rpx 密排；内容超高纵向滚动不静默裁切；Operate 场景重复结构即肌肉记忆。

### 2.3 字体（全部本地化，零远程加载）

- 正文系统栈：'PingFang SC','Microsoft YaHei','Hiragino Sans GB',system-ui
- 等宽：'SFMono-Regular',Consolas,'Liberation Mono' + tabular-nums（数字统一等宽）
- 纸面：'Songti SC','SimSun',serif（记录单/签字区）
- 显示字：ZCOOL QingKe HuangYou **75 字子集 base64 内嵌** @font-face（从原型提取；OFL 1.1 无保留名，子集合规）；**按需扩字**覆盖「博威无纸化」及各屏标题用字
- 字号层级 ×2：display 44rpx / 正文 24rpx / 弱 20rpx / mono 数据 20rpx / 装饰微标 18rpx；屏内正文下限 20rpx，装饰性微标下限 18rpx（原型 ≥9px 口径，图标尺寸不计入）

### 2.4 图标

原型 64 个 Font Awesome Free 6.5.2 symbol → 子集化为 **iconfont base64 WOFF2**（styles/icons.wxss，.i-xxx 类，颜色随 color 继承）；小程序无法直用 SVG sprite，此为等价落地。署名（图标 CC BY 4.0 + 字体 OFL 1.1）落在「我的」页可见中文文本。零外链。

### 2.5 共享组件（components/）

- custom-tabbar：4–5 项自适应等宽、角标、safe-area 底部留白（wx.getWindowInfo 取 safeArea）
- nav-bar：自定义导航（状态栏高度自适应 + 胶囊避让 + 返回/品牌区，品牌 = 圆形徽标 + 博威无纸化）
- status-tag：状态色映射标签
- perm-button：禁用两档——永久权限拒绝 = 原生 disabled + 相邻中文「所需能力 · 允许角色」文本；临时前置未满足 = catchtap 拦截 + 原因文本（保持可聚焦可读）
- empty-state（真没有 vs 筛选后没有两种文案）/ skeleton（首屏骨架）/ error-retry（失败可重试）——屏 13–15 结构文案直搬
- mask-field：六类字段脱敏渲染（服务端掩码值 + △标记，前端不解密）
- signature-paper：纸面签字区（手写 canvas + GPS + 时间戳，签后锁定）
- photo-thumb：水印缩略图（斜排工号 + 时间角标；真实水印服务端烧录）
- 工作台部件：task-card / kpi-row / qa-grid / sec-head / week-strip / sync-note

### 2.6 四态范式与无障碍

403 独立路由（不回显目标 id）；空/加载/失败为组件态，每个数据页四态齐全。可点件一律 button；WXML aria-role/aria-label 中文标注；每屏最多一个页面级主操作，列表行内重复动作用次按钮。

### 2.7 WXSS 组织

app.wxss @import tokens/icons/fonts + 基础类（.btn/.chip/.tag/.card/.mono/.disp）；页面 wxss 只写布局差异；无浅色分支。

### 2.8 品牌与全中文（D4/D5）

品牌文字一律「博威无纸化」；app.json navigationBarTitleText、nav-bar、登录页、「我的」署名统一。原型英文装饰微标（TODAY/SHIFT/SYNCED/FRI 等）改中文（今日任务/班次/已同步/周五）。页面仅渲染圆形徽标裁切件，BOWEI 字母不上页。

---

## §3 身份与权限（单账号多角色）

### 3.1 账号统一（后端 V41，M1）

- `use_unit_account`（V34）并入 `sys_employee` + `sys_user_role`：按手机号匹配——已有员工账号则合并为多角色（追加 CLIENT_ADMIN/CLIENT_SIGNER，department=USE_UNIT，保留 use_unit_id 关联）；仅单位账号则新建员工记录并迁移 BCrypt 哈希与 openid 绑定。
- 冲突规则：同手机号员工密码优先；邀请码绑定关系迁移到员工 openid 绑定（uk_openid 不变量不变）。
- **旧表不删**（留存口径）：use_unit_account 保留并标记已迁移；`/unit/login`、`/unit/login-by-wechat` 废弃（契约保留标注），统一走 `/auth/login`。
- 生产全测试数据（docs/12 G 组清库为人工门禁）；迁移按 V36 模式：dev/test 回填、生产空库 no-op，**严禁自动 TRUNCATE/DELETE**。

### 3.2 登录流（单入口）

- 登录页 = 账号/口令唯一表单，不选角色、无单位入口；微信免密为已绑定账号可选能力（入口在「我的」；§2.3 openid 只信服务端换取不变）。
- 登录响应：`token + userInfo + roles[] + perms[] + scope + activeWorkspace`；多角色权限取**并集**；默认首页按 ROLE_PRIORITY（维保 > 安装 > 使用单位）。
- 单会话：em_token/unit_token 双令牌废除，统一单 token 存储；登录清旧会话。
- **AGENTS §3.9 重写为**：「单账号单会话：一个手机号 = 一个账号，同一时刻一个会话令牌；账号可持多角色，权限取并集，数据范围逐角色判断；activeWorkspace 决定工作台与 Tab 渲染，可在『我的』切换；登录即清旧会话。微信绑定为可选能力，入口只在『我的』页。」（§10 追加修订记录）

### 3.3 工作台切换

「我的 → 切换工作台」列出账号持有的工作台；切换后重请求 `GET /me/menus?workspace=...`；tabs/home 随该工作台，权限保持并集，数据范围逐角色判断（ScopeResolver 读写谓词不变，写绝不扩区域/部门）。

### 3.4 菜单下发（新端点）

- `GET /me/menus?workspace=` 返回 `user/home/tabs/quickActions/actions/badges`（蓝图 §5.1 结构）；新增 MiniappMenuService 按 PermissionMatrix v2 + ROLE_TABS 投影；管理端 `/auth/menus` 菜单树不动。
- actions = 按钮级权限码映射（前端 wx:if，后端每端点再校验）；badges = pendingOrders/pendingSigns。
- 前端 config/menus.js 兜底逐字镜像蓝图 ROLE_TABS/ROLE_HOME/ROLE_PRIORITY（CommonJS 移植）；漂移守卫测试锁定前后端一致。

### 3.5 四层守卫

- config/routes.js RouteMeta 白名单全量登记（含扩展路由）；utils/guard.js 提供 canAccess（登录态 → 角色 → 权限码）+ definePage 包装（onLoad/onShow 双层校验）。
- 无令牌 → reLaunch 登录?redirect=；无权限 → reLaunch 403（不回显目标 id）；单据状态层页内判断（后端权威）。
- 旧 utils/role-view.js 由菜单驱动渲染取代，M6 删除。

### 3.6 角色口径

13 规范角色（M1）不变；蓝图 9 角色有专属 ROLE_TABS；扩展角色归属最近工作台只读投影：MAINT_QUALITY → 维保工作台（同 MAINT_SUP tabs 去写动作）、MAINT_BIZ → 维保工作台（项目/合同只读）、INSTALLER → 安装工作台（同 INST_CLERK）；旧角色码别名继续兼容（RoleCatalog v2）。

### 3.7 使用单位签字链路

复用 UnitRecordController sign-view/confirm-by-token（免登录 + 幂等守卫），包装为一次性签署任务（taskId+token）路由到 pkg-client/pages/sign/index；发起 = 主检/主管（record:sign_request）；订阅消息通知依赖 C-01（本期留待办入口 + 站内消息）。

---

## §4 业务域与状态机

### 4.1 维保作业域（pkg-maintenance + 维保工作台）

**合规主干逐字不动**（AGENTS §3.1–3.5）：接单 → 签到（GPS 必填/缺失 422/Haversine 三级阈值/UNKNOWN 留痕回填 SELF_COLLECTED/主配合分别留痕/重复 1003）→ 配合人动态码（HMAC 5s 步长 60s 有效/原子消费/校验先于写）→ 清单（周期项 notInThisRun 豁免/关键项 photoRequired 至少 1 照片 NA 除外/异常显式 S0–S7）→ 各签各的（POST /work-orders/{id}/signature）→ 仅主维保签退（PROCESSING/≥30 分钟双保险/双签齐）→ **冻结快照 + 2.6 即报**（失败仅标记，逐条人工重报）。

**蓝图 §6.2 生命周期叠加在签退之后**（V42 加生命周期列，版本链复用 V38）：

双签完成(=签退) →〔待复核·开关 = sys_config 键 record_review_enabled（管理端配置页维护），默认关闭 = 免复核直达，与原型基准一致；主管 record:review〕→ 待使用单位签字 → 发起签署(主检/主管) → 待使用单位签署(一次性任务+token) → 已签署/拒签(必填原因退回维保人员) → **自动归档**(PDF+SHA-256，系统/主管 record:archive) → 已归档 → 修订(经理+平台双人 record:amend，新版本+新哈希，原签名永久保留，**不重报 2.6**，P-5)。

- 新增分支端点：配合人退回（待配合签字→作业中，无上报副作用）、复核通过/退回、发起签署、拒签退回；归档不触发任何平台调用。
- 远程配合豁免：solo_exempt（V38 已有）经理审批留痕。
- task 页（屏 04）：清单按**真实模板附件 A—D**分组（原型 28 项仅演示基准，实现用 ChecklistTemplateSeeder 真实模板）；点检项四态（待检/进行中展开实测+拍照/合格/异常强制备注）；签到/签退入口。
- orders 扩展页承接旧工单列表（状态筛选）；plan 页 = 日历月视图（派单仅定时驱动，读接口不触发，不变量不动）。

### 4.2 移交域（pkg-handover，含 C-12 小程序剩余项）

- 蓝图 §6.1 状态机与现有 HandoverService 状态做 **M0 逐态映射表**；C-12 判定结论（7.2/7.3 五路径：QUALIFIED/CONDITIONAL/RECTIFY/DISQUALIFIED）驱动流转：全合格→待双方签字、任一不合格→整改（1:1 逐条复验）、有条件移交三条件、☆NA 经理审批。
- inspect 页 74 项 G01–G13 逐项留证（结果/实测值/不合格描述/NA 原因码 NA-01–05/照片，☆与试验项不合格强制照片）；rectify 页逐条整改上传 + 复验；upload 页 D1–D20 资料核对（3 项强制卡点）；review 页主管审核签章；sign 页五节点签字。
- 容缺受理分支：缺件补传完成前不得「已生效」；生效→台账在保 + 首保计划（沿用现有生成口径）；平台同步沿用现有 2.3/2.4 链路，不新增平台调用。
- filing 四页保留迁包（外接建档 W2 流程不动，archive_source=2 + 首保 +3 日口径不变）。

### 4.3 急修/报修域（pages/order/*）

- 蓝图 §6.3 七态：待接单→抢单/派单→已接单→到场打卡(GPS+时间戳)→处置中→提交急修单→待使用单位确认→已闭环/待整改；改派/升级（SLA 阈值 config:manage，order:escalate）；取消必填原因。
- 现有 fault 状态机与七态做 M0 映射（维保工单绝不套此七态）；order:close = 系统/主管，维保人员闭环后只读。
- 使用单位确认需 CLIENT_SIGNER（管理员须兼任）；复用 token 确认链路。
- **新增评价端点** `POST /faults/{id}/evaluate`（client:evaluate，已闭环后可评，1–5 星 + 文字），三处同步。
- report 页合并一键报修（CLIENT order:create）与员工代报修（V38 已有）。

### 4.4 使用单位域（pkg-client + client/home）

- 工作台（屏 10）：待签署/待评价 KPI + 报修入口 + 最近记录；todo 合并待签记录单/急修单（管理员兼任签字人不加 Tab，签字动作按 CLIENT_SIGNER 校验独立留痕）。
- sign 页（屏 11）：核对项目/异常整改/现场照片 → **勾选确认门控**（未勾选拦截激活 + 原因文本）→ 手写签名 → 时间戳/GPS/设备指纹 → 锁定；拒签必填原因。
- orders 本单位工单只读 + 进度；evaluate 服务评价。

### 4.5 管理统计域（pkg-admin，M6）

- projects（屏 03）：外接台账 KPI + 合同临期提醒；「新增外接项目」= 永久权限拒绝档（缺 project:edit 原生 disabled + 中文能力文本）。
- contracts 金额服务端分级脱敏（M3 已有）；users/roles/stats/audit/settings 复用管理端 M3/M5 端点（MultiRoleGate 放行验证），小程序做只读投影 + 配置页（SLA/保养周期/签字模板）。
- PLATFORM_ADMIN 三 Tab（组织/审计/配置）+ 我的；不可代签、不可改已归档签名（红线不变）。

### 4.6 交付前置（M0）

三张状态机映射表（记录/移交/急修 旧态→新态）+ 49 页路由对照 + 106 唯一权限码覆盖核对（86 v2 三态 + 20 旧码独有） + 原型资产提取清单，作为 M1 实施依据。

---

## §5 数据流与接口变更

### 5.1 services 层调整（页面只调 services，方法签名 mock↔真实稳定）

| 模块 | 变更 |
|---|---|
| auth.js | 统一登录（roles/perms/scope/activeWorkspace）；删门户登录分支；改密统一 |
| 新增 menu.js | getMenus(workspace) + 兜底 ROLE_TABS 镜像 + 漂移守卫 |
| order.js | 维保工单：保留签到/动态码/清单/签退/签字；新增配合退回/复核/发起签署/归档投影 |
| fault.js | 急修/报修：池/抢单/派单/到场/提交/确认/取消；新增 evaluate |
| unit.js → client.js | 待办/签署/本单位工单/评价；门户登录方法删除；token 签署链路保留 |
| handover.js | 扩 74 项留证/逐条复验/资料上传/审核（复用 C-12 既有端点） |
| home.js | 工作台汇总（KPI/今日任务/急修横幅/快捷动作/角标） |
| filing/elevator/message/upload/location | 保留不动 |

### 5.2 接口三处同步清单（services + mock/server.js + docs/04 A，逐批登记）

1. `POST /auth/login` 响应扩展（改）
2. `GET /me/menus?workspace=`（新，MiniappMenuService；管理端 /auth/menus 不动）
3. 记录生命周期：`POST /work-orders/{id}/signature-refuse`（配合退回）、`POST /work-orders/{id}/review`（复核通过/退回）、`POST /work-orders/{id}/sign-request`（发起签署）、`POST /unit/records/{id}/refuse-by-token`（拒签必填原因）、`GET /work-orders/{id}/archive`（PDF+哈希投影）；归档系统自动；修订复用管理端 amend-v2（不重报 2.6）
4. `POST /faults/{id}/evaluate`（新）+ 急修七态缺口端点（M0 映射后定）
5. 门户读端点（/unit/records、/unit/faults、/unit/pending-signs、/unit/elevators）改统一令牌 + CLIENT 数据谓词（改）；/unit/login* 废弃保留契约标注；/unit/account/password 并入 /auth/change-password
6. mock 同步新增约 12 路由（83→约 95）；演示账号按蓝图基准人物多角色配置（李振国 MAINT_TECH / 张伟 CLIENT_ADMIN+CLIENT_SIGNER 兼任 / 王强 INST_PM / 刘倩 INST_CLERK / 主管 / 经理 / 平台管理员）

### 5.3 数据流

- 页面 onLoad → guard 双层校验 → services → utils/request（**单 token** Bearer / X-Idempotency-Key / 401→reLaunch 登录 / callContainer 分流不变）→ 四态渲染。
- app.onLaunch 恢复会话 → getMenus → globalData（tabs/home/actions/badges）；custom-tabbar 读 globalData，角标随汇总刷新。
- 工作台/详情只传 id/token，进页重拉（防脏数据越权）。

### 5.4 离线与文件

- utils/offline.js 不动：清单/照片/签名离线快照 + 持久化补传队列，恢复自动补传，冲突以现场提交为准。
- 照片/签名读路径统一 FileStorageService.urlOf 重签（?s=）；缩略图水印服务端烧录口径不变。

### 5.5 错误处理与脱敏

- 错误码/文案全取 constants（ERROR_CODES/TOAST_TEXT/EMPTY_TEXT），页面不硬编码；1403→403 页或页内无权限态；1001/1002/1003 业务拦截 toast 留痕。
- 四态组件全覆盖；分页失败保留已载数据 + 重试入口。
- 六类字段脱敏**服务端权威**（roles-permissions §5.3），前端不接收明文，导出按导出人权限重新生成：

| 字段 | 默认展示 | 完整可见 |
|---|---|---|
| 使用单位联系人手机号 | 138****6021 | 单位管理员、维保人员（本人派工单）、主管/经理、平台；安装项目负责人仅移交期内 |
| 安装部/维保人员手机号 | 脱敏 | 本单位管理员/主管、单位管理员（本单位相关工单）、平台 |
| 身份证号 | 310***********1234 | 平台 + 授权 HR（需审批） |
| 合同金额 | 脱敏/不展示 | 维保经理、单位管理员（本单位）、平台 |
| 故障现场照片 | 水印 + 缩略图 | 相关维保人员、使用单位、主管、平台 |
| 电梯详细地址 | 派工人员完整 | 其他角色仅小区/楼栋 |

- 日志脱敏红线不变（token/手机号/openid/密钥不落日志）。

---

## §6 测试与验收

### 6.1 每批门禁（AGENTS §4.4，逐批全绿才提交）

- 根 npm run lint；后端 mvn test；触及 admin 时三件套；派单/模板链路改动跑 verify-dispatch.js / verify-templates.js。
- 新增路由白名单回归脚本 scripts/verify-miniapp-routes.js：app.json 每页必登记 RouteMeta；ROLE_TABS 前端镜像与后端投影逐字漂移守卫；旧→新映射完整性（49 页无遗漏）。

### 6.2 后端新增测试（约 40+ 例）

- V41 账号合并：同手机号多角色合并/单位独立建号/密码优先/openid 迁移/生产空库 no-op。
- MiniappMenuService：9 角色 tabs 投影、多角色并集去重 ≤5、workspace 切换、actions/badges。
- 记录生命周期：复核可配、发起签署门控（双签齐）、拒签必填原因、自动归档 PDF+哈希、归档/修订不触发 2.6、配合退回无副作用。
- evaluate（仅已闭环 + CLIENT 范围）、统一登录响应、门户端点改鉴权后旧 unit_token 拒绝、越权 1403 无副作用。

### 6.3 小程序链路验收（mock 或本地后端，人工 + 脚本）

9 角色登录路由 + 多角色并集 + 工作台切换；维保全链路（签到→动态码→清单→双签→签退→2.6 快照）→ 生命周期（复核/发起/客户签署/拒签/归档）；移交 74 项留证→1:1 整改→逐条复验→签字→生效；急修七态 + 评价；客户签署勾选门控；四态范式逐页抽查；六类脱敏对照。

### 6.4 真机项（docs/03 清单 + 新增）

水印/签名笔迹/定位拒绝提示/单手操作；**深色主题强光可读性**为新增真机检查项。

### 6.5 视觉验收

17 屏逐屏对照原型；**结构等价取证与真实绘制取证分开标注**（模拟器/真机截图为真实绘制；脚本计数为结构），测量条件声明，不以结构冒充目视。

### 6.6 执行模式

Windows 环境子代理不可用 → inline 执行 + 每任务自审留痕；验收结论回写 docs/05、docs/10、docs/12。

---

## §7 批次拆分（M0—M6）

| 批 | 内容 | 门禁 |
|---|---|---|
| M0 映射基线 | 三张状态机映射表（记录/移交/急修 旧→新）+ 49 页路由对照 + 106 唯一权限码覆盖核对（86 v2 三态 + 20 旧码独有） + 原型资产提取清单（令牌/字体子集/图标/徽标裁切） | 用户审阅 |
| M1 后端底座 | V41 账号统一 + V42 记录生命周期；/auth/login 扩展、/me/menus、生命周期端点、evaluate、门户端点改鉴权；mock+docs/04 三处同步 | mvn test 全绿 + 三处同步核对 |
| M2 小程序壳 | app.json 重构（子包/自定义导航/custom 导航栏）、tokens/icons/fonts、tabbar/nav-bar/四态/脱敏/perm-button 组件、登录页重建（博威无纸化+徽标）、三工作台、profile（切换工作台）、403、guard/routes/menus；旧页暂留（switchTab shim） | lint + 模拟器走查（登录→三工作台→切 Tab→403）+ 路由脚本 |
| M3 维保作业域 | pkg-maintenance 9 页 + 工作台任务接线 + 生命周期 UI + 急修三页；删旧 order/*、fault/* | lint + mock 全链路 + verify-dispatch/templates |
| M4 移交/外接域 | pkg-handover 12 页（含 C-12 逐项留证 UI）+ filing 迁包；删旧 handover/*、filing/* | lint + 移交全流程 + C-12 测试回归 |
| M5 使用单位域 | pkg-client 4 页 + client 工作台接线 + 签署门控 + evaluate；删旧 unit/*；unit.js→client.js | lint + 签署链路 + evaluate 测试 |
| M6 管理统计+收尾 | pkg-admin 7 页（MultiRoleGate 放行验证）、工具页翻新、四态/脱敏全覆盖审计、删 role-view.js 与全部旧页残留、文档全同步（03/04/12/14 + AGENTS §3.9 重写 + §10 修订 + 基线计数） | 全门禁 + 全路由走查 + 17 屏视觉对照 |

**计划口径**：本规格为纲领性设计（同管理端重构先例），实施计划按批次逐个产出（writing-plans），M0/M1 先行，每批计划独立审阅后执行。

**依赖**：M1→M2→{M3,M4,M5}→M6；M3→M5（记录生命周期 UI 共享）。每批门禁全绿后提交 codex/ 分支并 fast-forward 合入 master、清理分支；docs/12 逐批回写。

**不做**：不引新 UI 框架；不开自动重试；不改平台调用链路；订阅消息真实推送留 C-01；生产清库仍人工门禁。

---

## §8 风险、依赖与文档同步

**风险**：
- 深色主题强光可读性 → 新增真机检查项，对比度令牌已 ≥4.5:1 兜底。
- 主包体积 → 业务页全进子包 + preloadRule；字体/图标 base64 子集控制体积。
- 账号合并冲突 → 密码优先规则 + 生产全测试数据（清库门禁兜底）；dev/test 回填测试锁定。
- 过渡期旧页 switchTab 失效 → M2 统一 shim，各域迁移完即删。
- ZCOOL 子集缺字 → M0 资产提取时按全量标题用字扩子集（OFL 无保留名合规）。

**依赖**：C-01 订阅消息（签署通知本期降级为站内消息 + 待办入口）；生产清库人工门禁；MultiRoleGate 小程序放行验证（M6）。

**文档同步**：docs/03（页面结构 49 页）、docs/04（接口三处同步逐批）、docs/05/10（验收结论）、docs/12（待办逐批回写）、docs/14（账号统一落地）、AGENTS §3.9 重写 + §10 修订 + 基线计数；部署/配置若变动同步 README/docs/02/docs/11。

**分支说明**：本规格提交于 codex/miniapp-redesign（自 codex/admin-zh-localize HEAD 创建，携带其 7 个已提交未合入的管理端中文化 commit，合入时一并收敛）。