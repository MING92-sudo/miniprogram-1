-- ============================================================================
-- 全角色 × 全流程阶段测试数据（本地开发库专用，幂等可重复执行）
-- 用法：mysql -h127.0.0.1 -umaintenance_app maintenance < backend/scripts/test-data-all-stages.sql
-- 口径：① 先清 E2E 联调残留与本脚本 t_ 前缀测试集，再整批插入；
--       ② 所有演示账号密码统一 123456（BCrypt 哈希复用种子账号）；
--       ③ 清单/记录 JSON 克隆 wo_2/ur_1 模板（freqLabel 按工单类型改写表号）；
--       ④ 不触碰平台凭证、不改表结构、不触发任何上报。
-- ============================================================================
SET NAMES utf8mb4;
START TRANSACTION;

-- ─────────────────────────────────────────────────────────────
-- 0. 清理：E2E 联调残留 + 本脚本测试集（幂等）
-- ─────────────────────────────────────────────────────────────
DELETE sur FROM sys_user_role sur JOIN sys_employee e ON sur.user_id = e.id
  WHERE e.role = 'WORKER' OR e.account LIKE '139%';
DELETE FROM sys_employee WHERE role = 'WORKER' OR account LIKE '139%';
DELETE FROM sys_org WHERE name LIKE 'E2E%';
DELETE ca FROM client_account ca JOIN use_unit u ON ca.use_unit_id = u.id
  WHERE u.unit_name LIKE 'E2E%' OR u.unit_name LIKE '端到端%';
DELETE FROM client_account WHERE phone LIKE '139%' OR name LIKE 'E2E%' OR name LIKE '端到端%';
DELETE FROM use_unit WHERE unit_name LIKE 'E2E%' OR unit_name LIKE '端到端%';
DELETE FROM elevator WHERE elevator_code LIKE 'E2E%';
DELETE FROM fault WHERE descr LIKE 'E2E%';
DELETE FROM maintenance_contract WHERE contract_no LIKE 'E2E%';
DELETE FROM maintain_record WHERE id LIKE 'ur\_%' AND CHAR_LENGTH(id) > 10;
DELETE FROM work_order WHERE id LIKE 'wo\_%' AND CHAR_LENGTH(id) > 10;
DELETE FROM handover_order WHERE id LIKE 'ho\_%' AND CHAR_LENGTH(id) > 10;

DELETE FROM sys_user_role WHERE user_id LIKE 't\_%';
DELETE FROM sys_employee WHERE id LIKE 't\_%';
DELETE FROM client_account WHERE id LIKE 't\_%';
DELETE FROM use_unit WHERE id LIKE 't\_%';
DELETE FROM elevator WHERE id LIKE 't\_%';
DELETE FROM maintenance_contract WHERE id LIKE 't\_%';
DELETE FROM work_order WHERE id LIKE 't\_%';
DELETE FROM maintain_record WHERE id LIKE 't\_%';
DELETE FROM fault WHERE id LIKE 't\_%';
DELETE FROM handover_check WHERE id LIKE 't\_%';
DELETE FROM handover_rectification WHERE id LIKE 't\_%';
DELETE FROM handover_archive WHERE id LIKE 't\_%';
DELETE FROM handover_confirmation WHERE id LIKE 't\_%';
DELETE FROM handover_order WHERE id LIKE 't\_%';
DELETE FROM external_filing_doc WHERE id LIKE 't\_%';
DELETE FROM external_filing_order WHERE id LIKE 't\_%';
DELETE FROM sys_grant WHERE id LIKE 't\_%';
DELETE FROM biz_project WHERE id LIKE 't\_%';
DELETE FROM message WHERE id LIKE 't\_%';
DELETE FROM sys_invite WHERE id LIKE 't\_%';
DELETE FROM app_file WHERE id LIKE 't\_%';

-- 共享模板（克隆真实数据结构）
SET @emp_hash := (SELECT password_hash FROM sys_employee WHERE id = 'emp_lzg');
SET @cl_full := (SELECT checklist_json FROM work_order WHERE id = 'wo_2');
SET @cl_empty := REPLACE(@cl_full, '"result":"NORMAL"', '"result":null');
SET @items := (SELECT items_json FROM maintain_record WHERE id = 'ur_1');
SET @photos := (SELECT photos_json FROM maintain_record WHERE id = 'ur_1');
SET @ctx := (SELECT preview_context FROM maintain_record WHERE id = 'ur_1');
SET @today := DATE(NOW());
SET @hash64 := '9f3c7a21b8d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e';

-- ─────────────────────────────────────────────────────────────
-- 1. 员工账号补齐（缺失角色：维保主管/商务/质检 + 配合人员李强；密码 123456）
-- ─────────────────────────────────────────────────────────────
INSERT INTO sys_employee (id, name, phone, account, password_hash, role, role_text, department, group_name, platform_id, enabled) VALUES
('t_sup', '王主管', '13800000021', '13800000021', @emp_hash, 'MAINT_SUP', '维保主管', 'MAINT', '维保一组', '6901282369105174999', 1),
('t_biz', '周商务', '13800000022', '13800000022', @emp_hash, 'MAINT_BIZ', '维保商务', 'MAINT', NULL, NULL, 1),
('t_qa', '吴质检', '13800000023', '13800000023', @emp_hash, 'MAINT_QUALITY', '维保质检', 'MAINT', NULL, NULL, 1),
('t_lq', '李强', '13800000024', '13800000024', @emp_hash, 'MAINT_TECH', '维保人员', 'MAINT', '维保一组', '6901284774286860288', 1);

INSERT INTO sys_user_role (id, user_id, user_type, role_code, org_id, created_at) VALUES
('t_ur_sup', 't_sup', 'EMPLOYEE', 'MAINT_SUP', 'org_team_maint_2', NOW()),
('t_ur_biz', 't_biz', 'EMPLOYEE', 'MAINT_BIZ', 'org_dept_maint', NOW()),
('t_ur_qa', 't_qa', 'EMPLOYEE', 'MAINT_QUALITY', 'org_dept_maint', NOW()),
('t_ur_lq', 't_lq', 'EMPLOYEE', 'MAINT_TECH', 'org_team_maint_2', NOW());

