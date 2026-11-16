// 电梯档案（docs/01 §3.4.1；平台 2.7 自动获取 + 本地维护字段）
const { get, put } = require('../utils/request')

// 扫码回填电梯信息（急修单用，docs/04 A.6）
function getElevatorByCode(code) {
  return get('/elevators/by-code', { code })
}

function getElevatorProfile(elevatorId) {
  return get('/elevators/' + elevatorId + '/profile')
}

// 电梯列表（已对接监管平台的全部电梯）
function getElevatorList() {
  return get('/elevators')
}

// 维保人员现场维护设备本地信息（项目名称/品牌/制造单位/地址/载重/速度/日期等，docs/04 A.2.1）
// 2026-11-16 审查清理：updateElevatorProjectName 已删——projectName 并入 updateLocal（后端同谓词），
// 旧 PUT /elevators/{id}/project-name 端点契约保留。
function updateElevatorLocal(elevatorId, fields) {
  return put('/elevators/' + elevatorId + '/local', fields)
}

module.exports = {
  getElevatorByCode,
  getElevatorList,
  getElevatorProfile,
  updateElevatorLocal
}
