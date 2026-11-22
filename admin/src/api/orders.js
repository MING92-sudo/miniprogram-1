import http from './client'


/** 工单池列表：默认分页 size=20，管理端列表传大 size 拉全量 */
export function workOrders(params) {
  return http.get('/work-orders', { params: params || {} })
}

/** 未认领 PENDING 幂等 ok； */
export function releaseToPool(id) {
  return http.post(`/admin/orders/${id}/release-to-pool`, {})
}
