// 设备信息档案字段口径（用户 2026-11-19 需求；后端 DeviceProfileValidator 同口径，标签不得改写）。
//
// 9 项字段，**前 5 项为上传维保记录（监管平台 2.6）的必传项**，缺任一项都不能上传维保记录：
//   电梯编码 elevatorCode  —— 本系统电梯编码（贴梯二维码内容），不是「设备代码」
//   设备代码 deviceCode    —— 监管平台设备代码
//   注册代码 registrationCode —— 特种设备注册代码（后端列名 reg_code；≠ 登记证编码）
//   单位内编号 insideNumber   —— 使用单位内部梯号
//   出厂编号 factoryNumber    —— 设备铭牌出厂编号
// 后 4 项选填：登记证编码 certNo（使用登记证编号，如 梯11渝BSP8194(20)，独立字段不上报平台）、
//   项目名称（小区名称）projectName、所在区域 regionId、年检日期 nextCheckDate
export const DEVICE_FIELDS = [
  { key: 'elevatorCode', label: '电梯编码', required: true, mono: true, placeholder: '本系统电梯编码（贴梯二维码内容）' },
  { key: 'deviceCode', label: '设备代码', required: true, mono: true, placeholder: '监管平台设备代码' },
  { key: 'registrationCode', label: '注册代码', required: true, mono: true, placeholder: '特种设备注册代码（20 位）' },
  { key: 'insideNumber', label: '单位内编号', required: true, mono: true, placeholder: '使用单位内部梯号' },
  { key: 'factoryNumber', label: '出厂编号', required: true, mono: true, placeholder: '设备铭牌出厂编号' },
  { key: 'certNo', label: '登记证编码', required: false, mono: true, placeholder: '使用登记证编号，如：梯11渝BSP8194(20)' },
  { key: 'projectName', label: '项目名称（小区名称）', required: false, placeholder: '如：蓝湾国际（与使用单位名称不同）' },
  { key: 'regionId', label: '所在区域', required: false, type: 'region' },
  { key: 'nextCheckDate', label: '年检日期', required: false, type: 'date' }
]

/** 必传项（顺序固定，前端校验与后端提示同序） */
export const REQUIRED_DEVICE_FIELDS = DEVICE_FIELDS.filter((f) => f.required)

/** 必传项标签，如 ['电梯编码','设备代码','注册代码','单位内编号','出厂编号'] */
export const REQUIRED_DEVICE_LABELS = REQUIRED_DEVICE_FIELDS.map((f) => f.label)

/** 表单校验规则：必传项逐项非空，提示文案带标签（「注册代码必填」而非泛化的「必填」） */
export function deviceRules() {
  const rules = {}
  REQUIRED_DEVICE_FIELDS.forEach((f) => {
    rules[f.key] = [(v) => (String(v == null ? '' : v).trim() ? '' : f.label + '必填')]
  })
  return rules
}

/** 档案行 → 表单模型（后端 row 的 registrationCode；兼容旧 regCode / profile.platform） */
export function deviceFormOf(row, profile) {
  const r = row || {}
  const p = (profile && profile.platform) || {}
  const l = (profile && profile.local) || {}
  return {
    elevatorCode: r.elevatorCode || p.elevatorCode || '',
    deviceCode: r.deviceCode || p.deviceCode || '',
    registrationCode: r.registrationCode || r.regCode || p.registrationCode || '',
    insideNumber: r.insideNumber || '',
    factoryNumber: r.factoryNumber || p.factoryNumber || '',
    certNo: r.certNo || '',
    projectName: r.projectName || l.projectName || '',
    regionId: r.regionId || '',
    nextCheckDate: r.nextCheckDate || l.nextCheckDate || '',
    category: r.category || ''
  }
}

/** 缺失的必传项标签（后端下发的 missingRequired 优先，兜底前端自查） */
export function missingRequiredOf(row) {
  if (row && Array.isArray(row.missingRequired)) return row.missingRequired
  return REQUIRED_DEVICE_FIELDS.filter((f) => {
    const v = row ? row[f.key] || (f.key === 'registrationCode' ? row.regCode : '') : ''
    return !String(v == null ? '' : v).trim()
  }).map((f) => f.label)
}
