-- 组织树口径修正：使用单位是外部客户主体，不进内部组织树；部门须归入公司下级。
--
-- 背景（两张截图暴露的问题）：
--   ① V36 建部门骨架时把「安装部/维保部」的 parent_id 写成 NULL，导致它们与公司平级，
--      与「公司 → 部门 → 班组」的层级口径不符；
--   ② V36 把「使用单位」建成一个 type=DEPT 的分组占位节点，其下挂各客户 UNIT 节点。
--      P3 客户账号体系（V48 client_account / use_unit）落地后，使用单位已属外部租户主体，
--      由「使用单位档案」维护；该占位节点残留在 /org 里会渲染成一个孤立的「使用单位 · 部门」。
--
-- 处理：
--   ① 两个部门挂到公司（取 sort_order 最小的 COMPANY 节点；单公司模型唯一）；
--   ② 下线「使用单位」占位节点：其下 UNIT 客户节点**不删**（CLIENT_ADMIN/CLIENT_SIGNER 的
--      sys_user_role.org_id 锚定它们，且 /org 已按 type 过滤 UNIT 不渲染），只解除父子关系；
--      先清掉可能存在的角色锚定，再删节点本身。
--
-- 幂等：①限定 parent_id 为空才更新；②按主键 id 删除，重复执行影响 0 行。
-- 空库：sys_org 无 COMPANY 时 JOIN 命中 0 行，无副作用。

-- ① 安装部 / 维保部 → 公司下级
UPDATE sys_org d
JOIN (SELECT id FROM sys_org WHERE type = 'COMPANY' ORDER BY sort_order, created_at LIMIT 1) c
SET d.parent_id = c.id
WHERE d.id IN ('org_dept_install', 'org_dept_maint')
  AND (d.parent_id IS NULL OR d.parent_id = '');

-- ② 使用单位分组占位节点下线（客户 UNIT 节点保留为顶级单级节点）
UPDATE sys_org SET parent_id = NULL WHERE parent_id = 'org_dept_unit';

UPDATE sys_user_role SET org_id = NULL WHERE org_id = 'org_dept_unit';

DELETE FROM sys_org WHERE id = 'org_dept_unit' AND type = 'DEPT';
