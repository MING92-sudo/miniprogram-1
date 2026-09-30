// TSG T5002—2017 附件A（曳引与强制驱动电梯）检查项演示种子数据
//
// 结构严格对齐 docs/01 §3.7.3 与 docs/02 §10.1（经项目组逐字核对 TSG 原文）：
//   表A-1 半月 31 项；表A-2 季度 +13（累计44）；表A-3 半年 +15（累计59）；表A-4 年度 +17（累计76）
//   各频次取并集（TSG 原文：「除符合半月维保的项目(内容)和要求外，还应当符合表A-2/A-3/A-4」）
//
// ⚠️ 条目名称/要求为按 TSG 附件A 风格整理的演示种子（锚点项序号与文档一致：
//    A-1-01 机房、滑轮间环境；A-1-29 层门锁紧元件啮合长度≥7mm；A-1-31 底坑停止装置；
//    A-4-06 限速器安全钳联动试验[按使用年限]）。正式种子数据须按 docs/02 §10.2 五步流程
//    从 TSG T5002—2017 原件逐字提取、人工复核关键项后替换本文件。
//
// isKey/photoRequired 依 TSG 注A-2 规则自动判定：基本要求含「试验/测试/校验/检测」字样
// 的条目为关键项（须测试/试验并留证），其余为检查、调整、清洁和润滑类一般项。

const KEY_WORDS = ['试验', '测试', '校验', '检测']

function autoKey(requirement) {
  return KEY_WORDS.some((w) => (requirement || '').indexOf(w) > -1)
}

// 行结构：[seq, name, requirement, judgeType, extra]
// judgeType: NUMERIC(读数型) / STANDARD(标准值) / MANUFACTURER(按本机说明书判定) / QUALITATIVE(定性)
// extra: { valueMin, valueUnit, ageCondition, execCycleMonth, seasonWindow }
const HALF = [
  [1, '机房、滑轮间环境', '机房清洁，通风、照明正常，温度符合要求，门窗完好无渗水', 'QUALITATIVE'],
  [2, '手动紧急操作装置', '齐全、完好，在指定位置', 'QUALITATIVE'],
  [3, '驱动主机', '运行时无异常振动和异响，各紧固部位无松动', 'QUALITATIVE'],
  [4, '减速机润滑油', '油位在油标上下限之间，无渗漏', 'QUALITATIVE'],
  [5, '制动器各销轴部位', '润滑良好，动作灵活', 'QUALITATIVE'],
  [6, '制动器间隙', '打开时制动衬片与制动轮（盘）间隙符合制造单位要求', 'MANUFACTURER'],
  [7, '制动器作为轿厢意外移动保护装置制停子系统的自监测', '自监测功能有效，制停能力符合要求', 'QUALITATIVE'],
  [8, '编码器', '清洁，安装牢固', 'QUALITATIVE'],
  [9, '限速器各销轴部位', '润滑良好，转动灵活', 'QUALITATIVE'],
  [10, '曳引轮、导向轮、复绕轮', '转动灵活，无异常声响，轴承部位无异常温升', 'QUALITATIVE'],
  [11, '曳引钢丝绳', '无异常磨损、断丝，绳头组合无松动', 'QUALITATIVE'],
  [12, '限速器钢丝绳', '张紧适度，无异常磨损', 'QUALITATIVE'],
  [13, '轿顶装置', '清洁，检修运行正常，各安全开关有效', 'QUALITATIVE'],
  [14, '轿厢照明、风扇', '照明正常，风扇运转无异响', 'QUALITATIVE'],
  [15, '轿厢应急照明', '断电后自动投入，持续有效', 'QUALITATIVE'],
  [16, '轿厢报警装置', '报警按钮有效，轿厢与机房（值班室）通话清晰', 'QUALITATIVE'],
  [17, '平层准确度', '平层准确，误差在允许范围内', 'QUALITATIVE'],
  [18, '导靴上油杯', '油杯油量充足，导靴润滑良好，靴衬磨损正常', 'QUALITATIVE'],
  [19, '对重装置', '对重块无松动，压板紧固', 'QUALITATIVE'],
  [20, '补偿装置（补偿链/绳）', '悬挂可靠，运行无异响，无缠绕', 'QUALITATIVE'],
  [21, '层门门扇', '启闭灵活，无变形、脱轨', 'QUALITATIVE'],
  [22, '层门悬挂装置', '挂轮（悬挂轮/绳）磨损正常，固定牢固', 'QUALITATIVE'],
  [23, '层门门导轨', '清洁、润滑，无异物卡阻', 'QUALITATIVE'],
  [24, '层门地坎、轿厢地坎', '清洁，地坎槽无杂物', 'QUALITATIVE'],
  [25, '门刀与层门地坎、门锁滚轮与轿厢地坎间隙', '间隙符合要求，运行无碰擦', 'QUALITATIVE'],
  [26, '门机装置', '门机皮带（链条）张紧适度，开、关门顺畅', 'QUALITATIVE'],
  [27, '关门防夹装置', '安全触板/光幕动作有效', 'QUALITATIVE'],
  [28, '门锁装置', '锁钩啮合可靠，门锁回路导通', 'QUALITATIVE'],
  [29, '层门锁紧元件啮合长度', '啮合长度不小于 7mm（杂物电梯见表C-1-17：≥5mm）', 'NUMERIC', { valueMin: 7, valueUnit: 'mm' }],
  [30, '底坑环境', '无积水、渗漏，无杂物，照明正常', 'QUALITATIVE'],
  [31, '底坑停止装置', '动作有效，位置醒目', 'QUALITATIVE']
]

