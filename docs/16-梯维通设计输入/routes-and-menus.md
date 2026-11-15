# 梯维通 · 小程序路由与菜单配置 V1.3

> 一套小程序、一个登录入口、9 个账号角色、3 类工作台。  
> 本文定义：页面路由、底部导航、菜单配置、路由守卫、深链处理、目录结构。

---

## 0. 结论先行

1. **不使用微信原生 tabBar**。原生 tabBar 最多 5 项，且 `wx.switchTab` 只能跳 tabBar 页面；维保 / 安装 / 使用单位三套导航无法共存。
2. 采用 **自定义底部导航组件 + `wx.reLaunch`**。所有页面都是普通页面，按角色渲染 3–5 个 Tab，切换用 `reLaunch` 清栈。
3. 路由分 **主包 + 4 个子包**：登录、三个首页、工单、电梯、我的在主包；移交、维保、使用单位、管理分别进子包。
4. 守卫分四层：**登录态 → 角色 → 权限点 → 单据状态**；前端只负责跳转与隐藏，后端每个接口再校验一次。
5. 多角色：权限取并集、Tab 合并去重、默认进入最高优先级工作台；「我的」里切换工作台。

---

## 1. 路由设计原则

- **页面只传 id / token，不传大对象**；详情页根据 id 重新拉取，避免脏数据与越权。
- **Tab 页用 `reLaunch`**，详情页用 `navigateTo`，登录 / 403 用 `reLaunch`。
- **主包首屏只放登录与三个首页**，控制主包体积；业务页面全部进子包。
- **所有跳转路径来自白名单**，不接受任意 URL；扫码与订阅消息同样走白名单。
- **权限码与路由绑定**，新页面上线必须同时登记 `RouteMeta` 与角色 / 权限点。

---

## 2. 目录结构

```text
miniprogram/
├─ app.ts
├─ app.json
├─ app.wxss
├─ pages/                       # 主包
│  ├─ login/index
│  ├─ install/home/index        # 安装部工作台
│  ├─ maint/home/index          # 维保工作台
│  ├─ client/home/index         # 使用单位首页
│  ├─ order/list/index
│  ├─ order/detail/index
│  ├─ order/report/index
│  ├─ elevator/list/index
│  ├─ elevator/detail/index
│  ├─ profile/index
│  └─ 403/index
├─ pkg-handover/pages/          # 安装移交
│  ├─ list/index
│  ├─ detail/index
│  ├─ create/index
│  ├─ upload/index
│  ├─ review/index
│  ├─ inspect/index
│  ├─ sign/index
│  └─ rectify/index
├─ pkg-maintenance/pages/       # 维保作业
│  ├─ plan/index
│  ├─ task/index
│  ├─ record-detail/index
│  ├─ record-sign/index
│  └─ record-archive/index
├─ pkg-client/pages/            # 使用单位
│  ├─ todo/index
│  ├─ sign/index
│  ├─ orders/index
│  └─ evaluate/index
├─ pkg-admin/pages/             # 管理与统计
│  ├─ projects/index
│  ├─ contracts/index
│  ├─ users/index
│  ├─ roles/index
│  ├─ stats/index
│  ├─ audit/index
│  └─ settings/index
├─ components/
│  ├─ custom-nav/               # 自定义底部导航
│  └─ empty/
├─ config/
│  ├─ routes.ts
│  ├─ menus.ts
│  └─ icons.ts
└─ utils/
   ├─ auth.ts
   ├─ guard.ts
   ├─ store.ts
   └─ request.ts
```

---

## 3. 角色首页与底部导航

| 角色 | 默认首页 | Tab 1 | Tab 2 | Tab 3 | Tab 4 | Tab 5 |
|---|---|---|---|---|---|---|
| 安装部资料员 | `/pages/install/home/index` | 工作台 | 移交单 | 资料 | 我的 | — |
| 安装部项目负责人 | `/pages/install/home/index` | 工作台 | 移交单 | 项目 | 我的 | — |
| 安装部主管 | `/pages/install/home/index` | 工作台 | 移交单 | 审核 | 统计 | 我的 |
| 维保人员 | `/pages/maint/home/index` | 工作台 | 工单 | 电梯 | 我的 | — |
| 维保主管 | `/pages/maint/home/index` | 工作台 | 工单 | 项目 | 统计 | 我的 |
| 维保部经理 | `/pages/maint/home/index` | 工作台 | 项目 | 合同 | 统计 | 我的 |
| 使用单位管理员 | `/pages/client/home/index` | 首页 | 待办 | 电梯 | 我的 | — |
| 使用单位签字人 | `/pages/client/home/index` | 首页 | 待办 | 电梯 | 我的 | — |
| 平台管理员 | `/pkg-admin/pages/users/index` | 组织 | 审计 | 配置 | 我的 | — |

> 使用单位管理员兼任签字人时，不新增 Tab；“待办”内合并待签记录单 / 急修单，签字时校验 `CLIENT_SIGNER`。　**TabBar 唯一事实源（V1.3 收口 P1-5，本行为说明性补充，不新增定义）**：本节 Tab 1–Tab 5 只是 §5 `ROLE_TABS`（`routes-and-menus.md:257-315`）中 `text` 字段的投影，两者不一致时一律以 `ROLE_TABS` 为准；`roles-permissions.md` §3 已按本规则逐行回写（新 §3 表体 = `roles-permissions.md:69-77`，四处历史冲突见其 §10 决策 8）。曾出现在旧权限矩阵里的维保部经理「质量」Tab 在本节、§4 路由清单与 `ROLE_TABS` 中都不存在，**已确认删除**（实测：`ROLE_TABS` 区 L257-315 与 §4 路由清单 L109-172 内「质量」各 0 次；本文件其余命中全部是删除说明与验收表述，逐处见 §12 N2）：它既无路由也无权限码，意图由「统计」Tab（`routes-and-menus.md:294`，即 `ROLE_TABS.MAINT_MGR` 第 4 项）+ 后台管理端记录单复核（`admin-spec.md:232`）承接，不得再加回来。

