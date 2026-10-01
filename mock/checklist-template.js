// 附件A—D 检查项种子数据（共257行）。
// 来源：TSG T5002—2017 原件附件A—D。
// 本文件不再使用演示种子。
//
// 结构（原文「除符合上表项目(内容)和要求外，还应当符合下表」的累加式约定）：
//   表A-1 半月 31 项；表A-2 季度 +13（累计44）；表A-3 半年 +15（累计59）；表A-4 年度 +17（累计76）
//
// 原文注释（逐字）：
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
// name/requirement 为原文逐字文本；judgeType 依 基本要求 判定：
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

// ── 附件B：液压驱动电梯（B1 30项 / B2 13项 / B3 9项 / B4 14项）──
const B_HALF = [
  [1, '机房环境', '清洁，室温符合要求，门窗完好，照明正常', 'QUALITATIVE'],
  [2, '机房内手动泵操作装置', '齐全，在指定位置', 'QUALITATIVE'],
  [3, '油箱', '油量、油温正常，无杂质、无漏油现象', 'QUALITATIVE'],
  [4, '电动机', '运行时无异常振动和异常声响', 'QUALITATIVE'],
  [5, '层门和轿门旁路装置', '工作正常', 'QUALITATIVE'],
  [6, '阀、泵、消音器、油管、表、接口等部件', '无漏油现象', 'QUALITATIVE'],
  [7, '编码器', '清洁，安装牢固', 'QUALITATIVE'],
  [8, '轿顶', '清洁，防护栏安全可靠', 'QUALITATIVE'],
  [9, '轿顶检修开关、停止装置', '工作正常', 'QUALITATIVE'],
  [10, '导靴上油杯', '吸油毛毡齐全，油量适宜，油杯无泄漏', 'QUALITATIVE'],
  [11, '井道照明', '齐全，正常', 'QUALITATIVE'],
  [12, '限速器各销轴部位', '润滑，转动灵活，电气开关正常', 'QUALITATIVE'],
  [13, '轿厢照明、风扇、应急照明', '工作正常', 'QUALITATIVE'],
  [14, '轿厢检修开关、停止装置', '工作正常', 'QUALITATIVE'],
  [15, '轿内报警装置、对讲系统', '正常', 'QUALITATIVE'],
  [16, '轿内显示、指令按钮', '齐全，有效', 'QUALITATIVE'],
  [17, '轿门防撞击保护装置(安全触板，光幕、光电等)', '功能有效', 'QUALITATIVE'],
  [18, '轿门门锁触点', '清洁，触点接触良好，接线可靠', 'QUALITATIVE'],
  [19, '轿门运行', '开启和关闭工作正常', 'QUALITATIVE'],
  [20, '轿厢平层准确度', '符合标准值', 'STANDARD'],
  [21, '层站召唤、层楼显示', '齐全，有效', 'QUALITATIVE'],
  [22, '层门地坎', '清洁', 'QUALITATIVE'],
  [23, '层门自动关门装置', '正常', 'QUALITATIVE'],
  [24, '层门门锁自动复位', '用层门钥匙打开手动开锁装置释放后，层门门锁能自动复位', 'QUALITATIVE'],
  [25, '层门门锁电气触点', '清洁，触点接触良好，接线可靠', 'QUALITATIVE'],
  [26, '层门锁紧元件啮合长度', '不小于7mm', 'NUMERIC', { valueMin: 7, valueUnit: 'mm' }],
  [27, '底坑', '清洁，无渗水、积水，照明正常', 'QUALITATIVE'],
  [28, '底坑停止装置', '工作正常', 'QUALITATIVE'],
  [29, '液压柱塞', '无漏油，运行顺畅，柱塞表面光滑', 'QUALITATIVE'],
  [30, '井道内液压油管、接口', '无漏油', 'QUALITATIVE']
]