const QUARTER = [
  [1, '制动器弹簧压缩量', '压缩量符合制造单位要求', 'MANUFACTURER'],
  [2, '曳引机底座与减震装置', '减震垫无老化，地脚螺栓紧固', 'QUALITATIVE'],
  [3, '联轴器', '同轴度良好，无裂纹', 'QUALITATIVE'],
  [4, '层门自闭装置', '层门自闭有效', 'QUALITATIVE'],
  [5, '门锁触点', '清洁，接触良好，无烧蚀', 'QUALITATIVE'],
  [6, '门机电动机温升', '运行温升正常，热保护有效', 'QUALITATIVE'],
  [7, '轿厢护脚板', '完好，固定牢固', 'QUALITATIVE'],
  [8, '轿顶、对重导靴', '磨损量符合制造单位要求', 'MANUFACTURER'],
  [9, '导轨', '润滑良好，连接部位无松动，工作面无异常磨损', 'QUALITATIVE'],
  [10, '补偿绳张紧装置', '张紧适度，张紧轮转动灵活，安全开关有效', 'QUALITATIVE'],
  [11, '限速器张紧装置', '张紧轮位置正常，断绳保护开关有效', 'QUALITATIVE'],
  [12, '井道照明', '灯具完好，照度正常', 'QUALITATIVE'],
  [13, '消防返回功能检查（如有）', '消防开关触发后电梯返回指定层站', 'QUALITATIVE']
]

const HALF_YEAR = [
  [1, '制动器动作试验', '制动器动作灵活、可靠，制停有效', 'QUALITATIVE'],
  [2, '制动衬片磨损', '磨损量符合制造单位要求，铆钉（螺钉）头无露出', 'MANUFACTURER'],
  [3, '曳引轮槽磨损', '磨损量不超过制造单位要求', 'MANUFACTURER'],
  [4, '门机皮带张紧度', '张紧度符合制造单位要求', 'MANUFACTURER'],
  [5, '轿顶轮、对重轮轴承', '转动灵活，无异响', 'QUALITATIVE'],
  [6, '绳头组合紧固', '螺母、销轴紧固，各钢丝绳受力均匀', 'QUALITATIVE'],
  [7, '随行电缆', '无损伤、扭曲，固定可靠', 'QUALITATIVE'],
  [8, '轿厢称量（超载）装置试验', '超载报警有效，超载时开门不运行', 'QUALITATIVE'],
  [9, '上行超速保护装置动作试验', '动作可靠、有效', 'QUALITATIVE'],
  [10, '耗能型缓冲器', '油位符合要求、无渗漏，柱塞无锈蚀', 'QUALITATIVE'],
  [11, '蓄能型缓冲器（如有）', '无移位、松动，固定可靠', 'QUALITATIVE'],
  [12, '安全钳钳口间隙', '楔块与导轨侧面间隙符合制造单位要求', 'MANUFACTURER'],
  [13, '接触器、继电器触点', '无烧蚀，动作可靠', 'QUALITATIVE'],
  [14, '相序保护装置', '动作有效', 'QUALITATIVE'],
  [15, '悬挂钢丝绳断丝与磨损定量检查', '断丝与磨损不超过 GB/T 31821 规定值', 'STANDARD']
]

