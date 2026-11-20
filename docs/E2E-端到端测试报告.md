# 端到端测试报告（全端点）

> 执行时间：2026-11-20 00:20—01:40（GMT+8）
> 被测系统：`miniprogram-1` 后端（Spring Boot 3.3.4 + MyBatis-Plus + Flyway V1—V54 + MySQL 8.4）
> 测试入口：`scripts/e2e/run.js`（Node 22，无第三方依赖，内置 fetch）

## 一、结论

| 维度 | 结果 |
|---|---|
| 端到端用例 | **307 项，全部通过（307/307）** |
| 端点覆盖 | **210/210（100%）**，来自 36 个 Controller（`scripts/e2e/coverage.js` 静态比对，可复跑；2026-11-20 删除 `GET /auth/menus` 与两个 `bind-*` 遗留通道后由 212 降为 210） |
| 后端单元测试回归 | **721 项全部通过**（改动后回归，含 12 项新增缺陷回归用例，无失败） |
| 测试中发现并修复缺陷 | **10 个**（其中 4 个为线上必现故障） |

三套件构成：

| 套件 | 项数 | 说明 |
|---|---|---|
| A/B 认证与权限矩阵 | 49 | 统一登录分流、双主体边界、令牌失效、越权拒绝 |
| C 全端点 smoke | 204 | 36 控制器逐条调用，断言无 5xx、契约字段正确 |
| F 核心业务流 | 54 | 建档→客户账号→工单签到→动态码→清单→双签→签退→发起签字→客户签字→归档 |

## 二、测试方法

后端契约把业务失败表达为 **HTTP 200 + `body.code != 0`**（`GlobalExceptionHandler`），因此判定不看 HTTP status：

- `code === 0` → 成功
- `code ∈ {4xx, 14xx, 2xxx}` → 业务拒绝（可预期）
- HTTP ≥ 500 **或** `code ∈ [500,999]` → 服务端缺陷，判 FAIL

角色矩阵覆盖 11 个员工身份（ADMIN / SYS_ADMIN / 维保经理 / 维保技师 / 班组长 / 维保工 ×2 / 安装主管 / 项目经理 / 资料员 / 安装员）与 4 个客户账号（两个单位）。

破坏性操作用脚本自建的临时实体（使用单位 / 客户账号 / 员工 / 电梯 / 区域），收尾删除；演示数据只做读写验证，不改语义。

## 三、发现并修复的缺陷

### 3.1 线上必现故障

| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| 1 | `GET /home/summary` 恒定 500，**管理端首页打不开** | `WorkOrderService` 里 `.and(w -> { if (creatorIds != null) {...} })`：特权角色 `creatorIds=null` 时条件块为空，MyBatis-Plus 仍拼出 `AND ()`，MySQL 报 1064 语法错误 | 条件块整体移出链式调用，仅在需要时附加；空集合改 `IS NULL` 而非 `IN ()` |
| 2 | 移交单 / 外接备案 / 计划冲突等**详情接口 500** | `JsonUtil.MAPPER = new ObjectMapper()` 是裸版本，未注册 `JavaTimeModule`；`convertValue(实体, Map)` 遇 `LocalDateTime` 抛 "Java 8 date/time type not supported" | 注册 `JavaTimeModule`，`LocalDateTime` 固定 `yyyy-MM-dd HH:mm:ss`，与 Spring MVC 的 ObjectMapper 同口径 |
| 3 | **登出后令牌仍可继续使用**（安全） | `POST /auth/logout` 是空实现，只返回 `{ok:true}`，不吊销会话 | 从 Authorization 解析 JWT 主体，员工清 `session_id`/`session_admin` 并 `scope_version+1`，客户清会话并 `scope_version+1`；旧令牌立即 401 |

### 3.2 契约与健壮性

| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| 4 | 缺查询参数 / 非 multipart 上传打过来，返回 **500** | `MissingServletRequestParameterException`、`MultipartException`、`HttpMediaTypeNotSupportedException`、`MethodArgumentTypeMismatchException` 未映射，落到兜底 handler | 补 4 个映射：缺参 400、类型错 400、非 multipart 415、媒体类型 415 |
| 5 | `POST /grants` 缺 `startAt/endAt` 时 500 | `LocalDateTime.parse(String.valueOf(body.get("startAt")))` 对 null 解析字符串 `"null"` | 新增 `requireDateTime`：缺失/格式错统一 422，并校验结束晚于开始 |
| 6 | `POST /admin/migration-issues/{id}/resolve` 传错类型 500 | `(Map) body.get("decision")` 直接强转，传字符串即 ClassCastException | 改 `instanceof Map` 判型，非对象按缺省处理 |
| 7 | 用规范角色码建员工被拒（422「角色不合法：MAINT_TECH」） | `RoleCatalog.known/assignable` 只查历史码表（WORKER/LEADER…），而登录响应、种子数据、角色分配均已切 13 个规范码 | `known` 改走 `canonical`；新增 `assignable` 按 `principalTypeOf` 判定，历史码与规范码等价接受，客户主体仍排除 |
| 8 | 删除使用单位后，其**客户账号仍能登录**（数据完整性） | `deleteUseUnit` 只删 `use_unit`，不处理 `client_account` | 删除前解绑维保记录的客户签字外键（保留姓名快照），再级联删该单位客户账号，返回 `removedClientAccounts` |
| 9 | **登出「假成功」**：写库吊销若失败，接口仍报 `{ok:true}`，会话实际未吊销 | `logout` 的 `try` 块把 `employeeMapper.update(...)` 也包在内，任何 `RuntimeException` 被 `catch (RuntimeException ignore)` 静默吞掉——与缺陷 3 同一后果的隐藏入口 | 收窄 `try` 只包 JWT 解析；令牌有效但吊销失败必须向外抛错。补 5 例回归单测（含「吊销失败必须抛出」） |
| 10 | **客户签字必定 500**（`confirm-by-token` / `confirm`），整条签字链路不可用 | 签退时 `r.safetyJson = JsonUtil.write(body.get("safetyConfirm"))`，前端不传即写入字符串 `"null"`；PDF 归档时 `JsonUtil.MAPPER.readValue("null", Map)` **正常返回 null 不抛错**，`safetyLine` 随即 `flags.get()` → NPE → 事务回滚 | 写入侧缺省写 SQL `NULL`；读取侧对 null 结果兜底为空 Map。补 1 例 PDF 渲染回归（`safetyJson="null"` 仍出 PDF） |

> 前 4 个是用户可感知的功能故障（首页白屏、详情打不开、登出无效、客户签字 500），其余为契约违规与越权/脏数据风险。

## 四、关键链路验证结果

### 4.1 统一登录分流（用户核心诉求）

| 账号 | 主体 | 角色 | 落点 |
|---|---|---|---|
| 13800000003 / 123456 | CUSTOMER | CLIENT_ADMIN | 客户工作台（uu_1） |
| 13800000012 / 123456 | CUSTOMER | CLIENT_SIGNER | 客户工作台（uu_1） |
| 13800003005 / 123456 | CUSTOMER | CLIENT_ADMIN | 客户工作台（uu_2） |
| 13800000009 / 123456 | EMPLOYEE | PLATFORM_ADMIN | 管理端 |
| 13800000011 / 123456 | EMPLOYEE | MAINT_TECH | 维保工作台 |

- 客户账号走 `client=admin` → 403；维保人员走管理端 → 403；班组长可读管理端（`console:read` 含 LEADER，设计如此）但写档 403。
- 客户令牌访问 `/admin/**`、`/users`、`/work-orders` → 403；访问 `/unit/**`、`/auth/client/me` → 200。
- 停用客户账号 → 登录 403，启用后恢复 200。

