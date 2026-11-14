# 博威无纸化小程序重构 M4 移交/外接域实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Steps use checkbox syntax.

**Goal:** pkg-handover 12 页（蓝图 8 + 外接建档 4）；C-12 74 项逐项留证 UI；删旧 handover/*（6）filing/*（4）。

**Architecture:** 既有页脚本化逐字迁移（list/create/detail/rectify/sign + filing 4）+ 深色换肤；新建 inspect（C-12 74 项 G01–G13 逐项留证，接 getHandoverChecklist/submitHandoverChecklist）、upload（D1–D20 资料）、review（INST_MGR 内部审核）。rectify 升级 1:1 逐条复验（recheckHandoverItem）。

**Spec:** spec §4.2；M0 映射 §2；docs/15（GB/T 10060—2023 表1）。

## Global Constraints
- C-12 判定后端冻结（7.2/7.3 五路径），前端不改写结论；不合格 1:1 整改不可增删；逐条复验全 PASS 才回待确认；☆NA 经理审批。
- 容缺受理：缺件补传完成前不得已生效；生效→台账在保+首保（沿用）；平台 2.3/2.4 不新增。
- filing 外接建档 W2 流程不动（archive_source=2+首保+3 日）。
- 迁移保逻辑（diff 自审）；页面只调 services/handover|filing；全中文；四态；definePage。
- 门禁：lint + routes + M0 + m1-sync + dispatch/templates；inline + 台账。

## Tasks
- T1: 迁移 list/create/detail/sign + filing 4 页（脚本化逐字 + 换肤）
- T2: inspect 新建（C-12 74 项逐项留证：结果/实测/不合格描述/NA 原因码/照片，☆与试验项不合格强制照片）
- T3: rectify 升级 1:1 逐条整改复验 + upload 新建（D1–D20）+ review 新建（INST_MGR）
- T4: 收口（app.json 子包注册 + 删旧 handover/filing 10 页 + nav 映射清理 + routes built + 门禁 + docs）

## 裁决
- R1：旧 handover/inspect（6 类）被 C-12 74 项取代，不迁移旧 inspect，新建。
- R2：detail 迁移保留全部状态机展示；C-12 判定结论只读回显。
- R3：upload/review 新端点若后端缺（M1 未含 handover review/void），前端按现有端点投影，缺口列 docs/12（不擅自新增后端端点）。