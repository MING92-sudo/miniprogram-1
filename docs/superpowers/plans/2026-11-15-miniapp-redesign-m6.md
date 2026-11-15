# 博威无纸化小程序重构 M6 管理统计域 + 收尾实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans.

**Goal:** pkg-admin 7 页（只读投影 + 配置）+ 删全部旧页 + role-view + AGENTS §3.9 重写 + 基线计数 + 文档全同步。

**Spec:** spec §4.5/§7 M6；M0 映射 §4。

## Tasks（已完成）
- T1: services/admin.js（8 方法）+ mock 8 路由 + pkg-admin 7 页（projects/contracts/users/roles/stats/audit 只读 + settings 配置；users/audit/settings 挂 custom-tabbar）
- T2: 删 13 旧页（home/mine/elevator 旧/unit/*）+ role-view.js；登录页移除门户兜底（单账号）
- T3: app.json 从 routes 全量重建（精确 49：主包 17 + 4 子包）；M0 守卫收紧 routes-old→newCol + routes-total 49
- T4: AGENTS §3.9 单账号单会话多角色重写 + §10 V3.18 + 基线计数（49 页/13 services/V43/452 单测）
- T5: 门禁全绿（routes 10/10、M0 12/12、m1-sync 12/12、dispatch/templates/lint）+ docs/03 V2.18 / docs/04 V2.54 / docs/12 M6 回写

## 裁决
- R1：admin 页只读投影，写操作（项目/合同/账号/角色编辑）perm-button 一档禁用引导管理端（小程序不做管理写）。
- R2：unit.js→client.js 重命名延后（services/unit 仍被新页 portalGet 消费，单账号下走 em_token；重命名为 cosmetic，列后续）。
- R3：门户兜底移除（单账号 D1）；未迁移生产账号由 V41 迁移 + 生产清库人工门禁（G 组）覆盖。

## 剩余人工门禁（非代码）
- master 整合（并行会话协调，docs/12 H 组）。
- 生产清库（G 组，备份+用户确认）。
- 真实绘制走查（开启开发者工具服务端口后 node scripts/e2e/walkthrough.js）。
- 移交内部审核/作废后端端点（C-13）。