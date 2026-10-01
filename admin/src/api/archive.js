import http from './client'

/**
 * 档案维护：
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