-- ─────────────────────────────────────────────────────────────
-- 2. 使用单位 uu_3 + 电梯（1 台全字段 + 1 台缺必传项「待补全」演示）
-- ─────────────────────────────────────────────────────────────
INSERT INTO use_unit (id, unit_name, organization_code, unit_principal, unit_principal_phone, elevator_administer, elevator_administer_phone, emergency_phone) VALUES
('t_uu3', '重庆江北城物业有限公司', '91500105MA5U8XY31A', '陈建国', '13900000001', '陈洁', '13800000031', '023-68001122');

INSERT INTO elevator (id, elevator_code, elevator_name, project_name, region_id, location, reg_code, device_code, inside_number, model, use_unit_id, category, next_check_date, next_maintenance_date, factory_number, elevator_administer, elevator_administer_phone, emergency_phone, lng, lat, brand, manufacturer, product_no, drive_mode, rated_load, rated_load_unit, rated_speed, rated_speed_unit, stations_doors, work_type_code, interval_days, worker_name, worker_phone, worker_platform_id, assistant_name, assistant_platform_id, last_maintenance_at, archive_source, status) VALUES
('t_el1', 'EM-2024-101', '江北城 C 座客梯', '江北城', 'rg_7X5BrvaLTAeWFTNT2JbgkA', '江北区江北城大街 12 号', 'TSCQ5001130101', 'DT-CQ-2024-101', 'KT-01', '通力 MonoSpace', 't_uu3', '曳引与强制驱动电梯', DATE_ADD(@today, INTERVAL 120 DAY), DATE_ADD(@today, INTERVAL 12 DAY), 'KONE2020-5531', '陈洁', '13800000031', '023-68001122', 106.578000, 29.583000, '通力', '通力电梯有限公司', 'KMS-2020-5531', '曳引驱动', 1000, 'kg', 1.75, 'm/s', '18/18', 'HM', 15, '李振国', '13800000011', '6901282369105174537', '李强', '6901284774286860288', DATE_SUB(NOW(), INTERVAL 3 DAY), 1, 'ACTIVE'),
('t_el2', 'EM-2024-102', '江北城 D 座货梯', '江北城', 'rg_7X5BrvaLTAeWFTNT2JbgkA', '江北区江北城大街 12 号', NULL, 'DT-CQ-2024-102', 'HT-01', '日立 HGP', 't_uu3', '曳引与强制驱动电梯', DATE_ADD(@today, INTERVAL 200 DAY), DATE_ADD(@today, INTERVAL 9 DAY), NULL, '陈洁', '13800000031', '023-68001122', 106.578100, 29.583100, '日立', '日立电梯（中国）有限公司', NULL, '曳引驱动', 2000, 'kg', 1.0, 'm/s', '6/6', 'HM', 15, '李振国', '13800000011', '6901282369105174537', '李强', '6901284774286860288', NULL, 1, 'ACTIVE');

-- ─────────────────────────────────────────────────────────────
-- 3. 客户账号（uu_2 补签字人；uu_3 管理员+签字人；GRACE 宽限；DISABLED 停用；密码 123456）
-- ─────────────────────────────────────────────────────────────
SET @ca_hash := (SELECT password_hash FROM client_account WHERE id = 'ca_1');
INSERT INTO client_account (id, use_unit_id, name, phone, password_hash, role_code, status, grace_until, enabled, created_by, created_at, updated_at, disabled_reason) VALUES
('t_ca_u2s', 'uu_2', '吴签', '13800003006', @ca_hash, 'CLIENT_SIGNER', 'ACTIVE', NULL, 1, 'emp_sys', NOW(), NOW(), NULL),
('t_ca_u3a', 't_uu3', '陈洁', '13800000031', @ca_hash, 'CLIENT_ADMIN', 'ACTIVE', NULL, 1, 'emp_sys', NOW(), NOW(), NULL),
('t_ca_u3s', 't_uu3', '刘安', '13800000032', @ca_hash, 'CLIENT_SIGNER', 'ACTIVE', NULL, 1, 'emp_sys', NOW(), NOW(), NULL),
('t_ca_grace', 't_uu3', '钱宽', '13800000033', @ca_hash, 'CLIENT_ADMIN', 'GRACE', DATE_ADD(NOW(), INTERVAL 15 DAY), 1, 'emp_sys', NOW(), NOW(), NULL),
('t_ca_dis', 'uu_1', '赵停', '13800000034', @ca_hash, 'CLIENT_ADMIN', 'DISABLED', NULL, 0, 'emp_sys', NOW(), NOW(), '合同到期停用');

-- ─────────────────────────────────────────────────────────────
-- 4. 合同（生效 / 临期 / 已到期）
-- ─────────────────────────────────────────────────────────────
INSERT INTO maintenance_contract (id, contract_no, use_unit_id, start_date, end_date, status, amount) VALUES
('t_ct1', 'HT-2026-001', 'uu_1', '2026-01-01', '2027-01-01', 'ACTIVE', 186000.00),
('t_ct2', 'HT-2026-002', 'uu_2', '2025-12-15', DATE_ADD(@today, INTERVAL 20 DAY), 'ACTIVE', 98000.00),
('t_ct3', 'HT-2025-088', 't_uu3', '2025-06-01', DATE_SUB(@today, INTERVAL 5 DAY), 'EXPIRED', 56000.00);

-- ─────────────────────────────────────────────────────────────
-- 5. 维保工单（PENDING 超期/今日/3日内/年度、PROCESSING 填写中/待配合签字/可签退、
--    DONE 上报失败、设备档案缺项门禁演示）
-- ─────────────────────────────────────────────────────────────
SET @cl_tm := REPLACE(REPLACE(@cl_empty, '"freqLabel":"半月维保项目（表A-1）"', '"freqLabel":"季度维保项目（表A-2）"'), '"freq":"HALF"', '"freq":"QUARTER"');
SET @cl_oy := REPLACE(REPLACE(@cl_empty, '"freqLabel":"半月维保项目（表A-1）"', '"freqLabel":"年度维保项目（表A-4）"'), '"freq":"HALF"', '"freq":"YEAR"');
SET @lzg := '6901282369105174537';
SET @lq := '6901284774286860288';
-- 记录编号与规则一致（Ids.nextRecordId：'1948' + 毫秒时间戳 + 2位序号）——
-- 记录/工单/导出 PDF/平台 2.6 上报 originalRecordId 同值，种子不再用 t_orig_* 假编号
SET @oid_ms := ROUND(UNIX_TIMESTAMP(NOW(3)) * 1000);
SET @oid_ps := CONCAT('1948', @oid_ms, '01');
SET @oid_ps2 := CONCAT('1948', @oid_ms, '02');
SET @oid_ps3 := CONCAT('1948', @oid_ms, '03');
SET @oid_rf := CONCAT('1948', @oid_ms, '04');
SET @oid_sg := CONCAT('1948', @oid_ms, '05');
SET @oid_done := CONCAT('1948', @oid_ms, '06');
SET @oid_v1 := CONCAT('1948', @oid_ms, '07');
SET @oid_failed := CONCAT('1948', @oid_ms, '08');

