# 管理端（maintenance-admin）

电梯无纸化维保系统 Web 管理端，一期 MVP 按docs/09《管理端设计》实现。

## 技术栈

Vue 3 + Vite + Element Plus + Pinia + Vue Router + axios + ECharts + exceljs（均按 docs/09 §二选型）。

## 本地开发

```bash
cd admin
npm install
npm run dev        # http://localhost:5173，/api 代理到 http://localhost:8080
```

先启动后端（仓库根目录）：`java -jar backend/target/maintenance-backend-0.0.1-SNAPSHOT.jar`（见根 README）。

## 演示账号（SEED_DEMO_DATA=true 种子）

| 账号 | 密码 | 角色 | 管理端权限 |
|---|---|---|---|
| 13800000009 | 123456 | ADMIN 维保部管理员 | 全部功能（含档案维护、重报、触发同步） |
| 13800000010 | 123456 | SYS_ADMIN 系统管理员 | ADMIN 权限（用户权限/审计日志已按 docs/01 §10.2 裁剪，角色保留兼容） |
| 13800000002 | 123456 | LEADER 班组长 | 只读（看板/工单/台账/统计） |
| 13800000001 | 123456 | WORKER 维保人员 | 无管理端权限（登录被拒，走小程序） |

## 构建

```bash
npm run build      # 产物 dist/，同域反代部署（docs/09 §五）
npm run lint       # eslint（含 .vue）
npm test           # vitest：auth 守卫 + api 信封解包
```

## 红线

- 不做平台凭证（`REG_*`）配置界面（AGENTS §2.1）；环境变量仅 `VITE_API_BASE`（见 `.env.example`）。
- 不提供批量自动重试入口，重报仅逐条人工触发（AGENTS §2.3）。
