-- 外接建档单（W2）+ 建档资料 + 接管查验 + 交接确认（Task 7）
--
-- 背景：外接建档是电梯建档的第二条路径——使用单位从其它维保单位转来，我方接管后建档。
--       与移交（W1）的差别：①前置条件必须有已生效合同；②不合格项不阻塞建档，但责任归属必填；
--       ③归档时 archive_source=2、首保 planTime=归档日+3 自然日（移交是 +15）。
-- 状态机（设计文档 §6.2）：INIT → DOC_COLLECTING → TAKEOVER_CHECKING → PENDING_CONFIRM → ARCHIVED。
-- 交接确认单（handover_confirmation）：钥匙工具核对 + 遗留问题 + 双方签字（我方账号节点通过 + 对方手写）。

CREATE TABLE external_filing_order (
  id                   VARCHAR(32)  NOT NULL PRIMARY KEY,
  filing_no            VARCHAR(32)  NOT NULL COMMENT '外接建档单号（Ids.next("ef")）',
  status               VARCHAR(24)  NOT NULL DEFAULT 'INIT' COMMENT 'INIT/DOC_COLLECTING/TAKEOVER_CHECKING/PENDING_CONFIRM/ARCHIVED',
  contract_id          VARCHAR(32)  NOT NULL COMMENT '关联维保合同（必须已生效）',
  use_unit_id          VARCHAR(32)  NOT NULL COMMENT '使用单位（须与合同一致）',
  elevator_id          VARCHAR(32)  NULL COMMENT '归档建梯后回填（1:1）',
  initiator_id         VARCHAR(32)  NULL COMMENT '发起人',
  initiator_name       VARCHAR(64)  NULL COMMENT '发起人姓名',
  confirmed_by_id      VARCHAR(32)  NULL COMMENT '确认人（维保部经理）',
  confirmed_by_name    VARCHAR(64)  NULL COMMENT '确认人姓名',
  confirmed_at         DATETIME     NULL COMMENT '确认（归档）时间',
  -- 电梯信息快照列（归档建电梯用，类型照 elevator 表对应列）
  project_name         VARCHAR(128) NULL,
  supervision_pass_date DATE        NULL,
  elevator_code        VARCHAR(64)  NULL,
  elevator_name        VARCHAR(128) NULL,
  category             VARCHAR(32)  NULL,
  location             VARCHAR(256) NULL,
  reg_code             VARCHAR(64)  NULL,
  device_code          VARCHAR(64)  NULL,
  inside_number        VARCHAR(32)  NULL,
  model                VARCHAR(64)  NULL,
  brand                VARCHAR(64)  NULL,
  manufacturer         VARCHAR(128) NULL,
  product_no           VARCHAR(64)  NULL,
  drive_mode           VARCHAR(32)  NULL,
  rated_load           INT          NULL,
  rated_load_unit      VARCHAR(8)   NULL,
  rated_speed          DECIMAL(6,2) NULL,
  rated_speed_unit     VARCHAR(8)   NULL,
  stations_doors       VARCHAR(16)  NULL,
  work_type_code       VARCHAR(8)   NULL,
  UNIQUE KEY uk_filing_no (filing_no),
  KEY idx_filing_elevator (elevator_id),
  KEY idx_filing_contract (contract_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE external_filing_doc (
  id           VARCHAR(32)  NOT NULL PRIMARY KEY,
  filing_id    VARCHAR(32)  NOT NULL COMMENT '外接建档单 id',
  doc_type     VARCHAR(32)  NOT NULL COMMENT 'USE_REG_CERT/INSPECTION_REPORT/MAINTENANCE_RECORD/MAINTENANCE_MANUAL/ELEVATOR_DRAWING/OTHER',
  file_id      VARCHAR(32)  NULL COMMENT '文件 id（missing_flag=1 时可为空）',
  missing_flag TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '资料缺失待补办（不阻断建档）',
  remark       VARCHAR(255) NULL,
  KEY idx_filing_doc (filing_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE external_takeover_check (
  id           VARCHAR(32)  NOT NULL PRIMARY KEY,
  filing_id    VARCHAR(32)  NOT NULL COMMENT '外接建档单 id',
  checker_id   VARCHAR(32)  NULL COMMENT '查验人',
  checker_name VARCHAR(64)  NULL,
  checked_at   DATETIME     NULL,
  remark       VARCHAR(255) NULL COMMENT '整体备注',
  KEY idx_takeover_filing (filing_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE external_takeover_check_item (
  id             VARCHAR(32)  NOT NULL PRIMARY KEY,
  check_id       VARCHAR(32)  NOT NULL COMMENT 'external_takeover_check.id',
  item_type      VARCHAR(32)  NOT NULL COMMENT 'RUNNING/SAFETY_DEVICE/BRAKE/EMERGENCY_OPERATION/MACHINE_ROOM_SHAFT/ACCESSORIES',
  result         VARCHAR(16)  NOT NULL DEFAULT 'NA' COMMENT 'PASS/FAIL/NA',
  responsibility VARCHAR(24)  NULL COMMENT '责任归属（FAIL 必填）：PREV_MAINT 前维保单位/USE_UNIT 使用单位/OURS_AFTER_TAKEOVER 我方接管后',
  remark         VARCHAR(255) NULL,
  KEY idx_takeover_item_check (check_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE handover_confirmation (
  id                    VARCHAR(32)  NOT NULL PRIMARY KEY,
  filing_id             VARCHAR(32)  NOT NULL COMMENT '外接建档单 id',
  key_tool_check        VARCHAR(512) NULL COMMENT '钥匙/工具核对情况',
  leftover_issue        VARCHAR(512) NULL COMMENT '遗留问题（含不合格项责任归属汇总）',
  our_sign_file_id      VARCHAR(32)  NULL COMMENT '我方签字（节点通过即签字，可空）',
  counterpart_sign_name VARCHAR(64)  NOT NULL COMMENT '对方签字人姓名',
  counterpart_sign_file_id VARCHAR(32) NOT NULL COMMENT '对方手写签名图',
  confirmed_by_id       VARCHAR(32)  NULL,
  confirmed_by_name     VARCHAR(64)  NULL,
  at                    DATETIME     NULL,
  KEY idx_confirmation_filing (filing_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