### 4.2 工单现场作业闭环（22 步全绿）

签到（带定位）→ 状态转 PROCESSING → 重复签到拒绝 → 生成双人动态码 → 错误码拒绝（1003）→ 配合人凭码签到 → 读取 31 项清单 → 逐项填报 → 主维保/配合人双签 → **时长不足拦截（422，门禁生效）** → 回拨签到时间 → 签退生成记录 → 工单转 DONE → 记录 `PENDING` → 客户待签列表可见 → 分享链接签字视图可读 → **发起使用单位签字（双签齐门控，PENDING_CLIENT_SIGN → PENDING_CLIENT_SIGNATURE）** → 客户凭 share_token 匿名签字 → `CONFIRMED` 落库 → 归档投影（PDF 重签 URL + SHA-256）。

> 该链路曾出现「假绿」：早期版本漏了「发起使用单位签字」这一步，`confirm-by-token` 报 422 后流程静默中断，剩余 3 步没跑却因分母同步变小而显示 18/18。已补 ⑰c 步骤并改为断言成功，现 22/22 且每步都真实执行。

### 4.3 建档闭环

新建使用单位 → 开通客户账号 → 新客户登录自动分流 → 客户可见本单位电梯（初始 0 台）→ 新建员工（规范码 MAINT_TECH）→ 新建电梯（5 项档案必填）→ 客户立即可见 → 管理端可见 → 改名落库 → 清空必填项被拒（422）→ 倒序删除 → **删除后账号不可登录**。

### 4.4 覆盖率核对（`coverage.js`）

静态扫描 `@Get/Post/Put/Delete/PatchMapping` 得到 36 个 Controller、212 条路由，与三套用例中实际出现的路径字面量逐条比对，要求 100%。本轮核对补出 5 个盲区：

| 端点 | 处理 |
|---|---|
| `POST /auth/bind-wechat` | 补 smoke：微信未配置 → `2001`（业务码，非 500） |
| `POST /auth/change-password` | 补 smoke：错误原口令 → `422` |
| `POST /auth/bind-employee` | 补 smoke 负向：WORKER 在 body 指定 `LEADER` → `403`（角色只取库中真实值） |
| `POST /auth/bind-use-unit` | 补 smoke 负向：WORKER 调用 → `403` |
| `GET /location/reverse` | 用例本已覆盖，是核对脚本未剥 `?query` 造成误报，已修脚本 |

> **遗留通道已清理（2026-11-20）**：`/auth/bind-employee`、`/auth/bind-use-unit` 在统一登录改造后已无任何页面调用（`pages/`、`components/`、`services/` 零引用），且 `bind-use-unit` 签发 `UNIT_ADMIN` 员工令牌，与「客户只能从 `client_account` 登录」的口径相悖。实测越权防护有效（非本人角色一律 403），不构成在途漏洞，但属「登录后自铸角色令牌」的能力口子，已连同 `roleLogin()`、前端封装、mock 路由一并删除。`cases.smoke.js` 现以 **404** 断言锁死，重加即红。

### 4.5 客户签字链路的两处边界（已实测，非缺陷但需知悉）

| 边界 | 实测结果 | 影响与建议 |
|---|---|---|
| `POST /files/upload` 不在客户令牌白名单 | 客户令牌 403（smoke C11 固定断言） | 客户门户**无法自行上传签名图**；要么由员工侧上传后把 `fileId` 带过来，要么用 `signatureUrl` 内联 base64。若要实现「客户在小程序里手写签名」，需把 `POST /files/upload` 加进客户白名单（仅限图片/大小受限） |
| 客户签字只实现到 `services/` 层 | `confirmByToken` / `getSignView` / `refuseRecordByToken` 有封装，但 `pages/`、`components/` **零调用**，`app.json` 里也无对应页面 | 后端契约完整可用（本次端到端已验证透明通道全绿），**前端签字页尚未开发**；且匿名通道签字人姓名为空（`client_signer_name` 仅客户令牌路径落库），合规上建议引导客户登录后签字而非纯匿名链接 |