---

## 4. 路由清单

图例：`INST_*` = 安装部三角色，`MAINT_*` = 维保三角色，`CLIENT_*` = 使用单位两角色。

### 4.1 主包

| 路由 | 页面 | 允许角色 | 权限点 | 关键参数 |
|---|---|---|---|---|
| `/pages/login/index` | 登录 | 未登录 / 已登录 | — | `redirect?` |
| `/pages/install/home/index` | 安装部工作台 | `INST_*` | — | — |
| `/pages/maint/home/index` | 维保工作台 | `MAINT_*` | — | — |
| `/pages/client/home/index` | 使用单位首页 | `CLIENT_*` | — | — |
| `/pages/order/list/index` | 工单列表 | `MAINT_*` | `order:view_pool` | `state?` |
| `/pages/order/detail/index` | 工单详情 | `MAINT_*` / `CLIENT_*` | `order:view_pool` / `order:view_own` | `id` |
| `/pages/order/report/index` | 一键报修 | `CLIENT_*` | `order:create` | `elevatorId?` |
| `/pages/elevator/list/index` | 电梯列表 | 全部已登录 | `elevator:view` | `scope?` |
| `/pages/elevator/detail/index` | 电梯档案 | 全部已登录 | `elevator:view` | `id` |
| `/pages/profile/index` | 我的 / 切换工作台 | 全部已登录 | — | — |
| `/pages/403/index` | 无权限 | 全部已登录 | — | `from?` |

### 4.2 安装移交子包

| 路由 | 页面 | 允许角色 | 权限点 | 关键参数 |
|---|---|---|---|---|
| `/pkg-handover/pages/list/index` | 移交单列表 | `INST_*` · `MAINT_SUP` · `MAINT_MGR` | `handover:view` | `state?` |
| `/pkg-handover/pages/detail/index` | 移交单详情 | 相关角色 + `CLIENT_ADMIN`（生效后） | `handover:view` | `id` |
| `/pkg-handover/pages/create/index` | 发起移交 | `INST_PM` | `handover:create` | `projectId?` |
| `/pkg-handover/pages/upload/index` | 资料上传 | `INST_CLERK` · `INST_PM` | `handover:doc_upload` | `handoverId` |
| `/pkg-handover/pages/review/index` | 内部审核 | `INST_MGR` | `handover:review` | `id` |
| `/pkg-handover/pages/inspect/index` | 现场验收 | `MAINT_TECH`（被指派） | `handover:inspect` | `id` |
| `/pkg-handover/pages/sign/index` | 双方签字 | `INST_MGR` · `MAINT_SUP` · `MAINT_MGR` | `handover:sign_install` / `handover:sign_maint` | `id` |
| `/pkg-handover/pages/rectify/index` | 整改回复 | `INST_PM` · `INST_CLERK` | `handover:rectify` | `id` |

### 4.3 维保作业子包

| 路由 | 页面 | 允许角色 | 权限点 | 关键参数 |
|---|---|---|---|---|
| `/pkg-maintenance/pages/plan/index` | 维保计划 | `MAINT_SUP` · `MAINT_MGR` | `plan:view` | `month?` |
| `/pkg-maintenance/pages/task/index` | 作业执行 | `MAINT_TECH` | `task:accept` | `id` |
| `/pkg-maintenance/pages/record-detail/index` | 记录单详情 | `MAINT_*` · `CLIENT_*` | `record:view` | `id` |
| `/pkg-maintenance/pages/record-sign/index` | 维保双人签字 | `MAINT_TECH`（主检 / 配合） | `record:sign_tech` | `id` `as=primary\|assist` |
| `/pkg-maintenance/pages/record-archive/index` | 归档 / PDF | `MAINT_SUP` · `MAINT_MGR` · `CLIENT_ADMIN` | `record:export` | `id` |

### 4.4 使用单位子包

| 路由 | 页面 | 允许角色 | 权限点 | 关键参数 |
|---|---|---|---|---|
| `/pkg-client/pages/todo/index` | 待办签字 | `CLIENT_*` | `record:sign` / `order:confirm` | — |
| `/pkg-client/pages/sign/index` | 签署确认 | `CLIENT_SIGNER` | `client:sign` | `taskId` `token` |
| `/pkg-client/pages/orders/index` | 本单位工单 | `CLIENT_*` | `order:view_own` | `state?` |
| `/pkg-client/pages/evaluate/index` | 服务评价 | `CLIENT_*` | `client:evaluate` | `orderId` |

### 4.5 管理子包

