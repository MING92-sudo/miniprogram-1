# 智慧特种设备维保小程序（电梯无纸化维保）

> 最后更新：2026-10-01（与代码同步核对）

## 项目组成

| 部分 | 位置 | 现状 |
|---|---|---|
| 微信小程序前端 | 仓库根目录（微信开发者工具打开） | ✅ 27 个页面、12 个业务模块，全流程可在 mock 下演示 |
| 自建后端 | `backend/`（Spring Boot 3.3.4 / Java 17 / Maven） | ✅ P2/P3：41 条业务路由 + 平台写链路（2.6 自动/手动重报）+ 管理端路由 |
| Web 管理端 | `admin/`（Vue3 + Vite + Element Plus，docs/09） | ✅ 一期 MVP：看板/工单监控/上报异常闭环/平台同步/档案管理/台账/统计 |
| 平台联调脚本 | `scripts/` | ✅ `test-full.ps1` 等已实测打通平台 2.2—2.8 |
| 项目文档 | `docs/` | 需求、架构、UI、接口、测试、平台确认清单、联调记录、开发计划（01—09） |

## 小程序前端

- **技术栈**：原生小程序 + 自建分层（`services/` 业务接口、`utils/` 请求/鉴权/离线队列、`mock/` 本地数据、`constants/` 枚举与错误码），UI 组件为自定义样式（未引用第三方组件库）。
- **页面（27 个）**：工单台/工单列表/详情/签到/动态码/作业清单/检查项/签退、水印相机、签名板、困人救援（登记/列表/详情）、故障（上报/详情/列表）、合规台账（自行检查/演练）、电梯档案（列表/详情）、知识库、消息中心、我的/离线队列、使用单位签字确认、登录页。
- **Mock 开关**：`config/index.js` 中 `useMock: true` 为前端独立演示模式（无需后端）；联调时改 `false` 并把 `apiBaseUrl` 指向自建后端，`services/` 层接口签名不变。
- **开发者工具工程范围**：微信开发者工具的项目根是仓库根，`project.config.json` 的 `packOptions.ignore` 已排除 `admin/`、`backend/`、`docs/`、`scripts/` 与 `.env*`。管理端每次 `npm run build` 都会改写 `admin/dist/assets/index-<hash>.js`，工具若仍索引到已删除的旧 hash，会报 `ENOENT ... admin/dist/assets/index-*.js`（不影响小程序本身）；此时执行「工具 → 清除缓存 → 清除全部缓存」后重新编译即可。
- **登录方式**：账号密码登录（账号=维保单位分配的手机号）+ 登录后绑定微信；微信侧 openid 由后端用 `wx.login` 的 code 调 `jscode2session` 换取（`WX_APPID`/`WX_APPSECRET`，doc/04 A.1），不再依赖 `X-WX-OPENID`；演示账号见 `mock/data.js`（密码统一 `123456`）。
- **离线能力**：`utils/offline.js` 持久化队列 + `app.js` 网络恢复自动补传。

## 自建后端（backend/）

定位：小程序与监管平台之间的 **token 中控 + 接口转发层**。平台凭证（`REG_*`）只存在于此服务进程，小程序前端永远不接触真实凭证。

```bash
cd backend
mvn spring-boot:run        # 默认 8080；容器内由 Dockerfile 设 SERVER_PORT=80
# 验证：GET http://localhost:8080/health
```

详细说明见 [backend/README.md](backend/README.md)。

## 监管平台对接（已实测结论）

- 业务根路径：`https://tzsb.scjgj.cq.gov.cn:1443/api/wlw/maintenance/`（= 网关 `/api/wlw` + 模块 `/maintenance`）。
- **2.1 登录实际为 GET + 查询串**（规范写的 POST + Body 是错的）；业务接口 2.2—2.8 为 **POST + `x-www-form-urlencoded` 表单**，2.3/2.4 为 multipart（带合同/证书 PDF）。
- 2026-09-30 全链路实测：2.2 主体查询 → 2.3 服务关系 → 2.4 人员登记 → 2.5 取 platform_id → 2.6 记录上报 → 2.8 存量上报 **全部成功**。详见 `docs/07-full-test-20260930.md` 与 `docs/07-平台联调实测记录.md`。
- **`originalRecordId` 幂等性仍未书面确认**：重复提交返回成功，是否产生重复记录需平台管理端核对；确认前自动重试开关 `REG_RETRY_AUTO` 必须保持 `false`。

