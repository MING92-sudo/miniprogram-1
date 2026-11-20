# 小程序端 BUG 测试报告

> 执行时间：2026-11-20 01:20—02:15（GMT+8）
> 被测对象：`miniprogram-1` 小程序（微信开发者工具 2.02.2609250，基础库模拟器）
> 后端：本地 Spring Boot（127.0.0.1:8080），`config/index.js` 临时切 `ENV='dev'` 直连（验完已还原 `prod`）
> 驱动方式：IDE 服务端口 39882 + 自动化端口 9420，`miniprogram-automator` 0.13.0 驱动真机模拟器

## 一、结论

| 维度 | 结果 |
|---|---|
| 巡检范围 | 49 个注册路由（主包 19 + 分包 30），3 个角色（平台管理员 / 维保技师 / 客户管理员） |
| 发现 BUG | **6 个**（2 个 P0 阻断级，2 个 P1 功能级，2 个 P2 体验级） |
| 已修复 | **5 个**（第 6 个为 console warning，定位后待修） |
| 修复后验证 | 客户 / 员工两条登录链路、列表→详情纵深链路在模拟器内全部走通；后端 724 单测全绿 |

**最严重的两个问题同源**：客户账号统一到 `client_account` 后，多个「客户端白名单放行的员工端点」内部仍按员工主体判断，导致客户被放行却拿不到数据、甚至被判未登录。

## 二、BUG 清单

### BUG-1（P0，已修）客户登录成功后立刻被踢回登录页

| 项 | 内容 |
|---|---|
| 现象 | 客户账号输账号密码登录，toast 提示「登录成功」，随即页面回到登录页；`em_token` 被清空。三个不同账号（管理员/技师/客户）风测时甚至全部落到同一个管理端页面 |
| 根因 | 登录成功后 `App.setAuth` → `loadMenus()` → `GET /me/menus`。该端点在客户端白名单内，但 `MiniappMenuController.currentUser` 只查 `employeeMapper`，客户令牌 subject 是 `client_account.id` → 查不到 → 抛 **401**。前端 request 层把 401 当「登录已失效」→ `clearAuth()` + `redirectToLogin()`，把刚写入的登录态清掉 |
| 证据 | 直连接口 `POST /auth/login` 返回完全正确（`principalType=CUSTOMER`、`home=/pages/client/home/index`、token 318 字符）；`GET /api/me/menus` 带客户令牌返回 `{"code":401,"message":"登录已过期，请重新登录"}`；hook `wx.showToast` 抓到 `{"title":"登录成功"}` 但 `wx.getStorageSync('em_token')` 为空 |
| 修复 | 前端 `services/menu.js`：客户主体直接走本地镜像，不请求 `/me/menus`；后端 `MiniappMenuController`：客户主体返回 200 + 客户菜单结构（`principalType=CUSTOMER`、`home` 客户首页、空 tabs） |
| 验证 | 客户登录 → `pages/client/home/index`，`role=CLIENT_ADMIN`、`principal=CUSTOMER`、`home` 正确 |

### BUG-2（P0，已修）客户主体在员工端点上全线失效（空数据 / 1403 / 401）

| 项 | 内容 |
|---|---|
| 现象 | 客户能进电梯列表但显示「暂无电梯」（实际有 2 台）；点进电梯详情报「无权查看其他使用单位电梯」；`/auth/me` 返回 401 |
| 根因 | `ElevatorController.unitAnchor()` 用 `sys_user_role`（`user_type='EMPLOYEE' AND role_code LIKE 'CLIENT%'`）取组织锚点——这是「员工持 CLIENT 角色」的历史形态。客户统一到 `client_account` 后锚点在 `client_account.use_unit_id`，旧口径对客户令牌恒查不到 → 锚点 null → 列表空、`requireUnit` 判 1403。`/auth/me` 同 BUG-1 的成因 |
| 证据 | 客户令牌 `GET /api/elevators` → `{"code":0,"data":[]}`；`GET /api/unit/elevators` → 2 台；`GET /api/elevators/el_1/profile` → 1403「无权查看其他使用单位电梯」 |
| 修复 | `ElevatorController`：`unitAnchor` 优先按客户主体从 `client_account.use_unit_id` 取锚点（`ClientAccountMapper` setter 注入，不动构造器签名），旧 `sys_user_role` 口径保留为回退；`AuthController.me` 增加客户主体分支返回账号信息 |
| 验证 | 客户令牌 11 个白名单端点全绿（`mp-client-endpoints.js`）：`/elevators` len=2、`/elevators/{id}/profile` 正常、`/auth/me` code=0 |

### BUG-3（P1，已修）客户电梯列表页走错接口，显示「暂无电梯」

| 项 | 内容 |
|---|---|
| 现象 | 客户从工作台进电梯列表，页面显示空态「电梯建档后会出现在这里」，但客户名下确有电梯 |
| 根因 | `pages/elevator/list/index.js` 固定调 `getElevatorList()` → `GET /elevators`（员工读接口，按员工数据域过滤）；客户应走门户投影 `GET /unit/elevators` |
| 修复 | 按 `isCustomerPrincipal()` 分流：客户走 `unit.getUnitElevators()`，员工保持原接口 |
| 验证 | 客户列表能取到 `el_1` 并进入详情，标题显示「世纪大厦 1# 客梯」 |

> 注：修完 BUG-2 后后端 `/elevators` 对客户也能返回数据，但前端仍保留门户接口——分层更清晰，且门户投影字段（`nextMaintenanceDate` 等）才是客户页面消费的口径。