const B_QUARTER = [
  [1, '安全溢流阀(在油泵与单向阀之间)', '其工作压力不得高于满负荷压力的170%', 'NUMERIC', { valueMax: 170, valueUnit: '%' }],
  [2, '手动下降阀', '通过下降阀动作，轿厢能下降；系统压力小于该阀最小操作压力时，手动操作应无效(间接式液压电梯)', 'QUALITATIVE'],
  [3, '手动泵', '通过手动泵动作，轿厢被提升；相连接的溢流阀工作压力不得高于满负荷压力的2.3倍', 'NUMERIC', { valueMax: 2.3, valueUnit: '倍' }],
  [4, '油温监控装置', '功能可靠', 'QUALITATIVE'],
  [5, '限速器轮槽、限速器钢丝绳', '清洁，无严重油腻', 'QUALITATIVE'],
  [6, '验证轿门关闭的电气安全装置', '工作正常', 'QUALITATIVE'],
  [7, '轿厢侧靴衬、滚轮', '磨损量不超过制造单位要求', 'MANUFACTURER'],
  [8, '柱塞侧靴衬', '清洁，磨损量不超过制造单位要求', 'MANUFACTURER'],
  [9, '层门、轿门系统中传动钢丝绳、链条、胶带', '按照制造单位要求进行清洁、调整', 'MANUFACTURER'],
  [10, '层门门导靴', '磨损量不超过制造单位要求', 'MANUFACTURER'],
  [11, '消防开关', '工作正常，功能有效', 'QUALITATIVE'],
  [12, '耗能缓冲器', '电气安全装置功能有效，油量适宜，柱塞无锈蚀', 'QUALITATIVE'],
  [13, '限速器张紧轮装置和电气安全装置', '工作正常', 'QUALITATIVE']
]

const B_HALF_YEAR = [
  [1, '控制柜内各接线端子', '各接线紧固，整齐，线号齐全清晰', 'QUALITATIVE'],
  [2, '控制柜', '各仪表显示正确', 'QUALITATIVE'],
  [3, '导向轮', '轴承部无异常声响', 'QUALITATIVE'],
  [4, '悬挂钢丝绳', '磨损量、断丝数未超过要求', 'QUALITATIVE'],
  [5, '悬挂钢丝绳绳头组合', '螺母无松动', 'QUALITATIVE'],
  [6, '限速器钢丝绳', '磨损量、断丝数不超过制造单位要求', 'MANUFACTURER'],
  [7, '柱塞限位装置', '符合要求', 'QUALITATIVE'],
  [8, '上下极限开关', '工作正常', 'QUALITATIVE'],
  [9, '柱塞、消音器放气操作', '符合要求', 'QUALITATIVE']
]

const B_YEAR = [
  [1, '控制柜接触器、继电器触点', '接触良好', 'QUALITATIVE'],
  [2, '动力装置各安装螺栓', '紧固', 'QUALITATIVE'],
  [3, '导电回路绝缘性能测试', '符合标准值', 'STANDARD'],
  [4, '限速器安全钳联动试验(每2年进行一次限速器动作速度校验)', '工作正常', 'QUALITATIVE', { execCycleMonth: 24 }],
  [5, '随行电缆', '无损伤', 'QUALITATIVE'],
  [6, '层门装置和地坎', '无影响正常使用的变形，各安装螺栓紧固', 'QUALITATIVE'],
  [7, '轿顶、轿厢架、轿门及附件安装螺栓', '紧固', 'QUALITATIVE'],
  [8, '轿厢称重装置', '准确有效', 'QUALITATIVE'],
  [9, '安全钳钳座', '固定，无松动', 'QUALITATIVE'],
  [10, '轿厢及油缸导轨支架', '牢固', 'QUALITATIVE'],
  [11, '轿厢及油缸导轨', '清洁，压板牢固', 'QUALITATIVE'],
  [12, '轿底各安装螺栓', '紧固', 'QUALITATIVE'],
  [13, '缓冲器', '固定，无松动', 'QUALITATIVE'],
  [14, '轿厢沉降试验', '符合标准值', 'STANDARD']
]