INSERT INTO work_order (id, order_no, elevator_id, work_type, work_type_code, plan_time, status, worker_name, assistant_name, worker_platform_id, assistant_platform_id, auto_dispatched, checklist_json, use_unit_id, use_unit_name) VALUES
('t_wo_overdue', 'WO-T-OVD', 'el_1', '半月维保', 'HM', CONCAT(DATE_SUB(@today, INTERVAL 1 DAY), ' 09:00:00'), 'PENDING', '李振国', '李强', @lzg, @lq, 1, @cl_empty, 'uu_1', '重庆世纪物业管理有限公司'),
('t_wo_today', 'WO-T-TODAY', 'el_1', '半月维保', 'HM', CONCAT(@today, ' 09:00:00'), 'PENDING', '李振国', '李强', @lzg, @lq, 1, @cl_empty, 'uu_1', '重庆世纪物业管理有限公司'),
('t_wo_soon', 'WO-T-SOON', 'el_2', '季度维保', 'TM', CONCAT(DATE_ADD(@today, INTERVAL 2 DAY), ' 09:00:00'), 'PENDING', '李振国', '李强', @lzg, @lq, 1, @cl_tm, 'uu_1', '重庆世纪物业管理有限公司'),
('t_wo_oy', 'WO-T-OY', 't_el1', '年度维保', 'OY', CONCAT(DATE_ADD(@today, INTERVAL 5 DAY), ' 09:00:00'), 'PENDING', '李振国', '李强', @lzg, @lq, 0, @cl_oy, 't_uu3', '重庆江北城物业有限公司'),
('t_wo_devblock', 'WO-T-DEV', 't_el2', '半月维保', 'HM', CONCAT(@today, ' 09:00:00'), 'PENDING', '李振国', '李强', @lzg, @lq, 0, @cl_empty, 't_uu3', '重庆江北城物业有限公司'),
('t_wo_cg', 'WO-T-CG', 'el_1', '半月维保', 'HM', CONCAT(@today, ' 09:00:00'), 'PENDING', '陈刚', '', '990002', '', 0, @cl_empty, 'uu_1', '重庆世纪物业管理有限公司'),
('t_wo_fresh', 'WO-T-FRESH', 'el_1', '半月维保', 'HM', CONCAT(@today, ' 08:00:00'), 'PROCESSING', '李振国', '李强', @lzg, @lq, 0, @cl_empty, 'uu_1', '重庆世纪物业管理有限公司'),
('t_wo_filled', 'WO-T-FILLED', 'el_3', '半月维保', 'HM', CONCAT(@today, ' 08:00:00'), 'PROCESSING', '李振国', '李强', @lzg, @lq, 0, @cl_full, 'uu_2', '重庆蓝湾物业服务有限公司'),
('t_wo_ready', 'WO-T-READY', 'el_2', '半月维保', 'HM', CONCAT(@today, ' 08:00:00'), 'PROCESSING', '李振国', '李强', @lzg, @lq, 0, @cl_full, 'uu_1', '重庆世纪物业管理有限公司'),
('t_wo_failed', 'WO-T-FAILED', 'el_3', '半月维保', 'HM', CONCAT(DATE_SUB(@today, INTERVAL 1 DAY), ' 09:00:00'), 'DONE', '李振国', '李强', @lzg, @lq, 0, @cl_full, 'uu_2', '重庆蓝湾物业服务有限公司'),
('t_wo_done', 'WO-T-DONE', 'el_1', '半月维保', 'HM', CONCAT(DATE_SUB(@today, INTERVAL 3 DAY), ' 09:00:00'), 'DONE', '李振国', '李强', @lzg, @lq, 0, @cl_full, 'uu_1', '重庆世纪物业管理有限公司');

-- PROCESSING 三单的签到时间与签字态
UPDATE work_order SET checkin_time = DATE_SUB(NOW(), INTERVAL 130 MINUTE) WHERE id = 't_wo_fresh';
UPDATE work_order SET checkin_time = DATE_SUB(NOW(), INTERVAL 150 MINUTE),
  worker_signature_file_id = 't_file_sig_w', worker_signature_at = DATE_SUB(NOW(), INTERVAL 20 MINUTE)
  WHERE id = 't_wo_filled';
UPDATE work_order SET checkin_time = DATE_SUB(NOW(), INTERVAL 160 MINUTE),
  worker_signature_file_id = 't_file_sig_w', worker_signature_at = DATE_SUB(NOW(), INTERVAL 30 MINUTE),
  assistant_signature_file_id = 't_file_sig_a', assistant_signature_at = DATE_SUB(NOW(), INTERVAL 29 MINUTE)
  WHERE id = 't_wo_ready';
UPDATE work_order SET checkin_time = CONCAT(DATE_SUB(@today, INTERVAL 1 DAY), ' 08:52:00'),
  checkout_time = CONCAT(DATE_SUB(@today, INTERVAL 1 DAY), ' 11:20:00'), duration = '02:28:00',
  original_record_id = @oid_failed, report_status = 'FAILED' WHERE id = 't_wo_failed';
UPDATE work_order SET checkin_time = CONCAT(DATE_SUB(@today, INTERVAL 3 DAY), ' 08:50:00'),
  checkout_time = CONCAT(DATE_SUB(@today, INTERVAL 3 DAY), ' 11:10:00'), duration = '02:20:00',
  worker_signature_file_id = 't_file_sig_w', worker_signature_at = CONCAT(DATE_SUB(@today, INTERVAL 3 DAY), ' 11:05:00'),
  assistant_signature_file_id = 't_file_sig_a', assistant_signature_at = CONCAT(DATE_SUB(@today, INTERVAL 3 DAY), ' 11:06:00'),
  original_record_id = @oid_done, report_status = 'REPORTED' WHERE id = 't_wo_done';