| 路由 | 页面 | 允许角色 | 权限点 | 关键参数 |
|---|---|---|---|---|
| `/pkg-admin/pages/projects/index` | 项目 | `MAINT_SUP` · `MAINT_MGR` · `INST_PM` · `INST_MGR` | `project:view` | — |
| `/pkg-admin/pages/contracts/index` | 合同 | `MAINT_SUP`△ · `MAINT_MGR` · `CLIENT_ADMIN`△ | `contract:view` | 金额字段按 §5.3 脱敏（`roles-permissions.md:230`） |
| `/pkg-admin/pages/users/index` | 人员 | `INST_MGR` · `MAINT_SUP` · `MAINT_MGR` · `CLIENT_ADMIN` · `PLATFORM_ADMIN` | `user:view` | — |
| `/pkg-admin/pages/roles/index` | 角色 | `PLATFORM_ADMIN` | `role:assign` | — |
| `/pkg-admin/pages/stats/index` | 统计 | `INST_MGR` · `MAINT_SUP` · `MAINT_MGR` · `CLIENT_ADMIN` · `PLATFORM_ADMIN` | `stats:view` | — |
| `/pkg-admin/pages/audit/index` | 审计 | `PLATFORM_ADMIN` | `audit:view` | — |
| `/pkg-admin/pages/settings/index` | 系统配置 | `PLATFORM_ADMIN` | `config:manage` | — |

---

## 5. 菜单配置来源

### 5.1 后端返回（推荐）

登录后调用 `GET /api/v1/me/menus`，由后端按角色与数据范围下发；前端不硬编码角色判断。

```json
{
  "user": {
    "id": "u_10086",
    "name": "李振国",
    "orgId": "org_maint_cd",
    "roles": ["MAINT_TECH"],
    "activeWorkspace": "MAINT_TECH"
  },
  "home": "/pages/maint/home/index",
  "tabs": [
    { "key": "maint-home", "text": "工作台", "icon": "gauge", "path": "/pages/maint/home/index" },
    { "key": "maint-orders", "text": "工单", "icon": "clipboard", "path": "/pages/order/list/index", "badgeKey": "pendingOrders" },
    { "key": "maint-elevators", "text": "电梯", "icon": "elevator", "path": "/pages/elevator/list/index" },
    { "key": "profile", "text": "我的", "icon": "user", "path": "/pages/profile/index" }
  ],
  "quickActions": [
    { "key": "scan", "text": "扫码开工", "icon": "qrcode", "path": "/pages/elevator/detail/index", "mode": "scan" },
    { "key": "emergency", "text": "急修单", "icon": "bolt", "path": "/pages/order/list/index?state=urgent" },
    { "key": "report", "text": "报修接单", "icon": "wrench", "path": "/pages/order/list/index?state=pending" },
    { "key": "handover", "text": "移交验收", "icon": "file-signature", "path": "/pkg-handover/pages/list/index" }
  ],
  "actions": {
    "order:grab": true,
    "record:sign_tech": true,
    "handover:create": false
  },
  "badges": { "pendingOrders": 3, "pendingSigns": 2 }
}
```

规则：

- **后端是唯一事实来源**；`config/menus.ts` 只作为兜底与首屏占位。
- `path` 必须在白名单内；`icon` 只返回本地图标 key，不返回远程 URL。
- `actions` 是按钮级权限映射，前端用它控制 `wx:if`；后端仍需校验。
- Tab 最多 5 项；超出的功能进入「我的 / 更多」。
- 切换工作台时重新请求 `/me/menus?workspace=...`，避免多角色权限串台。

### 5.2 前端兜底配置

`config/menus.ts`：

