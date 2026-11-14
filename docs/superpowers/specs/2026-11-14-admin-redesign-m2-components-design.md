# 管理端重构 M2：自建组件库 + 应用壳 设计文档

**文档编号**：DM-ELEV-ADMIN-M2-2026-001
**版本**：V1.0（2026-11-14）
**程序级依据**：`docs/superpowers/specs/2026-11-14-admin-redesign-design.md`（V1.7，五项战略决策 + §6 目标 IA + §7 组件库规格 + §9 里程碑）
**设计输入**：`docs/16-梯维通设计输入/admin-spec.md`（§8 token / §9 mock 基准集 / §11 验收场景）、`roles-permissions.md`、`routes-and-menus.md`；视觉/交互验收基准 = `Desktop/UI/admin/` 静态原型（vanilla JS + mock-data.js，file:// 零依赖，1440×900 优先兼容 1280）
**承接**：M1 后端权限底座已落地并合并 master（V35/V36、RoleCatalog v2 十三角色、PermissionMatrix v2 八十六码三态、LegacyCapabilityTranslator、JWT scopeVersion、AuthInterceptor v2 多角色集、ScopeResolver、GrantService、AuditLogService；后端 362 单测）

> 本文件是 M2 子项目的详细设计（spec），经用户评审通过后转 writing-plans 产出实施计划。M2 是纯前端里程碑，**零触碰后端/小程序/mock/services/业务查询/上报/派单**。

---

## 0. 五项已确认决策（2026-11-14，用户逐项裁决）

| # | 决策 | 取舍 |
|---|---|---|
| Q1 | **双布局共存** | 新 AppShell 只包新视图；旧 17 业务视图保留旧 AdminLayout + Element Plus 原样可用；EP + 旧 permission.js 到 M5 最后一视图迁完统一移除。否决「新壳包全部」（视觉混搭）与「M2 一次性移 EP」（违背分批、风险集中）。 |
| Q2 | **前端镜像新权限模型** | 新增 `permission-v2.js`（13 角色 + 86 码三态 + canonical 别名，fail-closed，逐字镜像 M1）；旧 24 码 permission.js 留旧视图；新 IA 菜单用前端常量渐进渲染；后端 MenuService 新 IA 下发推迟 M3。否决「M2 扩后端 MenuService」（扩范围）与「新壳用旧 24 码」（脱节）。 |
| Q3 | **原型基准集 + 真实多角色机制** | 工作台 KPI/待办用 admin-spec §9 mock 基准集（过走查）；多角色切换机制完整实现（activeRole 驱动壳范围/菜单/PermButton），M2 用基准集 5 视角，生产真实 roleSet 接线随 M3。否决「真实后端数据」（M2 含后端改动）与「最小落地页」（过不了走查）。 |
| Q4 | **样式 SFC + composables** | `admin/src/ui/` PascalCase 单文件组件，scoped style 消费 CSS 变量 token；props down/events up + v-model；headless 逻辑抽 composables 与样式分离便于单测；统一 index.js 导出。否决「headless 原语 + 主题层」（YAGNI 换肤）与「原型 vanilla 直译」（耦合难测）。 |
| Q5 | **组件单测 + 视图测试 + 人工走查** | composables 纯逻辑单测 + 组件交互测试（vitest + happy-dom）+ 登录/工作台/403 视图测试（关 D-02）+ 1440×900 对照原型人工走查留截图。否决「Playwright 视觉回归」（新依赖 + 工作量）与「仅组件单测」（D-02 未关）。 |

---

## 1. 架构与共存

### 1.1 目录结构（新增 `admin/src/ui/`，旧代码零改动）

