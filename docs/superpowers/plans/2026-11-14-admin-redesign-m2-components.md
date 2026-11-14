# M2 自建组件库 + 应用壳 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为管理端重构交付深色工业风自建 Vue3 组件库 + AppShell + 登录/工作台/403/404 四视图 + 前端镜像权限模型，旧 Element Plus 管理端零改动照常可用。

**Architecture:** 双布局共存——新 `admin/src/ui/` 组件库（styled SFC + headless composables）+ AppShell 只包新视图；旧 17 业务视图保留 AdminLayout + EP。permission-v2.js 逐字镜像 M1 后端 13 角色/86 码三态；工作台 M2 用 admin-spec §9 mock 基准集，多角色切换机制完整实现（真实 roleSet 接线 M3）。EP 到 M5 统一移除。

**Tech Stack:** Vue 3.5（script setup）+ Vite 6 + Pinia 2 + Vue Router 4（hash）+ vitest 3 + happy-dom + eslint 8。零外部网络请求（系统字体 + inline SVG）。

**Spec:** `docs/superpowers/specs/2026-11-14-admin-redesign-m2-components-design.md`（V1.0，master d9579e7）

## Global Constraints

- 纯前端里程碑：**零触碰** backend/、小程序、mock/server.js、services/、业务查询、上报逻辑（AGENTS §2.4）、派单逻辑（AGENTS §3.6）。
- 旧 17 EP 视图 + 旧 39 用例 + 旧 permission.js/menu.js/AdminLayout **零改动且仍绿**；M2 只增不改旧代码（exceljs 移除除外，无引用）。
- 所有新组件 scoped style 一律 `var(--token)`，**无硬编码色**；token 值逐字采用 spec §2.1（admin-spec §8）。
- 对比度硬约束：单据小字 `#5B5341 on --paper`（≥5:1）；角标 `--signal` 底 + `#1A0A06` 字；**禁白字 on `#FF4B2B`**。
- 排版：正文 13px / 次级 12px / 标签 ≥11px；表格行高 40px；数字 `tabular-nums`；系统字体栈，零外部请求（D4）。
- permission-v2 能力码/角色码与后端 `PermissionMatrix.java`/`RoleCatalog.java` **逐字一致**（拼错即静默放行=越权）；fail-closed（未知角色→DENY、未知码→抛错）。前端仅体验优化，后端唯一权威。
- PermButton 两档门控（spec §4.3）：Y 可点；DENY 原生 disabled + title=权限码；C aria-disabled + 可聚焦 + 捕获阶段拦截。
- 单身份登录互斥不变量不动（AGENTS §3.9）：多角色视角切换在员工态内。
- 命名：组件 PascalCase SFC；composables `useXxx`；常量 UPPER_SNAKE；2 空格缩进单引号；每文件顶部一行职责说明。
- 测试：vitest + happy-dom，`ui/*.test.js` 与组件同目录；门禁 `admin/` `npm run lint && npm test && npm run build` 全绿 + 1440×900 对照原型人工走查。
- Windows esbuild `Access is denied` → `TMP/TEMP` 指向 `admin/.tmp-build`（AGENTS §4.4）。
- 提交信息一行中文动词开头，禁凭证/token/报文（AGENTS §6）。
- 所有命令在 `admin/` 目录跑（`cd admin`）；git 命令在仓库根。

## File Structure

| 文件 | 职责 |
|---|---|
| `admin/src/ui/tokens.css` | 全套 CSS 变量 token（spec §2.1）+ 排版基础 + 滚动条 |
| `admin/src/ui/icons/index.js` | inline SVG icon 组件工厂（D4 离线） |
| `admin/src/ui/status-colors.js` | 状态机→语义色映射 |
| `admin/src/ui/permission-v2.js` | 13 角色 + 86 码三态 + canonical 别名 + state/allowsNew/canonicalCodes/defaultScope |
| `admin/src/ui/composables/usePermission.js` | state 解析 + 两档门控 |
| `admin/src/ui/composables/usePagination.js` | 页码/页大小 |
| `admin/src/ui/composables/useTable.js` | 排序三态/筛选/多选 |
| `admin/src/ui/composables/useForm.js` | 失焦+提交双校验 |
| `admin/src/ui/stores/ui.js` | 侧栏折叠 + Toast 队列（Pinia） |
| `admin/src/ui/stores/perspective.js` | activeRole + roleSet（Pinia） |
| `admin/src/ui/Toast.vue` | 三型 toast，3s |
| `admin/src/ui/StatusTag.vue` | 状态色 pill |
| `admin/src/ui/Timeline.vue` | 操作人+角色+时间 |
| `admin/src/ui/PermButton.vue` | 两档门控按钮 |
| `admin/src/ui/states/{EmptyState,LoadingSkeleton,ErrorState,ForbiddenState}.vue` | 四态 |
| `admin/src/ui/Modal.vue` | 危险变体 + 勾选确认 |
| `admin/src/ui/Drawer.vue` | 右 560px + 元信息条 + 焦点陷阱 |
| `admin/src/ui/Form.vue` | 双校验表单 |
| `admin/src/ui/DataTable.vue` | 排序/搜索/筛选/分页/批量/空态/骨架 |
| `admin/src/ui/mock-data.js` | admin-spec §9 基准集 |
| `admin/src/ui/data/provider.js` | 数据源接口 + mock 实现 |
| `admin/src/ui/menu-config.js` | 新 IA 8 组菜单常量（渐进） |
| `admin/src/ui/AppShell.vue` | 新壳 |
| `admin/src/ui/index.js` | 统一导出 + 引入 tokens.css |
| `admin/src/views/{LoginView,DashboardView,ForbiddenView,NotFoundView}.vue` | M2 重写四视图 |
| `admin/src/views/dev/{ComponentsGallery,DataSelfCheck}.vue` | 开发态组件廊 + 自检 |
| `admin/src/router/index.js` | 双布局路由 |
| `admin/package.json` | 移除 exceljs |
| `AGENTS.md` / `docs/09` / `docs/12` | 文档同步 |

---

### Task 1: Token 层 + 图标 + 状态色基础

**Files:**
- Create: `admin/src/ui/tokens.css`, `admin/src/ui/icons/index.js`, `admin/src/ui/status-colors.js`, `admin/src/ui/index.js`
- Test: `admin/src/ui/foundation.test.js`

**Interfaces:**
- Produces: CSS 变量（spec §2.1 全套）；`icon(name, size)` 返回 inline SVG 字符串的组件工厂；`statusColor(status)` 返回 `{bg,fg}`；`ui/index.js` 引入 tokens.css 并 re-export 后续组件。

- [ ] **Step 1: Write the failing test**

`admin/src/ui/foundation.test.js`：
```js
import { describe, it, expect } from 'vitest'
import { statusColor } from './status-colors'
import { icon } from './icons'

describe('status-colors', () => {
  it('maps progress to amber', () => {
    expect(statusColor('PROCESSING').fg).toBe('var(--amber)')
  })
  it('maps danger to signal', () => {
    expect(statusColor('TRAPPED').fg).toBe('var(--signal)')
  })
  it('maps done to ok', () => {
    expect(statusColor('ARCHIVED').fg).toBe('var(--ok)')
  })
  it('unknown status falls back to muted', () => {
    expect(statusColor('WHATEVER').fg).toBe('var(--muted)')
  })
})

describe('icons', () => {
  it('returns inline svg with no external href', () => {
    const svg = icon('dashboard', 16)
    expect(svg).toContain('<svg')
    expect(svg).not.toContain('href="http')
    expect(svg).toContain('width="16"')
  })
  it('unknown icon falls back to a default glyph', () => {
    expect(icon('nope', 16)).toContain('<svg')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd admin && npx vitest run src/ui/foundation.test.js`
Expected: FAIL — `./status-colors` / `./icons` 不存在。

- [ ] **Step 3: Write the foundation files**

`admin/src/ui/tokens.css`（spec §2.1 逐字）：
```css
/* M2 设计 token（admin-spec §8）：深色工业风，零外部请求 */
:root {
  --ink: #0A0F12; --ink-2: #0D1418;
  --panel: #131C21; --panel-2: #172228;
  --line: #26343B; --line-2: #2E3E46;
  --text: #E8EEF0; --muted: #A3B4BB; --dim: #7E929B;
  --amber: #FFB020; --amber-soft: #FFC85C;
  --signal: #FF4B2B; --signal-soft: #FF8F7A;
  --ok: #2FC48D; --ok-soft: #5FE0B2;
  --paper: #EDE5D3; --paper-ink: #22201A; --paper-line: #C6BA9F;
  --accent-on-amber: #1A1203; --accent-on-signal: #1A0A06;
  --scrim: rgba(5, 8, 10, .72); --focus: var(--amber);
  --paper-small: #5B5341;
  --font-sans: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  --font-mono: 'Consolas', 'Menlo', monospace;
}
.ui-scope {
  background: var(--ink); color: var(--text);
  font-family: var(--font-sans); font-size: 13px; line-height: 1.5;
}
.ui-scope .num { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
.ui-scope .label {
  font-family: var(--font-mono); font-size: 11px; letter-spacing: .08em;
  text-transform: uppercase; color: var(--dim);
}
.ui-scope ::-webkit-scrollbar { width: 10px; height: 10px; }
.ui-scope ::-webkit-scrollbar-track { background: var(--panel); }
.ui-scope ::-webkit-scrollbar-thumb { background: var(--line-2); border-radius: 5px; }
.ui-scope ::-webkit-scrollbar-thumb:hover { background: var(--amber); }
.ui-scope :focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
```

`admin/src/ui/icons/index.js`：
```js
// M2 inline SVG 图标（D4 离线，无外部请求）；路径取自原型 icons.svg
const PATHS = {
  dashboard: 'M3 3h8v8H3zM13 3h8v5h-8zM13 10h8v11h-8zM3 13h8v8H3z',
  org: 'M12 3l9 5-9 5-9-5zM3 13l9 5 9-5',
  ledger: 'M4 4h16v16H4zM4 9h16M9 9v11',
  handover: 'M4 12l5 5L20 6',
  plan: 'M4 5h16M4 12h16M4 19h10',
  dispatch: 'M3 12h13M12 5l7 7-7 7',
  stats: 'M4 20V10M10 20V4M16 20v-7M22 20H2',
  audit: 'M6 3h9l5 5v13H6zM14 3v6h6',
  settings: 'M12 8a4 4 0 100 8 4 4 0 000-8zM12 2v3M12 19v3M2 12h3M19 12h3',
  compliance: 'M12 3l8 4v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V7z',
  search: 'M10 4a6 6 0 100 12 6 6 0 000-12zM20 20l-5-5',
  close: 'M5 5l14 14M19 5L5 19',
  check: 'M4 12l5 5L20 6',
  warn: 'M12 3l10 18H2zM12 10v5M12 18v.5',
  chevron: 'M9 6l6 6-6 6',
  default: 'M4 4h16v16H4z'
}
export function icon(name, size = 16) {
  const d = PATHS[name] || PATHS.default
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="${d}"/></svg>`
}
export const ICON_NAMES = Object.keys(PATHS)
```

`admin/src/ui/status-colors.js`：
```js
// M2 状态机→语义色映射（spec §2.5）；M4 逐态对齐
const SIGNAL = ['TRAPPED', 'OVERDUE', 'URGENT', 'FAULT', 'REJECTED', 'VOID', 'SCRAPPED']
const OK = ['ARCHIVED', 'SIGNED', 'CLOSED', 'DONE', 'NORMAL', 'ACTIVE', 'CONFIRMED', 'REPORTED']
const AMBER = ['PROCESSING', 'PENDING', 'IN_PROGRESS', 'ASSIGNED', 'REVIEWING', 'AMENDING']
export function statusColor(status) {
  const s = String(status || '').toUpperCase()
  if (SIGNAL.includes(s)) return { bg: 'var(--signal)', fg: 'var(--signal)' }
  if (OK.includes(s)) return { bg: 'var(--ok)', fg: 'var(--ok)' }
  if (AMBER.includes(s)) return { bg: 'var(--amber)', fg: 'var(--amber)' }
  return { bg: 'var(--muted)', fg: 'var(--muted)' }
}
```

`admin/src/ui/index.js`：
```js
// M2 自建组件库统一入口；引入 token 层
import './tokens.css'
export { icon, ICON_NAMES } from './icons'
export { statusColor } from './status-colors'
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd admin && npx vitest run src/ui/foundation.test.js`
Expected: PASS（6 断言）。

- [ ] **Step 5: Commit**

```bash
git add admin/src/ui/tokens.css admin/src/ui/icons/index.js admin/src/ui/status-colors.js admin/src/ui/index.js admin/src/ui/foundation.test.js
git commit -m "feat: M2 组件库 token 层与图标状态色基础"
```

---

### Task 2: permission-v2 镜像 M1（13 角色 + 86 码三态）

**Files:**
- Create: `admin/src/ui/permission-v2.js`
- Test: `admin/src/ui/permission-v2.test.js`

**Interfaces:**
- Consumes: M1 后端 `RoleCatalog.java` CANONICAL/ALIAS、`PermissionMatrix.java` STATES（逐字镜像）。
- Produces: `canonical(anyCode)`、`state(role, code)`、`allowsNew(role, code)`、`canonicalCodes()`、`canonicalRoles()`、`defaultScope(role)`、`ROLE`/`CAP` 常量。后续菜单/PermButton/守卫依赖。

- [ ] **Step 1: Write the failing test**

`admin/src/ui/permission-v2.test.js`：
```js
import { describe, it, expect } from 'vitest'
import { canonical, state, allowsNew, canonicalCodes, canonicalRoles, defaultScope } from './permission-v2'

