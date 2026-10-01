-- 管理端二期（docs/09 二期核心 / docs/08 P4 本地部分）：
-- ① 检查项模板落库（OFFICIAL 257 行由 ChecklistTemplateSeeder 从 classpath JSON 播种，payload 存原文保证生成口径一致；
--    CUSTOM 为消防/防爆等自定义模板，category_scope 匹配 elevator.special_type，缺失即 1006 语义）
-- ② 计划调度与延期审批（A.5：maintain_plan + plan_delay）

CREATE TABLE checklist_template (
  id             BIGINT        NOT NULL AUTO_INCREMENT,
  template_type  VARCHAR(16)   NOT NULL COMMENT 'OFFICIAL/CUSTOM',
  appendix       VARCHAR(4)    NULL COMMENT 'OFFICIAL: A/B/C/D',
  category_scope VARCHAR(32)   NOT NULL COMMENT 'OFFICIAL: 品种主名；CUSTOM: 消防电梯/防爆电梯等（匹配 elevator.special_type）',
  freq           VARCHAR(16)   NULL COMMENT 'OFFICIAL: HALF/QUARTER/HALF_YEAR/YEAR',
  item_code      VARCHAR(24)   NULL,
  seq            INT           NULL,
  name           VARCHAR(128)  NOT NULL,
  judge_type     VARCHAR(16)   NULL,
  is_key         TINYINT(1)    NOT NULL DEFAULT 0,
  photo_required TINYINT(1)    NOT NULL DEFAULT 0,
  enabled        TINYINT(1)    NOT NULL DEFAULT 1,
  payload        MEDIUMTEXT    NOT NULL COMMENT '模板条目 JSON 原文（与 checklist-template.json 逐字段一致）',
  created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_tpl_scope (template_type, appendix, freq),
  KEY idx_tpl_custom (template_type, category_scope, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE maintain_plan (
  id            VARCHAR(32) NOT NULL PRIMARY KEY,
  elevator_id   VARCHAR(32) NOT NULL,
  plan_date     DATE        NOT NULL,
  work_type_code VARCHAR(8) NOT NULL,
  status        VARCHAR(20) NOT NULL COMMENT 'UNASSIGNED/ASSIGNED/POSTPONE_PENDING/DISPATCHED/CANCELLED',
  principal_id  VARCHAR(32) NULL,
  assistant_id  VARCHAR(32) NULL,
  order_id      VARCHAR(32) NULL COMMENT '指派后生成的工单',
  created_at    DATETIME    NOT NULL,
  updated_at    DATETIME    NOT NULL,
  KEY idx_plan_date (plan_date),
  KEY idx_plan_elevator (elevator_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE plan_delay (
  id                   VARCHAR(32) NOT NULL PRIMARY KEY,
  plan_id              VARCHAR(32) NOT NULL,
  reason               VARCHAR(255) NOT NULL,
  delay_days           INT         NOT NULL,
  expected_date        DATE        NOT NULL,
  status               VARCHAR(16) NOT NULL COMMENT 'PENDING/APPROVED/REJECTED',
  applicant_id         VARCHAR(32) NULL,
  approver_id          VARCHAR(32) NULL,
  approve_comment      VARCHAR(255) NULL,
  platform_date_synced TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '0=平台侧 nextMaintenanceDate 已固化无法修改（A.5 约束提示）',
  created_at           DATETIME    NOT NULL,
  decided_at           DATETIME    NULL,
  KEY idx_delay_plan (plan_id),
  KEY idx_delay_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE elevator ADD COLUMN special_type VARCHAR(8) NULL COMMENT '特殊类别：消防/防爆/NULL（自定义模板匹配，缺失即 1006 提示）';
