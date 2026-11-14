-- M1 权限底座 schema（spec §2.1）：权限核心五表 + sys_employee.scope_version + 角色默认范围种子。
-- 建表约定跟随仓库（VARCHAR(32) 字符串主键 + VARCHAR(16) 枚举 + DATETIME + KEY idx_ + utf8mb4 + COMMENT）。
-- 纯 schema + 静态配置，全环境执行；不含任何业务数据删除（生产清库是人工上线步骤，AGENTS §6）。

CREATE TABLE sys_org (
  id          VARCHAR(32)  NOT NULL PRIMARY KEY,
  type        VARCHAR(16)  NOT NULL COMMENT 'COMPANY/DEPT/TEAM/UNIT',
  name        VARCHAR(128) NOT NULL,
  code        VARCHAR(64)  NULL,
  parent_id   VARCHAR(32)  NULL,
  leader_id   VARCHAR(32)  NULL COMMENT '负责人 empId/use_unit.id',
  status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
  sort_order  INT          NULL,
  created_at  DATETIME     NOT NULL,
  UNIQUE KEY uk_org_code (code),
  KEY idx_org_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组织树：公司→部门→班组；使用单位单级（应用层禁挂子节点）';

CREATE TABLE sys_user_role (
  id          VARCHAR(32)  NOT NULL PRIMARY KEY,
  user_id     VARCHAR(32)  NOT NULL,
  user_type   VARCHAR(16)  NOT NULL COMMENT 'EMPLOYEE/UNIT',
  role_code   VARCHAR(32)  NOT NULL COMMENT '规范角色码（新码）',
  org_id      VARCHAR(32)  NULL COMMENT '该角色组织锚点：TEAM→班组节点 / ORG_FLAT→单位节点',
  created_at  DATETIME     NOT NULL,
  UNIQUE KEY uk_user_role (user_id, user_type, role_code),
  KEY idx_ur_user (user_id),
  KEY idx_ur_role (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户-角色多对多；同部门多角色应用层校验、跨部门禁止（R7）';

CREATE TABLE sys_role_scope (
  role_code   VARCHAR(32)  NOT NULL PRIMARY KEY,
  scope       VARCHAR(16)  NOT NULL COMMENT 'SELF/TEAM/PROJECT/ORG/ORG_FLAT/ALL',
  scope_ref   VARCHAR(32)  NULL COMMENT '固定范围引用（多数角色为空，动态解析）',
  version     INT          NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色默认数据范围配置';

CREATE TABLE sys_grant (
  id          VARCHAR(32)  NOT NULL PRIMARY KEY,
  grantee_id  VARCHAR(32)  NOT NULL,
  perm_code   VARCHAR(64)  NOT NULL COMMENT '追加权限点',
  scope       VARCHAR(16)  NULL,
  scope_ref   VARCHAR(32)  NULL,
  start_at    DATETIME     NOT NULL,
  end_at      DATETIME     NOT NULL,
  status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ACTIVE/REVOKED/WITHDRAWN',
  granted_by  VARCHAR(32)  NOT NULL,
  reason      VARCHAR(255) NULL,
  created_at  DATETIME     NOT NULL,
  KEY idx_grant_grantee (grantee_id, status),
  KEY idx_grant_end (end_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='限时授权：end>start 应用层校验，到期自动回收';

CREATE TABLE sys_audit_log (
  id          VARCHAR(32)  NOT NULL PRIMARY KEY,
  actor_id    VARCHAR(32)  NULL,
  actor_role  VARCHAR(32)  NULL,
  action      VARCHAR(64)  NOT NULL COMMENT '权限码动作',
  target      VARCHAR(64)  NULL,
  org_id      VARCHAR(32)  NULL,
  project_id  VARCHAR(32)  NULL,
  before_snap TEXT         NULL,
  after_snap  TEXT         NULL,
  hash_ref    VARCHAR(128) NULL,
  reason      VARCHAR(255) NULL,
  device      VARCHAR(64)  NULL,
  ip          VARCHAR(64)  NULL,
  gps         VARCHAR(64)  NULL,
  result      VARCHAR(16)  NOT NULL COMMENT 'SUCCESS/FAILED/BLOCKED',
  created_at  DATETIME     NOT NULL,
  KEY idx_audit_actor (actor_id, created_at),
  KEY idx_audit_action (action, created_at),
  KEY idx_audit_org (org_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务审计：只读不可删改；旧 op_log 保留满足留存';

ALTER TABLE sys_employee
  ADD COLUMN scope_version INT NOT NULL DEFAULT 0 COMMENT '角色/范围变更递增→旧 token 失效（M1）';

-- 角色默认数据范围种子（spec §2.2，全环境静态配置）
INSERT INTO sys_role_scope (role_code, scope, scope_ref, version) VALUES
  ('PLATFORM_ADMIN', 'ALL',      NULL, 1),
  ('SYS_ADMIN',      'ALL',      NULL, 1),
  ('INST_MGR',       'ORG',      NULL, 1),
  ('INST_PM',        'PROJECT',  NULL, 1),
  ('INST_CLERK',     'PROJECT',  NULL, 1),
  ('INSTALLER',      'SELF',     NULL, 1),
  ('MAINT_MGR',      'ALL',      NULL, 1),
  ('MAINT_QUALITY',  'ORG',      NULL, 1),
  ('MAINT_BIZ',      'ORG',      NULL, 1),
  ('MAINT_SUP',      'TEAM',     NULL, 1),
  ('MAINT_TECH',     'SELF',     NULL, 1),
  ('CLIENT_ADMIN',   'ORG_FLAT', NULL, 1),
  ('CLIENT_SIGNER',  'ORG_FLAT', NULL, 1);