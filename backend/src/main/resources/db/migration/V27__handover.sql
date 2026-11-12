-- 移交单（W1）状态机 + 必传卡点 + 现场查验/整改/复验/签字（Task 4，安装移交）
--
-- 背景：移交是电梯建档的第一条路径——安装部把新装电梯移交给维保部，跑 6 态状态机，
--       最后归档建电梯（归档副作用在 Task 5 的 archive()，本迁移不涉及）。
--       状态机（docs/superpowers/specs §6.1）：INIT → DOC_SUBMITTING → INSPECTING → PENDING_CONFIRM → ARCHIVED；
--       INSPECTING 可转 RECTIFYING（安全装置/制动器不合格），RECTIFYING 复验通过回 PENDING_CONFIRM。
-- 签字：handover_sign 为节点确认记录（Task 6 signNode 消费，本任务仅建表）。

CREATE TABLE handover_order (
  id                          VARCHAR(32)  NOT NULL PRIMARY KEY,
  handover_no                 VARCHAR(32)  NOT NULL COMMENT '移交单号（Ids.next("ho") 唯一）',
  status                      VARCHAR(24)  NOT NULL DEFAULT 'INIT' COMMENT 'INIT/DOC_SUBMITTING/INSPECTING/RECTIFYING/PENDING_CONFIRM/ARCHIVED',
  elevator_id                 VARCHAR(32)  NULL COMMENT '归档建梯后回填（1:1）',
  project_name                VARCHAR(128) NOT NULL COMMENT '项目名称',
  supervision_pass_date       DATE         NULL COMMENT '监督检验合格日',
  initiator_id                VARCHAR(32)  NULL COMMENT '发起人=安装部项目经理',
  initiator_name              VARCHAR(64)  NULL COMMENT '发起人姓名',
  receiver_id                 VARCHAR(32)  NULL COMMENT '接收工程师（assignReceiver 写）',
  receiver_name               VARCHAR(64)  NULL COMMENT '接收工程师姓名',
  supervision_report_file_id  VARCHAR(32)  NULL COMMENT '监督检验报告（3 必传资料）',
  maintenance_manual_file_id  VARCHAR(32)  NULL COMMENT '维保说明书（3 必传资料）',
  quality_certificate_file_id VARCHAR(32)  NULL COMMENT '整机质量证明（3 必传资料）',
  confirmed_by_id             VARCHAR(32)  NULL COMMENT '确认人（维保部经理）',
  confirmed_by_name           VARCHAR(64)  NULL COMMENT '确认人姓名',
  confirmed_at                 DATETIME     NULL COMMENT '确认时间',
  -- 电梯信息快照列（供 Task 5 archive() 归档建电梯用，类型照 elevator 表对应列）
  elevator_code               VARCHAR(64)  NULL,
  elevator_name               VARCHAR(128) NULL,
  category                    VARCHAR(32)  NULL,
  location                    VARCHAR(256) NULL,
  reg_code                    VARCHAR(64)  NULL,
  device_code                 VARCHAR(64)  NULL,
  inside_number               VARCHAR(32)  NULL,
  model                       VARCHAR(64)  NULL,
  brand                       VARCHAR(64)  NULL,
  manufacturer                VARCHAR(128) NULL,
  product_no                  VARCHAR(64)  NULL,
  drive_mode                  VARCHAR(32)  NULL,
  rated_load                  INT          NULL,
  rated_load_unit             VARCHAR(8)   NULL,
  rated_speed                 DECIMAL(6,2) NULL,
  rated_speed_unit            VARCHAR(8)   NULL,
  stations_doors              VARCHAR(16)  NULL,
  work_type_code              VARCHAR(8)   NULL,
  UNIQUE KEY uk_handover_no (handover_no),
  KEY idx_handover_elevator (elevator_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE handover_check (
  id           VARCHAR(32)  NOT NULL PRIMARY KEY,
  handover_id  VARCHAR(32)  NOT NULL COMMENT '移交单 id',
  checker_id   VARCHAR(32)  NULL COMMENT '查验工程师',
  checker_name VARCHAR(64)  NULL COMMENT '查验人姓名',
  checked_at   DATETIME     NULL COMMENT '查验时间',
  remark       VARCHAR(255) NULL COMMENT '整体备注',
  KEY idx_check_handover (handover_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE handover_check_item (
  id        VARCHAR(32)  NOT NULL PRIMARY KEY,
  check_id  VARCHAR(32)  NOT NULL COMMENT 'handover_check.id',
  item_type VARCHAR(32)  NOT NULL COMMENT 'RUNNING/SAFETY_DEVICE/BRAKE/EMERGENCY_OPERATION/MACHINE_ROOM_SHAFT/ACCESSORIES',
  result    VARCHAR(16)  NOT NULL COMMENT 'PASS=合格/FAIL=不合格/NA=不适用',
  remark    VARCHAR(255) NULL COMMENT '单项备注',
  KEY idx_item_check (check_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE handover_rectification (
  id                   VARCHAR(32) NOT NULL PRIMARY KEY,
  handover_id          VARCHAR(32) NOT NULL COMMENT '移交单 id',
  item_type            VARCHAR(32) NOT NULL COMMENT '整改项类别（同 check_item.item_type）',
  before_photo_file_id VARCHAR(32) NULL COMMENT '整改前照片',
  after_photo_file_id  VARCHAR(32) NULL COMMENT '整改后照片',
  recheck_result       VARCHAR(16) NULL COMMENT '复验结果 PASS/FAIL',
  rechecker_id         VARCHAR(32) NULL COMMENT '复验人',
  rechecker_name       VARCHAR(64) NULL COMMENT '复验人姓名',
  rechecked_at         DATETIME    NULL COMMENT '复验时间',
  KEY idx_rect_handover (handover_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE handover_sign (
  id         VARCHAR(32) NOT NULL PRIMARY KEY,
  handover_id VARCHAR(32) NOT NULL COMMENT '移交单 id',
  node_type  VARCHAR(32) NOT NULL COMMENT '签字节点',
  actor_id   VARCHAR(32) NULL COMMENT '签字人',
  actor_name VARCHAR(64) NULL COMMENT '签字人姓名',
  action     VARCHAR(16) NOT NULL COMMENT 'APPROVE=通过/REJECT=驳回',
  `at`       DATETIME    NULL COMMENT '签字时间',
  KEY idx_sign_handover (handover_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