```ts
export type RoleCode =
  | 'INST_CLERK' | 'INST_PM' | 'INST_MGR'
  | 'MAINT_TECH' | 'MAINT_SUP' | 'MAINT_MGR'
  | 'CLIENT_ADMIN' | 'CLIENT_SIGNER'
  | 'PLATFORM_ADMIN';

export interface TabItem {
  key: string;
  text: string;
  icon: string;
  path: string;
  badgeKey?: string;
}

export const ROLE_PRIORITY: RoleCode[] = [
  'MAINT_MGR', 'MAINT_SUP', 'MAINT_TECH',
  'INST_MGR', 'INST_PM', 'INST_CLERK',
  'CLIENT_ADMIN', 'CLIENT_SIGNER', 'PLATFORM_ADMIN', 'SYS_ADMIN'
];

export const ROLE_HOME: Record<RoleCode, string> = {
  INST_CLERK: '/pages/install/home/index',
  INST_PM: '/pages/install/home/index',
  INST_MGR: '/pages/install/home/index',
  MAINT_TECH: '/pages/maint/home/index',
  MAINT_SUP: '/pages/maint/home/index',
  MAINT_MGR: '/pages/maint/home/index',
  CLIENT_ADMIN: '/pages/client/home/index',
  CLIENT_SIGNER: '/pages/client/home/index',
  PLATFORM_ADMIN: '/pkg-admin/pages/users/index',
  SYS_ADMIN: '/pkg-admin/pages/users/index'
};

export const ROLE_TABS: Record<RoleCode, TabItem[]> = {
  INST_CLERK: [
    { key: 'install-home', text: '工作台', icon: 'gauge', path: '/pages/install/home/index' },
    { key: 'handover-list', text: '移交单', icon: 'file-signature', path: '/pkg-handover/pages/list/index' },
    { key: 'handover-docs', text: '资料', icon: 'folder', path: '/pkg-handover/pages/list/index?tab=docs' },
    { key: 'profile', text: '我的', icon: 'user', path: '/pages/profile/index' }
  ],
  INST_PM: [
    { key: 'install-home', text: '工作台', icon: 'gauge', path: '/pages/install/home/index' },
    { key: 'handover-list', text: '移交单', icon: 'file-signature', path: '/pkg-handover/pages/list/index' },
    { key: 'install-projects', text: '项目', icon: 'building', path: '/pkg-admin/pages/projects/index' },
    { key: 'profile', text: '我的', icon: 'user', path: '/pages/profile/index' }
  ],
  INST_MGR: [
    { key: 'install-home', text: '工作台', icon: 'gauge', path: '/pages/install/home/index' },
    { key: 'handover-list', text: '移交单', icon: 'file-signature', path: '/pkg-handover/pages/list/index' },
    { key: 'handover-review', text: '审核', icon: 'file-check', path: '/pkg-handover/pages/list/index?tab=review' },
    { key: 'stats', text: '统计', icon: 'chart', path: '/pkg-admin/pages/stats/index' },
    { key: 'profile', text: '我的', icon: 'user', path: '/pages/profile/index' }
  ],
  MAINT_TECH: [
    { key: 'maint-home', text: '工作台', icon: 'gauge', path: '/pages/maint/home/index' },
    { key: 'maint-orders', text: '工单', icon: 'clipboard', path: '/pages/order/list/index', badgeKey: 'pendingOrders' },
    { key: 'maint-elevators', text: '电梯', icon: 'elevator', path: '/pages/elevator/list/index' },
    { key: 'profile', text: '我的', icon: 'user', path: '/pages/profile/index' }
  ],
  MAINT_SUP: [
    { key: 'maint-home', text: '工作台', icon: 'gauge', path: '/pages/maint/home/index' },
    { key: 'maint-orders', text: '工单', icon: 'clipboard', path: '/pages/order/list/index', badgeKey: 'pendingOrders' },
    { key: 'maint-projects', text: '项目', icon: 'building', path: '/pkg-admin/pages/projects/index' },
    { key: 'stats', text: '统计', icon: 'chart', path: '/pkg-admin/pages/stats/index' },
    { key: 'profile', text: '我的', icon: 'user', path: '/pages/profile/index' }
  ],
  MAINT_MGR: [
    { key: 'maint-home', text: '工作台', icon: 'gauge', path: '/pages/maint/home/index' },
    { key: 'maint-projects', text: '项目', icon: 'building', path: '/pkg-admin/pages/projects/index' },
    { key: 'maint-contracts', text: '合同', icon: 'file-text', path: '/pkg-admin/pages/contracts/index' },
    { key: 'stats', text: '统计', icon: 'chart', path: '/pkg-admin/pages/stats/index' },
    { key: 'profile', text: '我的', icon: 'user', path: '/pages/profile/index' }
  ],
  CLIENT_ADMIN: [
    { key: 'client-home', text: '首页', icon: 'home', path: '/pages/client/home/index' },
    { key: 'client-todo', text: '待办', icon: 'file-signature', path: '/pkg-client/pages/todo/index', badgeKey: 'pendingSigns' },
    { key: 'client-elevators', text: '电梯', icon: 'elevator', path: '/pages/elevator/list/index' },
    { key: 'profile', text: '我的', icon: 'user', path: '/pages/profile/index' }
  ],
  CLIENT_SIGNER: [
    { key: 'client-home', text: '首页', icon: 'home', path: '/pages/client/home/index' },
    { key: 'client-todo', text: '待办', icon: 'file-signature', path: '/pkg-client/pages/todo/index', badgeKey: 'pendingSigns' },
    { key: 'client-elevators', text: '电梯', icon: 'elevator', path: '/pages/elevator/list/index' },
    { key: 'profile', text: '我的', icon: 'user', path: '/pages/profile/index' }
  ],
  PLATFORM_ADMIN: [
    { key: 'admin-org', text: '组织', icon: 'users', path: '/pkg-admin/pages/users/index' },
    { key: 'stats', text: '统计', icon: 'chart', path: '/pkg-admin/pages/stats/index' },
    { key: 'admin-audit', text: '审计', icon: 'shield', path: '/pkg-admin/pages/audit/index' },
    { key: 'admin-settings', text: '配置', icon: 'settings', path: '/pkg-admin/pages/settings/index' },
    { key: 'profile', text: '我的', icon: 'user', path: '/pages/profile/index' }
  ],
  // 2026-11-15 用户裁决：SYS_ADMIN 全权限、平台域渲染；项目/合同/角色矩阵经「我的」perm 入口
  SYS_ADMIN: [
    { key: 'admin-org', text: '组织', icon: 'users', path: '/pkg-admin/pages/users/index' },
    { key: 'stats', text: '统计', icon: 'chart', path: '/pkg-admin/pages/stats/index' },
    { key: 'admin-audit', text: '审计', icon: 'shield', path: '/pkg-admin/pages/audit/index' },
    { key: 'admin-settings', text: '配置', icon: 'settings', path: '/pkg-admin/pages/settings/index' },
    { key: 'profile', text: '我的', icon: 'user', path: '/pages/profile/index' }
  ]
};

export function resolveHome(roles: RoleCode[]): string {
  const role = ROLE_PRIORITY.find((r) => roles.includes(r));
  return role ? ROLE_HOME[role] : '/pages/profile/index';
}

export function resolveTabs(roles: RoleCode[]): TabItem[] {
  const seen = new Set<string>();
  const tabs: TabItem[] = [];
  for (const role of ROLE_PRIORITY) {
    if (!roles.includes(role)) continue;
    for (const tab of ROLE_TABS[role]) {
      if (seen.has(tab.key)) continue;
      seen.add(tab.key);
      tabs.push(tab);
    }
  }
  return tabs.slice(0, 5);
}
```

