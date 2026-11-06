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

// 维保人员现场更正项目名称（小区名称，docs/04 A.2.1）
function updateElevatorProjectName(elevatorId, projectName) {
  return put('/elevators/' + elevatorId + '/project-name', { projectName })
}

module.exports = {
  getElevatorByCode,
  getElevatorList,
  getElevatorProfile,
  updateElevatorProjectName
}
