const { get, put } = require('../utils/request')

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

function updateElevatorLocal(elevatorId, fields) {
  return put('/elevators/' + elevatorId + '/local', fields)
}

module.exports = {
  getElevatorByCode,
  getElevatorList,
  getElevatorProfile,
  updateElevatorLocal
}
