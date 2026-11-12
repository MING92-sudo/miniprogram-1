import http from './client'

/** 维保合同（Task 3/14）：列表 + 新建 + 详情；正文由管理端录入，平台不接收合同 */
export function listContracts() {
  return http.get('/contracts')
}

export function createContract(body) {
  return http.post('/contracts', body)
}

export function contractDetail(id) {
  return http.get(`/contracts/${id}`)
}