// ── 附件C：杂物电梯（C1 20项 / C2 8项 / C3 10项 / C4 14项）──
const C_HALF = [
  [1, '机房、通道环境', '清洁，门窗完好，照明正常', 'QUALITATIVE'],
  [2, '手动紧急操作装置', '齐全，在指定位置', 'QUALITATIVE'],
  [3, '驱动主机', '运行时无异常振动和异常声响', 'QUALITATIVE'],
  [4, '制动器各销轴部位', '润滑，动作灵活', 'QUALITATIVE'],
  [5, '制动器间隙', '打开时制动衬与制动轮不发生摩擦', 'QUALITATIVE'],
  [6, '限速器各销轴部位', '润滑，转动灵活，电气开关正常', 'QUALITATIVE'],
  [7, '轿顶', '清洁', 'QUALITATIVE'],
  [8, '轿顶停止装置', '工作正常', 'QUALITATIVE'],
  [9, '导靴上油杯', '吸油毛毡齐全，油量适宜，油杯无泄漏', 'QUALITATIVE'],
  [10, '对重/平衡重块及压板', '对重/平衡重块无松动，压板紧固', 'QUALITATIVE'],
  [11, '井道照明', '齐全，正常', 'QUALITATIVE'],
  [12, '轿门门锁触点', '清洁，触点接触良好，接线可靠', 'QUALITATIVE'],
  [13, '层站召唤、层楼显示', '齐全，有效', 'QUALITATIVE'],
  [14, '层门地坎', '清洁', 'QUALITATIVE'],
  [15, '层门门锁自动复位', '用层门钥匙打开手动开锁装置释放后，层门门锁能自动复位', 'QUALITATIVE'],
  [16, '层门门锁电气触点', '清洁，触点接触良好，接线可靠', 'QUALITATIVE'],
  [17, '层门锁紧元件啮合长度', '不小于5mm', 'NUMERIC', { valueMin: 5, valueUnit: 'mm' }],
  [18, '层门门导靴', '无卡阻，滑动顺畅', 'QUALITATIVE'],
  [19, '底坑环境', '清洁，无渗水、积水，照明正常', 'QUALITATIVE'],
  [20, '底坑停止装置', '工作正常', 'QUALITATIVE']
]

const C_QUARTER = [
  [1, '减速机润滑油', '油量适宜，除蜗杆伸出端外均无渗漏', 'QUALITATIVE'],
  [2, '制动衬', '清洁，磨损量不超制造单位要求', 'MANUFACTURER'],
  [3, '曳引轮槽、悬挂装置', '清洁，无严重油腻，张力均匀', 'QUALITATIVE'],
  [4, '限速器轮槽、限速器钢丝绳', '清洁，无严重油腻', 'QUALITATIVE'],
  [5, '靴衬', '清洁，磨损量不超过制造单位要求', 'MANUFACTURER'],
  [6, '层门、轿门系统中传动钢丝绳、链条、传动带', '按照制造单位要求进行清洁、调整', 'MANUFACTURER'],
  [7, '层门门导靴', '磨损量不超过制造单位要求', 'MANUFACTURER'],
  [8, '限速器张紧轮装置和电气安全装置', '工作正常', 'QUALITATIVE']
]

const C_HALF_YEAR = [
  [1, '电动机与减速机联轴器', '连接无松动，弹性元件外观良好，无老化等现象', 'QUALITATIVE'],
  [2, '驱动轮、导向轮轴承部', '无异常声响，无振动，润滑良好', 'QUALITATIVE'],
  [3, '制动器上检测开关', '工作正常，制动器动作可靠', 'QUALITATIVE'],
  [4, '控制柜内各接线端子', '各接线紧固、整齐，线号齐全清晰', 'QUALITATIVE'],
  [5, '控制柜各仪表', '显示正确', 'QUALITATIVE'],
  [6, '悬挂装置', '磨损量、断丝数未超过要求', 'QUALITATIVE'],
  [7, '绳头组合', '螺母无松动', 'QUALITATIVE'],
  [8, '限速器钢丝绳', '磨损量、断丝数不超过制造单位要求', 'MANUFACTURER'],
  [9, '对重缓冲距离', '符合标准值', 'STANDARD'],
  [10, '上、下极限开关', '工作正常', 'QUALITATIVE']
]