> 多角色合并：按 `ROLE_PRIORITY` 取并集、按 `key` 去重、最多 5 项。切换工作台后以该角色的 `home/tabs` 为准重新拉取。

---

## 6. 路由元信息与守卫

`config/routes.ts`（节选，完整按第 4 节补齐）：　**RouteMeta 口径（V1.3 补充）**：示例 `ROUTES` 只给 8 个代表页面写了 `roles` / `perm`，**未列出的路由不代表没有权限要求**；守卫规则（`canAccess` → `ensureAuth` → `definePage` 的 `onLoad` + `onShow` 双层校验）对第 4 节路由清单列出的全部路由一致适用。逐权限码的完整归属（哪条路由直接承载 / 哪个页内动作复用已有路由 / 由后台管理端承接 / 由系统自动执行）以 `roles-permissions.md` §12 权限覆盖表为唯一口径，本节不重复维护该表，避免形成第二处事实源。控件不可用的两档门控（永久权限拒绝＝原生 `disabled` + `title`；临时前置条件未满足＝`aria-disabled` + 捕获阶段拦截）见 `roles-permissions.md` §8.4，路由层不引入第三套判定。

```ts
import type { RoleCode } from './menus';

export interface RouteMeta {
  path: string;
  name: string;
  auth: boolean;
  roles?: RoleCode[];
  perm?: string;
  tab?: boolean;
}

export const ROUTES: Record<string, RouteMeta> = {
  '/pages/login/index': { path: '/pages/login/index', name: '登录', auth: false },
  '/pages/maint/home/index': {
    path: '/pages/maint/home/index', name: '维保工作台', auth: true,
    roles: ['MAINT_TECH', 'MAINT_SUP', 'MAINT_MGR'], tab: true
  },
  '/pages/install/home/index': {
    path: '/pages/install/home/index', name: '安装工作台', auth: true,
    roles: ['INST_CLERK', 'INST_PM', 'INST_MGR'], tab: true
  },
  '/pages/client/home/index': {
    path: '/pages/client/home/index', name: '使用单位首页', auth: true,
    roles: ['CLIENT_ADMIN', 'CLIENT_SIGNER'], tab: true
  },
  '/pkg-handover/pages/create/index': {
    path: '/pkg-handover/pages/create/index', name: '发起移交', auth: true,
    roles: ['INST_PM'], perm: 'handover:create'
  },
  '/pkg-handover/pages/review/index': {
    path: '/pkg-handover/pages/review/index', name: '内部审核', auth: true,
    roles: ['INST_MGR'], perm: 'handover:review'
  },
  '/pkg-maintenance/pages/record-sign/index': {
    path: '/pkg-maintenance/pages/record-sign/index', name: '维保双人签字', auth: true,
    roles: ['MAINT_TECH'], perm: 'record:sign_tech'
  },
  '/pkg-client/pages/sign/index': {
    path: '/pkg-client/pages/sign/index', name: '使用单位签署', auth: true,
    roles: ['CLIENT_SIGNER'], perm: 'client:sign'
  }
};
```

`utils/guard.ts`：

```ts
import { ROUTES, type RouteMeta } from '../config/routes';
import { store } from './store';

export function canAccess(meta: RouteMeta, me = store.me): boolean {
  if (!meta.auth) return true;
  if (!me) return false;
  if (meta.roles && !meta.roles.some((r) => me.roles.includes(r))) return false;
  if (meta.perm && !me.perms.includes(meta.perm)) return false;
  return true;
}

export function ensureAuth(meta: RouteMeta): boolean {
  if (!meta.auth) return true;
  if (!store.token || !store.me) {
    wx.reLaunch({ url: `/pages/login/index?redirect=${encodeURIComponent(meta.path)}` });
    return false;
  }
  if (!canAccess(meta)) {
    wx.reLaunch({ url: '/pages/403/index' });
    return false;
  }
  return true;
}

/** 页面包装：onLoad / onShow 都校验，防止从后台切回时权限已变更 */
export function definePage(meta: RouteMeta, options: WechatMiniprogram.Page.Options): void {
  Page({
    ...options,
    onLoad(query) {
      if (!ensureAuth(meta)) return;
      options.onLoad?.call(this, query);
    },
    onShow() {
      if (!ensureAuth(meta)) return;
      options.onShow?.call(this);
    }
  });
}
```

登录成功后的回跳：

```ts
const ALLOWED_REDIRECTS = new Set([
  '/pages/maint/home/index',
  '/pages/install/home/index',
  '/pages/client/home/index',
  '/pages/elevator/detail/index',
  '/pkg-client/pages/sign/index',
  '/pkg-maintenance/pages/record-detail/index'
]);

function safeRedirect(raw?: string): string | null {
  if (!raw) return null;
  const path = decodeURIComponent(raw).split('?')[0];
  return ALLOWED_REDIRECTS.has(path) ? raw : null;
}

// 登录成功
const target = safeRedirect(query.redirect) ?? resolveHome(me.roles);
wx.reLaunch({ url: target });
```

> 重定向必须走白名单，防止被构造链接跳到任意外部 / 内部页面。

---

## 7. 自定义底部导航

`components/custom-nav/index.wxml`：