-- 签字样图文件（手写风格 PNG 已提交 data/files/，objectKey 与 FileStorageService.localPath 同口径）
INSERT INTO app_file (id, object_key, url, content_type, size_bytes, created_at) VALUES
('t_file_sig_w', '2026-11-20/t_file_sig_w.png', '/files/t_file_sig_w', 'image/png', 10617, NOW()),
('t_file_sig_a', '2026-11-20/t_file_sig_a.png', '/files/t_file_sig_a', 'image/png', 8602, NOW()),
('t_file_sig_c', '2026-11-20/t_file_sig_c.png', '/files/t_file_sig_c', 'image/png', 15069, NOW());

-- ─────────────────────────────────────────────────────────────
-- 6. 维保记录（生命周期全覆盖：待发起签字/待签署×2/拒签退回/已签署/已归档/
--    v1 被替代 + v2 修订链 / 上报失败）
-- ─────────────────────────────────────────────────────────────
INSERT INTO maintain_record (id, elevator_name, elevator_code, work_type, work_type_code, worker_name, assistant_name, worker_platform_id, assistant_platform_id, checkin_time, checkout_time, duration, items_json, photos_json, problem_codes_json, original_record_id, report_status, retry_count, next_maintenance_date, confirm_status, share_token, preview_context, version, lifecycle_status, use_unit_id, use_unit_name, elevator_id, work_order_id, created_at) VALUES
('t_rec_pend_sign', '世纪大厦 1# 客梯', 'EM-2024-001', '半月维保', 'HM', '李振国', '李强', @lzg, @lq, CONCAT(@today, ' 08:52:00'), CONCAT(@today, ' 11:20:00'), '02:28:00', @items, @photos, '["S0"]', @oid_ps, 'REPORTED', 0, DATE_ADD(@today, INTERVAL 15 DAY), 'PENDING', 't-tok-sign1', @ctx, 1, 'PENDING_CLIENT_SIGN', 'uu_1', '重庆世纪物业管理有限公司', 'el_1', NULL, NOW()),
('t_rec_pend_sig2', '蓝湾国际 A 座货梯', 'EM-2024-003', '半月维保', 'HM', '李振国', '李强', @lzg, @lq, CONCAT(@today, ' 08:40:00'), CONCAT(@today, ' 10:55:00'), '02:15:00', @items, @photos, '["S0"]', @oid_ps2, 'REPORTED', 0, DATE_ADD(@today, INTERVAL 15 DAY), 'PENDING', 't-tok-sign2', @ctx, 1, 'PENDING_CLIENT_SIGNATURE', 'uu_2', '重庆蓝湾物业服务有限公司', 'el_3', NULL, NOW()),
('t_rec_pend_sig3', '江北城 C 座客梯', 'EM-2024-101', '半月维保', 'HM', '李振国', '李强', @lzg, @lq, CONCAT(@today, ' 09:05:00'), CONCAT(@today, ' 11:35:00'), '02:30:00', @items, @photos, '["S0"]', @oid_ps3, 'REPORTED', 0, DATE_ADD(@today, INTERVAL 15 DAY), 'PENDING', 't-tok-sign3', @ctx, 1, 'PENDING_CLIENT_SIGNATURE', 't_uu3', '重庆江北城物业有限公司', 't_el1', NULL, NOW()),
('t_rec_refused', '世纪大厦 1# 客梯', 'EM-2024-001', '半月维保', 'HM', '李振国', '李强', @lzg, @lq, CONCAT(DATE_SUB(@today, INTERVAL 2 DAY), ' 08:52:00'), CONCAT(DATE_SUB(@today, INTERVAL 2 DAY), ' 11:20:00'), '02:28:00', @items, @photos, '["S5"]', @oid_rf, 'REPORTED', 0, DATE_ADD(@today, INTERVAL 13 DAY), 'REFUSED', 't-tok-rf', @ctx, 1, 'REFUSED_RETURNED', 'uu_1', '重庆世纪物业管理有限公司', 'el_1', NULL, NOW()),
('t_rec_signed', '蓝湾国际 A 座货梯', 'EM-2024-003', '半月维保', 'HM', '李振国', '李强', @lzg, @lq, CONCAT(DATE_SUB(@today, INTERVAL 1 DAY), ' 08:40:00'), CONCAT(DATE_SUB(@today, INTERVAL 1 DAY), ' 10:55:00'), '02:15:00', @items, @photos, '["S0"]', @oid_sg, 'REPORTED', 0, DATE_ADD(@today, INTERVAL 14 DAY), 'CONFIRMED', 't-tok-sg', @ctx, 1, 'SIGNED', 'uu_2', '重庆蓝湾物业服务有限公司', 'el_3', NULL, NOW()),
('t_rec_archived', '世纪大厦 1# 客梯', 'EM-2024-001', '半月维保', 'HM', '李振国', '李强', @lzg, @lq, CONCAT(DATE_SUB(@today, INTERVAL 3 DAY), ' 08:50:00'), CONCAT(DATE_SUB(@today, INTERVAL 3 DAY), ' 11:10:00'), '02:20:00', @items, @photos, '["S0"]', @oid_done, 'REPORTED', 0, DATE_ADD(@today, INTERVAL 12 DAY), 'CONFIRMED', 't-tok-ar', @ctx, 1, 'ARCHIVED', 'uu_1', '重庆世纪物业管理有限公司', 'el_1', 't_wo_done', NOW()),
('t_rec_v1', '世纪大厦 1# 客梯', 'EM-2024-001', '半月维保', 'HM', '李振国', '李强', @lzg, @lq, CONCAT(DATE_SUB(@today, INTERVAL 4 DAY), ' 08:50:00'), CONCAT(DATE_SUB(@today, INTERVAL 4 DAY), ' 11:10:00'), '02:20:00', @items, @photos, '["S0"]', @oid_v1, 'REPORTED', 0, DATE_ADD(@today, INTERVAL 11 DAY), 'CONFIRMED', 't-tok-v1', @ctx, 1, 'ARCHIVED', 'uu_1', '重庆世纪物业管理有限公司', 'el_1', NULL, NOW()),
('t_rec_v2', '世纪大厦 1# 客梯', 'EM-2024-001', '半月维保', 'HM', '李振国', '李强', @lzg, @lq, CONCAT(DATE_SUB(@today, INTERVAL 4 DAY), ' 08:50:00'), CONCAT(DATE_SUB(@today, INTERVAL 4 DAY), ' 11:10:00'), '02:20:00', @items, @photos, '["S0"]', @oid_v1, 'REPORTED', 0, DATE_ADD(@today, INTERVAL 11 DAY), 'PENDING', 't-tok-v2', @ctx, 2, 'PENDING_CLIENT_SIGNATURE', 'uu_1', '重庆世纪物业管理有限公司', 'el_1', NULL, NOW()),
('t_rec_failed', '蓝湾国际 A 座货梯', 'EM-2024-003', '半月维保', 'HM', '李振国', '李强', @lzg, @lq, CONCAT(DATE_SUB(@today, INTERVAL 1 DAY), ' 08:52:00'), CONCAT(DATE_SUB(@today, INTERVAL 1 DAY), ' 11:20:00'), '02:28:00', @items, @photos, '["S0"]', @oid_failed, 'FAILED', 2, DATE_ADD(@today, INTERVAL 14 DAY), 'PENDING', 't-tok-fail', @ctx, 1, 'PENDING_CLIENT_SIGN', 'uu_2', '重庆蓝湾物业服务有限公司', 'el_3', 't_wo_failed', NOW());