```
admin/src/
├─ ui/                      ← M2 新建自建组件库
│  ├─ tokens.css            设计 token（§2）
│  ├─ icons/                内联 SVG icon 组件（D4 离线）
│  ├─ composables/          useTable / usePagination / useForm / usePermission
│  ├─ AppShell.vue          新壳（§4）
│  ├─ DataTable.vue Drawer.vue Modal.vue Form.vue Toast.vue StatusTag.vue Timeline.vue PermButton.vue
│  ├─ states/               EmptyState.vue LoadingSkeleton.vue ErrorState.vue ForbiddenState.vue
│  ├─ permission-v2.js      13 角色 + 86 码三态 + canonical（§4.2）
│  ├─ menu-config.js        新 IA 菜单常量（8 组，渐进渲染）
│  ├─ status-colors.js      状态机色映射
│  ├─ data/provider.js      数据源接口 + mock 实现（§6）
│  ├─ mock-data.js          admin-spec §9 基准集
│  └─ index.js              统一导出 + 引入 tokens.css
├─ views/                   旧 21 视图保留（EP）；M2 重写 LoginView/DashboardView/ForbiddenView/NotFoundView
├─ layout/AdminLayout.vue   旧壳保留（EP）
├─ stores/                  auth.js（复用）+ perspective.js（新）+ ui.js（新）
└─ utils/permission.js      旧 24 码保留（旧视图用）
```

### 1.2 路由共存（hash 模式不变）

- `/login` → 新 LoginView（public，M2 重写）。
- `/` → 新 AppShell，redirect **`/dashboard`**；children = 新 IA 视图（M2 仅 dashboard，M3–M5 逐波追加）。
- 旧业务路径（`/orders` `/orders/:id` `/ledger` `/handovers` `/filings` `/contracts` `/regions` `/archive/company` `/archive/use-units` `/archive/employees` `/archive/elevators` `/register/workers` `/reports/failed` `/reports/logs` `/schedule` `/templates` `/faults`）→ 旧 AdminLayout + EP 视图，**路径与组件零改动**。
- `/403` → 新 ForbiddenView；`/:pathMatch(.*)*` → 新 NotFoundView。
- 新壳菜单含过渡组「旧版（过渡）」列出未迁移旧视图入口，每迁一个删一个。
- **更正**：新工作台路由是 `#/dashboard`（program spec §6.1/§6.2，重写 DashboardView），非占位 `/workbench`。M3–M5 每迁一个视图，该路由从 AdminLayout 分支移入 AppShell 分支。

### 1.3 移除时机

- M2 **不删 EP**（旧视图依赖）；`exceljs` 无引用，M2 顺手移除（docs/12 D-05 一半）。
- EP 全量移除 + 旧 AdminLayout/permission.js/menu.js 删除 = M5 最后一视图迁完后。

### 1.4 权威不变

前端 permission-v2 仅体验优化；后端 PermissionMatrix v2 唯一权威；越权写后端判 403/1403 无副作用（AGENTS §3.1）。

---

## 2. 设计 token 与视觉

 adopting 蓝图既定深色工业风调色板（电梯维保主题），逐字落地 admin-spec §8，不另创 palette。

### 2.1 Token 表（`ui/tokens.css`）

| 组 | Token | 值 | 用途 |
|---|---|---|---|
| 底/面 | `--ink` / `--ink-2` | `#0A0F12` / `#0D1418` | 页面底 / 侧栏底 |
| | `--panel` / `--panel-2` | `#131C21` / `#172228` | 卡面 / 表头悬浮 |
| 线 | `--line` / `--line-2` | `#26343B` / `#2E3E46` | 1px 分隔 / 强描边 |
| 文字 | `--text` / `--muted` / `--dim` | `#E8EEF0` / `#A3B4BB` / `#7E929B` | 正文 / 次级 / 标签≥11px(5.4:1) |
| 主色 | `--amber` / `--amber-soft` | `#FFB020` / `#FFC85C` | 主操作·进行中 / 彩底次级黄字 |
| 危险 | `--signal` / `--signal-soft` | `#FF4B2B` / `#FF8F7A` | 急修·困人·超时 / 红底可读字 |
| 成功 | `--ok` / `--ok-soft` | `#2FC48D` / `#5FE0B2` | 合格·已签·闭环 / 深底绿字 |
| 单据 | `--paper` / `--paper-ink` / `--paper-line` | `#EDE5D3` / `#22201A` / `#C6BA9F` | 签字区底 / 墨色 / 分隔 |
| 新增语义 | `--accent-on-amber` / `--accent-on-signal` / `--scrim` / `--focus` | `#1A1203` / `#1A0A06` / `rgba(5,8,10,.72)` / `var(--amber)` | 彩底深字 / 遮罩 / 焦点环 |

### 2.2 对比度修正（硬约束，审查 P1）

- 单据小字 `#5B5341 on --paper`（≥5:1）。
- 角标 `--signal` 底 + `#1A0A06` 字（5.7:1）或黄底黑字；**禁白字 on `#FF4B2B`**。
- 所有 text/control 配对可读对比。

