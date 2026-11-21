# 管理端（maintenance-admin）

电梯无纸化维保系统 Web 管理端。系统事实与业务不变量以根目录 `docs/00-项目摘要.md` 为准。

## 技术栈

Vue 3 + Vite + Pinia + Vue Router + **自建组件库**（`src/ui/`，Element Plus 已移除，不引新 UI 框架，AGENTS §5.3）。

## 本地开发

```bash
cd admin
npm install
npm run dev        # http://localhost:5173，/api 代理到 http://localhost:8080
```

先启动后端（仓库根目录，见根 README §快速开始）。开发一律用 Vite 5173；8080 上的管理端是
`backend/src/main/resources/static` 构建产物，需 `npm run build:backend` 同步。

## 演示账号（SEED_DEMO_DATA=true 种子，密码统一 123456）

| 账号 | 角色 | 管理端权限 |
|---|---|---|
| 13800000009 | ADMIN 维保部管理员 | 全部功能（唯一全权管理员；SYS_ADMIN 已并入 PLATFORM_ADMIN，旧 13800000010 已下线） |
| 13800000002 | LEADER 班组长 | 只读（看板/工单/台账/统计） |
| 13800000001 | WORKER 维保人员 | 无管理端权限（登录 403，走小程序） |

角色口径：`LEADER` 只读；`ADMIN`/`PLATFORM_ADMIN` 可写；`WORKER`/客户登录 403（AGENTS §5.3）。
权限以后端强校验为准，前端隐藏只是体验优化。

## 构建与测试

```bash
npm run lint       # eslint（含 .vue）
npm test           # vitest：路由守卫 / 权限矩阵 / 菜单 / 组件
npm run build      # 产物 dist/（Windows esbuild Access is denied → TMP/TEMP 指向 admin/.tmp-build）
```

CI（`.github/workflows/backend-build.yml`）已包含本目录 lint/test/build 门禁。

## 红线

- 不提供平台凭证（`REG_*`）配置界面（AGENTS §3.7）。
- 不提供批量自动重试入口，重报仅逐条人工触发（AGENTS §3.3）。
