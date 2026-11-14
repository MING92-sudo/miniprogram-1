# 博威无纸化小程序重构 M5 使用单位域实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans.

**Goal:** pkg-client 4 页（todo/sign/orders/evaluate）+ 签署门控 + 评价；client 工作台接线。

**Spec:** spec §4.4/§3.7；M0 映射 §4；屏 11。

## Tasks（已完成）
- T1: sign（屏 11，taskId+token 免登录，getSignView/confirmByToken/refuseRecordByToken，勾选门控§8.4 第二档 + 手写 canvas + 版本/哈希核对 + 拒签必填原因）
- T2: todo（待签记录+待确认急修合并，CLIENT_SIGNER 校验）/orders（本单位只读）/evaluate（1–5 星+文字，evaluateFault）
- T3: 收口（app.json pkg-client 子包 + preloadRule + routes built M5 + nav 映射清空 + record-detail/checkout 签署链接改 pkg-client/sign + 门禁 + docs）

## 裁决
- R1：旧 unit/* 暂留至 M6（门户未迁移账号兜底 M1-R4，unit/home+mine 保留）；spec M5「删旧 unit/*」延后到 M6 与门户兜底一并清理。
- R2：unit.js→client.js 重命名留 M6（避免触及大量 legacy require 与并行会话）；新 pkg-client 页暂用 services/unit（portalGet 统一令牌优先+unit_token 兜底）。
- R3：sign 页 taskId 兼容 rid（taskId=recordId）；token 一次性校验后端权威。