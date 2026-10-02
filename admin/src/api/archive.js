import http from './client'

/**
 * 档案维护（docs/09 §1.2-4）：
 * GET /company、PUT /company；GET/POST/PUT /use-units、/employees；POST/PUT /elevators、GET /admin/elevators。
 * 写接口幂等键由 client 统一附加；1002 互斥冲突以 err.data.conflicts[] 返回。
 */
export function company() {
  return http.get('/company')
}

export function updateCompany(body) {
  return http.put('/company', body)
}

export function useUnits() {
  return http.get('/use-units')
}

export function createUseUnit(body) {
  return http.post('/use-units', body)
}

export function updateUseUnit(id, body) {
  return http.put(`/use-units/${id}`, body)
}

export function employees() {
  return http.get('/employees')
}

export function createEmployee(body) {
  return http.post('/employees', body)
}

export function updateEmployee(id, body) {
  return http.put(`/employees/${id}`, body)
}

export function elevatorsAdmin() {
  return http.get('/admin/elevators')
}

export function createElevator(body) {
  return http.post('/elevators', body)
}

export function updateElevator(id, body) {
  return http.put(`/elevators/${id}`, body)
}

/** ①档案增删改查：删除（在途工单/系统管理员/关联电梯时后端 422 拒绝） */
export function deleteEmployee(id) {
  return http.delete(`/employees/${id}`)
}

export function deleteUseUnit(id) {
  return http.delete(`/use-units/${id}`)
}

export function deleteElevator(id) {
  return http.delete(`/elevators/${id}`)
}

/** 手动派单（首保/补单：无首次维保时间的电梯由管理员触发） */
export function dispatchElevator(id) {
  return http.post(`/elevators/${id}/dispatch`, {})
}
