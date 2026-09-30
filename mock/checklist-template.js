// TSG T5002—2017 附件A（曳引与强制驱动电梯）检查项种子数据
// ✅ 正式种子：条目名称与基本要求已从《TSG T5002—2017 电梯维护保养规则》原件
//    （附件A 第4~7页）逐字提取并人工核对（2026-09-30），不再使用演示种子。
//
// 结构（TSG 原文「除符合上表项目(内容)和要求外，还应当符合下表」的累加式约定）：
//   表A-1 半月 31 项；表A-2 季度 +13（累计44）；表A-3 半年 +15（累计59）；表A-4 年度 +17（累计76）
//
// TSG 原文注释（逐字）：
//   注A-1：如果某些电梯没有表中的项目(内容)，如有的电梯不含有某种部件，项目(内容)可适当进行调整(下同)。
//   注A-2：维护保养项目(内容)和要求中对测试、试验有明确规定的，应当按照规定进行测试、试验，
//          没有明确规定的，一般为检查、调整、清洁和润滑(下同)。
//   注A-3：维护保养基本要求中，规定为"符合标准值"的，是指符合对应的国家标准、行业标准和制造单位要求(下同)。
//   注A-4：维护保养基本要求中，规定为"制造单位要求"的，按照制造单位的要求，
//          其他没有明确"要求"的，应当为安全技术规范、标准或者制造单位等的要求(下同)。
//
// isKey/photoRequired 依 注A-2 自动判定：基本要求含「试验/测试/校验/检测」字样的条目
// 为关键项（须测试/试验并强制附照片留证），其余为检查、调整、清洁和润滑类一般项。

const KEY_WORDS = ['试验', '测试', '校验', '检测']

// 注A-2 判定范围：项目(内容)与基本要求两列均须检查
// （如 A-4-06 限速器安全钳联动试验、A-4-07/08 动作试验，试验字样在项目(内容)列）
function autoKey(name, requirement) {
  const text = (name || '') + (requirement || '')
  return KEY_WORDS.some(function (w) {
    return text.indexOf(w) > -1
  })
}

// 行结构：[seq, name, requirement, judgeType, extra]
// name/requirement 为 TSG 原文逐字文本；judgeType 依 基本要求 判定：
//   STANDARD(原文"符合标准值/符合标准") / MANUFACTURER(原文"制造单位要求")
//   NUMERIC(原文量化限值) / QUALITATIVE(其余定性)
// extra: { valueMin, valueUnit, ageCondition }
const HALF = [
  [1, '机房、滑轮间环境', '清洁，门窗完好，照明正常', 'QUALITATIVE'],
  [2, '手动紧急操作装置', '齐全，在指定位置', 'QUALITATIVE'],
  [3, '驱动主机', '运行时无异常振动和异常声响', 'QUALITATIVE'],
  [4, '制动器各销轴部位', '动作灵活', 'QUALITATIVE'],
  [5, '制动器间隙', '打开时制动衬与制动轮不应发生摩擦，间隙值符合制造单位要求', 'MANUFACTURER'],
  [6, '制动器作为轿厢意外移动保护装置制停子系统时的自监测', '制动力人工方式检测符合使用维护说明书要求；制动力自监测系统有记录', 'QUALITATIVE'],
  [7, '编码器', '清洁，安装牢固', 'QUALITATIVE'],
  [8, '限速器各销轴部位', '润滑，转动灵活；电气开关正常', 'QUALITATIVE'],
  [9, '层门和轿门旁路装置', '工作正常', 'QUALITATIVE'],
  [10, '紧急电动运行', '工作正常', 'QUALITATIVE'],
  [11, '轿顶', '清洁，防护栏安全可靠', 'QUALITATIVE'],
  [12, '轿顶检修开关、停止装置', '工作正常', 'QUALITATIVE'],
  [13, '导靴上油杯', '吸油毛毡齐全，油量适宜，油杯无泄漏', 'QUALITATIVE'],
  [14, '对重/平衡重块及其压板', '对重/平衡重块无松动，压板紧固', 'QUALITATIVE'],
  [15, '井道照明', '齐全，正常', 'QUALITATIVE'],
  [16, '轿厢照明、风扇、应急照明', '工作正常', 'QUALITATIVE'],
  [17, '轿厢检修开关、停止装置', '工作正常', 'QUALITATIVE'],
  [18, '轿内报警装置、对讲系统', '工作正常', 'QUALITATIVE'],
  [19, '轿内显示、指令按钮、IC卡系统', '齐全，有效', 'QUALITATIVE'],
  [20, '轿门防撞击保护装置(安全触板，光幕、光电等)', '功能有效', 'QUALITATIVE'],
  [21, '轿门门锁电气触点', '清洁，触点接触良好，接线可靠', 'QUALITATIVE'],
  [22, '轿门运行', '开启和关闭工作正常', 'QUALITATIVE'],
  [23, '轿厢平层准确度', '符合标准值', 'STANDARD'],
  [24, '层站召唤、层楼显示', '齐全，有效', 'QUALITATIVE'],
  [25, '层门地坎', '清洁', 'QUALITATIVE'],
  [26, '层门自动关门装置', '正常', 'QUALITATIVE'],
  [27, '层门门锁自动复位', '用层门钥匙打开手动开锁装置释放后，层门门锁能自动复位', 'QUALITATIVE'],
  [28, '层门门锁电气触点', '清洁，触点接触良好，接线可靠', 'QUALITATIVE'],
  [29, '层门锁紧元件啮合长度', '不小于7mm', 'NUMERIC', { valueMin: 7, valueUnit: 'mm' }],
  [30, '底坑环境', '清洁，无渗水、积水，照明正常', 'QUALITATIVE'],
  [31, '底坑停止装置', '工作正常', 'QUALITATIVE']
]

