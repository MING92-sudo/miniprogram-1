// 后端 DeviceProfileValidator 同口径，标签不得改写）。
export const DEVICE_FIELDS = [
  { key: 'elevatorCode', label: '电梯编码', required: true, mono: true, placeholder: '本系统电梯编码（贴梯二维码内容）' },
  { key: 'deviceCode', label: '设备代码', required: true, mono: true, placeholder: '监管平台设备代码' },
  { key: 'registrationCode', label: '注册代码', required: true, mono: true, placeholder: '特种设备注册代码（20 位）' },
  { key: 'insideNumber', label: '单位内编号', required: true, mono: true, placeholder: '使用单位内部梯号' },
  { key: 'factoryNumber', label: '出厂编号', required: true, mono: true, placeholder: '设备铭牌出厂编号' },
  { key: 'certNo', label: '登记证编码', required: false, mono: true, placeholder: '使用登记证编号，如：梯11渝BSP8194(20)' },
  { key: 'projectName', label: '项目名称（小区名称）', required: false, placeholder: '如：蓝湾国际（与使用单位名称不同）' },
  { key: 'regionId', label: '所在区域', required: false, type: 'region' },
  { key: 'nextCheckDate', label: '下次年检日期', required: false, type: 'date' }
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

/** 2.7 记录可回填的标识字段（顺序与 DEVICE_FIELDS 一致） */
const PLATFORM_FILL_KEYS = ['elevatorCode', 'deviceCode', 'registrationCode', 'factoryNumber']

/** 平台占位值（空串 / null / undefined / 脱敏 *）不参与回填，与后端 hasRealValue 同口径 */
function platformValue(v) {
  const s = v == null ? '' : String(v).trim()
  return s === '' || s.includes('*') || /^(null|undefined)$/i.test(s) ? '' : s
}

/**
 * 把 2.7 平台记录写入设备档案表单（只覆盖平台有值的标识字段）。
 * @param {Record<string, unknown>} form 表单模型，原地修改
 * @param {Record<string, unknown>} platform 平台 2.7 原始记录
 * @returns {string[]} 实际回填的字段 key
 */
export function applyPlatformElevator(form, platform) {
  const p = platform || {}
  const filled = []
  for (const key of PLATFORM_FILL_KEYS) {
    const v = platformValue(p[key])
    if (v) {
      form[key] = v
      filled.push(key)
    }
  }
  return filled
}

/** 缺失的必传项标签（后端下发的 missingRequired 优先，兜底前端自查） */
export function missingRequiredOf(row) {
  if (row && Array.isArray(row.missingRequired)) return row.missingRequired
  return REQUIRED_DEVICE_FIELDS.filter((f) => {
    const v = row ? row[f.key] || (f.key === 'registrationCode' ? row.regCode : '') : ''
    return !String(v == null ? '' : v).trim()
  }).map((f) => f.label)
}