const C_YEAR = [
  [1, '减速机润滑油', '按照制造单位要求适时更换，油质符合要求', 'MANUFACTURER'],
  [2, '控制柜接触器、继电器触点', '接触良好', 'QUALITATIVE'],
  [3, '制动器铁芯(柱塞)', '分解进行清洁、润滑、检查，磨损量不超过制造单位要求', 'MANUFACTURER'],
  [4, '制动器制动弹簧压缩量', '符合制造单位要求，保持有足够的制动力', 'MANUFACTURER'],
  [5, '导电回路绝缘性能测试', '符合标准值', 'STANDARD'],
  [6, '限速器安全钳联动试验(每5年进行一次限速器动作速度校验)', '工作正常', 'QUALITATIVE', { execCycleMonth: 60 }],
  [7, '轿顶、轿厢架、轿门及附件安装螺栓', '紧固', 'QUALITATIVE'],
  [8, '轿厢及对重/平衡重导轨支架', '固定，无松动', 'QUALITATIVE'],
  [9, '轿厢及对重/平衡重导轨', '清洁，压板牢固', 'QUALITATIVE'],
  [10, '随行电缆', '无损伤', 'QUALITATIVE'],
  [11, '层门装置和地坎', '无影响正常使用的变形，各安装螺栓紧固', 'QUALITATIVE'],
  [12, '安全钳钳座', '固定，无松动', 'QUALITATIVE'],
  [13, '轿底各安装螺栓', '紧固', 'QUALITATIVE'],
  [14, '缓冲器', '固定，无松动', 'QUALITATIVE']
]

// ── 附件D：自动扶梯与自动人行道（D1 33项 / D2 5项 / D3 12项 / D4 13项）──
const D_HALF = [
  [1, '电器部件', '清洁，接线紧固', 'QUALITATIVE'],
  [2, '故障显示板', '信号功能正常', 'QUALITATIVE'],
  [3, '设备运行状况', '正常，没有异常声响和抖动', 'QUALITATIVE'],
  [4, '主驱动链', '运转正常，电气安全保护装置动作有效', 'QUALITATIVE'],
  [5, '制动器机械装置', '清洁，动作正常', 'QUALITATIVE'],
  [6, '制动器状态监测开关', '工作正常', 'QUALITATIVE'],
  [7, '减速机润滑油', '油量适宜，无渗油', 'QUALITATIVE'],
  [8, '电机通风口', '清洁', 'QUALITATIVE'],
  [9, '检修控制装置', '工作正常', 'QUALITATIVE'],
  [10, '自动润滑油罐油位', '油位正常，润滑系统工作正常', 'QUALITATIVE'],
  [11, '梳齿板开关', '工作正常', 'QUALITATIVE'],
  [12, '梳齿板照明', '照明正常', 'QUALITATIVE'],
  [13, '梳齿板梳齿与踏板面齿槽、导向胶带', '梳齿板完好无损，梳齿板梳齿与踏板面齿槽、导向胶带啮合正常', 'QUALITATIVE'],
  [14, '梯级或者踏板下陷开关', '工作正常', 'QUALITATIVE'],
  [15, '梯级或者踏板缺失监测装置', '工作正常', 'QUALITATIVE'],
  [16, '超速或非操纵逆转监测装置', '工作正常', 'QUALITATIVE'],
  [17, '检修盖板和楼层板', '防倾覆或者翻转措施和监控装置有效、可靠', 'QUALITATIVE'],
  [18, '梯级链张紧开关', '位置正确，动作正常', 'QUALITATIVE'],
  [19, '防护挡板', '有效，无破损', 'QUALITATIVE'],
  [20, '梯级滚轮和梯级导轨', '工作正常', 'QUALITATIVE'],
  [21, '梯级、踏板与围裙板之间的间隙', '任何一侧的水平间隙及两侧间隙之和符合标准值', 'STANDARD'],
  [22, '运行方向显示', '工作正常', 'QUALITATIVE'],
  [23, '扶手带入口处保护开关', '动作灵活可靠，清除入口处垃圾', 'QUALITATIVE'],
  [24, '扶手带', '表面无毛刺，无机械损伤，运行无摩擦', 'QUALITATIVE'],
  [25, '扶手带运行', '速度正常', 'QUALITATIVE'],
  [26, '扶手护壁板', '牢固可靠', 'QUALITATIVE'],
  [27, '上下出入口处的照明', '工作正常', 'QUALITATIVE'],
  [28, '上下出入口和扶梯之间保护栏杆', '牢固可靠', 'QUALITATIVE'],
  [29, '出入口安全警示标志', '齐全，醒目', 'QUALITATIVE'],
  [30, '分离机房、各驱动和转向站', '清洁，无杂物', 'QUALITATIVE'],
  [31, '自动运行功能', '工作正常', 'QUALITATIVE'],
  [32, '紧急停止开关', '工作正常', 'QUALITATIVE'],
  [33, '驱动主机的固定', '牢固可靠', 'QUALITATIVE']
]

