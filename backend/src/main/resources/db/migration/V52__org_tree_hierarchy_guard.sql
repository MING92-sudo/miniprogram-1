-- 组织层级兜底修复：把任何偏离「公司（唯一根）→ 部门 → 班组」口径的存量数据收敛。
--
-- 背景：V51 只修了 V36 建骨架时写死的两个部门（org_dept_install / org_dept_maint）与
-- 「使用单位」占位节点。但 createOrg 在 V52 之前不校验层级，历史库或人工造数仍可能留下：
--   ① 公司节点带上级；
--   ② 部门无上级 / 部门挂在部门下（与公司平级或嵌套，组织树渲染错位）；
--   ③ 班组无上级 / 挂在非部门节点下；
--   ④ sys_user_role.org_id 指向已删除节点（锚点悬空，按组织统计会漏人）。
-- 应用层已补 requireValidParent（LedgerService），本迁移负责把存量一次性收敛。
--
-- 幂等：均为条件更新，重复执行影响 0 行；空库无 COMPANY/DEPT 时 JOIN 命中 0 行。
-- 单公司模型：公司取 sort_order、created_at 最小者（与 V51 同口径）。

-- ① 公司必须是根节点
UPDATE sys_org
SET parent_id = NULL
WHERE type = 'COMPANY' AND parent_id IS NOT NULL AND parent_id <> '';

-- ② 部门只能挂在公司下：无父、父节点不存在、父不是公司 → 一律挂到公司
UPDATE sys_org d
LEFT JOIN sys_org p ON d.parent_id = p.id
JOIN (SELECT id FROM sys_org WHERE type = 'COMPANY' ORDER BY sort_order, created_at LIMIT 1) c ON 1 = 1
SET d.parent_id = c.id
WHERE d.type = 'DEPT'
  AND (d.parent_id IS NULL OR d.parent_id = '' OR p.id IS NULL OR p.type <> 'COMPANY');

-- ③ 班组只能挂在部门下：无父、父不存在、父不是部门 → 挂维保部（V36 建班组即挂维保部下）
UPDATE sys_org t
LEFT JOIN sys_org p ON t.parent_id = p.id
JOIN (SELECT id FROM sys_org
       WHERE type = 'DEPT' AND (id = 'org_dept_maint' OR name = '维保部')
       ORDER BY (id = 'org_dept_maint') DESC, sort_order, created_at LIMIT 1) d ON 1 = 1
SET t.parent_id = d.id
WHERE t.type = 'TEAM'
  AND (t.parent_id IS NULL OR t.parent_id = '' OR p.id IS NULL OR p.type <> 'DEPT');

-- ④ 角色组织锚点悬空 → 置空（节点已删除时不能继续按该 id 统计/过滤）
UPDATE sys_user_role ur
LEFT JOIN sys_org o ON ur.org_id = o.id
SET ur.org_id = NULL
WHERE ur.org_id IS NOT NULL AND ur.org_id <> '' AND o.id IS NULL;
