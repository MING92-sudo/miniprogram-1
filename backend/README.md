# maintenance-backend（自建后端骨架）

Spring Boot 3 后端骨架，定位为小程序与监管平台之间的 **token 中控 + 接口转发层**：

- 平台凭证（`REG_*`）只存在于此服务进程，小程序前端永远不接触真实凭证；
- 小程序统一调用 `https://<本服务域名>/api/v1/**`；
- 本服务用 `PlatformTokenService` 登录换 token 并缓存，`PlatformClient` 转发业务请求。

## 目录

```
backend/
├─ pom.xml
└─ src/main/
   ├─ java/com/cqwlw/maintenance/
   │  ├─ MaintenanceBackendApplication.java   # 启动类
   │  ├─ config/        # 平台凭证配置、HTTP 客户端
   │  ├─ common/        # 统一响应、全局异常
   │  ├─ controller/    # health / auth / reports 入口
   │  └─ service/       # 平台 token 中控、平台转发
   └─ resources/application.yml
```

## 运行

```bash
cd backend
mvn spring-boot:run   # 本地开发默认 8080（PowerShell: $env:SERVER_PORT='8080' 可自定义）
```

启动后验证：`GET http://localhost:8080/health`

## 微信云托管部署

本骨架已按 wxcloudrun-springboot 模板规范对齐：容器监听端口 **80**、`Dockerfile`
（JDK 17 多阶段构建）、`settings.xml`（腾讯云 Maven 镜像）、`container.config.json`。

### 小程序端调用（无需配 request 合法域名）

```js
wx.cloud.callContainer({
  config: { env: 'prod-d3gg6nba2f3160af9' },
  path: '/api/count',
  header: { 'X-WX-SERVICE': 'springboot-l1ws' },
  method: 'POST',
  data: { action: 'inc' }
})
```

### 部署/发布流程（二开仓库 MING92-sudo/miniprogram-1）

```bash
git add backend
git commit -m 'update' && git push -u origin master
```

随后在云托管控制台：

1. 流水线/手动上传时构建目录选择 `backend/`（Dockerfile 位于该目录）；
2. 「服务设置」监听端口保持 **80**；
3. REG_* 平台凭证在「服务设置 → 环境变量」配置，严禁写入代码或 container.config.json。

> 骨架阶段未接入 MySQL；接入时开启「云托管 MySQL」并在服务设置勾选打通，
> 连接信息通过 MYSQL_ADDRESS / MYSQL_USERNAME / MYSQL_PASSWORD 环境变量注入。

## 待实现（骨架 TODO）

1. 微信登录：code2session → openid → 自建 JWT（`AuthController`）
2. 平台 token 获取与缓存：`PlatformTokenService`（注意 V1.5 规范 scret 拼写）
3. 业务转发：`PlatformClient`（表单 x-www-form-urlencoded，非 JSON）
4. 上报留痕与失败挂起/人工处理策略（`retryAuto` 默认 false）
5. 数据库、鉴权拦截器、文件上传代理