const D_QUARTER = [
  [1, '扶手带的运行速度', '相对于梯级、踏板或者胶带的速度允差为0～＋2％', 'NUMERIC', { valueMin: 0, valueMax: 2, valueUnit: '%' }],
  [2, '梯级链张紧装置', '工作正常', 'QUALITATIVE'],
  [3, '梯级轴衬', '润滑有效', 'QUALITATIVE'],
  [4, '梯级链润滑', '运行工况正常', 'QUALITATIVE'],
  [5, '防灌水保护装置', '动作可靠(雨季到来之前必须完成)', 'QUALITATIVE', { seasonWindow: '雨季前' }]
]

const D_HALF_YEAR = [
  [1, '制动衬厚度', '不小于制造单位要求', 'MANUFACTURER'],
  [2, '主驱动链', '清理表面油污，润滑', 'QUALITATIVE'],
  [3, '主驱动链链条滑块', '清洁，厚度符合制造单位要求', 'MANUFACTURER'],
  [4, '电动机与减速机联轴器', '连接无松动，弹性元件外观良好，无老化等现象', 'QUALITATIVE'],
  [5, '空载向下运行制动距离', '符合标准值', 'STANDARD'],
  [6, '制动器机械装置', '润滑，工作有效', 'QUALITATIVE'],
  [7, '附加制动器', '清洁和润滑，功能可靠', 'QUALITATIVE'],
  [8, '减速机润滑油', '按照制造单位的要求进行检查、更换', 'QUALITATIVE'],
  [9, '调整梳齿板梳齿与踏板面齿槽啮合深度和间隙', '符合标准值', 'STANDARD'],
  [10, '扶手带张紧度张紧弹簧负荷长度', '符合制造单位要求', 'MANUFACTURER'],
  [11, '扶手带速度监控系统', '工作正常', 'QUALITATIVE'],
  [12, '梯级踏板加热装置', '功能正常，温度感应器接线牢固(冬季到来之前必须完成)', 'QUALITATIVE', { seasonWindow: '冬季前' }]
]

const D_YEAR = [
  [1, '主接触器', '工作可靠', 'QUALITATIVE'],
  [2, '主机速度检测功能', '功能可靠，清洁感应面、感应间隙符合制造单位要求', 'MANUFACTURER'],
  [3, '电缆', '无破损，固定牢固', 'QUALITATIVE'],
  [4, '扶手带托轮、滑轮群、防静电轮', '清洁，无损伤，托轮转动平滑', 'QUALITATIVE'],
  [5, '扶手带内侧凸缘处', '无损伤，清洁扶手导轨滑动面', 'QUALITATIVE'],
  [6, '扶手带断带保护开关', '功能正常', 'QUALITATIVE'],
  [7, '扶手带导向块和导向轮', '清洁，工作正常', 'QUALITATIVE'],
  [8, '进入梳齿板处的梯级与导轮的轴向窜动量', '符合制造单位要求', 'MANUFACTURER'],
  [9, '内外盖板连接', '紧密牢固，连接处的凸台、缝隙符合制造单位要求', 'MANUFACTURER'],
  [10, '围裙板安全开关', '测试有效', 'QUALITATIVE'],
  [11, '围裙板对接处', '紧密平滑', 'QUALITATIVE'],
  [12, '电气安全装置', '动作可靠', 'QUALITATIVE'],
  [13, '设备运行状况', '正常，梯级运行平稳，无异常抖动，无异常声响', 'QUALITATIVE']
]