### 2.3 排版

正文 13px / 次级 12px / 标签 ≥11px（等宽大写 + letter-spacing）；表格行高 40px；数字 `--font-mono` + `tabular-nums`；标题 20/16px。字体系统栈（`Segoe UI`/`PingFang SC`/`Microsoft YaHei` + `Consolas`/`Menlo`），**零外部请求**（D4，不引 Google Fonts）。

### 2.4 图标（D4）

从原型 `icons.svg` 提取路径建 inline SVG icon 组件（`ui/icons/`，stroke 借 palette）；无网络字体/CDN；file:// 与 dev server 均可用。

### 2.5 StatusTag 状态机色映射（`ui/status-colors.js`）

进行中→amber；急修/困人/超时/危险→signal；合格/已签署/已闭环→ok；待办/中性→muted；单据/签字→paper。M4 状态机逐态对齐（M0 状态机对照表）。

### 2.6 消费方式

`tokens.css` 由 `ui/index.js` 引入（新壳作用域）；组件 scoped style 一律 `var(--token)`，无硬编码色；旧 EP 视图不受影响（仍浅色）。滚动条按 palette 自定义（track `--panel` / thumb `--line-2` / hover `--amber`），非浏览器默认。

---

## 3. 组件库清单与规格

9 交互组件 + 4 态 + 4 composables，styled SFC（scoped style 消费 token）+ headless 逻辑分离。规格对齐 program spec §7 / admin-spec §11 / §4.3。

### 3.1 DataTable（`useTable` + `usePagination`）
- 列排序三态（升→降→默认，表头箭头）；关键字搜索；多筛选器（状态/范围 chips）；分页 8/16/32。
- 行多选 + 全选 + 批量操作条（选中数 + 批量动作经 PermButton 门控）；空态接 EmptyState；加载接 LoadingSkeleton。
- props：`columns`/`rows`/`loading`/`selectable`/`filters`；events：`sort`/`search`/`filter`/`page`/`select`/`bulk`。

### 3.2 Drawer
右侧 560px 滑入；顶部元信息条（角色/路由/权限码/数据范围）；底部操作按权限渲染（PermButton）；`--scrim` 遮罩 + Esc/点击遮罩关闭；焦点陷阱。

### 3.3 Modal
居中；危险变体 `--signal` 描边；二次确认需勾选 checkbox 才可提交（未勾「确认」disabled）；用于电梯报废等不可逆动作（场景 9）。

### 3.4 Form（`useForm`）
失焦 + 提交双校验；错误文案字段下方（`--signal`）；必填星号；成功 → Toast + 数据变化 + 审计。校验规则 headless 可单测（场景 8）。

### 3.5 Toast
成功/警告/错误三型（`--ok`/`--amber`/`--signal`），3 秒自动消失，堆叠队列；全局 `useToast()` 注入。

### 3.6 StatusTag
状态机色映射（§2.5）；pill 形，深底彩字按对比度修正。

### 3.7 Timeline
操作人 + 角色 + 时间三列；状态转移追加（场景 10/11）；`--font-mono` 时间。

### 3.8 PermButton（`usePermission`，两档门控 program spec §4.3）
- `Y` → 正常可点。
- `DENY`（永久缺权）→ 原生 `disabled` + `title`=所需权限码。
- `C`（临时前置未满足/需审批）→ `aria-disabled` + 可聚焦 + 捕获阶段拦截（点击触发审批流/提示，非简单置灰）。
- 后端校验唯一安全边界；props：`code`/`state`（可由 permission-v2 自动解析）/`tooltip`。

### 3.9 AppShell
见 §4.1。

### 3.10 四态（`states/`）
- EmptyState：筛选 chips + 「清空筛选」；inline SVG 插画（palette）。
- LoadingSkeleton：骨架 220ms 后显示；分页/排序本地瞬时操作**不**显示。
- ErrorState：可读原因 + 重试 + 退路；**不清空已填数据**（场景 15）。
- ForbiddenState：所需权限码 + 允许角色列表（场景 4）。

### 3.11 composables
`useTable`（排序/筛选/选择）、`usePagination`（页码/页大小）、`useForm`（校验/提交）、`usePermission`（state 解析 + 两档门控）——纯逻辑，vitest 单测主力。