```html
<view class="nav" style="padding-bottom: env(safe-area-inset-bottom)">
  <view
    wx:for="{{tabs}}"
    wx:key="key"
    class="item {{active === item.key ? 'on' : ''}}"
    data-path="{{item.path}}"
    data-key="{{item.key}}"
    bindtap="onTap"
  >
    <image class="ico" src="{{icons[item.icon]}}" mode="aspectFit" />
    <text class="txt">{{item.text}}</text>
    <view wx:if="{{badges[item.badgeKey]}}" class="badge">{{badges[item.badgeKey]}}</view>
  </view>
</view>
```

`components/custom-nav/index.ts`：

```ts
import { store } from '../../utils/store';
import { ICONS } from '../../config/icons';

Component({
  data: { tabs: [], active: '', icons: ICONS, badges: {} as Record<string, number> },
  lifetimes: { attached() { this.sync(); } },
  pageLifetimes: { show() { this.sync(); } },
  methods: {
    sync() {
      const s = store.state;
      this.setData({ tabs: s.tabs, active: s.activeTab, badges: s.badges });
    },
    onTap(e: WechatMiniprogram.TouchEvent) {
      const { path, key } = e.currentTarget.dataset as { path: string; key: string };
      if (key === this.data.active) return;
      wx.reLaunch({ url: path });
    }
  }
});
```

要点：

- 底部导航高度：**内容区 56px + 底部留白 10px + 安全区**，落地为 `min-height: 66px` + `padding-bottom: calc(10px + env(safe-area-inset-bottom, 0px))`；页面内容预留 `padding-bottom: calc(66px + env(safe-area-inset-bottom, 0px))`。旧口径「`56px + safe-area-inset-bottom`」与此不矛盾——66 只是把 10px 留白计入；`env()` 不支持时前一行 `padding-bottom: 10px` 兜底（V1.3 收口，原型实测见 `index.html:224-229`）。
- 不使用 `wx.switchTab`；Tab 切换统一 `reLaunch`，避免页面栈越叠越深。
- 详情页不显示底部导航；只有主页面（`tab: true`）挂载 `<custom-nav />`。
- 图标用本地 `icons.ts` 映射，禁止 emoji 与远程任意图片。
- 角标由 `/me/menus` 的 `badges` 下发，在 `onShow` 时刷新。

---

## 8. app.json 配置

```json
{
  "pages": [
    "pages/login/index",
    "pages/install/home/index",
    "pages/maint/home/index",
    "pages/client/home/index",
    "pages/order/list/index",
    "pages/order/detail/index",
    "pages/order/report/index",
    "pages/elevator/list/index",
    "pages/elevator/detail/index",
    "pages/profile/index",
    "pages/403/index"
  ],
  "subPackages": [
    {
      "root": "pkg-handover",
      "pages": [
        "pages/list/index",
        "pages/detail/index",
        "pages/create/index",
        "pages/upload/index",
        "pages/review/index",
        "pages/inspect/index",
        "pages/sign/index",
        "pages/rectify/index"
      ]
    },
    {
      "root": "pkg-maintenance",
      "pages": [
        "pages/plan/index",
        "pages/task/index",
        "pages/record-detail/index",
        "pages/record-sign/index",
        "pages/record-archive/index"
      ]
    },
    {
      "root": "pkg-client",
      "pages": [
        "pages/todo/index",
        "pages/sign/index",
        "pages/orders/index",
        "pages/evaluate/index"
      ]
    },
    {
      "root": "pkg-admin",
      "pages": [
        "pages/projects/index",
        "pages/contracts/index",
        "pages/users/index",
        "pages/roles/index",
        "pages/stats/index",
        "pages/audit/index",
        "pages/settings/index"
      ]
    }
  ],
  "window": {
    "navigationBarTitleText": "梯维通",
    "navigationBarBackgroundColor": "#0A0F12",
    "navigationBarTextStyle": "white",
    "backgroundColor": "#0A0F12",
    "backgroundTextStyle": "dark"
  },
  "permission": {
    "scope.userLocation": {
      "desc": "用于到场打卡、现场验收与电子签字定位"
    }
  },
  "requiredPrivateInfos": ["getLocation"],
  "lazyCodeLoading": "requiredComponents",
  "preloadRule": {
    "pages/maint/home/index": { "network": "all", "packages": ["pkg-maintenance"] },
    "pages/client/home/index": { "network": "all", "packages": ["pkg-client"] },
    "pages/install/home/index": { "network": "all", "packages": ["pkg-handover"] }
  }
}
```

> **不要配置原生 `tabBar`**。否则 `switchTab` 与自定义导航会冲突，且原生 tabBar 最多 5 项无法承载多角色导航。

---

## 9. 深链与场景跳转

| 场景 | 入口 | 处理 |
|---|---|---|
| 扫码设备码 | `q=route=/pages/elevator/detail/index?id=xxx` | 解析 `q` → 白名单校验 → 存 pending → 登录后回跳 |
| 使用单位签字 | 订阅消息 → `/pkg-client/pages/sign/index?taskId=xxx&token=xxx` | 未登录先登录；token 服务端一次性校验 |
| 维保任务提醒 | 订阅消息 → `/pkg-maintenance/pages/task/index?id=xxx` | 校验维保人员被指派 |
| 移交审核提醒 | 订阅消息 → `/pkg-handover/pages/review/index?id=xxx` | 校验安装主管角色 |
| 使用单位报修 | 小程序码 → `/pages/order/report/index?elevatorId=xxx` | 校验电梯属于本单位 |
| 合同到期提醒 | 订阅消息 → `/pkg-admin/pages/contracts/index` | 校验维保经理 |
| H5 / 外部链接 | 不支持任意 URL | 仅允许白名单路径，避免开放重定向 |

