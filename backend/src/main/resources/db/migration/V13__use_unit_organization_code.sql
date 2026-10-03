-- 2.2 queryID 实测（2026-10-04）：organizationCode 必填且为被查单位自己的信用代码，
-- 仅 unitName 会被平台拒绝（10001 organizationCode 不能为空）。
ALTER TABLE use_unit
    ADD COLUMN organization_code VARCHAR(64) NULL AFTER unit_name;