// 表内序号 → 检查项编码（附件-频次-序号，如 A-1-29）
const TABLE_NO = { HALF: 1, QUARTER: 2, HALF_YEAR: 3, YEAR: 4 }

function pad2(n) {
  return (n < 10 ? '0' : '') + n
}

function buildRows(rows, appendix, freq) {
  const tableNo = TABLE_NO[freq]
  return rows.map(function (r) {
    const extra = r[4] || {}
    const requirement = r[2]
    const isKey = autoKey(r[1], requirement)
    return {
      appendix: appendix,
      freq: freq,
      tableNo: tableNo,
      seq: r[0],
      itemCode: appendix + '-' + tableNo + '-' + pad2(r[0]),
      name: r[1],
      requirement: requirement,
      judgeType: r[3],
      valueMin: extra.valueMin != null ? extra.valueMin : null,
      valueMax: extra.valueMax != null ? extra.valueMax : null,
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
  HALF: buildRows(HALF, 'A', 'HALF'),
  QUARTER: buildRows(QUARTER, 'A', 'QUARTER'),
  HALF_YEAR: buildRows(HALF_YEAR, 'A', 'HALF_YEAR'),
  YEAR: buildRows(YEAR, 'A', 'YEAR')
}

const APPENDIX_B_TPL = {
  HALF: buildRows(B_HALF, 'B', 'HALF'),
  QUARTER: buildRows(B_QUARTER, 'B', 'QUARTER'),
  HALF_YEAR: buildRows(B_HALF_YEAR, 'B', 'HALF_YEAR'),
  YEAR: buildRows(B_YEAR, 'B', 'YEAR')
}

const APPENDIX_C_TPL = {
  HALF: buildRows(C_HALF, 'C', 'HALF'),
  QUARTER: buildRows(C_QUARTER, 'C', 'QUARTER'),
  HALF_YEAR: buildRows(C_HALF_YEAR, 'C', 'HALF_YEAR'),
  YEAR: buildRows(C_YEAR, 'C', 'YEAR')
}

const APPENDIX_D_TPL = {
  HALF: buildRows(D_HALF, 'D', 'HALF'),
  QUARTER: buildRows(D_QUARTER, 'D', 'QUARTER'),
  HALF_YEAR: buildRows(D_HALF_YEAR, 'D', 'HALF_YEAR'),
  YEAR: buildRows(D_YEAR, 'D', 'YEAR')
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

// 检查项编码与台账展示使用附件前缀，防止 B/C/D 与 A 的同名条目串表。
const FREQ_LABELS = {
  A: FREQ_LABEL,
  B: {
    HALF: '半月维保项目（表B-1）',
    QUARTER: '季度维保项目（表B-2）',
    HALF_YEAR: '半年维保项目（表B-3）',
    YEAR: '年度维保项目（表B-4）'
  },
  C: {
    HALF: '半月维保项目（表C-1）',
    QUARTER: '季度维保项目（表C-2）',
    HALF_YEAR: '半年维保项目（表C-3）',
    YEAR: '年度维保项目（表C-4）'
  },
  D: {
    HALF: '半月维保项目（表D-1）',
    QUARTER: '季度维保项目（表D-2）',
    HALF_YEAR: '半年维保项目（表D-3）',
    YEAR: '年度维保项目（表D-4）'
  }
}

// 品种 → 附件。消防员电梯/防爆电梯禁止使用标准模板。
const CATEGORY_APPENDIX = {
  '曳引驱动电梯': 'A',
  '强制驱动电梯': 'A',
  '液压乘客电梯': 'B',
  '液压载货电梯': 'B',
  '杂物电梯': 'C',
  '自动扶梯': 'D',
  '自动人行道': 'D'
}

module.exports = {
  APPENDIX_A_TPL,
  APPENDIX_B_TPL,
  APPENDIX_C_TPL,
  APPENDIX_D_TPL,
  APPENDIX_TPLS: { A: APPENDIX_A_TPL, B: APPENDIX_B_TPL, C: APPENDIX_C_TPL, D: APPENDIX_D_TPL },
  FREQ_CHAIN,
  FREQ_LABEL,
  FREQ_LABELS,
  CATEGORY_APPENDIX,
  TABLE_NO
}
