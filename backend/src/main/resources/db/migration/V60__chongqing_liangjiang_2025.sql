-- V60：重庆市 2025 年行政区划调整（党中央/国务院 2025-10 批复，渝府发〔2025〕15 号，2026-01-25 挂牌）
-- 撤销江北区、渝北区，设立两江新区（行政区划代码 500157）；全市 25 区 + 8 县 + 4 自治县 = 37 个县级行政区。
-- 原渝北区 5 镇划归北碚区（主数据只到区县级，北碚行无需变动）。
-- 全部语句幂等：判重插入、引用改挂、按名删除，可安全重复执行。

-- ① 新设两江新区（rg_cq_lj，sort_order=5 顶替原江北/渝北的中心城区位置）
INSERT INTO region (id, name, sort_order, remark)
SELECT 'rg_cq_lj', '两江新区', 5, '2025-11 设立（撤销江北区/渝北区，含北碚 5 街镇）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM region WHERE name = '两江新区');

-- ② 引用改挂：原属渝北区/江北区的电梯与员工 → 两江新区
UPDATE elevator SET region_id = 'rg_cq_lj'
  WHERE region_id IN (SELECT id FROM region WHERE name IN ('渝北区', '江北区'));
UPDATE sys_employee SET region_id = 'rg_cq_lj'
  WHERE region_id IN (SELECT id FROM region WHERE name IN ('渝北区', '江北区'));

-- ③ 在池工单区域快照同步改挂（行政区划调整属区域本体变更，非"电梯改区域不回溯"情形；
--    不改挂则旧渝北/江北快照永≠员工新区域，在池工单无人可接）
UPDATE work_order SET region_id = 'rg_cq_lj'
  WHERE region_id IN (SELECT id FROM region WHERE name IN ('渝北区', '江北区'));

-- ④ 删除旧两区（38 → 37）
DELETE FROM region WHERE name IN ('渝北区', '江北区');