const YEAR = [
  [1, '制动器制动能力试验', '制动能力符合要求', 'QUALITATIVE'],
  [2, '轿厢意外移动保护装置动作试验', '动作可靠、有效', 'QUALITATIVE'],
  [3, '导电回路绝缘性能测试', '绝缘电阻符合要求', 'QUALITATIVE'],
  [4, '限速器动作速度校验', '使用年限≤15年每2年一次，>15年每年一次，校验报告在有效期内', 'QUALITATIVE', { ageCondition: '使用年限≤15年：每2年一次；>15年：每年一次' }],
  [5, '缓冲器复位试验', '柱塞复位时间与行程正常', 'QUALITATIVE'],
  [6, '限速器安全钳联动试验', '联动动作可靠；限速器动作速度校验按使用年限执行', 'QUALITATIVE', { ageCondition: '使用年限≤15年：每2年一次；>15年：每年一次' }],
  [7, '电动机运行电流与温升', '运行电流、温升正常，轴承无异响', 'QUALITATIVE'],
  [8, '减速机齿面与轴向游隙', '齿面无异常磨损，轴向游隙符合制造单位要求', 'MANUFACTURER'],
  [9, '曳引钢丝绳直径测量', '直径减小量不超过 GB/T 31821 规定值', 'STANDARD'],
  [10, '绳头组合与各钢丝绳受力（年度复查）', '紧固、受力均匀', 'QUALITATIVE'],
  [11, '轿厢、层门指示一致性', '轿内、外显示与实际楼层一致，呼梯按钮有效', 'QUALITATIVE'],
  [12, '极限开关动作试验', '上、下极限开关动作有效', 'QUALITATIVE'],
  [13, '底坑检修装置', '爬梯、照明、插座完好', 'QUALITATIVE'],
  [14, '应急救援程序操作', '按应急救援程序操作有效（盘车、开门装置）', 'QUALITATIVE'],
  [15, '消防返回功能试验（如有）', '消防开关动作后电梯自动返回指定层站', 'QUALITATIVE'],
  [16, '控制系统故障自诊断', '故障自诊断正常，无未处理的故障记录', 'QUALITATIVE'],
  [17, '井道与机房防护', '井道安全门、机房护栏完好可靠', 'QUALITATIVE']
]

// 表内序号 → 检查项编码（附件-频次-序号，如 A-1-29；docs/01 §3.7.3 编码规则）
const TABLE_NO = { HALF: 1, QUARTER: 2, HALF_YEAR: 3, YEAR: 4 }

function pad2(n) {
  return (n < 10 ? '0' : '') + n
}

function buildRows(rows, freq) {
  const tableNo = TABLE_NO[freq]
  return rows.map(function (r) {
    const extra = r[4] || {}
    const requirement = r[2]
    const isKey = autoKey(requirement)
    return {
      appendix: 'A',
      freq: freq,
      tableNo: tableNo,
      seq: r[0],
      itemCode: 'A-' + tableNo + '-' + pad2(r[0]),
      name: r[1],
      requirement: requirement,
      judgeType: r[3],
      valueMin: extra.valueMin != null ? extra.valueMin : null,
      valueUnit: extra.valueUnit || '',
      isKey: isKey,
      photoRequired: isKey, // 注A-2：关键项（试验/测试/校验/检测）强制附照片留证
      ageCondition: extra.ageCondition || '',
      execCycleMonth: extra.execCycleMonth || null,
      seasonWindow: extra.seasonWindow || ''
    }
  })
}

const APPENDIX_A_TPL = {
  HALF: buildRows(HALF, 'HALF'),
  QUARTER: buildRows(QUARTER, 'QUARTER'),
  HALF_YEAR: buildRows(HALF_YEAR, 'HALF_YEAR'),
  YEAR: buildRows(YEAR, 'YEAR')
}

// 各维保频次对应模板表（取并集；FM 按需维保按合同，演示取半月基础表）
// HM=半月(31) TM=季度(44) SM=半年(59) OY=年度(76) FM=按需(演示=31)
const FREQ_CHAIN = {
  FM: ['HALF'],
  HM: ['HALF'],
  TM: ['HALF', 'QUARTER'],
  SM: ['HALF', 'QUARTER', 'HALF_YEAR'],
  OY: ['HALF', 'QUARTER', 'HALF_YEAR', 'YEAR']
}

const FREQ_LABEL = {
  HALF: '半月维保项目（表A-1）',
  QUARTER: '季度维保项目（表A-2）',
  HALF_YEAR: '半年维保项目（表A-3）',
  YEAR: '年度维保项目（表A-4）'
}

module.exports = {
  APPENDIX_A_TPL,
  FREQ_CHAIN,
  FREQ_LABEL,
  TABLE_NO
}