UPDATE maintain_record SET client_refuser_name = '王芳', refused_by_client_account_id = 'ca_1',
  client_refuse_reason = '现场照片模糊，请补拍后重新发起签署', client_refused_at = DATE_SUB(NOW(), INTERVAL 1 DAY)
  WHERE id = 't_rec_refused';
UPDATE maintain_record SET client_account_id = 'ca_4', client_signer_name = '吴静', client_signed_at = DATE_SUB(NOW(), INTERVAL 20 HOUR),
  signature_file_id = 't_file_sig_c', signature_url = 'https://example.invalid/t_sig_c.png' WHERE id = 't_rec_signed';
UPDATE maintain_record SET archive_hash = @hash64, archived_at = DATE_SUB(NOW(), INTERVAL 2 DAY), archive_file_id = 't_file_pdf_ar' WHERE id = 't_rec_archived';
UPDATE maintain_record SET archive_hash = @hash64, archived_at = DATE_SUB(NOW(), INTERVAL 3 DAY), archive_file_id = 't_file_pdf_v1' WHERE id = 't_rec_v1';
UPDATE maintain_record SET prev_version_id = 't_rec_v1', amend_reason = '制动器间隙读数修正（8mm→7mm）',
  amended_by = 'emp_zjl', amended_at = DATE_SUB(NOW(), INTERVAL 2 HOUR) WHERE id = 't_rec_v2';
-- 维保双方手写签字（导出 PDF 签字确认区渲染用）：所有记录均已经签退链，主/配合签字齐全
UPDATE maintain_record SET worker_signature_url = '/files/t_file_sig_w',
  assistant_signature_url = '/files/t_file_sig_a' WHERE id LIKE 't\_rec\_%';
-- 客户签字：已签署/已归档记录（t_rec_signed 原有 signature_file_id，修正失效外链为同源文件地址）
UPDATE maintain_record SET signature_url = '/files/t_file_sig_c' WHERE id = 't_rec_signed';
UPDATE maintain_record SET client_account_id = 'ca_1', client_signer_name = '王芳',
  client_signed_at = DATE_SUB(NOW(), INTERVAL 2 DAY),
  signature_file_id = 't_file_sig_c', signature_url = '/files/t_file_sig_c' WHERE id IN ('t_rec_archived', 't_rec_v1');

-- ─────────────────────────────────────────────────────────────
-- 7. 急修工单（七态 + 困人 + 已评价/可评价）
-- ─────────────────────────────────────────────────────────────
INSERT INTO fault (id, fault_no, elevator_code, fault_type, descr, status, dispatch_worker_id, dispatched_at, handle_desc, finished_at, created_at, arrived_at, confirmed_at, evaluate_score, evaluate_text, evaluated_at, refuse_reason, cancel_reason, use_unit_id, use_unit_name, created_by, sla_minutes) VALUES
('t_ft_trapped', 'FT-T-001', 'EM-2024-001', '困人', '轿厢 3 层困人 2 人，乘客情绪稳定，需紧急救援', 'OPEN', NULL, NULL, NULL, NULL, DATE_SUB(NOW(), INTERVAL 40 MINUTE), NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'uu_1', '重庆世纪物业管理有限公司', 'ca_1', 30),
('t_ft_open', 'FT-T-002', 'EM-2024-002', '故障', '运行异响，疑似导轨缺油', 'OPEN', NULL, NULL, NULL, NULL, DATE_SUB(NOW(), INTERVAL 2 HOUR), NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'uu_1', '重庆世纪物业管理有限公司', 'ca_1', 240),
('t_ft_assigned', 'FT-T-003', 'EM-2024-003', '故障', '层门地坎卡阻，开关门不畅', 'ASSIGNED', 'emp_lzg', DATE_SUB(NOW(), INTERVAL 3 HOUR), NULL, NULL, DATE_SUB(NOW(), INTERVAL 3 HOUR), NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'uu_2', '重庆蓝湾物业服务有限公司', 'ca_4', 240),
('t_ft_processing', 'FT-T-004', 'EM-2024-002', '故障', '轿厢照明全部不亮', 'PROCESSING', 'emp_lzg', DATE_SUB(NOW(), INTERVAL 5 HOUR), NULL, NULL, DATE_SUB(NOW(), INTERVAL 5 HOUR), DATE_SUB(NOW(), INTERVAL 1 HOUR), NULL, NULL, NULL, NULL, NULL, NULL, 'uu_1', '重庆世纪物业管理有限公司', 'ca_1', 240),
('t_ft_pconfirm', 'FT-T-005', 'EM-2024-001', '故障', '平层偏差明显', 'PENDING_CONFIRM', 'emp_lzg', DATE_SUB(NOW(), INTERVAL 1 DAY), '已调整平层感应器并复测正常', DATE_SUB(NOW(), INTERVAL 2 HOUR), DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 3 HOUR), NULL, NULL, NULL, NULL, NULL, NULL, 'uu_1', '重庆世纪物业管理有限公司', 'ca_1', 240),
('t_ft_closed', 'FT-T-006', 'EM-2024-003', '故障', '制动器异响', 'CLOSED', 'emp_lzg', DATE_SUB(NOW(), INTERVAL 2 DAY), '更换制动器衬垫，复测正常', DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY), NULL, NULL, NULL, NULL, NULL, 'uu_2', '重庆蓝湾物业服务有限公司', 'ca_4', 240),
('t_ft_eval', 'FT-T-007', 'EM-2024-001', '故障', '门机皮带老化', 'CLOSED', 'emp_lzg', DATE_SUB(NOW(), INTERVAL 4 DAY), '更换门机皮带，运行正常', DATE_SUB(NOW(), INTERVAL 4 DAY), DATE_SUB(NOW(), INTERVAL 4 DAY), DATE_SUB(NOW(), INTERVAL 4 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY), 5, '响应及时，处理专业', DATE_SUB(NOW(), INTERVAL 3 DAY), NULL, NULL, 'uu_1', '重庆世纪物业管理有限公司', 'ca_1', 240),
('t_ft_refused', 'FT-T-008', 'EM-2024-002', '故障', '按钮面板松动', 'REFUSED', 'emp_lzg', DATE_SUB(NOW(), INTERVAL 2 DAY), '已紧固面板', DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY), NULL, NULL, NULL, NULL, '现场仍松动，要求重新处理', NULL, 'uu_1', '重庆世纪物业管理有限公司', 'ca_1', 240),
('t_ft_cancel', 'FT-T-009', 'EM-2024-101', '故障', '误报：保洁误触警铃', 'CANCELLED', NULL, NULL, NULL, NULL, DATE_SUB(NOW(), INTERVAL 3 DAY), NULL, NULL, NULL, NULL, NULL, NULL, '客户确认误报，无需处理', 't_uu3', '重庆江北城物业有限公司', 't_ca_u3a', 240);

