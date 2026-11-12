-- 维保合同（最小版）+ 合同电梯关联（Task 3：外接建档 W2 的前置）
--
-- 背景：外接建档（W2，Task 7）必须关联一个「已生效合同」（起始日 ≤ 建档日）；
--       本迁移建合同主表 maintenance_contract 与合同↔电梯关联表 contract_elevator，
--       建合同/关联/生效期校验在 ContractService（POST/GET /contracts、GET /contracts/:id）。

CREATE TABLE maintenance_contract (
  id          VARCHAR(32)  NOT NULL PRIMARY KEY,
  contract_no VARCHAR(64)  NOT NULL COMMENT '合同号',
  use_unit_id VARCHAR(32)  NOT NULL COMMENT '使用单位（use_unit.id）',
  start_date  DATE         NOT NULL COMMENT '起始日',
  end_date    DATE         NOT NULL COMMENT '终止日',
  status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE=生效/TERMINATED=终止',
  amount      DECIMAL(12,2) NULL COMMENT '金额'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE contract_elevator (
  id          VARCHAR(32) NOT NULL PRIMARY KEY,
  contract_id VARCHAR(32) NOT NULL COMMENT '合同 id',
  elevator_id VARCHAR(32) NOT NULL COMMENT '电梯 id',
  UNIQUE KEY uk_contract_elevator (contract_id, elevator_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