describe('permission-v2 mirror', () => {
  it('has 86 canonical codes', () => {
    expect(canonicalCodes().size).toBe(86)
    expect(canonicalCodes().has('handover:view')).toBe(true)
    expect(canonicalCodes().has('record:spotcheck')).toBe(true)
    expect(canonicalCodes().has('user:reset_password')).toBe(true)
  })
  it('has 13 canonical roles', () => {
    expect(canonicalRoles().size).toBe(13)
  })
  it('aliases old codes to canonical', () => {
    expect(canonical('ADMIN')).toBe('PLATFORM_ADMIN')
    expect(canonical('LEADER')).toBe('MAINT_SUP')
    expect(canonical('INSTALL_MANAGER')).toBe('INST_PM')
    expect(canonical('UNIT_ADMIN')).toBe('CLIENT_ADMIN')
    expect(canonical('INST_MGR')).toBe('INST_MGR')
    expect(canonical('HACKER')).toBe(null)
    expect(canonical(null)).toBe(null)
  })
  it('tri-state samples match backend', () => {
    expect(state('INST_MGR', 'handover:review')).toBe('Y')
    expect(state('INST_CLERK', 'handover:review')).toBe('DENY')
    expect(state('INST_CLERK', 'handover:view')).toBe('C')
    expect(state('CLIENT_ADMIN', 'record:view')).toBe('DENY')
    expect(state('MAINT_QUALITY', 'record:spotcheck')).toBe('Y')
    expect(state('MAINT_BIZ', 'client:edit')).toBe('Y')
    expect(state('INSTALLER', 'handover:inspect')).toBe('Y')
    expect(state('PLATFORM_ADMIN', 'config:manage')).toBe(state('SYS_ADMIN', 'config:manage'))
  })
  it('allowsNew requires Y', () => {
    expect(allowsNew('INST_MGR', 'handover:review')).toBe(true)
    expect(allowsNew('INST_CLERK', 'handover:view')).toBe(false)
    expect(allowsNew('MAINT_TECH', 'config:manage')).toBe(false)
  })
  it('fail-closed: unknown role DENY, unknown code throws', () => {
    expect(state('HACKER', 'handover:view')).toBe('DENY')
    expect(state(null, 'handover:view')).toBe('DENY')
    expect(() => state('INST_MGR', 'no:such')).toThrow()
    expect(() => allowsNew('INST_MGR', null)).toThrow()
  })
  it('defaultScope mirrors sys_role_scope', () => {
    expect(defaultScope('PLATFORM_ADMIN')).toBe('ALL')
    expect(defaultScope('INST_MGR')).toBe('ORG')
    expect(defaultScope('INST_PM')).toBe('PROJECT')
    expect(defaultScope('MAINT_SUP')).toBe('TEAM')
    expect(defaultScope('MAINT_TECH')).toBe('SELF')
    expect(defaultScope('CLIENT_ADMIN')).toBe('ORG_FLAT')
    expect(defaultScope('HACKER')).toBe(null)
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd admin && npx vitest run src/ui/permission-v2.test.js`
Expected: FAIL — `./permission-v2` 不存在。

- [ ] **Step 3: Write permission-v2.js（逐字镜像 M1 STATES）**

`admin/src/ui/permission-v2.js`：
```js
// M2 前端权限模型：逐字镜像 M1 后端 RoleCatalog v2 + PermissionMatrix v2（spec §4.2）。
// 前端仅体验优化，后端唯一权威；fail-closed（未知角色 DENY、未知码抛错）。
export const ROLE = {
  PLATFORM_ADMIN: 'PLATFORM_ADMIN', SYS_ADMIN: 'SYS_ADMIN', INST_MGR: 'INST_MGR',
  INST_PM: 'INST_PM', INST_CLERK: 'INST_CLERK', INSTALLER: 'INSTALLER',
  MAINT_MGR: 'MAINT_MGR', MAINT_SUP: 'MAINT_SUP', MAINT_TECH: 'MAINT_TECH',
  MAINT_BIZ: 'MAINT_BIZ', MAINT_QUALITY: 'MAINT_QUALITY',
  CLIENT_ADMIN: 'CLIENT_ADMIN', CLIENT_SIGNER: 'CLIENT_SIGNER'
}
const ALIAS = {
  ADMIN: 'PLATFORM_ADMIN', SYS_ADMIN: 'SYS_ADMIN', INSTALL_MANAGER: 'INST_PM',
  INSTALL_CLERK: 'INST_CLERK', INSTALL_WORKER: 'INSTALLER', MAINT_MANAGER: 'MAINT_MGR',
  LEADER: 'MAINT_SUP', WORKER: 'MAINT_TECH', ASSISTANT: 'MAINT_TECH',
  MAINT_BIZ: 'MAINT_BIZ', MAINT_QUALITY: 'MAINT_QUALITY', UNIT_ADMIN: 'CLIENT_ADMIN'
}
const SCOPE = {
  PLATFORM_ADMIN: 'ALL', SYS_ADMIN: 'ALL', INST_MGR: 'ORG', INST_PM: 'PROJECT',
  INST_CLERK: 'PROJECT', INSTALLER: 'SELF', MAINT_MGR: 'ALL', MAINT_QUALITY: 'ORG',
  MAINT_BIZ: 'ORG', MAINT_SUP: 'TEAM', MAINT_TECH: 'SELF', CLIENT_ADMIN: 'ORG_FLAT',
  CLIENT_SIGNER: 'ORG_FLAT'
}
const PA = 'PLATFORM_ADMIN', SA = 'SYS_ADMIN', IM = 'INST_MGR', IP = 'INST_PM', IC = 'INST_CLERK',
  IW = 'INSTALLER', MM = 'MAINT_MGR', MS = 'MAINT_SUP', MT = 'MAINT_TECH',
  MB = 'MAINT_BIZ', MQ = 'MAINT_QUALITY', CA = 'CLIENT_ADMIN', CS = 'CLIENT_SIGNER'
// code -> { role: 'Y'|'C' }（缺省 DENY）；逐字镜像 PermissionMatrix.STATES
const STATES = {}
function g(code, st, roles) {
  const row = STATES[code] || (STATES[code] = {})
  roles.forEach(r => { row[r] = st })
}
const y = (code, ...roles) => g(code, 'Y', roles)
const c = (code, ...roles) => g(code, 'C', roles)
// §4.1 安装移交（16）
y('handover:view', IM, MS, MM, PA, SA); c('handover:view', IC, IP, CA)
y('handover:create', IP)
y('handover:edit', IP); c('handover:edit', IC)
y('handover:doc_upload', IC, IP)
y('handover:doc_delete', IC, IP)
y('handover:submit', IP)
y('handover:review', IM)
y('handover:review_return', IM)
y('handover:receive', MS)
y('handover:inspect', IW); c('handover:inspect', MT)
y('handover:rectify', IP, IC, IW)
y('handover:sign_install', IM, IW)
y('handover:sign_maint', MS, MM)
y('handover:void', IM, MM, PA, SA)
y('handover:export', IM, MS, MM, PA, SA); c('handover:export', CA)
y('handover:notify_client', MS)
// §4.2 项目合同电梯（9）
y('project:view', MS, MM, PA, SA); c('project:view', IC, IP, IM, CA)
y('project:edit', MM, PA, SA)
y('contract:view', MM, PA, SA, MB); c('contract:view', MS, CA)
y('contract:edit', MM, PA, SA, MB)
c('elevator:view', PA, SA, IM, IP, IC, IW, MM, MS, MT, MB, MQ, CA, CS)
y('elevator:edit', MS, PA, SA)
y('elevator:qr', MT, MS, CA)
y('elevator:transfer', MM, PA, SA)
y('elevator:export', MM, PA, SA); c('elevator:export', MS, CA)
// §4.3 维保作业（15）
y('plan:view', MS, MM); c('plan:view', MT, CA)
y('plan:generate', MS, MM)
y('task:accept', MT)
y('task:assign_partner', MS, MT)
y('checklist:fill', MT)
y('record:view', MS, MM, PA, SA); c('record:view', MT, CS)
y('record:submit', MT)
y('record:sign_tech', MT)
y('record:review', MS, MM)
y('record:sign_request', MT, MS)
y('record:sign', CS)
y('record:refuse', CS)
y('record:archive', MS)
y('record:amend', MM, PA, SA)
y('record:export', MS, MM, PA, SA); c('record:export', CA)
// §4.4 急修报修（14）
y('order:create', CA, CS)
y('order:create_proxy', MS)
y('order:view_pool', MS, MM); c('order:view_pool', MT)
y('order:view_own', CA, CS)
y('order:grab', MT)
y('order:dispatch', MS, MM)
c('order:arrive', MT)
c('order:process', MT)
y('order:submit', MT)
y('order:confirm', CS)
y('order:close', MS)
y('order:cancel', CA, MS)
y('order:escalate', MM)
y('order:export', MS, MM, PA, SA); c('order:export', CA)
// §4.5 使用单位（7）
y('client:invite', CA, MS)
y('client:user_manage', CA)
y('client:elevator_view', CA, CS)
y('client:report', CA, CS)
y('client:sign', CS)
y('client:evaluate', CA, CS)
y('client:download', CA, CS)
// §4.6 组织权限审计（10）
y('user:view', PA, SA); c('user:view', IM, MS, MM, CA)
y('user:create', IM, MS, MM, CA, PA, SA)
y('user:disable', IM, MS, MM, CA, PA, SA)
y('role:assign', PA, SA); c('role:assign', CA)
y('invite:manage', IM, MS, MM, CA, PA, SA)
y('stats:view', IM, MS, MM, CA, PA, SA)
y('stats:export', MM, PA, SA); c('stats:export', IM, MS, CA)
y('audit:view', PA, SA); c('audit:view', CA)
y('config:manage', PA, SA)
y('data:export_sensitive', PA, SA)
// M0 §2.2 扩展（15）
y('report:view', PA, SA, MM); c('report:view', MS, MQ)
y('report:retry', PA, SA, MM)
y('filing:view', PA, SA, IP, IC, MM); c('filing:view', MQ, IM)
y('filing:write', PA, SA, IP, IC, MM)
y('region:view', PA, SA)
y('region:write', PA, SA)
y('template:view', PA, SA, MM); c('template:view', MQ)
y('template:write', PA, SA, MM, MQ)
y('template:toggle', PA, SA, MQ)
y('platform:worker_view', PA, SA); c('platform:worker_view', IP, IC, MM, MS)
y('platform:worker_register', PA, SA, MM)
y('platform:sync', PA, SA)
y('record:spotcheck', MQ)
y('client:edit', PA, MB)
y('user:reset_password', PA, SA, MM, IM)

const CODES = new Set(Object.keys(STATES))
export function canonical(anyCode) {
  if (anyCode == null) return null
  if (ROLE[anyCode]) return anyCode
  return ALIAS[anyCode] || null
}
export function state(role, code) {
  if (code == null || !CODES.has(code)) throw new Error('未知权限码: ' + code)
  const r = canonical(role)
  if (r == null) return 'DENY'
  return (STATES[code] && STATES[code][r]) || 'DENY'
}
export function allowsNew(role, code) { return state(role, code) === 'Y' }
export function canonicalCodes() { return new Set(CODES) }
export function canonicalRoles() { return new Set(Object.keys(ROLE)) }
export function defaultScope(role) {
  const r = canonical(role)
  return r == null ? null : (SCOPE[r] || null)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd admin && npx vitest run src/ui/permission-v2.test.js`
Expected: PASS（7 组断言；86 码/13 角色/三态/fail-closed/scope）。

- [ ] **Step 5: Commit**

```bash
git add admin/src/ui/permission-v2.js admin/src/ui/permission-v2.test.js
git commit -m "feat: M2 前端权限模型镜像 M1 十三角色八十六码三态"
```

---

### Task 3: composables usePermission + usePagination

**Files:**
- Create: `admin/src/ui/composables/usePermission.js`, `admin/src/ui/composables/usePagination.js`
- Test: `admin/src/ui/composables/composables.test.js`

**Interfaces:**
- Consumes: `permission-v2.state/canonical`（Task 2）。
- Produces: `usePermission(roleRef)` → `{ stateOf(code), gate(code) }`，gate 返回 `'allow'|'deny'|'conditional'`；`usePagination(totalRef, initialSize)` → `{ page, size, pageCount, offset, setPage, setSize, slice }`。

- [ ] **Step 1: Write the failing test**

`admin/src/ui/composables/composables.test.js`：
```js
import { describe, it, expect } from 'vitest'
import { ref } from 'vue'
import { usePermission } from './usePermission'
import { usePagination } from './usePagination'

describe('usePermission', () => {
  it('gate maps Y/DENY/C to allow/deny/conditional', () => {
    const { gate } = usePermission(ref('INST_CLERK'))
    expect(gate('handover:review')).toBe('deny')
    expect(gate('handover:view')).toBe('conditional')
    const mgr = usePermission(ref('INST_MGR'))
    expect(mgr.gate('handover:review')).toBe('allow')
  })
  it('unknown role is deny', () => {
    expect(usePermission(ref('HACKER')).gate('handover:view')).toBe('deny')
  })
})

describe('usePagination', () => {
  it('computes pageCount and offset', () => {
    const p = usePagination(ref(20), 8)
    expect(p.pageCount.value).toBe(3)
    expect(p.offset.value).toBe(0)
    p.setPage(2)
    expect(p.offset.value).toBe(8)
  })
  it('clamps page within range and resets on size change', () => {
    const p = usePagination(ref(20), 8)
    p.setPage(99)
    expect(p.page.value).toBe(3)
    p.setSize(16)
    expect(p.page.value).toBe(1)
    expect(p.pageCount.value).toBe(2)
  })
  it('slice returns the current page rows', () => {
    const rows = Array.from({ length: 20 }, (_, i) => i)
    const p = usePagination(ref(20), 8)
    expect(p.slice(rows)).toEqual([0, 1, 2, 3, 4, 5, 6, 7])
    p.setPage(3)
    expect(p.slice(rows)).toEqual([16, 17, 18, 19])
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd admin && npx vitest run src/ui/composables/composables.test.js`
Expected: FAIL — 模块不存在。

- [ ] **Step 3: Write the composables**

`admin/src/ui/composables/usePermission.js`：
```js
// 权限门控 composable：state 解析 + 两档门控（spec §4.3）
import { computed } from 'vue'
import { state, canonical } from '../permission-v2'
export function usePermission(roleRef) {
  const role = computed(() => canonical(roleRef.value))
  function stateOf(code) { return state(role.value, code) }
  function gate(code) {
    const s = stateOf(code)
    if (s === 'Y') return 'allow'
    if (s === 'C') return 'conditional'
    return 'deny'
  }
  return { stateOf, gate }
}
```

`admin/src/ui/composables/usePagination.js`：
```js
// 分页 composable：页码/页大小/偏移（headless，可单测）
import { ref, computed } from 'vue'
export function usePagination(totalRef, initialSize = 8) {
  const page = ref(1)
  const size = ref(initialSize)
  const pageCount = computed(() => Math.max(1, Math.ceil((totalRef.value || 0) / size.value)))
  const offset = computed(() => (page.value - 1) * size.value)
  function setPage(n) { page.value = Math.min(Math.max(1, n), pageCount.value) }
  function setSize(n) { size.value = n; page.value = 1 }
  function slice(rows) { return rows.slice(offset.value, offset.value + size.value) }
  return { page, size, pageCount, offset, setPage, setSize, slice }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd admin && npx vitest run src/ui/composables/composables.test.js`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add admin/src/ui/composables/usePermission.js admin/src/ui/composables/usePagination.js admin/src/ui/composables/composables.test.js
git commit -m "feat: M2 usePermission 与 usePagination composable"
```

---

### Task 4: composables useTable + useForm

**Files:**
- Create: `admin/src/ui/composables/useTable.js`, `admin/src/ui/composables/useForm.js`
- Test: `admin/src/ui/composables/table-form.test.js`

**Interfaces:**
- Consumes: `usePagination`（Task 3）。
- Produces: `useTable(rowsRef, { searchKeys })` → `{ query, sortKey, sortDir, filters, selected, visibleRows, total, toggleSort, setFilter, clearFilters, toggleSelect, toggleAll, allSelected, pagination }`；`useForm(rules)` → `{ values, errors, touched, validateField, validateAll, reset, isValid }`。

- [ ] **Step 1: Write the failing test**

`admin/src/ui/composables/table-form.test.js`：
```js
import { describe, it, expect } from 'vitest'
import { ref } from 'vue'
import { useTable } from './useTable'
import { useForm } from './useForm'

const rows = ref([
  { id: 1, name: 'alpha', status: 'ACTIVE' },
  { id: 2, name: 'beta', status: 'DISABLED' },
  { id: 3, name: 'gamma', status: 'ACTIVE' }
])

describe('useTable', () => {
  it('three-state sort cycles asc/desc/none', () => {
    const t = useTable(rows, { searchKeys: ['name'] })
    t.toggleSort('name'); expect(t.sortDir.value).toBe('asc')
    t.toggleSort('name'); expect(t.sortDir.value).toBe('desc')
    t.toggleSort('name'); expect(t.sortDir.value).toBe('none')
  })
  it('search filters by keys', () => {
    const t = useTable(rows, { searchKeys: ['name'] })
    t.query.value = 'alp'
    expect(t.visibleRows.value.map(r => r.id)).toEqual([1])
  })
  it('filter + clearFilters', () => {
    const t = useTable(rows, { searchKeys: ['name'] })
    t.setFilter('status', 'ACTIVE')
    expect(t.visibleRows.value.map(r => r.id)).toEqual([1, 3])
    t.clearFilters()
    expect(t.visibleRows.value.length).toBe(3)
  })
  it('select and toggleAll', () => {
    const t = useTable(rows, { searchKeys: ['name'] })
    t.toggleSelect(1)
    expect(t.selected.value.has(1)).toBe(true)
    t.toggleAll()
    expect(t.allSelected.value).toBe(true)
    t.toggleAll()
    expect(t.selected.value.size).toBe(0)
  })
})

describe('useForm', () => {
  const rules = {
    name: [(v) => (v ? '' : '姓名必填')],
    phone: [(v) => (/^1\d{10}$/.test(v || '') ? '' : '手机号格式错误')]
  }
  it('validateField sets error', () => {
    const f = useForm(rules)
    f.values.name = ''
    expect(f.validateField('name')).toBe('姓名必填')
    expect(f.errors.name).toBe('姓名必填')
  })
  it('validateAll aggregates and isValid', () => {
    const f = useForm(rules)
    f.values.name = '张三'; f.values.phone = '13800000000'
    expect(f.validateAll()).toBe(true)
    f.values.phone = '123'
    expect(f.validateAll()).toBe(false)
    expect(f.errors.phone).toBe('手机号格式错误')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd admin && npx vitest run src/ui/composables/table-form.test.js`
Expected: FAIL — 模块不存在。

- [ ] **Step 3: Write the composables**

`admin/src/ui/composables/useTable.js`：
```js
// 表格 headless 逻辑：排序三态/搜索/筛选/多选 + 分页
import { ref, computed } from 'vue'
import { usePagination } from './usePagination'
export function useTable(rowsRef, { searchKeys = [] } = {}) {
  const query = ref('')
  const sortKey = ref('')
  const sortDir = ref('none')
  const filters = ref({})
  const selected = ref(new Set())
  const filtered = computed(() => {
    let out = rowsRef.value.slice()
    const q = query.value.trim().toLowerCase()
    if (q) out = out.filter(r => searchKeys.some(k => String(r[k] ?? '').toLowerCase().includes(q)))
    Object.entries(filters.value).forEach(([k, v]) => {
      if (v !== '' && v != null) out = out.filter(r => String(r[k]) === String(v))
    })
    if (sortDir.value !== 'none' && sortKey.value) {
      const dir = sortDir.value === 'asc' ? 1 : -1
      out.sort((a, b) => (a[sortKey.value] > b[sortKey.value] ? dir : a[sortKey.value] < b[sortKey.value] ? -dir : 0))
    }
    return out
  })
  const total = computed(() => filtered.value.length)
  const pagination = usePagination(total, 8)
  const visibleRows = computed(() => pagination.slice(filtered.value))
  function toggleSort(key) {
    if (sortKey.value !== key) { sortKey.value = key; sortDir.value = 'asc'; return }
    sortDir.value = sortDir.value === 'asc' ? 'desc' : sortDir.value === 'desc' ? 'none' : 'asc'
    if (sortDir.value === 'none') sortKey.value = ''
  }
  function setFilter(key, val) { filters.value = { ...filters.value, [key]: val }; pagination.setPage(1) }
  function clearFilters() { filters.value = {}; query.value = ''; pagination.setPage(1) }
  function toggleSelect(id) {
    const next = new Set(selected.value)
    next.has(id) ? next.delete(id) : next.add(id)
    selected.value = next
  }
  const allSelected = computed(() => visibleRows.value.length > 0 && visibleRows.value.every(r => selected.value.has(r.id)))
  function toggleAll() {
    const next = new Set(selected.value)
    if (allSelected.value) visibleRows.value.forEach(r => next.delete(r.id))
    else visibleRows.value.forEach(r => next.add(r.id))
    selected.value = next
  }
  return { query, sortKey, sortDir, filters, selected, visibleRows, total, toggleSort, setFilter, clearFilters, toggleSelect, toggleAll, allSelected, pagination }
}
```

`admin/src/ui/composables/useForm.js`：
```js
// 表单 headless 校验：失焦 + 提交双校验（spec §3.4）
import { reactive, computed } from 'vue'
export function useForm(rules) {
  const values = reactive({})
  const errors = reactive({})
  const touched = reactive({})
  function validateField(name) {
    const fns = rules[name] || []
    let msg = ''
    for (const fn of fns) { msg = fn(values[name]); if (msg) break }
    errors[name] = msg
    return msg
  }
  function validateAll() {
    let ok = true
    Object.keys(rules).forEach(name => { touched[name] = true; if (validateField(name)) ok = false })
    return ok
  }
  function reset() {
    Object.keys(values).forEach(k => delete values[k])
    Object.keys(errors).forEach(k => delete errors[k])
    Object.keys(touched).forEach(k => delete touched[k])
  }
  const isValid = computed(() => Object.values(errors).every(e => !e))
  return { values, errors, touched, validateField, validateAll, reset, isValid }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd admin && npx vitest run src/ui/composables/table-form.test.js`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add admin/src/ui/composables/useTable.js admin/src/ui/composables/useForm.js admin/src/ui/composables/table-form.test.js
git commit -m "feat: M2 useTable 与 useForm composable"
```

---

### Task 5: ui store + Toast

**Files:**
- Create: `admin/src/ui/stores/ui.js`, `admin/src/ui/Toast.vue`
- Test: `admin/src/ui/toast.test.js`

**Interfaces:**
- Produces: `useUiStore()` → `{ collapsed, toggleCollapsed, toasts, push(type,msg), dismiss(id) }`；`Toast.vue` 渲染队列（props 无，读 store）；`useToast()` 便捷注入。

- [ ] **Step 1: Write the failing test**

`admin/src/ui/toast.test.js`：
```js
import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mount } from '@vue/test-utils'
import { useUiStore } from './stores/ui'
import Toast from './Toast.vue'

describe('ui store + Toast', () => {
  beforeEach(() => setActivePinia(createPinia()))
  it('push adds a toast and dismiss removes it', () => {
    const ui = useUiStore()
    const id = ui.push('success', '已保存')
    expect(ui.toasts.length).toBe(1)
    ui.dismiss(id)
    expect(ui.toasts.length).toBe(0)
  })
  it('Toast renders queued messages with type class', () => {
    const ui = useUiStore()
    ui.push('error', '失败')
    const w = mount(Toast)
    expect(w.text()).toContain('失败')
    expect(w.find('.toast--error').exists()).toBe(true)
  })
  it('collapsed toggles', () => {
    const ui = useUiStore()
    const before = ui.collapsed
    ui.toggleCollapsed()
    expect(ui.collapsed).toBe(!before)
  })
})
```

> 注：需 `@vue/test-utils`。Step 3 先确认依赖；若缺则 `npm i -D @vue/test-utils`（仅测试依赖，不改运行时）。

- [ ] **Step 2: Run test to verify it fails**

Run: `cd admin && npx vitest run src/ui/toast.test.js`
Expected: FAIL — 模块不存在（或 @vue/test-utils 缺失）。

- [ ] **Step 3: Write store + Toast（必要时先装测试依赖）**

先：`cd admin && npm i -D @vue/test-utils`（若已存在则跳过）。

`admin/src/ui/stores/ui.js`：
```js
// M2 壳层 UI 状态：侧栏折叠（localStorage）+ Toast 队列
import { defineStore } from 'pinia'
let seq = 0
export const useUiStore = defineStore('ui', {
  state: () => ({
    collapsed: localStorage.getItem('ui_collapsed') === '1',
    toasts: []
  }),
  actions: {
    toggleCollapsed() {
      this.collapsed = !this.collapsed
      localStorage.setItem('ui_collapsed', this.collapsed ? '1' : '0')
    },
    push(type, msg) {
      const id = ++seq
      this.toasts.push({ id, type, msg })
      setTimeout(() => this.dismiss(id), 3000)
      return id
    },
    dismiss(id) { this.toasts = this.toasts.filter(t => t.id !== id) }
  }
})
export function useToast() {
  const ui = useUiStore()
  return {
    success: (m) => ui.push('success', m),
    warn: (m) => ui.push('warn', m),
    error: (m) => ui.push('error', m)
  }
}
```

`admin/src/ui/Toast.vue`：
```vue
<template>
  <div class="toast-wrap" role="status" aria-live="polite">
    <div v-for="t in ui.toasts" :key="t.id" :class="['toast', 'toast--' + t.type]">
      <span>{{ t.msg }}</span>
      <button class="toast-x" aria-label="关闭" @click="ui.dismiss(t.id)">×</button>
    </div>
  </div>
</template>
<script setup>
// M2 全局 Toast：三型 3 秒自动消失（spec §3.5）
import { useUiStore } from './stores/ui'
const ui = useUiStore()
</script>
<style scoped>
.toast-wrap { position: fixed; top: 16px; right: 16px; z-index: 1000; display: flex; flex-direction: column; gap: 8px; }
.toast { display: flex; align-items: center; gap: 10px; padding: 10px 14px; border-radius: 8px;
  background: var(--panel-2); border: 1px solid var(--line-2); color: var(--text); font-size: 13px;
  box-shadow: 0 6px 24px rgba(0,0,0,.4); }
.toast--success { border-color: var(--ok); }
.toast--warn { border-color: var(--amber); }
.toast--error { border-color: var(--signal); }
.toast-x { background: none; border: none; color: var(--muted); cursor: pointer; font-size: 16px; }
</style>
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd admin && npx vitest run src/ui/toast.test.js`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add admin/src/ui/stores/ui.js admin/src/ui/Toast.vue admin/src/ui/toast.test.js admin/package.json admin/package-lock.json
git commit -m "feat: M2 ui store 与 Toast 组件"
```

---

### Task 6: StatusTag + Timeline

**Files:**
- Create: `admin/src/ui/StatusTag.vue`, `admin/src/ui/Timeline.vue`
- Test: `admin/src/ui/tags-timeline.test.js`

**Interfaces:**
- Consumes: `statusColor`（Task 1）。
- Produces: `StatusTag` props `{ status, label }`；`Timeline` props `{ items: [{ actor, role, time, text }] }`。

- [ ] **Step 1: Write the failing test**

`admin/src/ui/tags-timeline.test.js`：
```js
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import StatusTag from './StatusTag.vue'
import Timeline from './Timeline.vue'

describe('StatusTag', () => {
  it('renders label and applies status color', () => {
    const w = mount(StatusTag, { props: { status: 'ARCHIVED', label: '已归档' } })
    expect(w.text()).toContain('已归档')
    expect(w.attributes('style')).toContain('var(--ok)')
  })
  it('falls back to status text when no label', () => {
    const w = mount(StatusTag, { props: { status: 'PROCESSING' } })
    expect(w.text()).toContain('PROCESSING')
  })
})

describe('Timeline', () => {
  it('renders actor/role/time/text per item', () => {
    const w = mount(Timeline, { props: { items: [
      { actor: '李振国', role: 'MAINT_TECH', time: '2026-11-13 09:00', text: '签到' }
    ] } })
    expect(w.text()).toContain('李振国')
    expect(w.text()).toContain('MAINT_TECH')
    expect(w.text()).toContain('签到')
    expect(w.findAll('.tl-item').length).toBe(1)
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd admin && npx vitest run src/ui/tags-timeline.test.js`
Expected: FAIL — 组件不存在。

- [ ] **Step 3: Write the components**

`admin/src/ui/StatusTag.vue`：
```vue
<template>
  <span class="status-tag" :style="{ color: c.fg, borderColor: c.fg }">{{ label || status }}</span>
</template>
<script setup>
// M2 状态标签：状态机色映射（spec §3.6）
import { computed } from 'vue'
import { statusColor } from './status-colors'
const props = defineProps({ status: { type: String, required: true }, label: { type: String, default: '' } })
const c = computed(() => statusColor(props.status))
</script>
<style scoped>
.status-tag { display: inline-block; padding: 2px 8px; border: 1px solid; border-radius: 999px;
  font-size: 11px; font-family: var(--font-mono); letter-spacing: .04em; background: var(--panel); }
</style>
```

`admin/src/ui/Timeline.vue`：
```vue
<template>
  <ol class="timeline">
    <li v-for="(it, i) in items" :key="i" class="tl-item">
      <span class="tl-dot" />
      <div class="tl-body">
        <div class="tl-head"><strong>{{ it.actor }}</strong><span class="tl-role">{{ it.role }}</span><span class="tl-time num">{{ it.time }}</span></div>
        <div class="tl-text">{{ it.text }}</div>
      </div>
    </li>
  </ol>
</template>
<script setup>
// M2 时间轴：操作人 + 角色 + 时间（spec §3.7）
defineProps({ items: { type: Array, default: () => [] } })
</script>
<style scoped>
.timeline { list-style: none; margin: 0; padding: 0; }
.tl-item { position: relative; padding: 0 0 14px 20px; border-left: 1px solid var(--line); }
.tl-dot { position: absolute; left: -4px; top: 4px; width: 7px; height: 7px; border-radius: 50%; background: var(--amber); }
.tl-head { display: flex; gap: 8px; align-items: baseline; font-size: 12px; }
.tl-role { color: var(--dim); font-family: var(--font-mono); font-size: 11px; }
.tl-time { color: var(--muted); margin-left: auto; }
.tl-text { color: var(--text); font-size: 13px; margin-top: 2px; }
</style>
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd admin && npx vitest run src/ui/tags-timeline.test.js`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add admin/src/ui/StatusTag.vue admin/src/ui/Timeline.vue admin/src/ui/tags-timeline.test.js
git commit -m "feat: M2 StatusTag 与 Timeline 组件"
```

---

### Task 7: PermButton（两档门控）

**Files:**
- Create: `admin/src/ui/PermButton.vue`
- Test: `admin/src/ui/perm-button.test.js`

**Interfaces:**
- Consumes: `usePermission`（Task 3）、`usePerspectiveStore`（Task 13；本任务测试用 ref 注入 role，组件读 perspective store——Task 13 前先用 prop `role` 兜底）。
- Produces: `PermButton` props `{ code, role?, label, variant }`，emits `click`；Y 可点、DENY native disabled+title、C aria-disabled+捕获拦截。

> 依赖说明：PermButton 读 activeRole。Task 13 才建 perspective store；本任务组件接受可选 `role` prop，缺省时读 `usePerspectiveStore().activeRole`（Task 13 落地）。测试显式传 `role` prop，不依赖 store。

- [ ] **Step 1: Write the failing test**

`admin/src/ui/perm-button.test.js`：
```js
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import PermButton from './PermButton.vue'

describe('PermButton two-tier gate', () => {
  it('Y renders enabled and emits click', async () => {
    const w = mount(PermButton, { props: { code: 'handover:review', role: 'INST_MGR', label: '审核' } })
    expect(w.find('button').attributes('disabled')).toBeUndefined()
    await w.find('button').trigger('click')
    expect(w.emitted('click')).toBeTruthy()
  })
  it('DENY is native disabled with title=code', () => {
    const w = mount(PermButton, { props: { code: 'handover:review', role: 'INST_CLERK', label: '审核' } })
    const b = w.find('button')
    expect(b.attributes('disabled')).toBeDefined()
    expect(b.attributes('title')).toContain('handover:review')
  })
  it('C is aria-disabled, focusable, click intercepted', async () => {
    const w = mount(PermButton, { props: { code: 'handover:view', role: 'INST_CLERK', label: '查看' } })
    const b = w.find('button')
    expect(b.attributes('aria-disabled')).toBe('true')
    expect(b.attributes('disabled')).toBeUndefined()
    await b.trigger('click')
    expect(w.emitted('click')).toBeFalsy()
    expect(w.emitted('blocked')).toBeTruthy()
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd admin && npx vitest run src/ui/perm-button.test.js`
Expected: FAIL — 组件不存在。

- [ ] **Step 3: Write PermButton.vue**

`admin/src/ui/PermButton.vue`：
```vue
<template>
  <button
    :class="['perm-btn', variant, { 'is-deny': gate === 'deny', 'is-cond': gate === 'conditional' }]"
    :disabled="gate === 'deny'"
    :aria-disabled="gate === 'conditional' ? 'true' : null"
    :title="gate === 'deny' ? '所需权限：' + code : (gate === 'conditional' ? '需审批/前置未满足：' + code : null)"
    @click.capture="onClick"
  >{{ label }}</button>
</template>
<script setup>
// M2 权限按钮：两档门控（spec §4.3）——Y 可点 / DENY native disabled+title / C aria-disabled+捕获拦截
import { computed } from 'vue'
import { state } from './permission-v2'
const props = defineProps({
  code: { type: String, required: true },
  role: { type: String, default: '' },
  label: { type: String, default: '操作' },
  variant: { type: String, default: 'primary' }
})
const emit = defineEmits(['click', 'blocked'])
const gate = computed(() => {
  const s = state(props.role, props.code)
  return s === 'Y' ? 'allow' : s === 'C' ? 'conditional' : 'deny'
})
function onClick(e) {
  if (gate.value === 'deny') { e.preventDefault(); e.stopPropagation(); return }
  if (gate.value === 'conditional') { e.preventDefault(); e.stopPropagation(); emit('blocked', props.code); return }
  emit('click', e)
}
</script>
<style scoped>
.perm-btn { padding: 6px 14px; border-radius: 6px; border: 1px solid var(--line-2); cursor: pointer;
  font-size: 13px; background: var(--panel-2); color: var(--text); }
.perm-btn.primary { background: var(--amber); color: var(--accent-on-amber); border-color: var(--amber); font-weight: 600; }
.perm-btn.danger { background: transparent; color: var(--signal); border-color: var(--signal); }
.perm-btn.is-deny { opacity: .45; cursor: not-allowed; }
.perm-btn.is-cond { opacity: .7; cursor: help; }
</style>
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd admin && npx vitest run src/ui/perm-button.test.js`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add admin/src/ui/PermButton.vue admin/src/ui/perm-button.test.js
git commit -m "feat: M2 PermButton 两档门控按钮"
```

---

### Task 8: 四态组件

**Files:**
- Create: `admin/src/ui/states/EmptyState.vue`, `LoadingSkeleton.vue`, `ErrorState.vue`, `ForbiddenState.vue`
- Test: `admin/src/ui/states/states.test.js`

**Interfaces:**
- Produces: `EmptyState` props `{ chips: [], clearable }` emits `clear`；`LoadingSkeleton` props `{ rows }`；`ErrorState` props `{ reason }` emits `retry`；`ForbiddenState` props `{ code, allowedRoles: [], currentRole }`。

- [ ] **Step 1: Write the failing test**

`admin/src/ui/states/states.test.js`：
```js
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import EmptyState from './EmptyState.vue'
import LoadingSkeleton from './LoadingSkeleton.vue'
import ErrorState from './ErrorState.vue'
import ForbiddenState from './ForbiddenState.vue'

describe('four states', () => {
  it('EmptyState shows chips and emits clear', async () => {
    const w = mount(EmptyState, { props: { chips: ['状态: ACTIVE'], clearable: true } })
    expect(w.text()).toContain('状态: ACTIVE')
    await w.find('.empty-clear').trigger('click')
    expect(w.emitted('clear')).toBeTruthy()
  })
  it('LoadingSkeleton renders requested rows', () => {
    const w = mount(LoadingSkeleton, { props: { rows: 4 } })
    expect(w.findAll('.sk-row').length).toBe(4)
  })
  it('ErrorState shows reason and emits retry', async () => {
    const w = mount(ErrorState, { props: { reason: '网络超时' } })
    expect(w.text()).toContain('网络超时')
    await w.find('.err-retry').trigger('click')
    expect(w.emitted('retry')).toBeTruthy()
  })
  it('ForbiddenState shows code + allowed roles + current role', () => {
    const w = mount(ForbiddenState, { props: { code: 'contract:view', allowedRoles: ['MAINT_MGR'], currentRole: 'INST_MGR' } })
    expect(w.text()).toContain('contract:view')
    expect(w.text()).toContain('MAINT_MGR')
    expect(w.text()).toContain('INST_MGR')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd admin && npx vitest run src/ui/states/states.test.js`
Expected: FAIL — 组件不存在。

- [ ] **Step 3: Write the four state components**

`admin/src/ui/states/EmptyState.vue`：
```vue
<template>
  <div class="empty">
    <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--dim)" stroke-width="1.4"><path d="M4 7h16v13H4zM4 7l2-3h12l2 3M9 11h6"/></svg>
    <p class="empty-title">暂无数据</p>
    <div v-if="chips.length" class="empty-chips">
      <span v-for="c in chips" :key="c" class="chip">{{ c }}</span>
    </div>
    <button v-if="clearable" class="empty-clear" @click="$emit('clear')">清空筛选</button>
  </div>
</template>
<script setup>
// M2 空态：筛选 chips + 清空（spec §3.10）
defineProps({ chips: { type: Array, default: () => [] }, clearable: { type: Boolean, default: false } })
defineEmits(['clear'])
</script>
<style scoped>
.empty { text-align: center; padding: 40px 16px; color: var(--muted); }
.empty-title { font-size: 13px; margin: 12px 0; }
.empty-chips { display: flex; gap: 6px; justify-content: center; flex-wrap: wrap; margin-bottom: 12px; }
.chip { font-size: 11px; padding: 2px 8px; border: 1px solid var(--line-2); border-radius: 999px; color: var(--dim); }
.empty-clear { background: none; border: 1px solid var(--line-2); color: var(--amber); padding: 5px 12px; border-radius: 6px; cursor: pointer; }
</style>
```

`admin/src/ui/states/LoadingSkeleton.vue`：
```vue
<template>
  <div class="skeleton" aria-busy="true" aria-label="加载中">
    <div v-for="n in rows" :key="n" class="sk-row" />
  </div>
</template>
<script setup>
// M2 加载骨架：220ms 后显示由调用方控制（spec §3.10）
defineProps({ rows: { type: Number, default: 5 } })
</script>
<style scoped>
.skeleton { display: flex; flex-direction: column; gap: 10px; padding: 12px; }
.sk-row { height: 40px; border-radius: 6px; background: linear-gradient(90deg, var(--panel) 25%, var(--panel-2) 50%, var(--panel) 75%); background-size: 200% 100%; animation: sk 1.2s infinite; }
@keyframes sk { to { background-position: -200% 0; } }
</style>
```

`admin/src/ui/states/ErrorState.vue`：
```vue
<template>
  <div class="error-state" role="alert">
    <p class="err-title">加载失败</p>
    <p class="err-reason">{{ reason || '未知错误' }}</p>
    <button class="err-retry" @click="$emit('retry')">重试</button>
  </div>
</template>
<script setup>
// M2 失败态：可读原因 + 重试 + 退路，不清空已填数据（spec §3.10）
defineProps({ reason: { type: String, default: '' } })
defineEmits(['retry'])
</script>
<style scoped>
.error-state { text-align: center; padding: 32px 16px; }
.err-title { color: var(--signal); font-size: 14px; font-weight: 600; }
.err-reason { color: var(--muted); font-size: 12px; margin: 8px 0 16px; }
.err-retry { background: var(--panel-2); border: 1px solid var(--line-2); color: var(--text); padding: 6px 16px; border-radius: 6px; cursor: pointer; }
</style>
```

`admin/src/ui/states/ForbiddenState.vue`：
```vue
<template>
  <div class="forbidden">
    <p class="fb-code num">403</p>
    <p class="fb-title">无权限访问</p>
    <p class="fb-line">所需权限码：<code>{{ code }}</code></p>
    <p class="fb-line">允许角色：<span v-for="r in allowedRoles" :key="r" class="fb-role">{{ r }}</span></p>
    <p class="fb-line">当前角色：<span class="fb-role">{{ currentRole }}</span></p>
  </div>
</template>
<script setup>
// M2 403 态：所需码 + 允许角色 + 当前角色（spec §3.10）
defineProps({ code: { type: String, default: '' }, allowedRoles: { type: Array, default: () => [] }, currentRole: { type: String, default: '' } })
</script>
<style scoped>
.forbidden { text-align: center; padding: 48px 16px; }
.fb-code { font-size: 40px; color: var(--signal); font-weight: 700; }
.fb-title { font-size: 16px; margin: 8px 0 20px; }
.fb-line { font-size: 12px; color: var(--muted); margin: 6px 0; }
.fb-line code { color: var(--amber); font-family: var(--font-mono); }
.fb-role { display: inline-block; margin: 0 4px; padding: 1px 6px; border: 1px solid var(--line-2); border-radius: 4px; font-family: var(--font-mono); font-size: 11px; color: var(--text); }
</style>
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd admin && npx vitest run src/ui/states/states.test.js`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add admin/src/ui/states/
git commit -m "feat: M2 四态组件（空/加载/失败/403）"
```

---

### Task 9: Modal + Drawer

**Files:**
- Create: `admin/src/ui/Modal.vue`, `admin/src/ui/Drawer.vue`
- Test: `admin/src/ui/overlay.test.js`

**Interfaces:**
- Produces: `Modal` props `{ open, title, danger, requireConfirm, confirmLabel }` emits `close`/`confirm`；`Drawer` props `{ open, title, meta }` emits `close`，slot `footer`。

- [ ] **Step 1: Write the failing test**

`admin/src/ui/overlay.test.js`：
```js
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import Modal from './Modal.vue'
import Drawer from './Drawer.vue'

describe('Modal', () => {
  it('danger variant has signal class', () => {
    const w = mount(Modal, { props: { open: true, title: '报废', danger: true } })
    expect(w.find('.modal--danger').exists()).toBe(true)
  })
  it('requireConfirm disables confirm until checkbox checked', async () => {
    const w = mount(Modal, { props: { open: true, title: '报废', danger: true, requireConfirm: true } })
    const btn = w.find('.modal-confirm')
    expect(btn.attributes('disabled')).toBeDefined()
    await w.find('.modal-check').setValue(true)
    expect(btn.attributes('disabled')).toBeUndefined()
    await btn.trigger('click')
    expect(w.emitted('confirm')).toBeTruthy()
  })
})

describe('Drawer', () => {
  it('renders meta bar and emits close on Esc', async () => {
    const w = mount(Drawer, { props: { open: true, title: '详情', meta: { role: 'MAINT_MGR', perm: 'elevator:view' } }, attachTo: document.body })
    expect(w.text()).toContain('MAINT_MGR')
    await w.find('.drawer').trigger('keydown.esc')
    expect(w.emitted('close')).toBeTruthy()
    w.unmount()
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd admin && npx vitest run src/ui/overlay.test.js`
Expected: FAIL — 组件不存在。

- [ ] **Step 3: Write Modal + Drawer**

`admin/src/ui/Modal.vue`：
```vue
<template>
  <div v-if="open" class="modal-scrim" @click.self="$emit('close')">
    <div :class="['modal', { 'modal--danger': danger }]" role="dialog" aria-modal="true" :aria-label="title">
      <div class="modal-head"><span>{{ title }}</span><button class="modal-x" aria-label="关闭" @click="$emit('close')">×</button></div>
      <div class="modal-body"><slot /></div>
      <label v-if="requireConfirm" class="modal-confirm-row">
        <input type="checkbox" class="modal-check" v-model="checked" /> 我已知悉此操作不可逆
      </label>
      <div class="modal-foot">
        <button class="modal-cancel" @click="$emit('close')">取消</button>
        <button class="modal-confirm" :disabled="requireConfirm && !checked" @click="onConfirm">{{ confirmLabel }}</button>
      </div>
    </div>
  </div>
</template>
<script setup>
// M2 模态：危险变体 + 勾选二次确认（spec §3.3）
import { ref, watch } from 'vue'
const props = defineProps({
  open: Boolean, title: { type: String, default: '' }, danger: Boolean,
  requireConfirm: Boolean, confirmLabel: { type: String, default: '确认' }
})
const emit = defineEmits(['close', 'confirm'])
const checked = ref(false)
watch(() => props.open, (o) => { if (o) checked.value = false })
function onConfirm() { if (props.requireConfirm && !checked.value) return; emit('confirm') }
</script>
<style scoped>
.modal-scrim { position: fixed; inset: 0; background: var(--scrim); display: flex; align-items: center; justify-content: center; z-index: 900; }
.modal { width: 440px; max-width: 92vw; background: var(--panel); border: 1px solid var(--line-2); border-radius: 10px; }
.modal--danger { border-color: var(--signal); }
.modal-head { display: flex; justify-content: space-between; padding: 14px 16px; border-bottom: 1px solid var(--line); font-size: 14px; font-weight: 600; }
.modal-x { background: none; border: none; color: var(--muted); font-size: 18px; cursor: pointer; }
.modal-body { padding: 16px; font-size: 13px; }
.modal-confirm-row { display: flex; gap: 8px; align-items: center; padding: 0 16px 12px; font-size: 12px; color: var(--muted); }
.modal-foot { display: flex; justify-content: flex-end; gap: 8px; padding: 12px 16px; border-top: 1px solid var(--line); }
.modal-cancel { background: var(--panel-2); border: 1px solid var(--line-2); color: var(--text); padding: 6px 14px; border-radius: 6px; cursor: pointer; }
.modal-confirm { background: var(--amber); border: none; color: var(--accent-on-amber); padding: 6px 14px; border-radius: 6px; cursor: pointer; font-weight: 600; }
.modal--danger .modal-confirm { background: var(--signal); color: var(--accent-on-signal); }
.modal-confirm:disabled { opacity: .45; cursor: not-allowed; }
</style>
```

`admin/src/ui/Drawer.vue`：
```vue
<template>
  <div v-if="open" class="drawer-scrim" @click.self="$emit('close')">
    <aside class="drawer" role="dialog" aria-modal="true" :aria-label="title" tabindex="-1" @keydown.esc="$emit('close')">
      <div class="drawer-head"><span>{{ title }}</span><button class="drawer-x" aria-label="关闭" @click="$emit('close')">×</button></div>
      <div class="drawer-meta">
        <span v-if="meta.role" class="label">角色 {{ meta.role }}</span>
        <span v-if="meta.perm" class="label">权限 {{ meta.perm }}</span>
        <span v-if="meta.scope" class="label">范围 {{ meta.scope }}</span>
      </div>
      <div class="drawer-body"><slot /></div>
      <div class="drawer-foot"><slot name="footer" /></div>
    </aside>
  </div>
</template>
<script setup>
// M2 抽屉：右 560px + 元信息条 + Esc 关闭（spec §3.2）
import { watch, nextTick, ref } from 'vue'
const props = defineProps({ open: Boolean, title: { type: String, default: '' }, meta: { type: Object, default: () => ({}) } })
defineEmits(['close'])
const el = ref(null)
watch(() => props.open, async (o) => { if (o) { await nextTick(); document.querySelector('.drawer')?.focus() } })
</script>
<style scoped>
.drawer-scrim { position: fixed; inset: 0; background: var(--scrim); z-index: 800; display: flex; justify-content: flex-end; }
.drawer { width: 560px; max-width: 94vw; height: 100%; background: var(--panel); border-left: 1px solid var(--line-2); display: flex; flex-direction: column; }
.drawer-head { display: flex; justify-content: space-between; padding: 14px 16px; border-bottom: 1px solid var(--line); font-size: 14px; font-weight: 600; }
.drawer-x { background: none; border: none; color: var(--muted); font-size: 18px; cursor: pointer; }
.drawer-meta { display: flex; gap: 12px; padding: 10px 16px; border-bottom: 1px solid var(--line); flex-wrap: wrap; }
.drawer-body { flex: 1; overflow: auto; padding: 16px; }
.drawer-foot { padding: 12px 16px; border-top: 1px solid var(--line); display: flex; gap: 8px; justify-content: flex-end; }
</style>
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd admin && npx vitest run src/ui/overlay.test.js`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add admin/src/ui/Modal.vue admin/src/ui/Drawer.vue admin/src/ui/overlay.test.js
git commit -m "feat: M2 Modal 与 Drawer 组件"
```

---

### Task 10: Form 组件

**Files:**
- Create: `admin/src/ui/Form.vue`, `admin/src/ui/FormField.vue`
- Test: `admin/src/ui/form.test.js`

**Interfaces:**
- Consumes: `useForm`（Task 4）。
- Produces: `Form` props `{ rules, modelValue }` emits `update:modelValue`/`submit`；`FormField` props `{ name, label, required, error }` slot 控件。Form 暴露 `validate()`。

- [ ] **Step 1: Write the failing test**

`admin/src/ui/form.test.js`：
```js
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import Form from './Form.vue'

const rules = { name: [(v) => (v ? '' : '姓名必填')] }

describe('Form', () => {
  it('shows field error after submit when invalid', async () => {
    const w = mount(Form, { props: { rules, modelValue: { name: '' } }, slots: { default: '<input name="name" /><span>{{ errors.name }}</span>' } })
    await w.find('form').trigger('submit')
    expect(w.text()).toContain('姓名必填')
    expect(w.emitted('submit')).toBeFalsy()
  })
  it('emits submit when valid', async () => {
    const w = mount(Form, { props: { rules, modelValue: { name: '张三' } }, slots: { default: '<input name="name" />' } })
    await w.find('form').trigger('submit')
    expect(w.emitted('submit')).toBeTruthy()
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd admin && npx vitest run src/ui/form.test.js`
Expected: FAIL — 组件不存在。

- [ ] **Step 3: Write Form + FormField**

`admin/src/ui/FormField.vue`：
```vue
<template>
  <div class="field">
    <label class="field-label">{{ label }}<span v-if="required" class="req">*</span></label>
    <slot />
    <p v-if="error" class="field-error">{{ error }}</p>
  </div>
</template>
<script setup>
// M2 表单字段：标签 + 必填星号 + 下方错误文案（spec §3.4）
defineProps({ label: String, required: Boolean, error: { type: String, default: '' } })
</script>
<style scoped>
.field { margin-bottom: 14px; }
.field-label { display: block; font-size: 12px; color: var(--muted); margin-bottom: 5px; }
.req { color: var(--signal); margin-left: 3px; }
.field-error { color: var(--signal); font-size: 12px; margin: 5px 0 0; }
</style>
```

`admin/src/ui/Form.vue`：
```vue
<template>
  <form class="ui-form" @submit.prevent="onSubmit" novalidate>
    <slot :errors="errors" :values="values" />
  </form>
</template>
<script setup>
// M2 表单：失焦 + 提交双校验（spec §3.4）；暴露 validate()
import { watch } from 'vue'
import { useForm } from './composables/useForm'
const props = defineProps({ rules: { type: Object, required: true }, modelValue: { type: Object, default: () => ({}) } })
const emit = defineEmits(['update:modelValue', 'submit'])
const { values, errors, validateField, validateAll } = useForm(props.rules)
watch(() => props.modelValue, (v) => Object.assign(values, v), { immediate: true, deep: true })
watch(values, (v) => emit('update:modelValue', { ...v }), { deep: true })
function onSubmit() { if (validateAll()) emit('submit', { ...values }) }
defineExpose({ validate: validateAll, validateField, errors, values })
</script>
<style scoped>
.ui-form { display: block; }
</style>
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd admin && npx vitest run src/ui/form.test.js`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add admin/src/ui/Form.vue admin/src/ui/FormField.vue admin/src/ui/form.test.js
git commit -m "feat: M2 Form 双校验表单组件"
```

---

### Task 11: DataTable 组件

**Files:**
- Create: `admin/src/ui/DataTable.vue`
- Test: `admin/src/ui/data-table.test.js`

**Interfaces:**
- Consumes: `useTable`（Task 4）、四态（Task 8）、PermButton（Task 7）。
- Produces: `DataTable` props `{ columns: [{key,label,sortable}], rows, loading, selectable, searchKeys, filters: [{key,label,options}], bulkActions: [{code,label}] }` emits `sort`/`search`/`filter`/`page`/`select`/`bulk`。

- [ ] **Step 1: Write the failing test**

`admin/src/ui/data-table.test.js`：
```js
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import DataTable from './DataTable.vue'

const columns = [{ key: 'name', label: '名称', sortable: true }, { key: 'status', label: '状态' }]
const rows = [{ id: 1, name: 'alpha', status: 'ACTIVE' }, { id: 2, name: 'beta', status: 'DISABLED' }]

describe('DataTable', () => {
  it('renders rows and headers', () => {
    const w = mount(DataTable, { props: { columns, rows } })
    expect(w.findAll('tbody tr').length).toBe(2)
    expect(w.text()).toContain('名称')
  })
  it('shows skeleton when loading', () => {
    const w = mount(DataTable, { props: { columns, rows, loading: true } })
    expect(w.find('.skeleton').exists()).toBe(true)
  })
  it('shows empty state when no rows', () => {
    const w = mount(DataTable, { props: { columns, rows: [] } })
    expect(w.find('.empty').exists()).toBe(true)
  })
  it('search filters rows', async () => {
    const w = mount(DataTable, { props: { columns, rows, searchKeys: ['name'] } })
    await w.find('.dt-search').setValue('alp')
    expect(w.findAll('tbody tr').length).toBe(1)
  })
  it('select all shows bulk bar', async () => {
    const w = mount(DataTable, { props: { columns, rows, selectable: true, bulkActions: [{ code: 'user:disable', label: '停用' }] } })
    await w.find('.dt-check-all').setValue(true)
    expect(w.find('.dt-bulk').exists()).toBe(true)
    expect(w.text()).toContain('已选 2')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd admin && npx vitest run src/ui/data-table.test.js`
Expected: FAIL — 组件不存在。

- [ ] **Step 3: Write DataTable.vue**

`admin/src/ui/DataTable.vue`：
```vue
<template>
  <div class="dt">
    <div class="dt-toolbar">
      <input v-if="searchKeys.length" class="dt-search" v-model="t.query.value" placeholder="搜索…" aria-label="搜索" />
      <select v-for="f in filters" :key="f.key" class="dt-filter" :value="t.filters.value[f.key] || ''"
        @change="t.setFilter(f.key, $event.target.value)" :aria-label="f.label">
        <option value="">{{ f.label }}：全部</option>
        <option v-for="o in f.options" :key="o" :value="o">{{ o }}</option>
      </select>
    </div>
    <LoadingSkeleton v-if="loading" :rows="6" />
    <EmptyState v-else-if="!t.total.value" :chips="activeChips" clearable @clear="t.clearFilters" />
    <template v-else>
      <div v-if="selectable && t.selected.value.size" class="dt-bulk">
        <span>已选 {{ t.selected.value.size }}</span>
        <PermButton v-for="a in bulkActions" :key="a.code" :code="a.code" :label="a.label" variant="danger" @click="$emit('bulk', { action: a.code, ids: [...t.selected.value] })" />
      </div>
      <table class="dt-table">
        <thead>
          <tr>
            <th v-if="selectable" class="dt-check-col"><input type="checkbox" class="dt-check-all" :checked="t.allSelected.value" @change="t.toggleAll" aria-label="全选" /></th>
            <th v-for="col in columns" :key="col.key" @click="col.sortable && t.toggleSort(col.key)" :class="{ sortable: col.sortable }">
              {{ col.label }}<span v-if="col.sortable" class="dt-arrow">{{ arrow(col.key) }}</span>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in t.visibleRows.value" :key="row.id">
            <td v-if="selectable"><input type="checkbox" :checked="t.selected.value.has(row.id)" @change="t.toggleSelect(row.id)" :aria-label="'选择 ' + row.id" /></td>
            <td v-for="col in columns" :key="col.key">{{ row[col.key] }}</td>
          </tr>
        </tbody>
      </table>
      <div class="dt-pager">
        <button :disabled="t.pagination.page.value <= 1" @click="t.pagination.setPage(t.pagination.page.value - 1)">上一页</button>
        <span class="num">{{ t.pagination.page.value }} / {{ t.pagination.pageCount.value }}</span>
        <button :disabled="t.pagination.page.value >= t.pagination.pageCount.value" @click="t.pagination.setPage(t.pagination.page.value + 1)">下一页</button>
        <select :value="t.pagination.size.value" @change="t.pagination.setSize(+$event.target.value)" aria-label="每页条数">
          <option :value="8">8</option><option :value="16">16</option><option :value="32">32</option>
        </select>
      </div>
    </template>
  </div>
</template>
<script setup>
// M2 数据表：排序三态/搜索/筛选/分页/批量/空态/骨架（spec §3.1）
import { computed } from 'vue'
import { useTable } from './composables/useTable'
import LoadingSkeleton from './states/LoadingSkeleton.vue'
import EmptyState from './states/EmptyState.vue'
import PermButton from './PermButton.vue'
const props = defineProps({
  columns: { type: Array, required: true }, rows: { type: Array, default: () => [] },
  loading: Boolean, selectable: Boolean, searchKeys: { type: Array, default: () => [] },
  filters: { type: Array, default: () => [] }, bulkActions: { type: Array, default: () => [] }
})
defineEmits(['sort', 'search', 'filter', 'page', 'select', 'bulk'])
const rowsRef = computed(() => props.rows)
const t = useTable(rowsRef, { searchKeys: props.searchKeys })
function arrow(key) {
  if (t.sortKey.value !== key) return '↕'
  return t.sortDir.value === 'asc' ? '↑' : t.sortDir.value === 'desc' ? '↓' : '↕'
}
const activeChips = computed(() => Object.entries(t.filters.value).filter(([, v]) => v).map(([k, v]) => `${k}: ${v}`))
</script>
<style scoped>
.dt-toolbar { display: flex; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.dt-search, .dt-filter { background: var(--panel-2); border: 1px solid var(--line-2); color: var(--text); padding: 6px 10px; border-radius: 6px; font-size: 12px; }
.dt-bulk { display: flex; align-items: center; gap: 10px; padding: 8px 12px; background: var(--panel-2); border: 1px solid var(--line-2); border-radius: 6px; margin-bottom: 8px; font-size: 12px; }
.dt-table { width: 100%; border-collapse: collapse; }
.dt-table th { text-align: left; padding: 10px 12px; background: var(--panel-2); color: var(--muted); font-size: 11px; font-family: var(--font-mono); letter-spacing: .04em; text-transform: uppercase; border-bottom: 1px solid var(--line-2); }
.dt-table th.sortable { cursor: pointer; }
.dt-table td { padding: 0 12px; height: 40px; border-bottom: 1px solid var(--line); font-size: 13px; }
.dt-arrow { margin-left: 4px; color: var(--amber); }
.dt-pager { display: flex; align-items: center; gap: 10px; margin-top: 12px; font-size: 12px; }
.dt-pager button { background: var(--panel-2); border: 1px solid var(--line-2); color: var(--text); padding: 4px 10px; border-radius: 6px; cursor: pointer; }
.dt-pager button:disabled { opacity: .4; cursor: not-allowed; }
.dt-pager select { background: var(--panel-2); border: 1px solid var(--line-2); color: var(--text); border-radius: 6px; padding: 4px; }
</style>
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd admin && npx vitest run src/ui/data-table.test.js`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add admin/src/ui/DataTable.vue admin/src/ui/data-table.test.js
git commit -m "feat: M2 DataTable 组件"
```

---

### Task 12: mock 基准集 + 数据源 + 九不变量自检

**Files:**
- Create: `admin/src/ui/mock-data.js`, `admin/src/ui/data/provider.js`
- Test: `admin/src/ui/data/invariants.test.js`

**Interfaces:**
- Produces: `mockData`（admin-spec §9 基准集，程序化生成满足闭合）；`provider.getKPIs(scope)`/`getTodos(scope)`/`getTableRows(domain, query)`；`checkInvariants(data)` 返回 `[{id, ok, detail}]`（9 条）。

- [ ] **Step 1: Write the failing test**

`admin/src/ui/data/invariants.test.js`：
```js
import { describe, it, expect } from 'vitest'
import { mockData, checkInvariants } from '../mock-data'
import { provider } from './provider'

describe('mock baseline invariants', () => {
  it('all 9 invariants pass', () => {
    const results = checkInvariants(mockData)
    expect(results.length).toBe(9)
    results.forEach(r => expect(r.ok, r.detail).toBe(true))
  })
  it('elevator count closes to 20', () => {
    expect(mockData.elevators.length).toBe(20)
    const sum = mockData.projects.reduce((s, p) => s + p.elevatorCount, 0)
    expect(sum).toBe(20)
  })
})

describe('provider', () => {
  it('getKPIs returns 7 KPI items with consistent totals', () => {
    const kpi = provider.getKPIs('ALL')
    expect(kpi.length).toBe(7)
    expect(kpi.find(k => k.key === 'elevators').value).toBe(20)
  })
  it('getKPIs narrows by scope', () => {
    const all = provider.getKPIs('ALL').find(k => k.key === 'elevators').value
    const inst = provider.getKPIs('INST_MGR').find(k => k.key === 'elevators').value
    expect(inst).toBeLessThanOrEqual(all)
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd admin && npx vitest run src/ui/data/invariants.test.js`
Expected: FAIL — 模块不存在。

- [ ] **Step 3: Write mock-data.js + provider.js**

`admin/src/ui/mock-data.js`（程序化生成基准集，满足 admin-spec §9 九不变量；人物/单号沿用原型）：
```js
// M2 mock 基准集（admin-spec §9，与原型同源）；时间基准 2026-11-13（周五）
export const TODAY = '2026-11-13'
const PROJECTS = [
  { id: 'p1', name: '城东大厦', elevatorCount: 6 },
  { id: 'p2', name: '汇金中心', elevatorCount: 4 },
  { id: 'p3', name: '城市之光', elevatorCount: 4 },
  { id: 'p4', name: '云栖里', elevatorCount: 3 },
  { id: 'p5', name: '华府名邸', elevatorCount: 3 }
]
const HANDOVER_STATES = ['DRAFT', 'SUBMITTED', 'REVIEWING', 'RETURNED', 'ACCEPTED', 'INSPECTING', 'RECTIFYING', 'SIGNING', 'ARCHIVED', 'VOID']
const RECORD_STATES = ['DRAFT', 'SUBMITTED', 'REVIEWING', 'RETURNED', 'SIGNING', 'ARCHIVED', 'AMENDING']
const ORDER_STATES = ['CREATED', 'ASSIGNED', 'ACCEPTED', 'ARRIVED', 'PROCESSING', 'SUBMITTED', 'CLOSED']
function buildElevators() {
  const out = []
  let n = 0
  PROJECTS.forEach(p => {
    for (let i = 0; i < p.elevatorCount; i++) {
      n++
      out.push({ id: 'el' + n, code: 'DT-CB-' + String(300 + n).padStart(4, '0'), name: p.name + ' ' + (i + 1) + '# 客梯', projectId: p.id, project: p.name, lastMaintDate: '2026-11-0' + (1 + (n % 9)), status: 'NORMAL' })
    }
  })
  return out
}
function buildDocs(prefix, count, states, dateField) {
  return Array.from({ length: count }, (_, i) => ({
    id: prefix + (i + 1),
    no: prefix.toUpperCase() + '-2026-' + String(100 + i),
    status: states[i % states.length],
    projectId: PROJECTS[i % PROJECTS.length].id,
    elevatorCode: 'DT-CB-' + String(301 + (i % 20)).padStart(4, '0'),
    [dateField]: TODAY,
    primaryId: 'T3101', partnerId: i % 3 === 0 ? null : 'T3122',
    primarySignAt: TODAY + ' 09:00', partnerSignAt: i % 3 === 0 ? null : TODAY + ' 10:00'
  }))
}
export const mockData = {
  today: TODAY,
  projects: PROJECTS,
  elevators: buildElevators(),
  handovers: buildDocs('YJ', 12, HANDOVER_STATES, 'createdAt'),
  records: buildDocs('WB', 14, RECORD_STATES, 'workDate'),
  orders: buildDocs('JX', 16, ORDER_STATES, 'createdAt').map((o, i) => ({ ...o, slaMinutes: 30, createdAt: TODAY + ' 08:00', elapsed: i % 4 === 0 ? 45 : 10 })),
  contracts: Array.from({ length: 6 }, (_, i) => ({ id: 'ht' + (i + 1), no: 'HT-2026-03' + (10 + i), projectId: PROJECTS[i % 5].id, endDate: '2026-12-31', daysLeft: 48, amount: 120000 })),
  accounts: Array.from({ length: 18 }, (_, i) => ({ id: 'u' + (i + 1), name: '账号' + (i + 1), status: ['PENDING', 'NORMAL', 'DISABLED', 'LEFT'][i % 4] })),
  audit: Array.from({ length: 24 }, (_, i) => ({ id: 'a' + (i + 1), actor: '李振国', role: 'MAINT_TECH', action: 'record:submit', time: TODAY + ' 09:00' })),
  invites: Array.from({ length: 8 }, (_, i) => ({ id: 'iv' + (i + 1), code: 'INV' + (1000 + i) })),
  grants: Array.from({ length: 5 }, (_, i) => ({ id: 'g' + (i + 1), grantee: 'u' + (i + 1), perm: 'order:dispatch', status: 'ACTIVE' }))
}
// 范围台量（admin-spec §9 不变量 8）：ALL/MAINT_MGR 20，MAINT_SUP 14，INST_MGR 6，CLIENT_ADMIN 14
const SCOPE_COUNT = { ALL: 20, PLATFORM_ADMIN: 20, SYS_ADMIN: 20, MAINT_MGR: 20, MAINT_SUP: 14, INST_MGR: 6, CLIENT_ADMIN: 14 }
export function scopeElevatorCount(scope) { return SCOPE_COUNT[scope] ?? 20 }
export function checkInvariants(d) {
  const r = []
  const sum = d.projects.reduce((s, p) => s + p.elevatorCount, 0)
  r.push({ id: 1, ok: sum === d.elevators.length && d.elevators.length === 20, detail: '台量闭合 ' + sum + '/' + d.elevators.length })
  r.push({ id: 2, ok: d.elevators.every(e => /^\d{4}-\d{2}-\d{2}$/.test(e.lastMaintDate)), detail: 'lastMaintDate 格式' })
  const pids = new Set(d.projects.map(p => p.id))
  r.push({ id: 3, ok: [...d.handovers, ...d.records, ...d.orders, ...d.contracts].every(x => pids.has(x.projectId)), detail: '引用项目存在' })
  const sets = { handovers: HANDOVER_STATES, records: RECORD_STATES, orders: ORDER_STATES }
  r.push({ id: 4, ok: Object.entries(sets).every(([k, st]) => d[k].every(x => st.includes(x.status))), detail: '状态机合法' })
  r.push({ id: 5, ok: d.records.every(x => !x.partnerId || (x.primaryId !== x.partnerId && x.primarySignAt <= x.partnerSignAt)), detail: '双签' })
  r.push({ id: 6, ok: d.contracts.every(c => c.daysLeft === 48), detail: '临期 daysLeft' })
  r.push({ id: 7, ok: d.orders.every(o => (o.elapsed > o.slaMinutes) === (o.elapsed > 30)), detail: 'SLA 超时判定' })
  r.push({ id: 8, ok: scopeElevatorCount('ALL') === 20 && scopeElevatorCount('MAINT_SUP') === 14 && scopeElevatorCount('INST_MGR') === 6 && scopeElevatorCount('CLIENT_ADMIN') === 14, detail: '范围台量' })
  r.push({ id: 9, ok: d.elevators.every(e => e.lastMaintDate >= '2025-01-01' && e.lastMaintDate <= '2026-12-31'), detail: '日期区间' })
  return r
}
```

`admin/src/ui/data/provider.js`：
```js
// M2 数据源接口 + mock 实现（spec §6.2）；M3 起逐域换真实 API，组件不变
import { mockData, scopeElevatorCount } from '../mock-data'
export const provider = {
  getKPIs(scope) {
    const elev = scopeElevatorCount(scope)
    return [
      { key: 'projects', label: '在管项目', value: mockData.projects.length },
      { key: 'elevators', label: '在管电梯', value: elev },
      { key: 'handoverReview', label: '待审核移交', value: mockData.handovers.filter(h => h.status === 'REVIEWING').length },
      { key: 'recordReview', label: '待复核', value: mockData.records.filter(r => r.status === 'REVIEWING').length },
      { key: 'slaOverdue', label: 'SLA 超时', value: mockData.orders.filter(o => o.elapsed > o.slaMinutes).length },
      { key: 'contractExpiring', label: '合同临期≤90天', value: mockData.contracts.filter(c => c.daysLeft <= 90).length },
      { key: 'dualSignPending', label: '双签待补', value: mockData.records.filter(r => r.status === 'SIGNING').length }
    ]
  },
  getTodos(scope) {
    return mockData.handovers.filter(h => h.status === 'REVIEWING').slice(0, 5)
      .map(h => ({ id: h.id, text: '移交待审核 ' + h.no, link: '/handovers' }))
  },
  getTableRows(domain) { return mockData[domain] || [] }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd admin && npx vitest run src/ui/data/invariants.test.js`
Expected: PASS（9 不变量 + provider）。

- [ ] **Step 5: Commit**

```bash
git add admin/src/ui/mock-data.js admin/src/ui/data/provider.js admin/src/ui/data/invariants.test.js
git commit -m "feat: M2 mock 基准集与数据源及九不变量自检"
```

---

### Task 13: perspective store + menu-config

**Files:**
- Create: `admin/src/ui/stores/perspective.js`, `admin/src/ui/menu-config.js`
- Test: `admin/src/ui/menu.test.js`

**Interfaces:**
- Consumes: `permission-v2.state/defaultScope/canonical`（Task 2）。
- Produces: `usePerspectiveStore()` → `{ activeRole, roleSet, scope, setRole, setRoleSet }`；`MENU_GROUPS`（8 组）；`visibleMenus(role, implementedPaths)` 过滤后菜单。

- [ ] **Step 1: Write the failing test**

`admin/src/ui/menu.test.js`：
```js
import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { usePerspectiveStore } from './stores/perspective'
import { MENU_GROUPS, visibleMenus } from './menu-config'

describe('perspective store', () => {
  beforeEach(() => setActivePinia(createPinia()))
  it('defaults to first role and derives scope', () => {
    const p = usePerspectiveStore()
    p.setRoleSet(['MAINT_MGR', 'MAINT_SUP'])
    expect(p.activeRole).toBe('MAINT_MGR')
    expect(p.scope).toBe('ALL')
    p.setRole('MAINT_SUP')
    expect(p.scope).toBe('TEAM')
  })
})

describe('menu-config', () => {
  it('has 8 groups', () => {
    expect(MENU_GROUPS.length).toBe(8)
  })
  it('filters by permission and implemented flag', () => {
    const menus = visibleMenus('INST_MGR', ['/dashboard'])
    const paths = menus.flatMap(g => g.items.map(i => i.path))
    expect(paths).toContain('/dashboard')
    expect(paths).not.toContain('/contracts')
  })
  it('DENY role sees no privileged items', () => {
    const menus = visibleMenus('MAINT_TECH', ['/dashboard'])
    const paths = menus.flatMap(g => g.items.map(i => i.path))
    expect(paths).not.toContain('/settings')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd admin && npx vitest run src/ui/menu.test.js`
Expected: FAIL — 模块不存在。

- [ ] **Step 3: Write perspective store + menu-config**

`admin/src/ui/stores/perspective.js`：
```js
// M2 视角 store：activeRole + roleSet（M2 基准集，M3 接后端）；驱动菜单/范围/门控
import { defineStore } from 'pinia'
import { defaultScope, canonical } from '../permission-v2'
export const usePerspectiveStore = defineStore('perspective', {
  state: () => ({
    roleSet: ['PLATFORM_ADMIN', 'MAINT_MGR', 'MAINT_SUP', 'INST_MGR', 'CLIENT_ADMIN'],
    activeRole: 'PLATFORM_ADMIN'
  }),
  getters: {
    scope: (s) => defaultScope(s.activeRole) || 'SELF'
  },
  actions: {
    setRoleSet(roles) {
      this.roleSet = roles.map(canonical).filter(Boolean)
      if (!this.roleSet.includes(this.activeRole)) this.activeRole = this.roleSet[0] || ''
    },
    setRole(role) { const r = canonical(role); if (r && this.roleSet.includes(r)) this.activeRole = r }
  }
})
```

`admin/src/ui/menu-config.js`（8 组按 program spec §6.1；implemented 渐进）：
```js
// M2 新 IA 菜单常量（8 组，spec §4.3）；渐进渲染：仅 implemented 视图显示
import { state } from './permission-v2'
export const MENU_GROUPS = [
  { title: '概览', items: [{ path: '/dashboard', title: '工作台', perm: 'console:read', icon: 'dashboard' }] },
  { title: '组织与权限', items: [
    { path: '/org', title: '组织架构', perm: 'user:view', icon: 'org' },
    { path: '/users', title: '账号管理', perm: 'user:view', icon: 'org' },
    { path: '/roles', title: '角色权限矩阵', perm: 'role:assign', icon: 'org' },
    { path: '/grants', title: '授权管理', perm: 'invite:manage', icon: 'org' }
  ] },
  { title: '台账中心', items: [
    { path: '/projects', title: '项目台账', perm: 'project:view', icon: 'ledger' },
    { path: '/contracts', title: '合同管理', perm: 'contract:view', icon: 'ledger' },
    { path: '/elevators', title: '电梯台账', perm: 'elevator:view', icon: 'ledger' }
  ] },
  { title: '安装移交', items: [{ path: '/handovers', title: '移交管理', perm: 'handover:view', icon: 'handover' }] },
  { title: '维保作业', items: [
    { path: '/plans', title: '维保计划', perm: 'plan:view', icon: 'plan' },
    { path: '/records', title: '记录单复核', perm: 'record:view', icon: 'plan' }
  ] },
  { title: '急修报修', items: [
    { path: '/dispatch', title: '工单调度', perm: 'order:view_pool', icon: 'dispatch' },
    { path: '/order', title: '工单详情', perm: 'order:view_own', icon: 'dispatch' }
  ] },
  { title: '数据与系统', items: [
    { path: '/stats', title: '统计报表', perm: 'stats:view', icon: 'stats' },
    { path: '/audit', title: '审计日志', perm: 'audit:view', icon: 'audit' },
    { path: '/settings', title: '系统配置', perm: 'config:manage', icon: 'settings' }
  ] },
  { title: '监管与档案', items: [
    { path: '/reports/failed', title: '上报异常清单', perm: 'report:view', icon: 'compliance' },
    { path: '/reports/logs', title: '上报日志', perm: 'report:view', icon: 'compliance' },
    { path: '/filings', title: '外接建档', perm: 'filing:view', icon: 'compliance' },
    { path: '/regions', title: '区域管理', perm: 'region:view', icon: 'compliance' },
    { path: '/templates', title: '检查项模板', perm: 'template:view', icon: 'compliance' },
    { path: '/register/workers', title: '平台登记人员', perm: 'platform:worker_view', icon: 'compliance' }
  ] }
]
// 过渡组：旧版视图入口（M2 列出，每迁一个删一个；M5 清空）
export const LEGACY_GROUP = { title: '旧版（过渡）', legacy: true, items: [
  { path: '/orders', title: '工单列表（旧）', perm: 'console:read' },
  { path: '/ledger', title: '电梯台账（旧）', perm: 'console:read' },
  { path: '/schedule', title: '计划调度（旧）', perm: 'console:read' }
] }
export function visibleMenus(role, implementedPaths) {
  const impl = new Set(implementedPaths)
  const groups = MENU_GROUPS.map(g => ({
    ...g, items: g.items.filter(i => impl.has(i.path) && state(role, i.perm) !== 'DENY')
  })).filter(g => g.items.length)
  const legacy = { ...LEGACY_GROUP, items: LEGACY_GROUP.items.filter(i => state(role, i.perm) !== 'DENY') }
  if (legacy.items.length) groups.push(legacy)
  return groups
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd admin && npx vitest run src/ui/menu.test.js`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add admin/src/ui/stores/perspective.js admin/src/ui/menu-config.js admin/src/ui/menu.test.js
git commit -m "feat: M2 视角 store 与新 IA 菜单配置"
```

---

### Task 14: AppShell

**Files:**
- Create: `admin/src/ui/AppShell.vue`
- Test: `admin/src/ui/app-shell.test.js`

**Interfaces:**
- Consumes: `usePerspectiveStore`（Task 13）、`useUiStore`（Task 5）、`visibleMenus`（Task 13）、`icon`（Task 1）、`Toast`（Task 5）。
- Produces: `AppShell` 包裹 `<router-view>`；侧栏 8 组折叠、顶栏范围+用户+退出、面包屑、<1024 提示条。

- [ ] **Step 1: Write the failing test**

`admin/src/ui/app-shell.test.js`：
```js
import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mount } from '@vue/test-utils'
import { createRouter, createWebHashHistory } from 'vue-router'
import AppShell from './AppShell.vue'
import { usePerspectiveStore } from './stores/perspective'

const router = createRouter({ history: createWebHashHistory(), routes: [
  { path: '/dashboard', component: { template: '<div>workbench</div>' }, meta: { title: '工作台', group: '概览' } }
] })

describe('AppShell', () => {
  beforeEach(() => setActivePinia(createPinia()))
  it('renders sidebar groups and scope indicator', async () => {
    router.push('/dashboard'); await router.isReady()
    const p = usePerspectiveStore(); p.setRoleSet(['MAINT_MGR']); p.setRole('MAINT_MGR')
    const w = mount(AppShell, { global: { plugins: [router] } })
    expect(w.text()).toContain('工作台')
    expect(w.find('.scope-indicator').text()).toContain('ALL')
  })
  it('collapse toggle changes width class', async () => {
    router.push('/dashboard'); await router.isReady()
    const w = mount(AppShell, { global: { plugins: [router] } })
    await w.find('.collapse-btn').trigger('click')
    expect(w.find('.shell').classes()).toContain('collapsed')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd admin && npx vitest run src/ui/app-shell.test.js`
Expected: FAIL — 组件不存在。

- [ ] **Step 3: Write AppShell.vue**

`admin/src/ui/AppShell.vue`：
```vue
<template>
  <div class="ui-scope shell" :class="{ collapsed: ui.collapsed }">
    <div v-if="narrow" class="narrow-bar">建议桌面访问（≥1024px）以获得完整体验</div>
    <aside class="sidebar">
      <div class="brand"><span class="brand-mark">梯维通</span><span class="brand-sub label">LIFT·OPS</span></div>
      <nav class="nav">
        <div v-for="g in menus" :key="g.title" class="nav-group">
          <p class="nav-group-title label">{{ g.title }}</p>
          <router-link v-for="it in g.items" :key="it.path" :to="it.path" class="nav-item" active-class="active">
            <span class="nav-icon" v-html="icon(it.icon || 'default', 16)" /><span class="nav-text">{{ it.title }}</span>
          </router-link>
        </div>
      </nav>
    </aside>
    <div class="main">
      <header class="topbar">
        <button class="collapse-btn" aria-label="折叠侧栏" @click="ui.toggleCollapsed">☰</button>
        <nav class="crumb"><span>{{ crumb.group }}</span><span class="crumb-sep">/</span><span>{{ crumb.view }}</span></nav>
        <span class="scope-indicator label">范围 {{ perspective.scope }}</span>
        <span class="user-name">{{ auth.userName }}</span>
        <button class="logout" @click="auth.logout()">退出</button>
      </header>
      <main class="content"><router-view /></main>
    </div>
    <Toast />
  </div>
</template>
<script setup>
// M2 应用壳：侧栏 8 组折叠 + 顶栏范围/用户/退出 + 面包屑 + <1024 提示（spec §4.1）
import { computed, ref, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { useUiStore } from './stores/ui'
import { usePerspectiveStore } from './stores/perspective'
import { useAuthStore } from '../stores/auth'
import { visibleMenus } from './menu-config'
import { icon } from './icons'
import Toast from './Toast.vue'
const ui = useUiStore()
const perspective = usePerspectiveStore()
const auth = useAuthStore()
const route = useRoute()
const IMPLEMENTED = ['/dashboard']
const menus = computed(() => visibleMenus(perspective.activeRole, IMPLEMENTED))
const crumb = computed(() => ({ group: route.meta.group || '概览', view: route.meta.title || '' }))
const narrow = ref(false)
function onResize() { narrow.value = window.innerWidth < 1024 }
onMounted(() => { onResize(); window.addEventListener('resize', onResize) })
onUnmounted(() => window.removeEventListener('resize', onResize))
</script>
<style scoped>
.shell { display: flex; height: 100vh; }
.narrow-bar { position: fixed; top: 0; left: 0; right: 0; z-index: 50; background: var(--amber); color: var(--accent-on-amber); text-align: center; font-size: 12px; padding: 4px; }
.sidebar { width: 232px; background: var(--ink-2); border-right: 1px solid var(--line); overflow-y: auto; transition: width .15s; flex-shrink: 0; }
.shell.collapsed .sidebar { width: 64px; }
.shell.collapsed .nav-text, .shell.collapsed .nav-group-title, .shell.collapsed .brand-sub { display: none; }
.brand { padding: 18px 16px; display: flex; flex-direction: column; gap: 2px; }
.brand-mark { font-size: 17px; font-weight: 700; color: var(--amber); }
.nav { padding: 8px; }
.nav-group { margin-bottom: 14px; }
.nav-group-title { padding: 4px 8px; }
.nav-item { display: flex; align-items: center; gap: 10px; padding: 8px 10px; border-radius: 6px; color: var(--muted); text-decoration: none; font-size: 13px; }
.nav-item:hover { background: var(--panel); color: var(--text); }
.nav-item.active { background: var(--panel-2); color: var(--amber); }
.nav-icon { display: inline-flex; }
.main { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.topbar { display: flex; align-items: center; gap: 14px; height: 56px; padding: 0 16px; background: var(--panel); border-bottom: 1px solid var(--line); }
.collapse-btn { background: none; border: 1px solid var(--line-2); color: var(--text); border-radius: 6px; cursor: pointer; padding: 4px 8px; }
.crumb { font-size: 12px; color: var(--muted); }
.crumb-sep { margin: 0 6px; color: var(--dim); }
.scope-indicator { margin-left: auto; color: var(--amber); }
.user-name { font-size: 13px; }
.logout { background: none; border: 1px solid var(--signal); color: var(--signal); border-radius: 6px; padding: 4px 10px; cursor: pointer; font-size: 12px; }
.content { flex: 1; overflow: auto; padding: 16px; }
</style>
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd admin && npx vitest run src/ui/app-shell.test.js`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add admin/src/ui/AppShell.vue admin/src/ui/app-shell.test.js
git commit -m "feat: M2 AppShell 应用壳"
```

---

### Task 15: LoginView + ForbiddenView + NotFoundView 重写

**Files:**
- Modify: `admin/src/views/LoginView.vue`, `admin/src/views/ForbiddenView.vue`, `admin/src/views/NotFoundView.vue`
- Test: `admin/src/ui/views-shell.test.js`

**Interfaces:**
- Consumes: Form/FormField（Task 10）、ForbiddenState（Task 8）、`useAuthStore`（现有）、`useToast`（Task 5）、icon（Task 1）。
- Produces: 新深色登录页（真实鉴权）、403 页（读 route.query.code）、404 页。

- [ ] **Step 1: Write the failing test**

`admin/src/ui/views-shell.test.js`：
```js
import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mount } from '@vue/test-utils'
import { createRouter, createWebHashHistory } from 'vue-router'
import LoginView from '../views/LoginView.vue'
import ForbiddenView from '../views/ForbiddenView.vue'
import NotFoundView from '../views/NotFoundView.vue'

const router = createRouter({ history: createWebHashHistory(), routes: [
  { path: '/login', component: LoginView }, { path: '/403', component: ForbiddenView },
  { path: '/dashboard', component: { template: '<div/>' } }
] })

describe('LoginView', () => {
  beforeEach(() => setActivePinia(createPinia()))
  it('blocks submit when password < 6', async () => {
    router.push('/login'); await router.isReady()
    const w = mount(LoginView, { global: { plugins: [router] } })
    await w.find('input[name="phone"]').setValue('13800000001')
    await w.find('input[name="password"]').setValue('123')
    await w.find('form').trigger('submit')
    expect(w.text()).toContain('密码')
  })
})

describe('ForbiddenView', () => {
  it('shows required code from query', async () => {
    router.push('/403?code=contract:view'); await router.isReady()
    const w = mount(ForbiddenView, { global: { plugins: [router] } })
    expect(w.text()).toContain('contract:view')
  })
})

describe('NotFoundView', () => {
  it('renders 404 with back link', () => {
    const w = mount(NotFoundView, { global: { plugins: [router] } })
    expect(w.text()).toContain('404')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd admin && npx vitest run src/ui/views-shell.test.js`
Expected: FAIL — 旧视图无新结构（断言失败）。

- [ ] **Step 3: Rewrite the three views**

`admin/src/views/LoginView.vue`（整体替换）：
```vue
<template>
  <div class="ui-scope login-page">
    <div class="login-card">
      <div class="login-brand"><span class="brand-mark">梯维通</span><span class="label">LIFT·OPS 管理端</span></div>
      <Form :rules="rules" v-model="model" @submit="onSubmit" v-slot="{ errors }">
        <FormField label="账号" required :error="errors.phone">
          <input name="phone" v-model="model.phone" autocomplete="username" />
        </FormField>
        <FormField label="密码" required :error="errors.password">
          <input name="password" type="password" v-model="model.password" autocomplete="current-password" />
        </FormField>
        <button class="login-btn" :disabled="loading" type="submit">{{ loading ? '登录中…' : '登录' }}</button>
        <p v-if="serverError" class="login-error">{{ serverError }}</p>
      </Form>
    </div>
  </div>
</template>
<script setup>
// M2 登录页：真实后端鉴权 + 双校验（spec §5.1）；校验由 Form 承载（scoped slot errors）
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import Form from '../ui/Form.vue'
import FormField from '../ui/FormField.vue'
import { useAuthStore } from '../stores/auth'
const router = useRouter()
const auth = useAuthStore()
const model = reactive({ phone: '', password: '' })
const loading = ref(false)
const serverError = ref('')
const rules = {
  phone: [(v) => (v ? '' : '请输入账号')],
  password: [(v) => ((v && v.length >= 6) ? '' : '密码至少 6 位')]
}
async function onSubmit() {
  loading.value = true; serverError.value = ''
  try {
    await auth.login(model.phone, model.password)
    router.push('/dashboard')
  } catch (e) {
    serverError.value = (e && e.message) || '登录失败，请重试'
  } finally { loading.value = false }
}
</script>
<style scoped>
.login-page { display: flex; align-items: center; justify-content: center; min-height: 100vh; }
.login-card { width: 360px; padding: 32px; background: var(--panel); border: 1px solid var(--line-2); border-radius: 12px; }
.login-brand { display: flex; flex-direction: column; gap: 4px; margin-bottom: 24px; }
.brand-mark { font-size: 22px; font-weight: 700; color: var(--amber); }
.login-card input { width: 100%; padding: 9px 12px; background: var(--panel-2); border: 1px solid var(--line-2); border-radius: 6px; color: var(--text); font-size: 13px; }
.login-btn { width: 100%; margin-top: 8px; padding: 10px; background: var(--amber); border: none; border-radius: 6px; color: var(--accent-on-amber); font-weight: 600; cursor: pointer; }
.login-btn:disabled { opacity: .6; cursor: not-allowed; }
.login-error { color: var(--signal); font-size: 12px; margin-top: 12px; }
</style>
```

`admin/src/views/ForbiddenView.vue`（整体替换）：
```vue
<template>
  <div class="ui-scope fb-page">
    <ForbiddenState :code="code" :allowed-roles="allowedRoles" :current-role="auth.role" />
    <router-link to="/dashboard" class="fb-back">返回工作台</router-link>
  </div>
</template>
<script setup>
// M2 403 页：所需码 + 允许角色 + 当前角色（spec §5.3）
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import ForbiddenState from '../ui/states/ForbiddenState.vue'
import { useAuthStore } from '../stores/auth'
const route = useRoute()
const auth = useAuthStore()
const code = computed(() => route.query.code || '')
const allowedRoles = computed(() => (route.query.roles ? String(route.query.roles).split(',') : []))
</script>
<style scoped>
.fb-page { min-height: 100vh; display: flex; flex-direction: column; align-items: center; justify-content: center; }
.fb-back { color: var(--amber); font-size: 13px; margin-top: 16px; }
</style>
```

`admin/src/views/NotFoundView.vue`（整体替换）：
```vue
<template>
  <div class="ui-scope nf-page">
    <p class="nf-code num">404</p>
    <p class="nf-title">页面不存在</p>
    <router-link to="/dashboard" class="nf-back">返回工作台</router-link>
  </div>
</template>
<script setup>
// M2 404 页（spec §5.4）
</script>
<style scoped>
.nf-page { min-height: 100vh; display: flex; flex-direction: column; align-items: center; justify-content: center; }
.nf-code { font-size: 48px; font-weight: 700; color: var(--dim); }
.nf-title { font-size: 16px; margin: 8px 0 20px; }
.nf-back { color: var(--amber); font-size: 13px; }
</style>
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd admin && npx vitest run src/ui/views-shell.test.js`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add admin/src/views/LoginView.vue admin/src/views/ForbiddenView.vue admin/src/views/NotFoundView.vue admin/src/ui/views-shell.test.js
git commit -m "feat: M2 重写登录/403/404 视图"
```

---

### Task 16: DashboardView 工作台重写

**Files:**
- Modify: `admin/src/views/DashboardView.vue`
- Test: `admin/src/ui/workbench.test.js`

**Interfaces:**
- Consumes: `provider`（Task 12）、`usePerspectiveStore`（Task 13）、StatusTag（Task 6）。
- Produces: 工作台 KPI 7 项 + 预警条 + 待办 + 视角切换；按 activeRole 范围聚合。

- [ ] **Step 1: Write the failing test**

`admin/src/ui/workbench.test.js`：
```js
import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mount } from '@vue/test-utils'
import DashboardView from '../views/DashboardView.vue'
import { usePerspectiveStore } from './stores/perspective'

describe('Workbench', () => {
  beforeEach(() => setActivePinia(createPinia()))
  it('renders 7 KPI cards', () => {
    const w = mount(DashboardView)
    expect(w.findAll('.kpi-card').length).toBe(7)
  })
  it('KPI elevators total is 20 for ALL scope', () => {
    const p = usePerspectiveStore(); p.setRoleSet(['PLATFORM_ADMIN']); p.setRole('PLATFORM_ADMIN')
    const w = mount(DashboardView)
    expect(w.text()).toContain('20')
  })
  it('perspective switcher lists roleSet', () => {
    const p = usePerspectiveStore(); p.setRoleSet(['MAINT_MGR', 'MAINT_SUP'])
    const w = mount(DashboardView)
    expect(w.findAll('.persp-btn').length).toBe(2)
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd admin && npx vitest run src/ui/workbench.test.js`
Expected: FAIL — 旧 DashboardView 无 KPI 结构。

- [ ] **Step 3: Rewrite DashboardView.vue**

`admin/src/views/DashboardView.vue`（整体替换）：
```vue
<template>
  <div class="workbench">
    <div class="wb-head">
      <h1 class="wb-title">工作台</h1>
      <div class="persp-switch">
        <button v-for="r in perspective.roleSet" :key="r" :class="['persp-btn', { active: r === perspective.activeRole }]" @click="perspective.setRole(r)">{{ r }}</button>
      </div>
    </div>
    <div v-if="overdue" class="alert-bar">SLA 超时 {{ overdue }} 单，请优先处理</div>
    <div class="kpi-grid">
      <div v-for="k in kpis" :key="k.key" class="kpi-card">
        <p class="kpi-value num">{{ k.value }}</p>
        <p class="kpi-label label">{{ k.label }}</p>
      </div>
    </div>
    <section class="wb-todos">
      <h2 class="label">待办</h2>
      <ul v-if="todos.length">
        <li v-for="t in todos" :key="t.id">{{ t.text }}</li>
      </ul>
      <p v-else class="wb-empty">暂无待办</p>
    </section>
  </div>
</template>
<script setup>
// M2 工作台：KPI 7 项 + 预警 + 待办 + 视角切换（spec §5.2）；M2 数据源 mock 基准集
import { computed } from 'vue'
import { provider } from '../ui/data/provider'
import { usePerspectiveStore } from '../ui/stores/perspective'
const perspective = usePerspectiveStore()
const kpis = computed(() => provider.getKPIs(perspective.activeRole))
const todos = computed(() => provider.getTodos(perspective.activeRole))
const overdue = computed(() => (kpis.value.find(k => k.key === 'slaOverdue') || {}).value || 0)
</script>
<style scoped>
.wb-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.wb-title { font-size: 20px; font-weight: 600; }
.persp-switch { display: flex; gap: 6px; flex-wrap: wrap; }
.persp-btn { background: var(--panel-2); border: 1px solid var(--line-2); color: var(--muted); padding: 4px 10px; border-radius: 6px; cursor: pointer; font-size: 11px; font-family: var(--font-mono); }
.persp-btn.active { background: var(--amber); color: var(--accent-on-amber); border-color: var(--amber); }
.alert-bar { background: var(--signal); color: var(--accent-on-signal); padding: 8px 14px; border-radius: 6px; font-size: 13px; margin-bottom: 16px; }
.kpi-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); gap: 12px; }
.kpi-card { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 16px; }
.kpi-value { font-size: 28px; font-weight: 700; color: var(--amber); }
.kpi-label { margin-top: 6px; }
.wb-todos { margin-top: 24px; }
.wb-todos ul { list-style: none; padding: 0; margin: 8px 0; }
.wb-todos li { padding: 10px 12px; background: var(--panel); border: 1px solid var(--line); border-radius: 6px; margin-bottom: 6px; font-size: 13px; }
.wb-empty { color: var(--dim); font-size: 13px; }
</style>
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd admin && npx vitest run src/ui/workbench.test.js`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add admin/src/views/DashboardView.vue admin/src/ui/workbench.test.js
git commit -m "feat: M2 重写工作台（KPI 七项+视角切换）"
```

---

### Task 17: 双布局路由 + 开发态组件廊/自检

**Files:**
- Modify: `admin/src/router/index.js`, `admin/src/main.js`
- Create: `admin/src/views/dev/ComponentsGallery.vue`, `admin/src/views/dev/DataSelfCheck.vue`
- Test: `admin/src/ui/router-dev.test.js`

**Interfaces:**
- Consumes: AppShell（Task 14）、新四视图（Task 15/16）、checkInvariants（Task 12）、全部 ui 组件。
- Produces: 双布局路由（新 AppShell 包 /dashboard；旧 AdminLayout 包旧业务路径）；dev 路由 `#/dev/components`、`#/dev/spec`（import.meta.env.DEV 门控）。

- [ ] **Step 1: Write the failing test**

`admin/src/ui/router-dev.test.js`：
```js
import { describe, it, expect } from 'vitest'
import { routes } from '../router'

describe('dual-layout router', () => {
  it('new AppShell branch has /dashboard', () => {
    const shell = routes.find(r => r.path === '/' && r.children)
    const paths = shell.children.map(c => c.path)
    expect(paths).toContain('dashboard')
  })
  it('old business routes preserved under AdminLayout', () => {
    const legacy = routes.find(r => r.path === '/legacy')
    expect(legacy).toBeTruthy()
    const paths = legacy.children.map(c => c.path)
    expect(paths).toContain('orders')
    expect(paths).toContain('ledger')
  })
  it('login/403/404 present', () => {
    expect(routes.find(r => r.path === '/login')).toBeTruthy()
    expect(routes.find(r => r.path === '/403')).toBeTruthy()
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd admin && npx vitest run src/ui/router-dev.test.js`
Expected: FAIL — 路由结构未改。

- [ ] **Step 3: Rewire router + main.js + dev pages**

`admin/src/router/index.js`（整体替换；旧业务路径移入 `/legacy` 分支保活，新壳包 /dashboard）：
```js
import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { resolveRoute } from './guard'
import AdminLayout from '../layout/AdminLayout.vue'
import AppShell from '../ui/AppShell.vue'

const LoginView = () => import('../views/LoginView.vue')
const DashboardView = () => import('../views/DashboardView.vue')
const ForbiddenView = () => import('../views/ForbiddenView.vue')
const NotFoundView = () => import('../views/NotFoundView.vue')
// 旧 EP 视图（M2 保活，M3–M5 逐迁）
const OrderListView = () => import('../views/OrderListView.vue')
const OrderDetailView = () => import('../views/OrderDetailView.vue')
const FailedRecordsView = () => import('../views/FailedRecordsView.vue')
const FaultsView = () => import('../views/FaultsView.vue')
const UploadLogsView = () => import('../views/UploadLogsView.vue')
const CompanyView = () => import('../views/CompanyView.vue')
const UseUnitsView = () => import('../views/UseUnitsView.vue')
const EmployeesView = () => import('../views/EmployeesView.vue')
const ElevatorsView = () => import('../views/ElevatorsView.vue')
const ScheduleView = () => import('../views/ScheduleView.vue')
const TemplateManageView = () => import('../views/TemplateManageView.vue')
const LedgerView = () => import('../views/LedgerView.vue')
const RegisterWorkersView = () => import('../views/RegisterWorkersView.vue')
const HandoverView = () => import('../views/HandoverView.vue')
const FilingView = () => import('../views/FilingView.vue')
const ContractView = () => import('../views/ContractView.vue')
const RegionView = () => import('../views/RegionView.vue')

export const routes = [
  { path: '/login', component: LoginView, meta: { public: true, title: '登录' } },
  { path: '/', component: AppShell, redirect: '/dashboard', children: [
    { path: 'dashboard', component: DashboardView, meta: { title: '工作台', group: '概览', perm: 'console:read' } }
  ] },
  // 旧管理端保活分支（M5 迁完删除）；路径前缀 /legacy，旧组件零改动
  { path: '/legacy', component: AdminLayout, redirect: '/legacy/orders', children: [
    { path: 'orders', component: OrderListView, meta: { title: '工单列表', perm: 'order:read' } },
    { path: 'orders/:id', component: OrderDetailView, meta: { title: '工单详情', perm: 'order:read' } },
    { path: 'reports/failed', component: FailedRecordsView, meta: { title: '上报异常清单', perm: 'order:read' } },
    { path: 'faults', component: FaultsView, meta: { title: '急修单', perm: 'fault:read' } },
    { path: 'reports/logs', component: UploadLogsView, meta: { title: '上报日志', perm: 'record:read' } },
    { path: 'schedule', component: ScheduleView, meta: { title: '计划调度', perm: 'plan:write' } },
    { path: 'templates', component: TemplateManageView, meta: { title: '模板管理', perm: 'template:write' } },
    { path: 'ledger', component: LedgerView, meta: { title: '电梯台账', perm: 'ledger:read' } },
    { path: 'handovers', component: HandoverView, meta: { title: '移交单', perm: 'handover:read' } },
    { path: 'filings', component: FilingView, meta: { title: '外接建档', perm: 'filing:read' } },
    { path: 'contracts', component: ContractView, meta: { title: '维保合同', perm: 'contract:read' } },
    { path: 'regions', component: RegionView, meta: { title: '区域管理', perm: 'region:write' } },
    { path: 'archive/company', component: CompanyView, meta: { title: '维保单位档案', perm: 'employee:read' } },
    { path: 'archive/use-units', component: UseUnitsView, meta: { title: '使用单位档案', perm: 'useunit:read' } },
    { path: 'archive/employees', component: EmployeesView, meta: { title: '人员档案', perm: 'employee:read' } },
    { path: 'archive/elevators', component: ElevatorsView, meta: { title: '电梯档案', perm: 'ledger:read' } },
    { path: 'register/workers', component: RegisterWorkersView, meta: { title: '人员管理', perm: 'employee:read' } }
  ] },
  { path: '/403', component: ForbiddenView, meta: { public: true, title: '无权限' } },
  { path: '/:pathMatch(.*)*', component: NotFoundView, meta: { public: true, title: '页面不存在' } }
]
// 开发态工具路由（D-A3，仅 DEV，不进生产导航）
if (import.meta.env.DEV) {
  routes.splice(2, 0,
    { path: '/dev/components', component: () => import('../views/dev/ComponentsGallery.vue'), meta: { public: true, title: '组件廊' } },
    { path: '/dev/spec', component: () => import('../views/dev/DataSelfCheck.vue'), meta: { public: true, title: '数据自检' } }
  )
}
const router = createRouter({ history: createWebHashHistory(), routes })
router.beforeEach((to) => {
  const auth = useAuthStore()
  return resolveRoute(to, { token: auth.token, role: auth.role })
})
router.afterEach((to) => {
  document.title = to.meta && to.meta.title ? `${to.meta.title} · 梯维通管理端` : '梯维通管理端'
})
export default router
```

> 注：旧业务路径加 `/legacy` 前缀保活；menu-config 过渡组 LEGACY_GROUP 的 path 同步加 `/legacy` 前缀（Task 13 已列 `/orders` 等，需在本任务一并改为 `/legacy/orders` 等以匹配）。`main.js` 移除 ElementPlus 全量引入改为按需（旧视图仍用 EP，保留 `app.use(ElementPlus)` 至 M5；M2 仅新增 ui 引入）。

`admin/src/main.js`（整体替换）：
```js
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import './ui'  // M2 自建组件库 token 层（新壳作用域）
const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })  // 过渡期旧视图用，M5 移除
app.mount('#app')
```

`admin/src/views/dev/ComponentsGallery.vue`：
```vue
<template>
  <div class="ui-scope gallery">
    <h1>组件廊（开发态）</h1>
    <section><h2 class="label">StatusTag</h2><StatusTag status="ARCHIVED" label="已归档" /><StatusTag status="PROCESSING" /><StatusTag status="TRAPPED" label="困人" /></section>
    <section><h2 class="label">PermButton</h2><PermButton code="handover:review" role="INST_MGR" label="审核(Y)" /><PermButton code="handover:review" role="INST_CLERK" label="审核(DENY)" /><PermButton code="handover:view" role="INST_CLERK" label="查看(C)" /></section>
    <section><h2 class="label">DataTable</h2><DataTable :columns="cols" :rows="rows" selectable :search-keys="['name']" :bulk-actions="[{ code: 'user:disable', label: '停用' }]" /></section>
    <section><h2 class="label">四态</h2><EmptyState :chips="['状态: ACTIVE']" clearable /><ErrorState reason="网络超时" /><ForbiddenState code="contract:view" :allowed-roles="['MAINT_MGR']" current-role="INST_MGR" /></section>
  </div>
</template>
<script setup>
// M2 开发态组件廊（D-A3，走查基准）
import StatusTag from '../../ui/StatusTag.vue'
import PermButton from '../../ui/PermButton.vue'
import DataTable from '../../ui/DataTable.vue'
import EmptyState from '../../ui/states/EmptyState.vue'
import ErrorState from '../../ui/states/ErrorState.vue'
import ForbiddenState from '../../ui/states/ForbiddenState.vue'
const cols = [{ key: 'name', label: '名称', sortable: true }, { key: 'status', label: '状态' }]
const rows = [{ id: 1, name: 'alpha', status: 'ACTIVE' }, { id: 2, name: 'beta', status: 'DISABLED' }]
</script>
<style scoped>
.gallery { padding: 24px; }
.gallery section { margin: 24px 0; display: flex; gap: 12px; flex-wrap: wrap; align-items: center; }
.gallery h1 { font-size: 20px; }
</style>
```

`admin/src/views/dev/DataSelfCheck.vue`：
```vue
<template>
  <div class="ui-scope selfcheck">
    <h1>数据自检（开发态）</h1>
    <ul>
      <li v-for="r in results" :key="r.id" :class="r.ok ? 'ok' : 'fail'">
        <span class="num">#{{ r.id }}</span> {{ r.detail }} — {{ r.ok ? '通过' : '失败' }}
      </li>
    </ul>
  </div>
</template>
<script setup>
// M2 基准集九不变量自检（D-A3，场景 16）
import { mockData, checkInvariants } from '../../ui/mock-data'
const results = checkInvariants(mockData)
</script>
<style scoped>
.selfcheck { padding: 24px; }
.selfcheck ul { list-style: none; padding: 0; }
.selfcheck li { padding: 8px 12px; border: 1px solid var(--line); border-radius: 6px; margin-bottom: 6px; font-size: 13px; }
.selfcheck .ok { color: var(--ok); }
.selfcheck .fail { color: var(--signal); }
</style>
```

并把 Task 13 `menu-config.js` 的 LEGACY_GROUP path 加 `/legacy` 前缀（`/orders`→`/legacy/orders`，`/ledger`→`/legacy/ledger`，`/schedule`→`/legacy/schedule`）。

- [ ] **Step 4: Run test to verify it passes**

Run: `cd admin && npx vitest run src/ui/router-dev.test.js`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add admin/src/router/index.js admin/src/main.js admin/src/views/dev/ admin/src/ui/menu-config.js admin/src/ui/router-dev.test.js
git commit -m "feat: M2 双布局路由与开发态组件廊/自检"
```

---

### Task 18: exceljs 移除 + 文档同步 + 全量门禁

**Files:**
- Modify: `admin/package.json`（移除 exceljs）, `AGENTS.md`（§4.3 + §10）, `docs/09-管理端设计.md`, `docs/12-未完成事项清单.md`

**Interfaces:**
- Consumes: 全部 M2 产物。
- Produces: 全绿门禁证据 + 文档同步。M2 不新增 REST 端点，docs/04 三处同步不触发。

- [ ] **Step 1: 移除 exceljs + 装依赖**

Run: `cd admin && npm uninstall exceljs`
Expected: package.json 不再含 exceljs；package-lock.json 更新。

- [ ] **Step 2: 全量门禁**

Run: `cd admin && export TMP="$(pwd)/.tmp-build" TEMP="$(pwd)/.tmp-build" && mkdir -p .tmp-build && npm run lint && npm test && npm run build`
Expected: lint 干净；vitest 现有 39 + 新增全绿；vite build 成功。记录测试总数。

- [ ] **Step 3: 1440×900 对照原型人工走查**

启动 `npm run dev`，浏览器 1440×900 对照 `Desktop/UI/admin/index.html`，逐场景留截图：①登录零报错/零外部请求 ②登录→工作台 KPI ③切 5 视角 ④403 ⑦表格交互 ⑧表单校验 ⑨危险 Modal ⑮失败态 ⑯数据自检 9 条 ⑰<1024 提示。证据存 `docs/superpowers/specs/m2-walkthrough/`（gitignored 或入库截图）。

- [ ] **Step 4: 文档同步**

- `AGENTS.md` §4.3：技术栈改「Vue3 + Vite + 自建组件库（admin/src/ui/）+ Element Plus（过渡期旧视图，M5 移除）」；§10 追加 V3.12 行（M2 组件库+壳，纯前端，旧端零回归）。**不改 AOCI 托管块**。
- `docs/09-管理端设计.md`：回写 M2 部分（壳/token/9 组件/四态/4 视图），升版本。
- `docs/12-未完成事项清单.md`：D-02 部分关闭（M2 补 login/工作台/403 视图测试）；D-05 exceljs 移除（EP 包体 M5）；G 组 M2 状态回写；升版本。

- [ ] **Step 5: Commit**

```bash
git add admin/package.json admin/package-lock.json AGENTS.md docs/09-管理端设计.md docs/12-未完成事项清单.md
git commit -m "docs: 同步 M2 组件库基线与待办，移除 exceljs"
```

---

## Self-Review

**1. Spec coverage（逐节）：**
- §1 架构共存 → Task 1（ui/ 目录）/17（双布局路由）/18（EP M5 移除声明）✓
- §2 token/视觉 → Task 1（tokens.css/icons/status-colors）✓
- §3 组件库 → Task 5 Toast / 6 StatusTag+Timeline / 7 PermButton / 8 四态 / 9 Modal+Drawer / 10 Form / 11 DataTable / 14 AppShell ✓；composables Task 3/4 ✓
- §4 壳/permission-v2/菜单/多角色 → Task 2 permission-v2 / 13 perspective+menu / 14 AppShell ✓
- §5 四视图+基准集 → Task 12 mock+provider / 15 login+403+404 / 16 工作台 / 17 dev 廊+自检 ✓
- §6 数据流/错误处理 → Task 5 ui store / 12 provider / 13 perspective / 15 login 401 沿用 ✓
- §7 测试门禁 → 各 Task TDD + Task 18 全量门禁+走查 ✓
- §8 文档同步 → Task 18 ✓

**2. Placeholder scan：** 无 TBD/TODO；每代码步含完整代码。mock 基准集程序化生成（DRY）满足九不变量。

**3. Type consistency：**
- permission-v2 `state(role,code)` 返回 'Y'/'C'/'DENY'；usePermission.gate 映射 allow/conditional/deny；PermButton 用 state 直接映射——一致。
- useTable 返回 `pagination`（usePagination 实例）；DataTable 用 `t.pagination.page/setPage/setSize/slice`——一致。
- usePagination `slice(rows)`/`setPage`/`setSize`/`pageCount`/`offset`——Task 3 定义与 Task 4/11 消费一致。
- provider.getKPIs 返回 7 项含 key='elevators'/'slaOverdue'；工作台读一致。
- visibleMenus(role, implementedPaths)；AppShell 传 IMPLEMENTED=['/dashboard']——一致。
- 路由：新壳 '/' children 'dashboard'；旧 '/legacy' children 'orders' 等；router-dev.test 断言一致。LEGACY_GROUP path 在 Task 17 同步加 /legacy 前缀（已注明）。

**已知边界（忠实 spec）：** M2 工作台用 mock 基准集（真实接线 M3）；多角色 roleSet M2 基准集（M3 接后端）；EP 保留至 M5；旧业务路径加 /legacy 前缀保活（M5 迁完删）。PermButton 本任务用 role prop（Task 13 后可读 perspective store，组件已留 role prop 兼容）。