-- ─────────────────────────────────────────────────────────────
-- 8. 安装移交单（七态 + 整改/归档子资源）
-- ─────────────────────────────────────────────────────────────
INSERT INTO handover_order (id, handover_no, created_at, status, elevator_id, project_name, elevator_code, elevator_name, category, location, reg_code, device_code, inside_number, model, brand, manufacturer, drive_mode, rated_load, rated_load_unit, rated_speed, rated_speed_unit, stations_doors, work_type_code, initiator_id, initiator_name, receiver_id, receiver_name, install_complete_date, install_unit_name) VALUES
('t_ho_doc', 'YJ-2026-T01', DATE_SUB(NOW(), INTERVAL 1 DAY), 'DOC_SUBMITTING', NULL, '江北城二期', 'EM-2024-101', '江北城 C 座客梯', '曳引与强制驱动电梯', '江北区江北城大街 12 号', 'TSCQ5001130101', 'DT-CQ-2024-101', 'KT-01', '通力 MonoSpace', '通力', '通力电梯有限公司', '曳引驱动', 1000, 'kg', 1.75, 'm/s', '18/18', 'HM', 'emp_wq', '王强', NULL, NULL, '2026-10-15', '重庆博威安装工程有限公司'),
('t_ho_review', 'YJ-2026-T02', DATE_SUB(NOW(), INTERVAL 2 DAY), 'PENDING_INST_REVIEW', NULL, '江北城二期', 'EM-2024-101', '江北城 C 座客梯', '曳引与强制驱动电梯', '江北区江北城大街 12 号', 'TSCQ5001130101', 'DT-CQ-2024-101', 'KT-01', '通力 MonoSpace', '通力', '通力电梯有限公司', '曳引驱动', 1000, 'kg', 1.75, 'm/s', '18/18', 'HM', 'emp_wq', '王强', NULL, NULL, '2026-10-15', '重庆博威安装工程有限公司'),
('t_ho_inspect', 'YJ-2026-T03', DATE_SUB(NOW(), INTERVAL 4 DAY), 'INSPECTING', NULL, '世纪大厦三期', 'EM-2024-001', '世纪大厦 1# 客梯', '曳引与强制驱动电梯', '渝北区龙山一路 88 号', 'TSCQ5001120001', 'DT-CQ-2024-001', 'KT-01', 'OTIS 300VF', '奥的斯', '奥的斯电梯（中国）有限公司', '曳引驱动', 1000, 'kg', 1.75, 'm/s', '18/18', 'HM', 'emp_wq', '王强', 'emp_zjl', '赵经理', '2026-10-20', '重庆博威安装工程有限公司'),
('t_ho_rectify', 'YJ-2026-T04', DATE_SUB(NOW(), INTERVAL 6 DAY), 'RECTIFYING', NULL, '世纪大厦三期', 'EM-2024-001', '世纪大厦 1# 客梯', '曳引与强制驱动电梯', '渝北区龙山一路 88 号', 'TSCQ5001120001', 'DT-CQ-2024-001', 'KT-01', 'OTIS 300VF', '奥的斯', '奥的斯电梯（中国）有限公司', '曳引驱动', 1000, 'kg', 1.75, 'm/s', '18/18', 'HM', 'emp_wq', '王强', 'emp_zjl', '赵经理', '2026-10-20', '重庆博威安装工程有限公司'),
('t_ho_confirm', 'YJ-2026-T05', DATE_SUB(NOW(), INTERVAL 8 DAY), 'PENDING_CONFIRM', NULL, '蓝湾国际二期', 'EM-2024-003', '蓝湾国际 A 座货梯', '曳引与强制驱动电梯', '江北区滨江路 6 号', 'TSCQ5001120003', 'DT-CQ-2024-003', 'HT-01', '三菱 GPS-III', '三菱', '上海三菱电梯有限公司', '曳引驱动', 2000, 'kg', 1.0, 'm/s', '6/6', 'HM', 'emp_wq', '王强', 'emp_zjl', '赵经理', '2026-10-25', '重庆博威安装工程有限公司'),
('t_ho_archived', 'YJ-2026-T06', DATE_SUB(NOW(), INTERVAL 20 DAY), 'ARCHIVED', 't_el1', '江北城一期', 'EM-2024-101', '江北城 C 座客梯', '曳引与强制驱动电梯', '江北区江北城大街 12 号', 'TSCQ5001130101', 'DT-CQ-2024-101', 'KT-01', '通力 MonoSpace', '通力', '通力电梯有限公司', '曳引驱动', 1000, 'kg', 1.75, 'm/s', '18/18', 'HM', 'emp_wq', '王强', 'emp_zjl', '赵经理', '2026-09-30', '重庆博威安装工程有限公司'),
('t_ho_voided', 'YJ-2026-T07', DATE_SUB(NOW(), INTERVAL 10 DAY), 'VOIDED', NULL, '蓝湾国际二期', 'EM-2024-004', '蓝湾国际 B 座客梯', '曳引与强制驱动电梯', '江北区滨江路 6 号', 'TSCQ5001120004', 'DT-CQ-2024-004', 'KT-01', '日立 YK', '日立', '日立电梯（中国）有限公司', '曳引驱动', 1000, 'kg', 1.5, 'm/s', '12/12', 'HM', 'emp_wq', '王强', NULL, NULL, '2026-10-22', '重庆博威安装工程有限公司');