深链兜底：

```ts
import { ROUTES } from '../config/routes';

const ROUTE_WHITELIST = new Set(Object.keys(ROUTES));

function parseScene(q?: string) {
  if (!q) return null;
  const query = decodeURIComponent(q);
  const route = query.match(/(?:^|&)route=([^&]+)/)?.[1];
  if (!route) return null;
  const path = route.split('?')[0];
  return ROUTE_WHITELIST.has(path) ? route : null;
}
```

---

## 10. 页面与原型对应

原型实测：`index.html` 共 **17 屏**（`data-title` 逐条提取，屏号与标题下表逐字一致）。屏 00–11 是业务流程屏，屏 12–16 是范式屏（异常态与脱敏对照），范式屏不新增路由，只声明宿主路由与状态范式。

| 原型 | 路由 | 说明 |
|---|---|---|
| 00 · 登录 · 角色路由 | `/pages/login/index` | 单入口、角色由后台识别；登录后 `resolveHome` 定首页 |
| 01 · 工作台 | `/pages/maint/home/index` | 维保人员；TabBar 角标取 `/me/menus` 的 `badges` |
| 02 · 安装移交验收 | `/pkg-handover/pages/detail/index` | 维保部视角接收并指派（`handover:receive`） |
| 03 · 外接项目接管 | `/pkg-admin/pages/projects/index` | 项目台账；「新增外接项目」= 永久权限拒绝档（缺 `project:edit` 时原生 `disabled`） |
| 04 · 半月保作业 | `/pkg-maintenance/pages/task/index` | 点检执行（`checklist:fill`） |
| 05 · 维保记录单签字 | `/pkg-maintenance/pages/record-sign/index` | 双签 + 版本号 / 哈希核对（`record:sign_tech`） |
| 06 · 急修单 | `/pages/order/detail/index` | 急修闭环 |
| 07 · 报修接单 | `/pages/order/list/index` | 维保抢单 / 派单（`order:view_pool` · `order:take`） |
| 08 · 电梯档案 | `/pages/elevator/detail/index` | 设备二维码；「导出台账」= 永久权限拒绝档（缺 `elevator:export`） |
| 09 · 安装部发起移交 | `/pkg-handover/pages/create/index` | 发起 + 上传（`handover:create`） |
| 10 · 使用单位工作台 | `/pages/client/home/index` | 使用单位 |
| 11 · 使用单位签字 | `/pkg-client/pages/sign/index` | 一次性签署任务；「确认签署」= 临时前置条件档（`aria-disabled` + 捕获阶段拦截） |
| 12 · 403 无权限 | `/pages/403/index?from` | 权限范式屏：只带 `from?`（本节 L127）；**不回显目标单据 id / 名称 / 状态**，文案与 404 保持一致 |
| 13 · 空态 | 宿主示例 `/pages/order/list/index?state` | 状态范式：**双分支** —— CASE A 本来没有数据（引导创建 / 引导接单）、CASE B 筛选后 0 条（数据仍在，引导清空或仅清除单个筛选） |
| 14 · 加载态 | 宿主示例 `/pages/elevator/detail/index?id` | 状态范式：首屏**骨架屏**，列表分页 loading 用骨架不用全屏转圈；`prefers-reduced-motion` 时降级为静态块 |
| 15 · 失败态 | 宿主示例 `/pkg-maintenance/pages/task/index?id` | 状态范式：可读原因 + 可重试动作 + 可退路（三件套）；失败**不清空本地已填数据**，弱网转离线续传 |
| 16 · 字段脱敏对照 | **无独立路由**（各业务页共用字段渲染） | 六类字段的「默认展示 / 完整形态 / 可看全的角色」对照；口径来源为本文件 §5.3 与后台 `admin-spec.md` §6 |

> 范式屏与路由的关系：屏 12 是**真路由**（`/pages/403/index`，`ensureAuth` 判定失败后 `reLaunch` 的目标）；屏 13 / 14 / 15 是**页面内状态**，同一套状态机挂在 §4 清单的每个列表页与详情页上，宿主路由仅为演示取值；屏 16 连状态都不是，是字段级渲染规则（脱敏在服务端完成，前端不接收明文）。因此**新增业务页不需要新增这四类路由**，但必须实现这三态 + 403 兜底。四态的后台对照口径、逐屏实测值与小程序侧差异见 `roles-permissions.md` §13.1；每屏的角色 / 路由 / 单据状态三元标注规则见 `index.html` 的 P2-7 注释（`index.html:153`）。


---

## 11. 验收清单

