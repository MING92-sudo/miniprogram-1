# 智慧特种设备维保小程序

- 前端：微信小程序（本仓库根目录，微信开发者工具打开）
- 后端：Spring Boot 3 骨架，位于 `backend/`（详见 `backend/README.md`），
  已按微信云托管 wxcloudrun-springboot 模板规范对齐：容器端口 80、Dockerfile、腾讯 Maven 镜像。

## 云托管部署

- 代码仓库流水线以**根目录 Dockerfile** 构建，实际后端源码在 `backend/`；
- `REG_*` 平台凭证一律配置在云托管「服务设置 → 环境变量」，严禁入库。
