import http from './client'

/** 工单监控（docs/09 §四）：复用小程序契约 GET /work-orders、GET /work-orders/{id} */
export function listOrders(params) {
  return http.get('/work-orders', { params })
}

export function getOrder(id) {
  return http.get(`/work-orders/${id}`)
}