## 凭证与安全

- `REG_*` 平台凭证一律配置在 `.env`（本地）/ 云托管「服务设置 → 环境变量」，**严禁入库**；`.env.example` 为模板。
- 微信小程序 `WX_APPID`/`WX_APPSECRET` 同样只进环境变量：`/auth/bind-wechat`、`/auth/wx-login` 用它把 code 换成真实 openid，这是 openid 的**唯一来源**（`DEV_OPENID` 配置与 `X-WX-OPENID` 请求头兜底已按 P0 修复移除——前者导致全员同一 openid 串号，后者在方案 A 下可伪造）。
- LBS 逆地址解析 key 同样不入库（`config/index.js` 中留空，调试时经 storage 注入）。

## 云托管部署

- 代码仓库流水线以**根目录 Dockerfile** 构建（内部转 `backend/` 多阶段构建），容器监听端口 80；
- 部署后小程序可用 `wx.request` 直连自定义域名（需在公众平台配置 request 合法域名），或用 `wx.cloud.callContainer` 免鉴权调用（无需配合法域名）；两种方式都通过 `/auth/bind-wechat`、`/auth/wx-login` 的 code → jscode2session 完成微信登录绑定（`X-WX-OPENID` 头不再被后端信任；「小程序免鉴权调用」可开可不开）。
- 对象存储：后端默认使用云托管托管桶（桶名/地域非凭证，已写入 `backend/src/main/resources/application.yml` 默认值），凭证走云托管内网临时接口 `/_/cos/getauth`，**无需在环境变量配置任何 COS 密钥**；仅自建 COS 桶本地联调时才注入 `COS_SECRET_ID/COS_SECRET_KEY`。

## Web 管理端（admin/，docs/09 一期）

```bash
cd admin
npm install
npm run dev        # http://localhost:5173，/api 代理到 localhost:8080（先启动后端）
npm run build      # 产物 dist/，生产走同域反代/静态托管（docs/09 §五）
```

角色与权限（后端强校验，非仅前端隐藏）：`LEADER` 只读；`ADMIN`/`SYS_ADMIN` 全部功能（档案维护、手动重报、触发平台同步）；`WORKER` 登录即拒（403）。演示账号（种子，密码均 `123456`）：

| 账号 | 角色 | 用途 |
|---|---|---|
| `13800000009` | ADMIN 维保部管理员 | 管理端全功能演示 |
| `13800000010` | SYS_ADMIN 系统管理员 | ADMIN 权限 + 用户权限/审计日志 |
| `13800000002` | LEADER 班组长 | 只读演示 |
| `13800000001` | WORKER 维保人员 | 小程序端（管理端登录被拒） |

红线：管理端不提供平台凭证（`REG_*`）配置界面（AGENTS §2.1）；不提供批量自动重试入口，重报仅逐条人工触发（AGENTS §2.3）。

## 相关文档索引

| 文档 | 内容 |
|---|---|
| `docs/01-需求文档.md` | 需求基准（V2.5，逐字核对 TSG T5002 与平台规范 V1.5） |
| `docs/02-技术架构文档.md` | 目标架构 + 当前实际形态对照 |
| `docs/03-UI设计文档.md` | 设计规范与页面清单（已与实际 27 页对齐） |
| `docs/04-接口文档.md` | A. 内部 API（已在 mock 全量实现）＋ B. 平台 V1.5 对接（已实测修订） |
| `docs/05-测试与验收方案.md` | 测试策略与当前执行状态 |
| `docs/06 / 06b` | 平台待确认清单、沟通话术 |
| `docs/07 / 07-full-test-20260930.md` | 平台联调实测记录（2026-09-30 全链路打通） |
| `docs/08-项目审查与开发计划.md` | 现状审查与分阶段计划（P0—P5） |
| `docs/09-管理端设计.md` | Web 管理端设计与一期落地记录 |
| `docs/10-管理端验收清单.md` | 管理端人工验收清单（角色矩阵/九大模块/红线自查） |
| `admin/README.md` | 管理端运行/构建/演示账号说明 |
| `AGENTS.md` | 项目规则（编码/提交/安全约定，AI 协作与人工共用） |