---

## 4. 应用壳与权限/菜单/多角色

### 4.1 AppShell（`ui/AppShell.vue`）
- 侧栏 **8 组**（program spec §6.1）：概览 / 组织与权限 / 台账中心 / 安装移交 / 维保作业 / 急修报修 / 数据与系统 / 监管与档案（D-A4 新增）；232↔64px 折叠存 localStorage；组标题等宽大写≥11px。
- 顶栏：当前数据范围指示 + 用户名 + 退出；面包屑 组/视图/对象；<1024 桌面提示条。
- 每页页头 meta：本页权限码 + 当前数据范围；禁用按钮 tooltip 注明所需码（§6.3）。

### 4.2 permission-v2.js（镜像 M1）
- 13 规范角色 + 86 码三态表（Y/C/DENY，逐字镜像后端 `PermissionMatrix.STATES`）+ `canonical(旧码)` 别名（镜像 RoleCatalog v2）。
- API：`state(role, code)`（未知角色→DENY、未知码→抛错 fail-closed）、`allowsNew(role, code)`（==Y）、`canonicalCodes()`、`defaultScope(role)`（镜像 sys_role_scope）。
- **漂移守卫测试**：断言前端 86 码集/13 角色与后端常量逐字一致（拼错即静默放行=越权，CI 守）。
- 旧 permission.js（24 码）原样留旧视图；M5 删。

### 4.3 menu-config.js（前端常量）
- 8 组结构按 §6.1；每项 `{ path, title, perm, implemented }`。渐进渲染：M2 仅「概览/工作台」implemented=true；其余新视图项隐藏；过渡组「旧版（过渡）」列 17 旧视图入口（每迁一个删一个）。
- 过滤：`state(activeRole, item.perm) !== DENY` 才显示；C 态项带只读徽标。后端 MenuService 新 IA 下发推迟 M3，前端常量兜底。

### 4.4 多角色机制（`stores/perspective.js`）
- `activeRole`（当前视角）+ `roleSet`（用户角色集）。切换 activeRole 驱动：壳范围指示、菜单过滤、PermButton 解析、数据范围标签。
- M2 roleSet 用 admin-spec §9 基准集 5 视角（PLATFORM_ADMIN/MAINT_MGR/MAINT_SUP/INST_MGR/CLIENT_ADMIN，过场景 3）；生产真实 roleSet 接线随 M3（读后端用户角色，M1 sys_user_role 已落地）。
- D-A3：生产无「角色切换器」开发工具；多角色用户经工作台切换视角。原型「设计规范/数据自检」页转开发工具，不进生产导航。
- **单身份登录互斥不变量不动**（AGENTS §3.9）：多角色在员工态内切换，门户态独立互斥。

### 4.5 数据范围指示
6 范围（SELF/TEAM/PROJECT/ORG/ORG_FLAT/ALL）按 activeRole 默认范围（permission-v2.defaultScope）显示标签；真实数据范围收敛后端 ScopeResolver（M1），前端仅展示。

---

## 5. M2 四视图与基准集

### 5.1 LoginView（`#/login` 重写，public）
账号 + 密码（沿用现有后端认证 `authApi.login`，client=admin，D5）；**真实鉴权**（M1 后端已活），非 mock。新深色工业风；Form 双校验（密码 ≥6 位，场景 2）；失焦/提交错误文案字段下方；登录中 LoadingSkeleton；失败 ErrorState（可读原因，不清空已填）；成功 → `/dashboard`，存 token/user/role（现有 auth store）。零外部请求；1440×900 对照原型登录屏。

### 5.2 DashboardView / 工作台（`#/dashboard` 重写，AppShell）
KPI **7 项**（§6.1：在管项目/电梯、待审核移交、待复核、SLA 超时、合同临期≤90 天、双签待补）+ 预警条 + 待办；按 activeRole 范围聚合，**分项之和=总数**（不变量 6/8）。M2 数据源 = admin-spec §9 基准集；KPI 卡 palette + `tabular-nums`；多角色视角切换（M2 基准集 5 视角，过场景 3）；真实接口接线随 M3。入口卡链接各视图：M2 仅已落地视图可点，其余置灰 + tooltip「M3/M4/M5 落地」或过渡组旧版入口。

