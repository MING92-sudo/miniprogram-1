# 项目规则（AGENTS.md）

> 电梯无纸化维保系统——编码与协作规则。AI 助手与人工开发者均须遵守。
> V4.0（2026-11-21）：文档体系收敛为 **本文件 + `docs/00-项目摘要.md`** 两份；旧 docs/01—16 及测试报告已删除（git 历史可查）；AOCI 已停用并整体移除。

## 1. 先读什么

- 系统事实、业务不变量、平台实测结论、上线门禁 → `docs/00-项目摘要.md`
- 接口契约 → `mock/server.js` + `services/`（代码即契约）
- 平台规范 V1.5 原文 → `doc_text.txt`（只读，本地文件不入库，全新克隆需另行获取；与实测冲突时以摘要 §4 为准）

## 2. 分层

1. 页面 → `services/*` → `utils/request.js`；页面禁止直接 `wx.request` / `wx.uploadFile`，业务数据不直接读 storage（走 `utils/offline.js`）。
2. 接口新增/修改**两处同步**：`services/` 方法 + `mock/server.js` 路由；方法签名不随 mock↔真实切换变化。
3. 生产默认 `wx.cloud.callContainer`；上传走 `services/upload.js` 的 `wx.uploadFile`；两条链路统一 `{code, message, data}`。
4. 后端平台调用按 `docs/00` §4 实测结论实现，不得按规范原文实现已被实测推翻的调用方式（如 2.1 用 POST）。

## 3. 安全红线（最高优先级）

1. 凭证（`REG_*`、`WX_APPSECRET`、`JWT_SECRET`、`COS_SECRET_*`、LBS key）只允许在 `.env`（gitignore）与云托管环境变量；严禁入库、入文档、入注释。
2. openid 只信 `wx.login` code → 后端 `jscode2session`；禁信任 `X-WX-OPENID` 头、禁 `dev_openid` 兜底；`JWT_SECRET` 无默认值，缺失即启动失败。
3. `originalRecordId` 幂等未经平台书面确认前**禁止任何自动重试**：仅 `FAILED` 记录逐条人工重报；不得新增批量重试端点/管理端批量入口/定时补偿。
4. 文件访问必须带时效签名（`GET /files/{id}` 校验 `?s=`）；签名与 token 不入日志。
5. 日志脱敏：不打印完整 token、手机号、openid、平台密钥。
6. 敏感文件不入库：`.env`、真实合同/证书 PDF、`scripts/tmp/`、trace/HAR。
7. 管理端不提供平台凭证配置界面，也不提供批量自动重试入口。

## 4. 业务不变量

签到围栏、双人动态码、D8 双人必填、区域抢单池认领、上报快照、派单基线、工单发号、单账号单会话与客户三层收敛等不变量，见 `docs/00-项目摘要.md` §3——**改动这些链路前必读**。绕过归属/状态校验向监管平台写虚假数据 = Blocker。

## 5. 编码约定

### 5.1 小程序
- CommonJS、ES2018、2 空格缩进、单引号；提交前根 `npm run lint` 必须通过。
- 错误码/文案/枚举只从 `constants/index.js` 取，禁止页面硬编码；services 方法动词开头。
- 时间一律 `yyyy-MM-dd HH:mm:ss`（GMT+8），用 `utils/util.js`；所有 POST/PUT/PATCH 自动携带 `X-Idempotency-Key`（`request.js` 已实现，新写接口不得绕过）。
- 照片/签名统一走 `services/upload.js` 与 `pages/common/watermark-camera|signature`；不引入新 UI 框架。

### 5.2 后端
- Java 17 / Spring Boot 3.3.4；统一 `ApiResponse`；配置经环境变量注入；容器端口 80。
- Flyway 只追加新版本，不得修改已执行过的迁移；写操作先校验（归属/状态/参数）后落库，失败不留半成品。
- 平台 token 缓存 TTL = `expires_in − 60s`，401 清缓存重登重试 1 次；改业务规则必须补同包单测（尤其签到/签退/动态码/派单/上报）。

### 5.3 管理端
- Vue3 + Vite + 自建组件库（`admin/src/ui/`）；不引新 UI 框架；权限以后端强校验为准，前端隐藏只是体验优化。
- 角色：`LEADER` 只读；`ADMIN`/`PLATFORM_ADMIN` 可写（SYS_ADMIN 已并入 PLATFORM_ADMIN）；`WORKER`/客户登录 403。

## 6. 提交前检查

| 改动范围 | 必跑 |
|---|---|
| 小程序根目录 | `npm run lint` |
| `admin/` | `npm run lint && npm test && npm run build`（Windows esbuild `Access is denied` → `TMP/TEMP` 指向 `admin/.tmp-build`） |
| `backend/` | `mvn test` |
| 签到/派单/模板链路 | 另跑 `node scripts/verify-dispatch.js`、`node scripts/verify-templates.js` |

- 小程序链路人工走查：登录 → 签到（主/配合分别）→ 清单 → 签退 → 记录预览；改离线队列补飞行模式断网补传。
- 平台写接口（2.3/2.4/2.6/2.8）会产生**真实监管数据**，跑 `scripts/*.ps1` 前必须确认。
- CI（`.github/workflows/backend-build.yml`）四道门禁全跑：后端 `mvn test`、`verify-dispatch/templates`、根 lint、admin lint/test/build。

## 7. Git

- `master` 为主干；AI 协作用 `codex/` 前缀分支，完成后按主题收敛回 master。
- 提交信息一行中文、动词开头；禁止凭证、token、报文原文入提交。
- 提交前 `git status` 确认无敏感文件；禁止未经确认的破坏性操作（`reset --hard`、`checkout --`、`clean -fd`、强推 master）。

## 8. AI 协作

1. 修改前先读目标文件与相邻实现，保持结构、命名、注释风格一致；不熟悉的文件/未提交改动视为用户工作，先调查再动。
2. 不擅自扩大范围：不顺手重构、不升级依赖、不开自动重试、不引新框架；确需变更先说明理由。
3. 完成后按 §6 自检，并在回复中说明改了哪些文件、跑了哪些检查、哪些未执行。
