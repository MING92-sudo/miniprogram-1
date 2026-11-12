import http from './client'

/** 区域主数据（Task 1/14）：区域 = 维保人员可见范围（docs/14 §5） */
export function listRegions() {
  return http.get('/regions')
}

export function createRegion(body) {
  return http.post('/regions', body)
}

export function updateRegion(id, body) {
  return http.put(`/regions/${id}`, body)
}

export function removeRegion(id) {
  return http.delete(`/regions/${id}`)
}

/** 给电梯/员工分配区域（员工区域决定可见范围） */
export function assignElevatorRegion(elevatorId, regionId) {
  return http.put(`/elevators/${elevatorId}/region`, { regionId })
}

export function assignEmployeeRegion(employeeId, regionId) {
  return http.put(`/employees/${employeeId}/region`, { regionId })
}