### 5.3 ForbiddenView（`#/403` 重写）
ForbiddenState——所需权限码 + 允许角色列表 + 当前角色（场景 4：INST_MGR 直达 #/contracts 等 → 403）；返回工作台链接。

### 5.4 NotFoundView（`#/404` 重写）
inline SVG 插画（palette）+ 可读文案 + 返回工作台；非默认浏览器样式。

### 5.5 开发态组件廊 + 数据自检（D-A3，`import.meta.env.DEV` 门控，不进生产导航）
- `#/dev/components`：9 组件 + 四态交互演示（DataTable 排序/筛选/分页/批量、Drawer、危险 Modal 勾选确认、Form 校验、Toast、StatusTag、Timeline、PermButton 三态）——走查与开发参考基准。
- `#/dev/spec`：基准集 9 条不变量自检，逐条显示通过/失败（场景 16）。

### 5.6 mock-data.js（`ui/mock-data.js`，admin-spec §9 唯一数据源，与原型同源）
时间基准 2026-11-13（周五）；5 项目 / 20 台电梯（城东 6+汇金 4+城市之光 4+云栖里 3+华府 3）/ 12 移交（10 态）/ 14 记录（7 态）/ 16 工单（7 态）/ 6 合同 / 18 账号（4 态）/ 24 审计 / 8 邀请 / 5 授权；人物/单号沿用原型不得改名。M2 过渡数据源（工作台 + 组件廊 + 走查）；M3 起逐视图换真实接口，mock-data 仅留 dev 廊/自检。

---

## 6. 数据流与错误处理

### 6.1 状态分层（Pinia）
- `stores/auth.js`（复用）：token/user/role；login 真实鉴权；`onUnauthorized` 401 清会话跳登录。
- `stores/perspective.js`（新）：activeRole + roleSet（M2 基准集，M3 接后端）。
- `stores/ui.js`（新）：侧栏折叠（localStorage）、Toast 队列、全局 loading。
- 组件局部状态用 composables，不上全局 store。

### 6.2 数据源抽象（mock→real 平滑过渡）
`ui/data/provider.js` 接口：`getKPIs(scope)`/`getTodos(scope)`/`getTableRows(domain, query)`。M2 = mock 实现（基准集按 scope 聚合）；M3 起逐域换真实 API 实现，**组件/composable 不变**（依赖注入）。真实 HTTP 复用现有 `api/client.js`（axios + Bearer + 401 拦截）；工作台 M2 不发请求（mock），login 发真实请求。

### 6.3 权限数据流
`permission-v2.state(activeRole, code)` → 菜单过滤 / PermButton 三态 / 路由守卫。前端仅体验优化，后端 PermissionMatrix v2 唯一权威；越权写后端判 403/1403 无副作用。

### 6.4 四态数据流
加载：请求触发 → 220ms 后显骨架（避免闪烁）；分页/排序本地瞬时不显骨架。空：EmptyState + 筛选 chips + 清空。失败：ErrorState 可读原因 + 重试 + 退路，保留已填表单数据。403：ForbiddenState 所需码 + 允许角色 + 当前角色。

### 6.5 错误处理
- **401**（过期/sv 不符/被顶下线，M1 sv 校验）：api/client `onUnauthorized` → clearSession → `/login`。
- **403/1403**（越权）：路由守卫直达无权页 → `/403`；API 返回 → ErrorState/Toast（后端已写 BLOCKED 审计，M1）。
- 网络/离线：ErrorState + 重试；navigator.onLine 提示。
- 表单校验：失焦 + 提交双校验，字段下方错误文案。
- 成功：Toast（3s）+ 数据变化 + 审计（审计由后端写，前端不直接写）。

### 6.6 安全不变量保留
无上报自动重试（§2.4，M2 不触上报）；单身份登录互斥（§3.9，视角切换在员工态内）；派单只定时任务（§3.6，M2 不触派单）；token 原样存储（sv 由后端校验）。

### 6.7 审计/脱敏
M2 工作台/组件廊用基准集演示审计 Timeline 与脱敏对照（合同金额掩码）；真实脱敏由后端响应裁剪（M3/M4，program spec §5.2），前端按权限渲染。

---

## 7. 测试与门禁

