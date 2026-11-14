# 博威无纸化小程序重构 M2 小程序壳实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地小程序新壳：主包 14 新页（登录/三工作台/profile/密码/403/电梯两页/offline）+ 深色工业风设计系统 + 14 共享组件 + 单会话菜单驱动导航 + 路由白名单守卫；旧 40 页暂留可达（switchTab shim + 新→旧过渡映射），原生 tabBar 删除。

**Architecture:** 全局 `navigationStyle:custom` + nav-bar 组件；旧页 json 逐页 `navigationStyle:default` 保留原生导航。Tab 数据源 = 后端 /me/menus（M1 已落地），前端 config/menus.js 逐字镜像兜底；config/routes.js 全量 49 路由 RouteMeta（含 batch/built 标记），utils/nav.js 统一跳转（tab=reLaunch、详情=navigateTo、未建路由走过渡映射回旧页）。M2 不新增后端端点：工作台数据由 services/home.js 客户端聚合既有端点。

**Tech Stack:** 原生小程序 CommonJS / ES2018 / WXSS（rpx=px×2）/ 无新 UI 框架 / Node 守卫脚本 / miniprogram-automator（模拟器走查，隔离依赖 scripts/e2e/）。

**Spec:** `docs/superpowers/specs/2026-11-15-miniapp-redesign-design.md` §1/§2/§3/§5.3/§6；映射 `docs/superpowers/specs/2026-11-15-miniapp-redesign-m0-mappings.md` §4/§5；原型 `docs/16-梯维通设计输入/`（index.html 17 屏 / routes-and-menus.md V1.3 / roles-permissions.md V1.3）。

## Global Constraints

- 品牌一律「博威无纸化」；**页面零英文**（装饰微标改中文；禁用提示=中文能力名+允许角色，权限码不上页；单号/电梯编号等业务编码除外）。
- logo 只渲染圆形徽标裁切件（M0 口径：background-size:100% auto + 视高=宽×335/450），BOWEI 字母不上页。
- 色彩令牌逐字取原型 :root（spec §2.1）；px×2→rpx；正文≥20rpx、装饰微标≥18rpx；对比度≥4.5:1。
- 页面只调 services；错误码/文案取 constants；枚举取 constants；时间 `yyyy-MM-dd HH:mm:ss` 走 utils/util。
- 合规主干不动：签到/动态码/清单/签退/各签各的/离线上报/上报快照；M2 不改这些链路代码（旧页暂留）。
- 不新增后端端点、不改平台调用、不开自动重试；mock 改动仅提升保真（menus 投影/演示人物），契约不变 → docs/04 不动。
- 旧页暂留可达：旧页 `wx.switchTab` 全部改 shim；旧页 json 加 `navigationStyle:default`；旧页视觉本期不翻新（M6 工具页翻新）。
- 并行会话共享工作区：提交前 `git status` 核对，只 add 本任务文件（`admin/src/views/SettingsView.vue` 未提交编辑与 `cloudbaserc.json` 属他人，不触碰）。
- 每任务门禁：根 `npm run lint` + 相关守卫脚本绿才提交；Windows 子代理不可用 → inline 执行 + 每任务自审留痕（.superpowers/sdd/2026-11-15-miniapp-redesign-m2/）。
- 取证口径：结构等价（脚本计数）与真实绘制（模拟器截图）分开标注，不互相冒充。

## File Structure

| 文件 | 职责 |
|---|---|
| `styles/tokens.wxss`（新） | page 级 CSS 变量（原型 :root 逐字）+ 基础工具类 |
| `styles/fonts.wxss`（生成） | ZCOOL 扩字子集 woff2 base64 @font-face |
| `styles/brand.wxss`（生成） | .brand-mark 圆形徽标 data-uri 裁切 |
| `scripts/asset-build/build-fonts.js`（新） | ttf→woff2→base64→styles/fonts.wxss |
| `scripts/asset-build/build-brand.js`（新） | logo.jpg→base64→styles/brand.wxss |
| `app.wxss`（重写） | @import tokens/icons/fonts/brand + .btn/.chip/.tag/.card/.mono/.disp 等基础类 |
| `config/menus.js`（新） | ROLE_PRIORITY/ROLE_HOME/ROLE_TABS 逐字镜像 + resolveHome/resolveTabs + TABS_ALIAS |
| `config/icons.js`（新） | 蓝图 icon key → 本地 .i-xxx 类映射（仅既有 64 类） |
| `config/routes.js`（新） | 49 路由 RouteMeta{path,name,auth,roles,perm,tab,batch,built} + OLD_PAGES 过渡集合 |
| `utils/guard.js`（重写） | canAccess/ensureAuth/definePage + 保留 ensureLogin/redirectToLogin |
| `utils/nav.js`（新） | open/switchTab shim/TRANSITION_MAP/safeRedirect/parseScene |
| `components/`（新 14 个） | custom-tabbar/nav-bar/status-tag/perm-button/empty-state/skeleton/error-retry/mask-field/sec-head/kpi-row/task-card/qa-grid/week-strip/sync-note |
| `app.js`（重写） | 单会话 globalData + loadMenus/switchWorkspace/refreshBadges |
| `app.json`（重写） | 50 页（40 旧+10 新路径）、无 tabBar、custom 导航、lazyCodeLoading |
| `pages/login/index`（重建） | 单入口账号口令 + 协议门控 + 品牌 |
| `pages/403/index`（新） | 屏 12 范式（不回显 id/路由原文，中文能力名） |
| `pages/profile/index`（新） | 单身份卡 + 切换工作台 + 入口 + 署名 + 退出 |
| `pages/profile/password`（新） | 改密（迁 mine/password 逻辑） |
| `pages/maint/home/index`（新） | 屏 01 维保工作台 |
| `pages/install/home/index`（新） | 安装工作台（屏 01 范式适配） |
| `pages/client/home/index`（新） | 屏 10 使用单位工作台 |
| `pages/elevator/list/index` + `detail/index`（新） | 电梯列表/档案（屏 08），scope 后端收敛 |
| `pages/offline/index`（新） | 离线队列（迁 mine/offline 逻辑） |
| `services/home.js`（扩） | getMaintHome/getInstallHome/getClientHome 客户端聚合 |
| `services/menu.js`（改） | 失败兜底 config/menus + 写入 globalData |
| `mock/server.js` + `mock/data.js`（改） | menus 全角色投影（require config/menus）+ 蓝图演示人物多角色 |
| `scripts/verify-miniapp-routes.js`（新） | 10 检查路由/菜单/导航守卫 |
| `scripts/verify-miniapp-m0.js`（改） | routes-old 改 old∪new 过渡并集（M6 收紧） |
| `scripts/e2e/`（新，隔离依赖） | miniprogram-automator 走查：登录→三工作台→切 Tab→403 |
| `docs/03-小程序设计.md` / `docs/12-未完成事项清单.md`（改） | 页面结构 + H 组 M2 回写 |