const QUARTER = [
  [1, '减速机润滑油', '油量适宜，除蜗杆伸出端外均无渗漏', 'QUALITATIVE'],
  [2, '制动衬', '清洁，磨损量不超过制造单位要求', 'MANUFACTURER'],
  [3, '编码器', '工作正常', 'QUALITATIVE'],
  [4, '选层器动静触点', '清洁，无烧蚀', 'QUALITATIVE'],
  [5, '曳引轮槽、悬挂装置', '清洁，钢丝绳无严重油腻，张力均匀，符合制造单位要求', 'MANUFACTURER'],
  [6, '限速器轮槽、限速器钢丝绳', '清洁，无严重油腻', 'QUALITATIVE'],
  [7, '靴衬、滚轮', '清洁，磨损量不超过制造单位要求', 'MANUFACTURER'],
  [8, '验证轿门关闭的电气安全装置', '工作正常', 'QUALITATIVE'],
  [9, '层门、轿门系统中传动钢丝绳、链条、传动带', '按照制造单位要求进行清洁、调整', 'MANUFACTURER'],
  [10, '层门门导靴', '磨损量不超过制造单位要求', 'MANUFACTURER'],
  [11, '消防开关', '工作正常，功能有效', 'QUALITATIVE'],
  [12, '耗能缓冲器', '电气安全装置功能有效，油量适宜，柱塞无锈蚀', 'QUALITATIVE'],
  [13, '限速器张紧轮装置和电气安全装置', '工作正常', 'QUALITATIVE']
]

const HALF_YEAR = [
  [1, '电动机与减速机联轴器', '连接无松动，弹性元件外观良好，无老化等现象', 'QUALITATIVE'],
  [2, '驱动轮、导向轮轴承部', '无异常声响，无振动，润滑良好', 'QUALITATIVE'],
  [3, '曳引轮槽', '磨损量不超过制造单位要求', 'MANUFACTURER'],
  [4, '制动器动作状态监测装置', '工作正常，制动器动作可靠', 'QUALITATIVE'],
  [5, '控制柜内各接线端子', '各接线紧固、整齐，线号齐全清晰', 'QUALITATIVE'],
  [6, '控制柜各仪表', '显示正常', 'QUALITATIVE'],
  [7, '井道、对重、轿顶各反绳轮轴承部', '无异常声响，无振动，润滑良好', 'QUALITATIVE'],
  [8, '悬挂装置、补偿绳', '磨损量、断丝数不超过要求', 'QUALITATIVE'],
  [9, '绳头组合', '螺母无松动', 'QUALITATIVE'],
  [10, '限速器钢丝绳', '磨损量、断丝数不超过制造单位要求', 'MANUFACTURER'],
  [11, '层门、轿门门扇', '门扇各相关间隙符合标准值', 'STANDARD'],
  [12, '轿门开门限制装置', '工作正常', 'QUALITATIVE'],
  [13, '对重缓冲距离', '符合标准值', 'STANDARD'],
  [14, '补偿链(绳)与轿厢、对重接合处', '固定，无松动', 'QUALITATIVE'],
  [15, '上、下极限开关', '工作正常', 'QUALITATIVE']
]

const YEAR = [
  [1, '减速机润滑油', '按照制造单位要求适时更换，保证油质符合要求', 'MANUFACTURER'],
  [2, '控制柜接触器、继电器触点', '接触良好', 'QUALITATIVE'],
  [3, '制动器铁芯(柱塞)', '进行清洁、润滑、检查，磨损量不超过制造单位要求', 'MANUFACTURER'],
  [4, '制动器制动能力', '符合制造单位要求，保持有足够的制动力，必要时进行轿厢装载125%额定载重量的制动试验', 'MANUFACTURER'],
  [5, '导电回路绝缘性能测试', '符合标准', 'STANDARD'],
  [6, '限速器安全钳联动试验(对于使用年限不超过15年的限速器，每2年进行一次限速器动作速度校验；对于使用年限超过15年的限速器，每年进行一次限速器动作速度校验)', '工作正常', 'QUALITATIVE', { ageCondition: '使用年限≤15年：每2年一次；>15年：每年一次' }],
  [7, '上行超速保护装置动作试验', '工作正常', 'QUALITATIVE'],
  [8, '轿厢意外移动保护装置动作试验', '工作正常', 'QUALITATIVE'],
  [9, '轿顶、轿厢架、轿门及其附件安装螺栓', '紧固', 'QUALITATIVE'],
  [10, '轿厢和对重/平衡重的导轨支架', '固定，无松动', 'QUALITATIVE'],
  [11, '轿厢和对重/平衡重的导轨', '清洁，压板牢固', 'QUALITATIVE'],
  [12, '随行电缆', '无损伤', 'QUALITATIVE'],
  [13, '层门装置和地坎', '无影响正常使用的变形，各安装螺栓紧固', 'QUALITATIVE'],
  [14, '轿厢称重装置', '准确有效', 'QUALITATIVE'],
  [15, '安全钳钳座', '固定，无松动', 'QUALITATIVE'],
  [16, '轿底各安装螺栓', '紧固', 'QUALITATIVE'],
  [17, '缓冲器', '固定，无松动', 'QUALITATIVE']
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
    const isKey = autoKey(r[1], requirement)
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

// 各维保频次对应模板表（取并累加；FM 按需维保按合同，演示取半月基础表）
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