### 7.1 测试分层（vitest + happy-dom，沿用现有 39 用例模式）
1. composables 纯逻辑单测（无 DOM）：useTable（排序三态/筛选/多选）、usePagination（页码/页大小边界）、useForm（校验规则/失焦+提交）、usePermission（state 解析/两档门控）。
2. 组件交互测试：DataTable（排序/搜索/筛选/分页/批量/空态/骨架）、Drawer（开关/焦点陷阱/Esc）、Modal（危险变体/勾选才可提交）、Form（双校验/错误文案）、Toast（三型/3s）、StatusTag（色映射）、Timeline、PermButton（Y/DENY/C 三态）。
3. permission-v2 漂移守卫：86 码集 + 13 角色与后端常量逐字一致；state() fail-closed；canonical 别名。**安全门禁**。
4. 视图级测试（关 D-02）：LoginView（校验/提交/失败保留输入）、工作台（KPI 分项之和=总数/视角切换驱动菜单）、ForbiddenView（所需码+允许角色）、NotFoundView。
5. 基准集自检测试：admin-spec §9 九条不变量逐条断言。

### 7.2 门禁（program spec M2 gate）
- `admin/`：`npm run lint && npm test && npm run build` 全绿。lint 新 ui/ 干净；test 现有 39 + 新增全绿；build vite 成功（EP 未删，新 ui/ tree-shake；exceljs 移除）。Windows esbuild `Access is denied` → `TMP/TEMP` 指向 `admin/.tmp-build`（AGENTS §4.4）。
- **1440×900 对照原型人工走查**（兼容 1280），留截图 + 场景勾选。M2 相关场景（admin-spec §11）：①登录零控制台报错/零外部请求 ②登录→工作台 KPI ③切 5 视角导航/范围/行数变化 ④INST_MGR 直达无权页 403 ⑦表格排序/搜索/筛选/分页/全选批量 ⑧表单校验拦截 ⑨危险 Modal 勾选确认 ⑮失败态+重试 ⑯数据自检 9 条通过 ⑰<1024 提示条。

### 7.3 零回归
旧 17 EP 视图 + 旧 39 用例 + 旧 permission.js/menu.js/AdminLayout 不动且仍绿；M2 只增不改旧代码（exceljs 移除除外，无引用）。

### 7.4 测试位置
`ui/*.test.js` 与组件同目录（colocated）；CI 现状仅后端（D-04），admin 门禁本地跑（AGENTS §4.4）。

---

## 8. 文档同步与范围边界

### 8.1 文档同步（program spec §12，M2 时机）
- **AGENTS.md §4.3**：技术栈修订为「Vue3 + Vite + 自建组件库（`admin/src/ui/`）+ Element Plus（过渡期旧视图，M5 移除）」；§10 追加修订记录。自建库非「新 UI 框架」（D-A5 已决策，不触 §4.1 TDesign 评估条款）。
- **docs/09-管理端设计.md**：回写 M2 部分（壳/token/9 组件/四态/4 视图），升版本；M3–M5 续写。
- **docs/12-未完成事项清单.md**：D-02 部分关闭（M2 补 login/工作台/403 视图测试，全量 M5）；D-05 exceljs 移除（EP 包体 M5）；G 组 M2 状态回写。
- **本 spec**：`docs/superpowers/specs/2026-11-14-admin-redesign-m2-components-design.md`。
- docs/16 设计输入只读参考（M0 已归档）。

### 8.2 M2 范围（IN）
token 层 · 9 组件 + 四态 + 4 composables · AppShell · permission-v2 + 漂移守卫 · menu-config（渐进）· mock-data 基准集 · 4 视图（login/dashboard/403/404）· 开发态组件廊 + 自检 · 组件/视图测试 · exceljs 移除 · 文档同步。

### 8.3 M2 不做（OUT，推迟）
- 17 业务视图迁移：M3 台账域 7 视图 / M4 作业域 5 + 合规 6 / M5 数据域 3。
- 后端 MenuService 新 IA 下发（M3）；逐视图真实 API 接线（M3–M5）；真实多角色 roleSet 接线（M3）。
- EP 全量移除 + 旧 AdminLayout/permission.js 删除（M5）；小程序角色对齐（M5）；生产清库（M5 上线）。
- 状态机逐态对齐（M4）；真实脱敏后端裁剪（M3/M4）；C 态审批工作流（M5）。

