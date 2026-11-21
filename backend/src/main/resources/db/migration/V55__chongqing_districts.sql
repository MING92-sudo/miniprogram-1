-- V55：重庆市 38 区县主数据（管理端电梯台账「所在区域」下拉全量可选）
--
-- 背景：region 表此前只有演示用的渝北区一行（V24 建表，随机 id），新建/编辑电梯时
--       「所在区域」下拉只有一项可选。本迁移按行政区划代码序补齐重庆全部 38 区县。
-- 幂等：按 name 判重——已存在的区（如演示库的渝北区 rg_7X5…）保留原 id 不重复插入，
--       可安全在已初始化库执行；新装库一次性写入 38 行（rg_cq_01…38）。

INSERT INTO region (id, name, sort_order, remark)
SELECT v.id, v.name, v.sort_order, NULL FROM (
    SELECT 'rg_cq_01' AS id, '万州区'   AS name, 1  AS sort_order UNION ALL
    SELECT 'rg_cq_02', '涪陵区',   2  UNION ALL
    SELECT 'rg_cq_03', '渝中区',   3  UNION ALL
    SELECT 'rg_cq_04', '大渡口区', 4  UNION ALL
    SELECT 'rg_cq_05', '江北区',   5  UNION ALL
    SELECT 'rg_cq_06', '沙坪坝区', 6  UNION ALL
    SELECT 'rg_cq_07', '九龙坡区', 7  UNION ALL
    SELECT 'rg_cq_08', '南岸区',   8  UNION ALL
    SELECT 'rg_cq_09', '北碚区',   9  UNION ALL
    SELECT 'rg_cq_10', '綦江区',   10 UNION ALL
    SELECT 'rg_cq_11', '大足区',   11 UNION ALL
    SELECT 'rg_cq_12', '渝北区',   12 UNION ALL
    SELECT 'rg_cq_13', '巴南区',   13 UNION ALL
    SELECT 'rg_cq_14', '黔江区',   14 UNION ALL
    SELECT 'rg_cq_15', '长寿区',   15 UNION ALL
    SELECT 'rg_cq_16', '江津区',   16 UNION ALL
    SELECT 'rg_cq_17', '合川区',   17 UNION ALL
    SELECT 'rg_cq_18', '永川区',   18 UNION ALL
    SELECT 'rg_cq_19', '南川区',   19 UNION ALL
    SELECT 'rg_cq_20', '璧山区',   20 UNION ALL
    SELECT 'rg_cq_21', '铜梁区',   21 UNION ALL
    SELECT 'rg_cq_22', '潼南区',   22 UNION ALL
    SELECT 'rg_cq_23', '荣昌区',   23 UNION ALL
    SELECT 'rg_cq_24', '开州区',   24 UNION ALL
    SELECT 'rg_cq_25', '梁平区',   25 UNION ALL
    SELECT 'rg_cq_26', '武隆区',   26 UNION ALL
    SELECT 'rg_cq_27', '城口县',   27 UNION ALL
    SELECT 'rg_cq_28', '丰都县',   28 UNION ALL
    SELECT 'rg_cq_29', '垫江县',   29 UNION ALL
    SELECT 'rg_cq_30', '忠县',     30 UNION ALL
    SELECT 'rg_cq_31', '云阳县',   31 UNION ALL
    SELECT 'rg_cq_32', '奉节县',   32 UNION ALL
    SELECT 'rg_cq_33', '巫山县',   33 UNION ALL
    SELECT 'rg_cq_34', '巫溪县',   34 UNION ALL
    SELECT 'rg_cq_35', '石柱土家族自治县',     35 UNION ALL
    SELECT 'rg_cq_36', '秀山土家族苗族自治县', 36 UNION ALL
    SELECT 'rg_cq_37', '酉阳土家族苗族自治县', 37 UNION ALL
    SELECT 'rg_cq_38', '彭水苗族土家族自治县', 38
) v
WHERE NOT EXISTS (SELECT 1 FROM region r WHERE r.name = v.name);