## 五、未验证 / 环境受限项

| 项 | 原因 | 当前表现 |
|---|---|---|
| 微信登录 `POST /auth/wx-login`、`reset-password-by-wechat` | 未配置 `WX_APPID/WX_APPSECRET` | 返回 `2001 微信登录未配置`（业务码，非 500） |
| 监管平台上报 `/platform/*` | 未配置 `REG_*` 凭证 | `2001 监管平台凭证未配置`；注册类端点缺参时 400 |
| PDF 导出（记录/移交/故障） | 依赖运行环境字体与文件存储 | 仅验证不 500，未比对版式 |
| 文件下载 `/files/{id}` | 需 URL 时效签名 | 无签名 403，属预期 |
| 排班生成 `POST /admin/plans/generate` | 需要月份/组织等前置参数 | 当前 422（缺参），未构造完整排班数据 |
| 小程序模拟器自动化 | 本机 IDE（2.02.2609231）自动化通道需 GUI 授权确认，无人值守下停在取 AppID 一步 | 见第六节说明 |

## 六、小程序端说明

登录页结构已在模拟器实测（`.lg-sub`/`.lg-roles`/`.lg-role-mini` 均为 0，说明旧文案块已彻底移除；`.lg-input` 2 个、`.lg-card`/`.lg-points`/`.lg-stats`/`.lg-foot` 在位）。

「输账号 → 自动分流」的页面级验证依赖 IDE 自动化通道。**需要在微信开发者工具里手动确认一次授权弹窗**（IDE 的「设置 → 安全设置」中开启服务端口与自动化，并在弹出的信任提示点允许），之后执行：

```bash
cd "/c/Program Files (x86)/Tencent/微信web开发者工具"
./cli.bat auto --project "C:\Users\18223\Desktop\WXapp\miniprogram-1" --auto-port 9431 --port 39882
cd /tmp/wxauto && node login-split.js 9431
```

> 注意：`config/index.js` 的 `ENV` 为 `prod` 时走云托管，模拟器无法访问本地 8080。要在模拟器里连本地后端，需临时改 `ENV='dev'`（已验证可直连），验完还原。
> `project.private.config.json` 的 `urlCheck` 已置 `false`（该文件在 `.gitignore`，仅本机生效）。

## 七、复跑方式

```bash
# 后端需在 8080 运行（按 README §5.2 带绝对 workingDirectory 启动，否则读不到根 .env）
cd miniprogram-1
node scripts/e2e/run.js              # 全量（认证 + 全端点 + 业务流）
node scripts/e2e/run.js --only auth  # 只跑认证矩阵
node scripts/e2e/run.js --only smoke # 只跑全端点
node scripts/e2e/run.js --only flow  # 只跑业务流
node scripts/e2e/coverage.js         # 端点覆盖核对（210/210，缺失即非 0 退出）
```

- 退出码：`0` 全通过、`1` 有失败、`2` 环境不可用
- 明细报告：`scripts/e2e/reports/latest.json`（含每项 http/code/message/耗时）
- 业务流会复位演示工单 `wo_1` 到未签到态（测试专用，非生产行为）

## 八、文件清单

| 文件 | 作用 |
|---|---|
| `scripts/e2e/lib.js` | HTTP 客户端、断言、结果收集、汇总 |
| `scripts/e2e/seed.js` | 账号口令与演示数据 ID 基线 |
| `scripts/e2e/cases.auth.js` | 认证与权限矩阵（49 项） |
| `scripts/e2e/cases.smoke.js` | 全端点 smoke（204 项） |
| `scripts/e2e/cases.flow.js` | 核心业务流（54 项） |
| `scripts/e2e/coverage.js` | 端点覆盖核对（36 Controller / 212 路由） |
| `scripts/e2e/run.js` | 运行器与报告落盘 |
