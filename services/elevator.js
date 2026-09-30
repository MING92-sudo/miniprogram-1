// 电梯档案（docs/01 §3.4.1；平台 2.7 自动获取 + 本地维护字段）
const { get } = require('../utils/request')

function getElevatorProfile(elevatorId) {
  return get('/elevators/' + elevatorId + '/profile')
}

module.exports = {
  getElevatorProfile
}