UPDATE handover_order SET supervision_report_file_id = 't_file_sr', maintenance_manual_file_id = 't_file_mm', quality_certificate_file_id = 't_file_qc'
  WHERE id IN ('t_ho_review', 't_ho_inspect', 't_ho_rectify', 't_ho_confirm', 't_ho_archived');
UPDATE handover_order SET review_by_id = 'emp_zhang', review_by_name = '张工', reviewed_at = DATE_SUB(NOW(), INTERVAL 3 DAY), review_remark = '资料齐全，同意转现场查验'
  WHERE id IN ('t_ho_inspect', 't_ho_rectify', 't_ho_confirm', 't_ho_archived');
UPDATE handover_order SET confirmed_by_id = 'emp_zjl', confirmed_by_name = '赵经理', confirmed_at = DATE_SUB(NOW(), INTERVAL 18 DAY)
  WHERE id = 't_ho_archived';
UPDATE handover_order SET void_reason = '重复发起，作废重新建档', void_by_id = 'emp_zhang', void_by_name = '张工', voided_at = DATE_SUB(NOW(), INTERVAL 9 DAY)
  WHERE id = 't_ho_voided';

INSERT INTO handover_check (id, handover_id, checker_id, checker_name, checked_at, remark, total_items, pass_count, fail_count, na_count, important_fail_count, conclusion) VALUES
('t_hc_rect', 't_ho_rectify', 'emp_lzg', '李振国', DATE_SUB(NOW(), INTERVAL 5 DAY), '74 项查验，2 项不合格转整改', 74, 70, 2, 2, 1, 'RECTIFY'),
('t_hc_arc', 't_ho_archived', 'emp_lzg', '李振国', DATE_SUB(NOW(), INTERVAL 19 DAY), '74 项查验全部合格', 74, 72, 0, 2, 0, 'QUALIFIED');

INSERT INTO handover_rectification (id, handover_id, item_type, item_no, resp_party, due_date, before_photo_file_id, rect_measure, source, voided) VALUES
('t_hr_1', 't_ho_rectify', 'CHECK_ITEM', 27, 'INSTALL', DATE_ADD(@today, INTERVAL 7 DAY), 't_file_before1', NULL, 'AUTO', 0),
('t_hr_2', 't_ho_rectify', 'CHECK_ITEM', 45, 'INSTALL', DATE_ADD(@today, INTERVAL 7 DAY), 't_file_before2', '已更换层门滑块并复测', 'AUTO', 0);

INSERT INTO handover_archive (id, handover_id, file_id, category, seq, sha256, archived_at) VALUES
('t_ha_1', 't_ho_archived', 't_file_checklist', 'CHECKLIST', 1, @hash64, DATE_SUB(NOW(), INTERVAL 18 DAY)),
('t_ha_2', 't_ho_archived', 't_file_rect', 'RECTIFICATION', 2, @hash64, DATE_SUB(NOW(), INTERVAL 18 DAY)),
('t_ha_3', 't_ho_archived', 't_file_docs', 'DOCS', 3, @hash64, DATE_SUB(NOW(), INTERVAL 18 DAY)),
('t_ha_4', 't_ho_archived', 't_file_photos', 'PHOTOS', 4, @hash64, DATE_SUB(NOW(), INTERVAL 18 DAY)),
('t_ha_5', 't_ho_archived', 't_file_confirm', 'CONFIRMATION', 5, @hash64, DATE_SUB(NOW(), INTERVAL 18 DAY)),
('t_ha_6', 't_ho_archived', 't_file_first', 'FIRST_ORDER', 6, @hash64, DATE_SUB(NOW(), INTERVAL 18 DAY)),
('t_ha_7', 't_ho_archived', 't_file_pending', 'PENDING_DOCS', 7, @hash64, DATE_SUB(NOW(), INTERVAL 18 DAY));

INSERT INTO handover_confirmation (id, filing_id, key_tool_check, leftover_issue, our_sign_file_id, counterpart_sign_name, counterpart_sign_file_id, confirmed_by_id, confirmed_by_name, at, handover_id) VALUES
('t_hcfm_1', 't_fi_archived', '钥匙 3 把、工具箱 1 套已点交', '无遗留问题', 't_file_sig_our', '陈建国', 't_file_sig_cpn', 'emp_zjl', '赵经理', DATE_SUB(NOW(), INTERVAL 18 DAY), NULL);