| # | 场景 | 预期 |
|---|---|---|
| 1 | 9 个角色分别登录 | 进入正确首页，底部 Tab 与角色匹配 |
| 2 | 多角色账号登录 | Tab 取并集、最多 5 项；默认最高优先级工作台；可在「我的」切换 |
| 3 | 直接访问无权限路由 | 跳 403；后端接口同步返回 403 |
| 4 | 未登录访问详情 / 签字链接 | 跳登录，登录后回到原目标 |
| 5 | 使用单位访问其他单位电梯 id | 404 / 403，不泄露数据是否存在 |
| 6 | 主检与配合人同一账号 | 双签页拒绝，提示选择另一名维保人员 |
| 7 | 仅主检签字 | 无法发起使用单位签字 |
| 8 | 使用单位签字 token 重放 | 一次性 token 失效，拒绝重复提交 |
| 9 | 角色 / 权限变更后 | token 版本或菜单刷新，旧权限立即失效 |
| 10 | Tab 切换 | `reLaunch` 清栈，无重复页面堆叠 |
| 11 | 子包页面首次加载 | 预加载生效，页面正常打开 |
| 12 | 底部导航 | 适配安全区，详情页不显示，角标正确 |
| 13 | 扫码 / 订阅消息入口 | 白名单校验通过，未登录可回跳 |
| 14 | `ROLE_TABS` 与 §3 表、`roles-permissions.md` §3 表逐格比对 | 9 个角色的 Tab `text` 顺序三处逐字一致；「质量」在 `ROLE_TABS` 与 §4 路由清单中出现 0 次（两份文档余下命中均为删除说明 / 业务词，非 Tab 项） |
| 15 | 抽查 §4 路由清单的权限点列与 §12 覆盖表 | 带权限点的路由行 29 行 / 去重 28 码全部可在 `roles-permissions.md` §4 找到；未出现的 43 码必须命中 ①②③④ 之一（`roles-permissions.md:455-458`），命中 ④ 的当前为 0 |
| 16 | 合同路由（本节 L166）与 `contract:view` 的角色集合比对 | `MAINT_SUP`△ 在列，且金额字段按 `roles-permissions.md:230` 脱敏，与 屏 03 的 `****` 掩码渲染一致 |
| 17 | 屏 12–16 的范式落点 | 屏 12 命中真路由 `/pages/403/index`（只带 `from?`）；屏 13 / 14 / 15 作为页面内状态不新增路由；屏 16 无路由（字段级渲染） |
| 18 | 底部导航高度 | 内容区 56 + 留白 10 = `min-height: 66px`，安全区存在时加高（本节 L507）；4 项与 5 项角色同高、单行不换行 |

## 12. V1.3 变更记录（批次 4b 路由侧裁决登记）

只登记**影响本文件**的裁决；权限矩阵侧的完整决策记录在 `roles-permissions.md` §10，两处不重复维护正文。

| # | 裁决 | 对本文件的影响 | 落点 |
|---|---|---|---|
| N1 | TabBar 唯一事实源 = `ROLE_TABS` | §3 表降级为 `ROLE_TABS` 的投影说明，冲突时以 `ROLE_TABS` 为准 | `routes-and-menus.md:105` |
| N2 | 「质量」Tab 删除 | 本文件从未把「质量」定义为 Tab：实测全文「质量」出现 **5 次**（L105 ×2、L681、L694 ×2），全部是「已删除」说明与验收表述，**TabBar 列与 `ROLE_TABS` 中 0 次**（§11 验收 14 逐格比对通过）。删除动作只发生在权限矩阵侧；本文件的作用是**证明它没有路由** | `routes-and-menus.md:105`、`roles-permissions.md:417` |
| N3 | 资料员 / 项目负责人的 Tab 差异 | 本文件 L95 / L96 的 4 项写法本就与 `ROLE_TABS` 一致，无需回写 | `routes-and-menus.md:257-315` |
| N4 | 合同路由补 `MAINT_SUP`△ | §4.5 合同行的允许角色补入维保主管，并把脱敏口径写进关键参数列；路由路径与行号未变 | `routes-and-menus.md:166` |
| N5 | 底部导航高度口径 | 「56px + safe-area」改写为内容区 56 + 留白 10 + 安全区，并给出 `env()` 不支持时的退化行为 | `routes-and-menus.md:507` |
| N6 | RouteMeta 不承载完整权限归属 | §6 追加口径说明：`ROUTES` 是节选示例，逐码归属以权限矩阵 §12 覆盖表为唯一口径 | `routes-and-menus.md:343` |
| N7 | 不可用控件两档门控并存 | 路由层不引入第三套判定，判定实现只在权限矩阵 §8.4 定义 | `routes-and-menus.md:343`、`roles-permissions.md:353-370` |
| N8 | 原型 17 屏 | §10 表从 12 行扩到 17 行，屏号与 `data-title` 逐字一致；屏 13–16 明确不新增路由 | `routes-and-menus.md:639-657` |
| N9 | 文档双日期口径 | 页脚改为「发布 2026-11-14 · 数据基准日 2026-11-13」，业务数据仍按基准日取值 | `routes-and-menus.md:707` |

> 版本口径：本文 V1.3 与 `roles-permissions.md` V1.3、`admin-spec.md`（后台管理端规格）、被冻结的 `index.html`（17 屏）同批对齐。`index.html` 内仍有 5 处按旧行号引用本文（`index.html:965` / `:969` / `:982` / `:1047` / `:1475` → L127 / L77 / L216 / L257 / L286），实测**行号在本次改写后仍然有效**，无需回改；权限矩阵侧的 11 处旧行号需要回改，登记在 `roles-permissions.md` §14 R7。

---

> 版本：V1.3 · 发布 2026-11-14 · 数据基准日 2026-11-13（业务数据与单据时间仍按基准日取值，版本号与发布日期按发布日记录）  
> 配套：`roles-permissions.md`（权限矩阵 V1.3，含 §12 权限覆盖表与 §13 小程序侧补充规格） / `index.html`（17 屏原型 V1.3） / `preview.png` / 后台管理端 `admin-spec.md` + `admin/`