### BUG-4（P2，已修）电梯详情页缺 id 时把后端错误直接显示给用户

| 项 | 内容 |
|---|---|
| 现象 | 分享/扫码直达电梯详情（无 `id`）时，页面显示「接口不存在」 |
| 根因 | `onLoad` 无条件 `fetchProfile()`，`id` 为空时请求 `/elevators//profile`，网关按「接口不存在」返回 |
| 修复 | `id` 为空时不发请求，直接提示「缺少电梯参数，请从电梯列表进入」；`fetchProfile` 内同样加守卫（防止 `onRetry` 空跑） |

### BUG-5（P2，已修）移交单详情缺 id 时提示文案误导

| 项 | 内容 |
|---|---|
| 现象 | 无 `id` 进入移交单详情，页面显示「加载失败」，像是后端故障 |
| 根因 | 页面已做了「无 id 不请求」的保护，但错误文案复用了 `TOAST_TEXT.LOAD_FAILED` |
| 修复 | 改为「缺少移交单参数，请从移交单列表进入」 |

### BUG-6（P3，待修）`nav-bar` 组件 title 收到 null

| 项 | 内容 |
|---|---|
| 现象 | 控制台 warning：`property "title" of "components/nav-bar/index" received type-uncompatible value: expected <String> but get null value` |
| 初步定位 | 多个页面用 `title="{{text.TITLE}}"` / `{{text.LIST_TITLE}}`，首帧 `data.text` 尚未赋值时传 null。已核对 `ELEVATOR_TEXT`/`ORDER_TEXT`/`PROFILE_TEXT`/`RECORD_TEXT` 都含对应键，非缺常量 |
| 影响 | 仅控制台噪音，标题在下一帧正常渲染；功能无影响 |
| 建议 | `components/nav-bar` 的 `title` 属性加 `value: ''` 默认值，或 `observers` 里兜底 `String(v || '')` |

## 三、顺带发现（非 BUG，但建议清理）

| 项 | 说明 |
|---|---|
| 登录页 data 残留已下线文案 | `pages/login/index.js` 的 `text` 仍保留 `EYEBROW`/`BRAND_SUB`/`POINTS`/`STATS`/`FLOORS`；这些区块的 DOM 已按用户要求删除（`.lg-sub`/`.lg-points`/`.lg-role-mini` 实测计数为 0），但数据键没清，属死数据 |
| 客户待办为空是真实空态 | `GET /unit/records/pending` 返回 `{list:[],total:0}`，与页面空态一致，不是 BUG |
| 客户签字页未在 `pages` 中实现 | `services/unit.js` 有 `confirmByToken`/`getSignView` 封装但无页面调用（与后端 E2E 报告 §4.5 结论一致） |

## 四、测试方法（可复跑）

```bash
# 1) 后端（本地）
cd miniprogram-1
mvn -o -f backend/pom.xml spring-boot:run "-Dspring-boot.run.workingDirectory=<仓绝对路径>"

# 2) 小程序临时切 dev（连本地后端），验完改回 prod
#    config/index.js: const ENV = 'dev'

# 3) 自动化巡检（token 只从环境变量读，不落库）
export WX_DEVTOOLS_TOKEN=<IDE 安全设置里的自动化 token>
bash scripts/e2e/mp-automate.sh 13800000003 13800000011   # 客户 + 技师
bash scripts/e2e/mp-automate.sh --check                   # 客户端点可用性穿透
```

| 脚本 | 作用 |
|---|---|
| `scripts/e2e/mp-bugs-1.js` | 未登录巡检：路由可达性 + 白屏/守卫跳转统计 |
| `scripts/e2e/mp-bugs-2.js` | 单角色登录 + 全路由巡检（异常/403/踢回登录） |
| `scripts/e2e/mp-bugs-3.js` | 带真实 id 的「列表→详情」纵深链路 |
| `scripts/e2e/mp-diag.js` | 登录链路诊断（直连接口 / onSubmit / toast 三段取证） |
| `scripts/e2e/mp-client-endpoints.js` | 客户令牌穿透白名单内全部端点，找「放行但主体不支持」 |
| `scripts/e2e/mp-automate.sh` | 统一入口（启动 IDE → 开自动化 → 跑巡检） |

## 五、踩坑记录（下次直接照做）

1. **IDE 是 CLI 的子进程**：`cli.bat open/auto` 返回后进程立即被回收，端口随之消失。必须把「启动 + 巡检」放在同一次命令里（`mp-automate.sh` 就是这么写的）。
2. **不要用 `cli.bat` 启动的 IDE 做长驻调试**：`cli.bat` 会 `set ELECTRON_RUN_AS_NODE=1`，IDE 继承后可能以 node 模式退出。
3. **自动化端口连上 ≠ 就绪**：`automator.connect` 成功后首个请求可能 `timeout waiting for automator response`，需先 `cli open` 打开项目，再 `cli auto`。
4. **automator 0.13.0 没有 `page.evaluate`**：只有 `mini.evaluate(fn)`；Page 只提供 `data()`/`$(sel)`/`$$(sel)`/`callMethod()`。
5. **巡检必须先清 storage**：IDE 保留上次会话的 `em_token`，`guard` 会直接把页面导向上次角色首页，让「登录失败」看起来像「登录成功但分流错误」（第一轮就踩了，三个账号全落同一页）。
6. **登录成功判据用 token 长度，不要用「离开登录页」**：后者会被旧登录态污染。
7. **`reLaunch` 后旧 Page 句柄失效**（`page is not on top of page stack`），后续一律用 `mini.evaluate` 重新取 `getCurrentPages()`。