**已核实事实（2026-11-15）**：后端 MiniappMenuService.java:27-253 = ROLE_PRIORITY/ROLE_HOME/TABS_ALIAS/BUTTON_CODES(19)/ROLE_TABS 全量；mock /me/menus 仅 WORKER/UNIT_ADMIN 两角色（server.js:63-87）；mock 登录 canonMap 单角色（:113-118）；utils/auth.js 已有 roles/perms/workspace/home 存取；services/menu.js getMenus(workspace) 已建；utils/request.js 401→guard.redirectToLogin；旧登录页 pages/login/index.js 有门户兜底分支（portal=true→unit/home）；图标 64 类无 folder/users/file-text/settings/home/gauge/clipboard/chart/file-check/wrench/shield（需映射）；DevTools CLI 在 `C:\Program Files (x86)\Tencent\微信web开发者工具\cli.bat`；eslint ecmaVersion 2018 无规则（解析级）。

**裁决（预检）**：
- R1：M0 守卫 routes-old 改 `oldCol ∪ newCol` 并集（过渡期 app.json 新旧并存）；M6 删旧页后收紧为 newCol 且计数 49。M0 文档追加修订行说明。
- R2：图标 key 映射只许用既有 64 类：gauge→i-gauge-high、clipboard→i-clipboard-list、home→i-house、folder→i-book、file-check→i-file-circle-check、chart→i-chart-line、file-text→i-file-pdf、users→i-user-tie、shield→i-shield-halved、settings→i-gear、wrench→i-screwdriver-wrench；其余同名（elevator/user/file-signature/building/qrcode/bolt）。
- R3：403 页按 D4 覆盖屏 12：显示中文能力名+允许角色+我的角色+数据范围，**不显示权限码原文/路由原文/目标 id**。
- R4：未建路由过渡：TRANSITION_MAP 新→旧（M0 §4 反向）；无旧等价（pkg-admin 7 页、record-sign、evaluate、upload、review）→ toast「该功能将在后续版本开放」+ reLaunch profile。各域迁移完成即删对应映射，M6 删全表。
- R5：登录页保留「微信一键登录」仅当本机已绑定（em_wechat_bound），账号口令为唯一表单；门户兜底分支（data.portal）保留至 M6（R4/M1）。
- R6：RouteMeta.perm 仅登记蓝图 §4 明确归属的码（可为数组 any-of）；扩展路由只 roles 不 perm（后端端点级强校验为准，蓝图 N6）。
- R7：工作台数据客户端聚合既有端点（/home/summary、/work-orders、/faults、/handovers、/unit/*），不新增后端端点；mock 演示人物按 spec §5.2-6 增补（李振国 MAINT_TECH / 张伟 CLIENT_ADMIN+CLIENT_SIGNER / 王强 INST_PM / 刘倩 INST_CLERK / 陈刚 MAINT_SUP / 维保经理 MAINT_MGR / 平台 PLATFORM_ADMIN），旧演示账号保留兼容。

---

### Task 1: 设计系统落地（tokens/fonts/brand/app.wxss）

**Files:**
- Create: `styles/tokens.wxss`、`scripts/asset-build/build-fonts.js`、`scripts/asset-build/build-brand.js`
- Generate: `styles/fonts.wxss`、`styles/brand.wxss`
- Rewrite: `app.wxss`
- Read: `assets/icons/icons.wxss`（确认基类写法）、`assets/fonts/zcool-subset.ttf`、`assets/brand/logo.jpg`、原型 index.html:23-330（令牌/基础类）

**Interfaces:**
- Produces: CSS 变量 --ink/--ink-2/--panel/--panel-2/--line/--line-2/--text/--muted/--dim/--amber/--amber-soft/--signal/--signal-soft/--ok/--ok-soft/--paper/--paper-ink/--paper-line；基础类 .mono/.disp/.row/.col/.between/.grow/.muted/.dim/.amber/.sig/.ok/.card/.plate/.btn/.btn-primary/.btn-ghost/.btn-signal/.btn-xs/.btn-sm/.link/.chip/.tag/.tag-amber/.tag-signal/.tag-ok/.tag-dim/.kv/.sec-head/.panel-title/.hint/.rule-note/.gate-note/.empty/.skel；.brand-mark；.i-xxx（assets/icons/icons.wxss 直接 @import，不复制）。Task 4/6/7/8 全部消费。

- [ ] **Step 1: tokens.wxss（逐字令牌 + 基础类，px×2）**

```css
/* styles/tokens.wxss — 深色工业风令牌（原型 index.html:23-42 逐字，rpx=px×2） */
page{
  --ink:#0A0F12;--ink-2:#0D1418;--panel:#131C21;--panel-2:#172228;
  --line:#26343B;--line-2:#2E3E46;--text:#E8EEF0;--muted:#A3B4BB;--dim:#7E929B;
  --amber:#FFB020;--amber-soft:#FFC85C;--signal:#FF4B2B;--signal-soft:#FF8F7A;
  --ok:#2FC48D;--ok-soft:#5FE0B2;--paper:#EDE5D3;--paper-ink:#22201A;--paper-line:#C6BA9F;
  background:var(--ink);color:var(--text);
  font-family:'PingFang SC','Microsoft YaHei','Hiragino Sans GB',system-ui,sans-serif;
  font-size:28rpx;line-height:1.5;
}
```

基础类按原型 L64-293 移植（.mono 等宽 tabular-nums；.disp ZCOOL；.btn 高 80rpx 圆角 10rpx；.btn-primary amber 底 #1A1203 字 + 0 4rpx 0 #8E6000 硬阴影；.tag 四色；.card 渐变 160deg #151F25→#0F181D；.kv 虚线分隔；.skel 扫光动画 + prefers-reduced-motion 静态降级）。字号档：fs18=18rpx 微标 / fs20 / fs22 / fs24 / fs26 / fs32 / fs38 / fs44 / fs52（原型 9/10/11/12/13/16/19/22/26px×2）。

- [ ] **Step 2: 字体与徽标生成脚本**

build-fonts.js：`require('ttf2woff2')`（asset-build 已有依赖）读 `assets/fonts/zcool-subset.ttf` → woff2 → base64 → 写 `styles/fonts.wxss`：
```css
@font-face{font-family:'ZCOOL QingKe HuangYou';font-style:normal;font-weight:400;font-display:swap;src:url('data:font/woff2;base64,...') format('woff2');}
```
build-brand.js：读 logo.jpg → base64 → 写 `styles/brand.wxss`：
```css
.brand-mark{background-image:url('data:image/jpeg;base64,...');background-size:100% auto;background-position:top;background-repeat:no-repeat;}
```
Run: `cd scripts/asset-build && node build-fonts.js && node build-brand.js`
Expected: 两 wxss 生成；fonts.wxss base64 解码字节数 = woff2 文件字节数（脚本自校验输出 JSON）。

- [ ] **Step 3: app.wxss 重写**

```css
@import './styles/tokens.wxss';
@import './assets/icons/icons.wxss';
@import './styles/fonts.wxss';
@import './styles/brand.wxss';
/* 基础类在 tokens.wxss；app.wxss 只放全局重置与页面容器 */
view,text,button,input,textarea,scroll-view{box-sizing:border-box}
button{background:none;border:none;padding:0;margin:0;font-size:inherit;line-height:inherit;color:inherit}
button::after{border:none}
.page-pad{padding:18rpx 28rpx 20rpx}
.tabbar-pad{padding-bottom:calc(152rpx + env(safe-area-inset-bottom,0px))}
```

- [ ] **Step 4: 门禁 + 提交**

Run: 根 `npm run lint`；`node scripts/verify-miniapp-m0.js`（资产检查不回归）。
自审：令牌 18 个逐字对照原型 :root；无英文装饰类新增。
```bash
git add styles/ app.wxss scripts/asset-build/build-fonts.js scripts/asset-build/build-brand.js
git commit -m "feat: M2 设计系统落地（深色令牌/图标直引/ZCOOL woff2 内嵌/圆形徽标裁切/基础类）"
```

---

### Task 2: config 三件套 + 路由守卫（RED→GREEN）

**Files:**
- Create: `config/menus.js`、`config/icons.js`、`config/routes.js`、`scripts/verify-miniapp-routes.js`
- Read: 后端 `MiniappMenuService.java:27-253`、`docs/16-梯维通设计输入/routes-and-menus.md` L239-334、M0 映射 §4

**Interfaces:**
- Produces: `menus.ROLE_PRIORITY/ROLE_HOME/ROLE_TABS/TABS_ALIAS/resolveHome(roles)/resolveTabs(roles)/activeWorkspace(roles)`；`icons.ICONS`（key→class）；`routes.ROUTES`（49 条）/`routes.OLD_PAGES`（40 旧页 Set）/`routes.BUILT`（已建 Set）/`routes.metaOf(path)`。Task 3/4/5/7 消费。

- [ ] **Step 1: 先写守卫 verify-miniapp-routes.js（RED）**

10 检查（JSON 计数输出，缺口退出码 1，风格同 verify-miniapp-m0.js）：
1. `routes-meta`：app.json 全部页（含 subPackages）∈ ROUTES ∪ OLD_PAGES
2. `routes-coverage`：M0 §4 新路由 49 条 ⊆ ROUTES
3. `routes-built`：ROUTES built:true ⊆ app.json 页；app.json 新页（∈M0 newCol）⊆ built
4. `tabs-drift-backend`：config/menus.js 的 ROLE_TABS/ROLE_HOME/ROLE_PRIORITY 与后端 Java 逐字一致（正则解析 `t.put("ROLE", List.of(tab(...)))` 与 `tab("key","text","icon","path"[,"badge"])` 元组；ROLE_HOME 解析 Map.of  entries；ROLE_PRIORITY 解析 List.of）
5. `tabs-drift-proto`：config/menus.js 与 docs/16 routes-and-menus.md ROLE_TABS 块逐字一致（解析 `{ key: '...', text: '...', icon: '...', path: '...'[, badgeKey: '...'] }`）
6. `tabs-paths`：全部 tab.path（去 query）∈ ROUTES
7. `nav-transition`：全部未建 tab/quickAction 目标 ∈ TRANSITION_MAP 键 或 fallback=profile（读 utils/nav.js）
8. `no-switchtab`：pages/ components/ utils/（除 utils/nav.js 定义处）无 `wx.switchTab` 调用
9. `appjson-shape`：无 tabBar；window.navigationStyle==='custom'；lazyCodeLoading==='requiredComponents'
10. `icons-map`：ICONS 值全部 ∈ styles/icons.wxss 既有 .i- 类；ROLE_TABS 全部 icon key ∈ ICONS 键

Run: `node scripts/verify-miniapp-routes.js` → FAIL（config 不存在）。

- [ ] **Step 2: config/menus.js（逐字镜像）**

CommonJS 移植蓝图 L239-334：ROLE_PRIORITY 9 码、ROLE_HOME 9 条、ROLE_TABS 9 角色（key/text/icon/path/badgeKey 逐字，含 INST_MGR/MAINT_SUP/MAINT_MGR 5 项）；TABS_ALIAS={MAINT_QUALITY:'MAINT_SUP',MAINT_BIZ:'MAINT_SUP',INSTALLER:'INST_CLERK'}（后端 :44-47 镜像）；resolveHome/resolveTabs（并集去重≤5，扩展角色别名投影追加在后）/activeWorkspace（ROLE_PRIORITY 首命中→别名→null）。

- [ ] **Step 3: config/icons.js + config/routes.js**

icons.js：`{gauge:'i-gauge-high',clipboard:'i-clipboard-list',elevator:'i-elevator',user:'i-user',home:'i-house','file-signature':'i-file-signature',folder:'i-book',building:'i-building','file-check':'i-file-circle-check',chart:'i-chart-line','file-text':'i-file-pdf',users:'i-user-tie',shield:'i-shield-halved',settings:'i-gear',qrcode:'i-qrcode',bolt:'i-bolt',wrench:'i-screwdriver-wrench'}`（裁决 R2）。

routes.js：49 条 RouteMeta。字段 {path,name,auth,roles?,perm?,tab?,batch,built}。蓝图 §4 归属逐条登记（order/detail perm=['order:view_pool','order:view_own'] any-of；todo perm=['record:sign','order:confirm']）；扩展路由只 roles（R6）：orders/checkin/dynamic-code/checkout=MAINT_TECH（orders 另 SUP/MGR/QUALITY/BIZ）；filing-*=INST_PM/INST_CLERK/INST_MGR（detail/inspect/confirm 另 MAINT_SUP/MAINT_MGR）；message/offline/common/profile/password=全部已登录；403 auth:false（防守卫循环，R3）。tab:true 标记 14 个 Tab 宿主（三 home/profile/order/list/elevator/list/handover/list/client/todo/admin projects/contracts/stats/users/audit/settings）。built:true 仅 M2 已建 14 页；batch 按 M0 §4。OLD_PAGES=40 旧页数组（M0 §4 旧列展开）。

- [ ] **Step 4: 守卫 GREEN + 提交**

Run: `node scripts/verify-miniapp-routes.js` → 检查 1/2/4/5/6/10 绿；3/7/8/9 允许红（app.json/nav/旧页未改，Task 3/9 闭合）——输出注明 pending。根 lint。
```bash
git add config/ scripts/verify-miniapp-routes.js
git commit -m "feat: M2 菜单/图标/路由白名单三件套 + 路由守卫（ROLE_TABS 双源漂移锁定）"
```

---

### Task 3: guard 重写 + nav 统一跳转 + 旧页 shim

**Files:**
- Rewrite: `utils/guard.js`
- Create: `utils/nav.js`
- Modify: 旧页全部 `wx.switchTab` 调用点（grep 全量替换为 `nav.switchTab`）；旧 40 页 json 加 `"navigationStyle": "default"`
- Read: 现 utils/guard.js（防抖 300ms 保留）、pages 各 js

**Interfaces:**
- Consumes: Task 2 ROUTES/OLD_PAGES/BUILT、menus.resolveHome。
- Produces: `guard.canAccess(meta,session)`/`ensureAuth(meta)`/`definePage(meta,options)`/`ensureLogin()`/`redirectToLogin()`；`nav.open(url)`/`nav.switchTab({url})`/`nav.back()`/`nav.safeRedirect(raw)`/`nav.parseScene(q)`/`nav.TRANSITION_MAP`。Task 5/6/7/8 消费。

- [ ] **Step 1: utils/nav.js**

```js
// 统一跳转（M2）：tab=reLaunch 清栈；详情=navigateTo；未建路由走过渡映射回旧页（裁决 R4）
const { ROUTES, BUILT } = require('../config/routes')
const { resolveHome } = require('../config/menus')
const { getRoles, getHome } = require('./auth')