### 8.4 M2 零触碰
后端代码、小程序、`mock/server.js`、`services/`、业务查询、上报逻辑（§2.4）、派单逻辑（§3.6）。旧 17 EP 视图 + 旧 39 用例零改动仍绿。

### 8.5 风险（program spec 风险 7）
自建库工作量集中 → M2 独立里程碑、原型为验收基准、组件测试先行。

---

## 9. 已核验上下文（实现前事实核对）

| 项 | 核验来源 | 结论 |
|---|---|---|
| 现有 admin 依赖 | `admin/package.json` | element-plus 2.9.3 + @element-plus/icons-vue + echarts 5.6 + exceljs 4.4（无引用）+ axios/pinia/vue 3.5/vue-router 4.5；dev vite 6/vitest 3/happy-dom/eslint |
| EP 全量引入 | `admin/src/main.js` | `app.use(ElementPlus, {locale:zhCn})` + `element-plus/dist/index.css`（942kB，D-05） |
| 旧壳 | `admin/src/layout/AdminLayout.vue` | el-container/el-aside/el-menu，浅色（#001529 侧栏/#fff 顶栏），MENUS 常量（旧 IA 24 码）+ remoteMenus（GET /auth/menus 兜底） |
| 旧路由 | `admin/src/router/index.js` | hash 模式，AdminLayout 包 18 业务路由 + login/403/404，meta.perm 24 旧码 |
| 旧权限模型 | `admin/src/utils/permission.js` | CAP 24 码 + ROLE 12 旧码 + CAPABILITY_ROLES 反投影 + expand(write⇒read) + can/canAny/isKnownRole，fail-closed |
| 旧 auth store | `admin/src/stores/auth.js` | token/user/role localStorage；can(cap)=canPerm(role,cap)；login client=admin；onUnauthorized 401 清会话 |
| M1 后端权限 | M1 spec + 已合并代码 | RoleCatalog v2 13 角色 + canonical；PermissionMatrix v2 86 码三态 + 24 旧码冻结；state/allowsNew/canonicalCapabilities；后端 362 单测 |
| token/组件/场景 | docs/16/admin-spec.md §8/§9/§11 | token 表逐字、mock 基准集、17 验收场景 |
| 目标 IA | program spec §6.1/§6.2 | 8 组 23 视图；工作台 #/dashboard；旧→新视图映射 |
| 原型 | Desktop/UI/admin/（index.html + assets） | vanilla JS + mock-data.js，file:// 零依赖，视觉/交互验收基准 |

---

## 10. 自审（spec self-review）

1. **占位扫描**：无 TBD/TODO；每节含具体规格/值/接口。token 值、组件 props/events、mock 基准集数量、验收场景均逐字来自 admin-spec/program spec。
2. **内部一致性**：§1 路由 /dashboard 与 §5.2 工作台一致（已更正 /workbench 占位）；§4.2 permission-v2 86 码/13 角色与 M1 后端一致；§3.8 PermButton 三态与 §4.2 state() 一致；§8 IN/OUT 与 §1.3 移除时机一致（EP M5 移除）。
3. **范围检查**：M2 纯前端、单一里程碑可实施；17 业务视图迁移明确推迟 M3–M5；后端零触碰。范围聚焦，无需再分解。
4. **歧义检查**：工作台数据源（M2 mock 基准集 vs M3 真实）已明确；多角色 roleSet（M2 基准集 vs M3 后端）已明确；菜单源（前端常量 vs M3 后端下发）已明确；EP 移除时机（M5）已明确。

---

## 11. 修订记录

| 版本 | 日期 | 修订内容 |
|---|---|---|
| V1.0 | 2026-11-14 | 首版：五项已确认决策（双布局共存/前端镜像权限/原型基准集+真实多角色/样式 SFC+composables/组件单测+视图测试+人工走查）；§1 架构共存（ui/ 目录 + 路由共存 + EP M5 移除）；§2 token 与视觉（admin-spec §8 逐字 + 对比度修正 + 排版 + 图标 + 状态色）；§3 组件库（9 组件 + 四态 + 4 composables）；§4 应用壳/permission-v2/菜单渐进/多角色；§5 四视图 + 开发态组件廊/自检 + mock 基准集；§6 数据流/错误处理/安全不变量；§7 五层测试 + 门禁 + 零回归；§8 文档同步 + IN/OUT 边界；§9 已核验上下文；§10 自审 |