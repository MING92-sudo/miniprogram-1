-- 新工单显式双人标记；NULL 仅用于历史存量工单兼容，禁止回填为单人豁免。
ALTER TABLE work_order
    ADD COLUMN duo_required TINYINT(1) NULL DEFAULT NULL
        COMMENT '新工单恒双人：1=必须双人，NULL=V63前历史存量' AFTER region_id;