// 新→旧过渡映射（M0 §4 反向；各域迁移完即删对应行，M6 删全表）
const TRANSITION_MAP = {
  '/pages/order/list/index': '/pages/fault/list',
  '/pages/order/detail/index': '/pages/fault/detail',
  '/pages/order/report/index': '/pages/fault/report', // CLIENT 工作台时改 /pages/unit/report（open 内按 workspace 分流）
  '/pkg-maintenance/pages/orders/index': '/pages/order/list',
  '/pkg-maintenance/pages/task/index': '/pages/order/detail',
  '/pkg-maintenance/pages/checkin/index': '/pages/order/checkin',
  '/pkg-maintenance/pages/dynamic-code/index': '/pages/order/dynamic-code',
  '/pkg-maintenance/pages/checkout/index': '/pages/order/checkout',
  '/pkg-maintenance/pages/record-detail/index': '/pages/order/record-preview',
  '/pkg-maintenance/pages/plan/index': '/pages/calendar/index',
  '/pkg-maintenance/pages/record-archive/index': '/pages/order/record-preview',
  '/pkg-handover/pages/list/index': '/pages/handover/list',
  '/pkg-handover/pages/create/index': '/pages/handover/init',
  '/pkg-handover/pages/detail/index': '/pages/handover/detail',
  '/pkg-handover/pages/inspect/index': '/pages/handover/inspect',
  '/pkg-handover/pages/rectify/index': '/pages/handover/rectify',
  '/pkg-handover/pages/sign/index': '/pages/handover/confirm',
  '/pkg-handover/pages/filing-init/index': '/pages/filing/init',
  '/pkg-handover/pages/filing-detail/index': '/pages/filing/detail',
  '/pkg-handover/pages/filing-inspect/index': '/pages/filing/inspect',
  '/pkg-handover/pages/filing-confirm/index': '/pages/filing/confirm',
  '/pkg-client/pages/todo/index': '/pages/unit/records',
  '/pkg-client/pages/sign/index': '/pages/unit/sign',
  '/pkg-client/pages/orders/index': '/pages/unit/faults'
}
// 无旧等价：pkg-admin 7 页 + record-sign + evaluate + handover upload/review → profile 兜底
```

open(url)：拆 path/query → 白名单（ROUTES 键）校验，不在白名单拒绝并 toast；path 未 built → TRANSITION_MAP 命中则改旧路径（保留 query；order/report 按 currentWorkspace CLIENT→/pages/unit/report），无映射 → wx.showToast('该功能将在后续版本开放') + reLaunch profile；已 built → meta.tab ? reLaunch : navigateTo（navigateTo 失败兜底 reLaunch）。switchTab({url})：旧 tab 路径映射（pages/home/index→当前 home；pages/order/list→/pages/order/list 旧页；pages/message/index、pages/mine/index 原样）→ reLaunch。safeRedirect：白名单 ALLOWED_REDIRECTS=蓝图 L436-443 六路径 + 三 home + profile。parseScene：蓝图 L323-330 移植。

- [ ] **Step 2: utils/guard.js 重写**

保留 redirectToLogin（防抖+300ms，注释保留）与 ensureLogin。新增：
```js
function canAccess(meta, session) {
  if (!meta.auth) return true
  const roles = (session && session.roles) || []
  const perms = (session && session.perms) || []
  if (!session || !session.token) return false
  if (meta.roles && !meta.roles.some(r => roles.includes(r))) return false
  if (meta.perm) {
    const need = Array.isArray(meta.perm) ? meta.perm : [meta.perm]
    if (!need.some(p => perms.includes(p))) return false
  }
  return true
}
function ensureAuth(meta) { // 无令牌→登录(redirect)；无权限→403（不带目标 id，R3）
  const session = { token: getToken(), roles: getRoles(), perms: getPerms() }
  if (meta.auth && !session.token) { redirectToLogin(meta.path); return false }
  if (!canAccess(meta, session)) { wx.reLaunch({ url: '/pages/403/index' }); return false }
  return true
}
function definePage(meta, options) { // onLoad/onShow 双层校验（蓝图 L418-430）
  Page(Object.assign({}, options, {
    onLoad(query) { if (!ensureAuth(meta)) return; options.onLoad && options.onLoad.call(this, query) },
    onShow() { if (!ensureAuth(meta)) return; options.onShow && options.onShow.call(this) }
  }))
}
```
redirectToLogin 增加可选 path 参（拼 ?redirect=encodeURIComponent）。

- [ ] **Step 3: 旧页 shim + json 导航样式**

grep `wx.switchTab` 全量清单 → 逐处改 `require('../../utils/nav').switchTab`（相对路径按页深调整）。40 旧页 json 各加 `"navigationStyle": "default"`（脚本化批量 + 逐页 diff 核对，不改其他字段）。
Run: `node scripts/verify-miniapp-routes.js` → no-switchtab 绿；根 lint。

- [ ] **Step 4: 提交**

```bash
git add utils/guard.js utils/nav.js pages/
git commit -m "feat: M2 路由守卫四层 + 统一跳转（未建路由过渡映射）+ 旧页 switchTab shim/原生导航保留"
```

---

### Task 4: 共享组件 14 个

**Files:**
- Create: `components/{custom-tabbar,nav-bar,status-tag,perm-button,empty-state,skeleton,error-retry,mask-field,sec-head,kpi-row,task-card,qa-grid,week-strip,sync-note}/index.{js,json,wxml,wxss}`
- Read: 原型 index.html:193-240（statusbar/navbar/tabbar）、243-335（tag/btn/kpi/task/qa/week/sync）、641-651（empty/skel）、2397-2520（403/失败态）、roles-permissions.md §8.4（两档门控）

**Interfaces:**
- Consumes: Task 1 基础类/令牌、Task 2 icons.ICONS、Task 3 nav。
- Produces（props/events 逐组件）：
  - custom-tabbar：无 props；读 globalData.tabs/badges；active 由当前路由匹配；tap→nav.open
  - nav-bar：props {title:'',brand:false,back:true}；back→nav.back()；状态栏/胶囊自适应
  - status-tag：props {text,tone}（tone∈amber/signal/ok/dim）
  - perm-button：props {perm,capability,allowRoles,gate:false,gateReason:'',type:'primary',size:'',text:''}；hasPerm=globalData.perms 含 perm；一档=!hasPerm→原生 disabled+中文能力注；二档=hasPerm&&gate→aria-disabled+catchtap 拦截+原因文本；通过→triggerEvent('tap')
  - empty-state：props {mode:'empty'|'filtered',icon:'i-inbox',title,desc,actionText:'',secondaryText:''}；events action/secondary
  - skeleton：props {rows:[{h,flex}]}；纯 CSS 扫光+reduced-motion 静态
  - error-retry：props {title,message,code:''}；events retry/back
  - mask-field：props {value,masked:false}；masked 追加 △ 标记；不解密
  - sec-head：props {title,actionText:''}；event action
  - kpi-row：props {items:[{value,label,alert:false}]}
  - task-card：props {time,title,desc,tagText,tagTone}；event tap
  - qa-grid：props {items:[{key,text,icon}]}；event action(detail={key,item})
  - week-strip：props {items:[{value,label,tone:''}]}
  - sync-note：props {title,desc,status:'已同步'}

- [ ] **Step 1: custom-tabbar + nav-bar（导航双件）**

custom-tabbar：attached/pageLifetimes.show 同步 globalData；active=当前页 route 与 tab.path（去 query）匹配；WXML grid 等宽（4–5 项自适应）；min-height:132rpx + padding-bottom:calc(20rpx+env(safe-area-inset-bottom))；角标 signal 底 mono 18rpx，锚定格心+34rpx（原型 L573 口径×2）；图标 `<text class="i {{icons[item.icon]}}">`。
nav-bar：attached 取 wx.getWindowInfo().statusBarHeight + wx.getMenuButtonBoundingClientRect()；导航高=(capsule.top-statusBarHeight)×2+capsule.height；右避让=windowWidth-capsule.left+16rpx；back 按钮 i-chevron-left；brand 区 .brand-mark 48rpx 圆裁 + 「博威无纸化」28rpx w7；title 居中 30rpx w6；底部 1px line 分隔。

- [ ] **Step 2: perm-button + status-tag + mask-field（权限/脱敏三件）**

perm-button 两档逐字落 §8.4：一档 `<button disabled>` + `<view class="gate-note">所需能力：{{capability}} · 允许角色：{{allowRoles}}</view>`；二档 `<button aria-disabled="{{true}}" catchtap="onGateTap">` + gate-note 显示 gateReason（保持可聚焦）；onGateTap 仅 wx.showToast(gateReason) 不发射 tap。status-tag 四 tone 映射原型 .tag-*。mask-field：`<text>{{value}}</text><text wx:if="{{masked}}" class="dim">△</text>`。

- [ ] **Step 3: 四态三件 + 工作台六件**

empty-state 双分支（屏 13：filtered 保留筛选 chip 由宿主页渲染，组件只提供清空动作）；skeleton 扫光 1.4s + @media prefers-reduced-motion 静态；error-retry 三件套（可读原因+重试+返回，屏 15）；sec-head/kpi-row/task-card/qa-grid/week-strip/sync-note 按原型 L295-335 移植（kpi 底部 36rpx amber 短划线、task 左 4rpx amber 边、qa 四列、week-strip 渐变+vdiv、sync-note ok 边）。

- [ ] **Step 4: 门禁 + 提交**

Run: 根 lint；守卫 icons-map/no-switchtab 不回归。自审：14 组件 json 均 `"component":true`；js 均 `options:{addGlobalClass:true}`；无英文文案。
```bash
git add components/
git commit -m "feat: M2 共享组件 14 件（自定义导航/双档权限按钮/四态/脱敏/工作台部件）"
```

---

### Task 5: app.js 单会话 + 菜单加载 + app.json 重构

**Files:**
- Rewrite: `app.js`、`app.json`
- Modify: `services/menu.js`（兜底+globalData 写入）
- Read: 现 app.js（initCloud/watchNetwork/watchUpdate 保留）、M1 登录响应字段

**Interfaces:**
- Consumes: Task 2 menus、Task 3 nav/guard。
- Produces: `app.globalData{token,userInfo,role,roles,perms,workspace,home,tabs,quickActions,actions,badges}`；`app.setAuth(data)`/`logout()`/`loadMenus(workspace)`/`switchWorkspace(ws)`/`refreshBadges()`。Task 6/7/8 消费。

- [ ] **Step 1: services/menu.js 兜底接线**

getMenus(workspace) 成功→写 globalData（tabs/home/quickActions/actions/badges/workspace）；失败→config/menus 兜底（resolveTabs/resolveHome + actions 由 perms 逐码 includes 投影 19 按钮码 + badges 置 0），console.warn 留痕；返回同一形状。

- [ ] **Step 2: app.js 重写**

保留 initCloud/watchUpdate/watchNetwork（补传逻辑不动）。restoreAuth：读 utils/auth 全字段；若 em_token 存在清 unit 旧会话（保留至 M6）；globalData 全量恢复。setAuth(data)：saveAuth 已由 services/auth.applyLoginResult 做，这里同步 globalData + 清 unit 旧会话 + loadMenus()。loadMenus(workspace)：未登录跳过；调 services/menu.getMenus。switchWorkspace(ws)：loadMenus(ws) → reLaunch(globalData.home)。refreshBadges()：loadMenus 静默刷新（不改 workspace）。logout：clearAuth + globalData 清空。

- [ ] **Step 3: app.json 重构**

pages = 新 14（login/index、login/agreement、install/home/index、maint/home/index、client/home/index、elevator/list/index、elevator/detail/index、profile/index、profile/password、403/index、message/index、offline/index、common/watermark-camera、common/signature）+ 旧 36（40 旧页去掉与新同路径的 login/index、login/agreement、message/index、common 两页 = 35？按 M0 §4 旧列实测去重：40 旧 − 5 同路径 = 35）→ 共 49 条注册（14 新路径中 9 个新建 + 5 个沿用）。**以 M0 §4 为准脚本生成并人工核对**。window：navigationBarTitleText:'博威无纸化'、navigationStyle:'custom'、navigationBarBackgroundColor:'#0A0F12'、navigationBarTextStyle:'white'、backgroundColor:'#0A0F12'、backgroundTextStyle:'light'。删 tabBar。lazyCodeLoading:'requiredComponents'。permission/requiredPrivateInfos/networkTimeout/sitemapLocation 保留。subPackages/preloadRule 本批不加（M3 起随域注册）。
注意：新页文件此时未全部建（Task 6/7/8 才建）——**app.json 重构放 Task 9 执行**，本任务只改 app.js/menu.js 并用旧 app.json 验证 lint；Task 9 统一注册。

- [ ] **Step 4: 门禁 + 提交**

Run: 根 lint；`node scripts/verify-miniapp-m0.js` 不回归。
```bash
git add app.js services/menu.js
git commit -m "feat: M2 单会话全局态 + 菜单下发兜底接线（工作台切换/角标刷新）"
```

---

### Task 6: 登录页重建 + 403 + 修改密码

**Files:**
- Rewrite: `pages/login/index.{js,json,wxml,wxss}`
- Create: `pages/403/index.*`、`pages/profile/password.*`
- Read: 现登录页（记住账号/微信绑定/门户兜底逻辑保留）、pages/mine/password.*（改密逻辑迁移）、原型屏 00（L1120-1221）/屏 12（L2397-2427）

**Interfaces:**
- Consumes: Task 3 guard/nav、Task 4 nav-bar/perm-button、Task 5 app.setAuth/loadMenus。
- Produces: 登录成功→safeRedirect||home reLaunch；403 页（中文能力名，R3）；改密页（auth.changePassword）。

- [ ] **Step 1: 登录页（屏 00 范式 + D4/§3.2 覆盖）**

结构：nav 区（品牌 .brand-mark + 博威无纸化 disp）；hero（电梯井道装饰改 CSS 线条 + 中文微标「电梯维保数字化」/「V1.3 · 2026」；标题「让每一台电梯 都有据可查」disp 62rpx；副标中文）；micro-hazard 警示条纹；登录卡（账号 input + 密码 input（可切换明文）+ 记住账号 checkbox + 协议 checkbox（《用户协议》《隐私政策》跳 agreement）+ 主按钮「登录工作台」btn-primary）；微信一键登录按钮仅 em_wechat_bound 时显示（R5）；三特性点（电子签名固化/弱网自动续传/作业全程留痕）；三角色区（维保部/安装部/使用单位 + 「登录后由账号权限自动进入对应工作台，无需选择角色」）；页脚「账号由所属单位管理员开通 · 首次使用请联系项目负责人」。
逻辑：onSubmit 校验（账号/密码/协议）→ auth.accountLogin → data.portal 兜底（saveUnitSession→reLaunch /pages/unit/home，R5/M1-R4）否则 clearUnitSession + applyLoginResult + app.setAuth → loadMenus → reLaunch(safeRedirect(query.redirect)||data.home||resolveHome(roles))。已登录 onLoad→onReady 延迟 300ms reLaunch home。微信登录分支保留（wxLogin→未命中 unitLoginByWechat 兜底）。删 skipLogin（新壳无未登录浏览态；登录规范「暂不登录」由 403/登录页本身承担——**保留 skipLogin 入口改为「暂不登录，查看介绍」跳 agreement**，满足不强制登录规范）。
json：navigationStyle 继承全局 custom；usingComponents nav-bar。

- [ ] **Step 2: 403 页（屏 12 + R3 覆盖）**

结构：nav-bar title「无访问权限」；主卡（i-lock 52rpx amber + disp「无访问权限」+ 说明「当前账号的数据范围内不可达该对象。已按守卫拦截，后端接口同步返回 403。」+ tag-signal「访问被拒绝」）；详情卡（所需能力=中文能力名/允许角色=中文/我的当前角色=中文角色名/我的数据范围=中文描述；**无权限码原文、无路由原文、无目标 id**——能力名由 globalData.lastDeny{capability,allowRoles} 提供，guard ensureAuth 拦截时写入，缺省显示「该功能」/「请联系管理员」）；rule-note（403/404 文案一致防探测）；主按钮「返回工作台」→nav.open(home)；次链接「联系维保主管核对范围」→message 页。
guard.js ensureAuth 拦截时：app.globalData.lastDeny={capability:meta.name,allowRoles:(meta.roles||[]).map(中文角色名).join('、')}（角色中文名表放 config/menus.js ROLE_TEXT）。

- [ ] **Step 3: profile/password（迁 mine/password）**

逻辑照搬（旧口令+新口令≥6 位+确认一致，auth.changePassword）；视觉改深色卡 + nav-bar「修改密码」；成功 toast 后 back。旧 pages/mine/password 暂留（M6 删）。

- [ ] **Step 4: 门禁 + 提交**

Run: 根 lint；守卫 routes-meta 暂红（新页未注册，Task 9 闭合）。自审：登录页零英文（V1.3 版本号属版本标识保留）；协议门控；门户兜底。
```bash
git add pages/login/ pages/403/ pages/profile/ utils/guard.js config/menus.js
git commit -m "feat: M2 登录页重建（单入口/品牌/协议门控）+ 403 范式页 + 改密迁页"
```

---

### Task 7: 三工作台 + services/home 聚合 + mock 演示人物

**Files:**
- Create: `pages/maint/home/index.*`、`pages/install/home/index.*`、`pages/client/home/index.*`
- Modify: `services/home.js`、`mock/server.js`、`mock/data.js`
- Read: 原型屏 01（L1224-1324）/屏 10（L2181-2285）、mock/data.js getHomeSummary(:430)/employees(:126)、services/handover.js、services/unit.js

**Interfaces:**
- Consumes: Task 4 工作台六件 + custom-tabbar/nav-bar、Task 5 globalData.quickActions/badges。
- Produces: `home.getMaintHome()`→{greeting,shift,sos,kpis[3],tasks[],week[3],sync}；`home.getInstallHome()`→{greeting,kpis[3],tasks[],week[3]}；`home.getClientHome()`→{greeting,signAlerts[],kpis[3],elevators[],records[],notice}。mock /me/menus 全角色投影（require config/menus.js）+ 演示人物 roles 并集。

- [ ] **Step 1: services/home.js 聚合（不新增端点）**

getMaintHome：Promise.all([get('/home/summary'), order.getOrderList({page:1,size:20}), fault.getFaultList({page:1,size:20})]) → kpis=[待维保=summary.dueToday+dueSoon+overdue，急修待接=faults OPEN 且 trapped，报修待接=faults OPEN 非 trapped]；tasks=今日 planTime 工单（前 3）+ 急修 OPEN（前 2）映射 task-card；sos=最近一条困人急修（trapped 优先，状态中文）；week=[本周完成=DONE 且本周签退，一次合格率=记录无异常占比（无数据显「—」），超时=summary.overdue]；sync={title:'监管平台数据同步正常',desc:'上次同步 '+最新记录时间,status:'已同步'}。getInstallHome：handover.getHandoverList({page:1,size:20}) → kpis=[待移交/待审核/整改中]；tasks=进行中移交前 3。getClientHome：unit.getUnitPendingSigns()+getUnitFaults()+getUnitElevators() → signAlerts（待签记录+待评价急修 CLOSED 未评价）、kpis=[在管电梯/本月已维保/待签署]、elevators 前 2、records 前 2、notice=年检最近到期提醒（profile.nextInspectDate）。
所有聚合失败→单源降级（该区块空态，不整页失败）；页面四态由骨架/错误组件承担。

- [ ] **Step 2: mock 演示人物 + menus 全投影（R7）**

mock/data.js employees 增补：MAINT_TECH 李振国 T3101（13800000011）、CLIENT_ZHANGWEI 张伟（13800000012，roles=[CLIENT_ADMIN,CLIENT_SIGNER]）、INST_PM 王强（13800000013）、INST_CLERK 刘倩（13800000014）、MAINT_MGR 维保经理（13800000015）、PLATFORM 平台管理员（13800000016）；旧账号保留。employees 各加 roles 数组（旧账号单角色 canon）。
mock /auth/login：roles=user.roles||[canon]；perms=MOCK_PERMS[role] 并集（19 按钮码按角色投影，演示口径注释标明）；home=menus.resolveHome(roles)；activeWorkspace=menus.activeWorkspace(roles)。
mock /me/menus：require('../../config/menus') → resolveTabs/resolveHome/activeWorkspace（workspace 参优先且 ∈roles）；quickActions 按 ws 前缀（MAINT 4 项/CLIENT 2 项/INST 2 项，与后端 :146-168 逐字）；actions=19 码逐码 anyMatch；badges={pendingOrders:OPEN 急修数（MAINT）,pendingSigns:待签记录数（CLIENT）}。

- [ ] **Step 3: maint/home（屏 01 逐区）**

definePage(ROUTES['/pages/maint/home/index'])；onShow 拉 getMaintHome + app.refreshBadges；区序：问候行（日期·片区/早安{name} disp 44rpx/单位·班组·工号 + shift 卡「班次 08:00–18:00」）→ sos 横幅（困人急修最近一条，tag 状态+单号+时间+「查看急修单」→nav.open order/detail）→ kpi-row → sec-head「今日任务」+task-card×N（「全部」→orders 扩展页过渡映射）→ qa-grid（globalData.quickActions）→ week-strip → sync-note；custom-tabbar；四态（骨架首屏/错误重试/空任务 empty-state）。英文微标全部中文（FRI→周五、TODAY→今日任务、SHIFT→班次、SYNCED→已同步）。

- [ ] **Step 4: install/home + client/home**

install/home：屏 01 范式适配——问候+KPI（待移交/待审核/整改中）+今日移交 task-card+qa（发起移交/资料上传）+week-strip；无 sos。client/home（屏 10 逐区）：问候（单位·职务·兼任标注）→ sign-alert（待签署 N 份·待评价 N 项 + 行「去签字」btn-primary xs→client/sign 过渡映射 + 「去评价」link→evaluate 过渡兜底）→ qa-grid（一键报修/急修进度/我的电梯/维保计划）→ week-strip → sec-head「我的电梯」elev-row×2 → sec-head「最近记录」×2 → notice-strip 年检提醒；custom-tabbar。

- [ ] **Step 5: 门禁 + 提交**

Run: 根 lint；`node scripts/verify-m1-sync.js`（mock 改动后三处同步不回归）；`node scripts/verify-miniapp-m0.js`。自审：三工作台零英文；聚合降级；mock 人物可登录。
```bash
git add pages/maint/ pages/install/ pages/client/ services/home.js mock/
git commit -m "feat: M2 三工作台（屏01/屏10 范式）+ 首页聚合层 + mock 蓝图演示人物/菜单全投影"
```

---

### Task 8: profile + 电梯两页 + offline 迁页

**Files:**
- Create: `pages/profile/index.*`、`pages/elevator/list/index.*`、`pages/elevator/detail/index.*`、`pages/offline/index.*`
- Read: pages/mine/index.js（微信绑定/退出逻辑）、pages/unit/mine/index.js（门户卡逻辑）、pages/elevator/list|detail.js（档案/更正逻辑）、pages/mine/offline.js（队列逻辑）、原型屏 08（L1968-2086）

**Interfaces:**
- Consumes: Task 4 组件、Task 5 switchWorkspace、services/elevator.js、utils/offline.js。
- Produces: profile（单身份卡+切换工作台+入口+署名+退出）；elevator/list（scope 后端收敛+搜索）；elevator/detail（屏 08：hero+QR+字段组+timeline+导出 perm-button 一档）；offline（队列列表+补传）。

- [ ] **Step 1: profile/index（合并 mine + unit/mine）**

单身份卡：姓名 disp + 单位/部门 + 角色 chips（中文，ROLE_TEXT）+ 工号；切换工作台区：账号 roles 映射的工作台集合（MAINT_*→维保/INST_*→安装/CLIENT_*→使用单位/PLATFORM_ADMIN→平台管理，去重），>1 时显示切换按钮组→app.switchWorkspace；入口列表：消息中心（message）、离线队列（offline）、修改密码（profile/password）、微信绑定（bindWeChat 流程照搬 mine：wx.login→auth.bindWeChat→em_wechat_bound 标记）、邀请码绑定（仅 CLIENT，照搬 unit/mine 逻辑→bindUnitWechat）；署名区：「图标：Font Awesome Free（CC BY 4.0）· 显示字：ZCOOL QingKe HuangYou（OFL 1.1）· 博威无纸化」中文可见文本（spec §2.4）；退出登录（confirm modal→auth.logout→app.logout→reLaunch login）。custom-tabbar。

- [ ] **Step 2: elevator/list/index**

搜索框（名称/编号/项目名前端过滤）+ elev-row 列表（名称/项目/下次维保/状态 tag）；数据 elevator.getElevatorList()（后端谓词收敛，前端不再按角色过滤）；四态齐全；行点击→detail；custom-tabbar（tab 宿主）。

- [ ] **Step 3: elevator/detail/index（屏 08）**

hero（名称+编号 mono+状态 tag+QR 区——复用旧 detail 的二维码生成逻辑，无则 wx.createCanvasContext 绘制设备码占位+「设备二维码」中文标注）；grid2 字段组（品种/型号/载重/速度/使用单位/项目名）；平台字段组（注册码/设备码/出厂编号，mask-field 脱敏电话）；本地字段编辑（项目名称更正 updateElevatorProjectName + 本地信息 updateElevatorLocal，perm-button 按 elevator:edit 一档——维保人员可更正项目名称沿用旧权限口径）；timeline 最近维保/急修记录；底部「导出台账」perm-button perm=elevator:export capability=「导出台账」allowRoles=「维保经理、平台管理员」（一档禁用示范，屏 08）；四态。

- [ ] **Step 4: offline/index（迁 mine/offline）**

逻辑照搬（utils/offline 队列列表/手动补传/清空已传）；深色卡 + nav-bar「离线队列」；空态 empty-state。

- [ ] **Step 5: 门禁 + 提交**

Run: 根 lint；守卫不回归。自审：电梯写操作权限口径与旧页一致；profile 单身份（无门户卡）。
```bash
git add pages/profile/ pages/elevator/ pages/offline/
git commit -m "feat: M2 我的页（单身份/切换工作台/署名）+ 电梯列表档案（屏08）+ 离线队列迁页"
```

---

### Task 9: app.json 重构 + 全门禁 + 模拟器走查 + 文档回写

**Files:**
- Rewrite: `app.json`
- Modify: `scripts/verify-miniapp-m0.js`（routes-old 并集，R1）、`docs/superpowers/specs/2026-11-15-miniapp-redesign-m0-mappings.md`（修订行）、`docs/03-小程序设计.md`、`docs/12-未完成事项清单.md`
- Create: `scripts/e2e/package.json`、`scripts/e2e/walkthrough.js`（miniprogram-automator）

**Interfaces:**
- Consumes: Task 1-8 全部。
- Produces: app.json 49 注册页（14 新+35 旧）；守卫 10 检查全绿；M0 守卫 11 检查全绿；模拟器走查证据（真实绘制截图 + 结构计数分开标注）。

- [ ] **Step 1: app.json 重构 + M0 守卫过渡更新**

按 Task 5 Step 3 口径生成（脚本从 M0 §4 解析旧列去重 + 新 14）；routes-old 检查改 `app.pages.filter(p => !oldCol.has(p) && !newCol.has(p))`（并集，注释标 M6 收紧）。
Run: `node scripts/verify-miniapp-routes.js` → 10 检查全绿；`node scripts/verify-miniapp-m0.js` → 11 检查全绿。

- [ ] **Step 2: 全量静态门禁**

Run: 根 `npm run lint`；`node scripts/verify-m1-sync.js`；`node scripts/verify-dispatch.js`；`node scripts/verify-templates.js`。后端无改动不跑 mvn（记录口径）。

- [ ] **Step 3: 模拟器走查（真实绘制取证）**

scripts/e2e 隔离安装 miniprogram-automator；DevTools CLI 开启自动化端口（`cli.bat auto --project <root> --auto-port 9420`）；walkthrough.js：① mock 模式登录李振国→断言 maint/home 渲染+4 Tab；② 切 Tab（工单→过渡旧页/电梯→新页/我的）；③ 退出→登录张伟→client/home+待办角标；④ 切换工作台（张伟单 workspace 无切换；用陈刚 MAINT_SUP 验证 5 Tab）；⑤ 登录王强→install/home；⑥ 直接 reLaunch 无权限路由→403 页；⑦ 每步截图存 scripts/e2e/shots/（gitignore）+ 控制台错误收集=0。
若自动化端口不可用（DevTools 未登录/版本不支持）→ 降级 computer-use 打开 DevTools 人工走查截图，**如实标注降级原因**；仍不可用 → 结构取证交付 + 真机/模拟器走查列待办，不冒充。

- [ ] **Step 4: 文档回写 + 提交**

docs/03：页面结构节更新（49 注册页=14 新+35 旧、自定义导航、组件清单、菜单驱动）+ 版本行；docs/12 H 组 M2 行 ✅（证据：守卫 10/10、M0 11/11、lint、走查结论）+ 修订行；M0 映射文档追加 R1 修订行。
```bash
git add app.json scripts/verify-miniapp-m0.js scripts/e2e/ docs/03-小程序设计.md docs/12-未完成事项清单.md docs/superpowers/specs/2026-11-15-miniapp-redesign-m0-mappings.md
git commit -m "feat: M2 壳收口（app.json 49 页/无 tabBar/custom 导航）+ 路由守卫 10 检查 + 模拟器走查 + 文档回写"
```

- [ ] **Step 5: 呈交 M2 证据，进入 M3 计划**

---

## Self-Review 记录

- spec §1.1 主包 17 = 蓝图 11 + 保留 6：M2 建 14（order 三页属 M3），login/agreement、message、common×2 沿用旧路径保留 → 主包注册 17 在 M3 闭合（order 三页新建时）。✔
- spec §1.2 导航形态：custom-tabbar/nav-bar/reLaunch/preloadRule（M3 随子包加）✔；§2 设计系统全令牌/字体/图标/组件 ✔；§3.2 单入口登录 ✔；§3.3 切换工作台 ✔；§3.5 四层守卫 ✔；§6.1 路由脚本 ✔。
- 缺口：spec §2.5 signature-paper/photo-thumb 两组件属 M3/M5 签字链路，本批不建（在 M3 计划登记）。perm-button 二档「捕获阶段拦截」小程序无 DOM 捕获概念 → catchtap 等价（事件不冒泡即拦截），注释标明。
- 类型一致性：menus.resolveHome(roles:Array)、guard.canAccess(meta,{token,roles,perms})、nav.open(url:String)、home.getXxxHome() 视图模型字段与 Task 7 页面消费一致。