-- ─────────────────────────────────────────────────────────────
-- 9. 外接建档单（四阶段 + 缺件待补办演示；交接确认记录挂归档单）
-- ─────────────────────────────────────────────────────────────
INSERT INTO external_filing_order (id, filing_no, created_at, status, contract_id, use_unit_id, elevator_id, initiator_id, initiator_name, project_name, supervision_pass_date, elevator_code, elevator_name, category, location, reg_code, device_code, inside_number, model, brand, manufacturer, drive_mode, rated_load, rated_load_unit, rated_speed, rated_speed_unit, stations_doors, work_type_code) VALUES
('t_fi_collect', 'JD-2026-T01', DATE_SUB(NOW(), INTERVAL 2 DAY), 'DOC_COLLECTING', 't_ct3', 't_uu3', NULL, 't_biz', '周商务', '江北城 D 座（外接）', '2024-05-20', 'EM-2024-102', '江北城 D 座货梯', '曳引与强制驱动电梯', '江北区江北城大街 12 号', NULL, 'DT-CQ-2024-102', 'HT-01', '日立 HGP', '日立', '日立电梯（中国）有限公司', '曳引驱动', 2000, 'kg', 1.0, 'm/s', '6/6', 'HM'),
('t_fi_check', 'JD-2026-T02', DATE_SUB(NOW(), INTERVAL 5 DAY), 'TAKEOVER_CHECKING', 't_ct2', 'uu_2', NULL, 't_biz', '周商务', '蓝湾国际 C 座（外接）', '2024-03-12', 'EM-2024-003', '蓝湾国际 A 座货梯', '曳引与强制驱动电梯', '江北区滨江路 6 号', 'TSCQ5001120003', 'DT-CQ-2024-003', 'HT-01', '三菱 GPS-III', '三菱', '上海三菱电梯有限公司', '曳引驱动', 2000, 'kg', 1.0, 'm/s', '6/6', 'HM'),
('t_fi_confirm', 'JD-2026-T03', DATE_SUB(NOW(), INTERVAL 9 DAY), 'PENDING_CONFIRM', 't_ct1', 'uu_1', NULL, 't_biz', '周商务', '世纪大厦 3#（外接）', '2023-11-08', 'EM-2024-002', '世纪大厦 2# 客梯', '曳引与强制驱动电梯', '渝北区龙山一路 88 号', 'TSCQ5001120002', 'DT-CQ-2024-002', 'KT-02', 'OTIS 300VF', '奥的斯', '奥的斯电梯（中国）有限公司', '曳引驱动', 1000, 'kg', 1.75, 'm/s', '18/18', 'HM'),
('t_fi_archived', 'JD-2026-T04', DATE_SUB(NOW(), INTERVAL 15 DAY), 'ARCHIVED', 't_ct1', 'uu_1', 'el_1', 't_biz', '周商务', '世纪大厦 1#（外接）', '2023-06-30', 'EM-2024-001', '世纪大厦 1# 客梯', '曳引与强制驱动电梯', '渝北区龙山一路 88 号', 'TSCQ5001120001', 'DT-CQ-2024-001', 'KT-01', 'OTIS 300VF', '奥的斯', '奥的斯电梯（中国）有限公司', '曳引驱动', 1000, 'kg', 1.75, 'm/s', '18/18', 'HM');

INSERT INTO external_filing_doc (id, filing_id, doc_type, file_id, missing_flag, remark) VALUES
('t_fd_1', 't_fi_collect', 'USE_REG_CERT', 't_file_reg', 0, NULL),
('t_fd_2', 't_fi_collect', 'INSPECTION_REPORT', NULL, 1, '原件在使用单位处，约定下周补齐');

UPDATE external_filing_order SET confirmed_by_id = 'emp_zjl', confirmed_by_name = '赵经理', confirmed_at = DATE_SUB(NOW(), INTERVAL 14 DAY)
  WHERE id = 't_fi_archived';

-- ─────────────────────────────────────────────────────────────
-- 10. 限时授权（生效中 / 已到期）+ 项目 + 消息 + 邀请码
-- ─────────────────────────────────────────────────────────────
INSERT INTO sys_grant (id, grantee_id, perm_code, scope, scope_ref, start_at, end_at, status, granted_by, reason, created_at) VALUES
('t_grant_1', 'emp_lzg', 'record:review', 'SELF', NULL, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 5 DAY), 'ACTIVE', 'emp_zjl', '本周代班复核（限时授权演示）', DATE_SUB(NOW(), INTERVAL 1 DAY)),
('t_grant_2', 'emp_lzg', 'report:retry', 'SELF', NULL, DATE_SUB(NOW(), INTERVAL 10 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY), 'ACTIVE', 'emp_zjl', '已到期待回收（回收任务演示）', DATE_SUB(NOW(), INTERVAL 10 DAY));

INSERT INTO biz_project (id, project_no, name, use_unit_id, maint_org_id, install_org_id, elevator_count, contract_id, contract_end_date, status, created_at) VALUES
('t_pj_1', 'PRJ-2026-T01', '江北城项目', 't_uu3', 'org_dept_maint', 'org_dept_install', 2, 't_ct3', DATE_SUB(@today, INTERVAL 5 DAY), 'ACTIVE', NOW());

INSERT INTO message (id, title, content, created_at, read_flag) VALUES
('t_msg_1', '今日维保任务提醒', '您今日有 2 条维保任务（1 条超期），请按时到场扫码签到', NOW(), 0),
('t_msg_2', '移交验收待办', '移交单 YJ-2026-T04 整改复验待处理，请及时跟进', NOW(), 0),
('t_msg_3', '平台对接公告', '监管平台业务接口已开放至 2.7，详见联调记录', DATE_SUB(NOW(), INTERVAL 2 DAY), 1);

INSERT INTO sys_invite (id, code, type, target_org_id, created_by, valid_days, expires_at, status, joined_count, created_at) VALUES
('t_inv_1', 'INV-MAINT-2026', 'EMPLOYEE', 'org_team_maint_2', 'emp_sys', 7, DATE_ADD(NOW(), INTERVAL 7 DAY), 'ACTIVE', 0, NOW());

COMMIT;

-- ─────────────────────────────────────────────────────────────
-- 11. 结果校验输出
-- ─────────────────────────────────────────────────────────────
SELECT 'employee' t, role, COUNT(*) c FROM sys_employee GROUP BY role
UNION ALL SELECT 'work_order', status, COUNT(*) FROM work_order GROUP BY status
UNION ALL SELECT 'fault', status, COUNT(*) FROM fault GROUP BY status
UNION ALL SELECT 'handover', status, COUNT(*) FROM handover_order GROUP BY status
UNION ALL SELECT 'record', lifecycle_status, COUNT(*) FROM maintain_record GROUP BY lifecycle_status
UNION ALL SELECT 'filing', status, COUNT(*) FROM external_filing_order GROUP BY status
UNION ALL SELECT 'client', status, COUNT(*) FROM client_account GROUP BY status
UNION ALL SELECT 'contract', status, COUNT(*) FROM maintenance_contract GROUP BY status;
