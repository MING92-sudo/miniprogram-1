// 管理端常量：文案口径与小程序 constants/index.js 保持一致
export const ROLE_TEXT = {
  WORKER: '维保人员',
  LEADER: '班组长',
  UNIT_ADMIN: '使用单位安全管理员',
  ADMIN: '维保部管理员',
  SYS_ADMIN: '系统管理员'
}

// 严重事故隐患码（S0—S7 连续编码）
export const PROBLEM_CODES = {
  S0: '未发现严重事故隐患',
  S1: '使用非法生产的特种设备',
  S2: '超过特种设备的规定参数范围使用',
  S3: '缺少安全附件、安全装置，或安全附件、安全装置失灵而继续使用',
  S4: '使用应当予以报废或者经检验判定为不合格的特种设备',
  S5: '使用有明显故障、异常情况的特种设备，或使用经责令改正而未予以改正的特种设备',
  S6: '特种设备发生事故不予报告而继续使用',
  S7: '其它严重事故隐患'
}

export const REPORT_STATUS = {
  SUBMITTED: '待上报',
  REPORTED: '已上报',
  FAILED: '上报失败',
  SKIPPED: '免上报'
}

export const CONFIRM_STATUS = {
  PENDING: '待确认',
  CONFIRMED: '已确认'
}

// 五类手机号互斥角色（emergencyPhone/recorderPhone 不参与）
export const MUTEX_ROLES = ['使用单位负责人', '使用单位安全管理员', '维保经理', '维保人员']
