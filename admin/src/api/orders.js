import http from './client'

/**
 * 维保工单（区域抢单池，2026-11-20 设计 §4.2/§4.4）：
 * 列表行含 regionId/regionName 快照（WorkOrderService.toMap），
 * 未认领 PENDING 工单 = 待接单池；「退回池」仅管理端（order:dispatch，审计先行 M12）。
 */

/** 工单池列表：默认分页 size=20，管理端列表传大 size 拉全量 */
export function workOrders(params) {
  return http.get('/work-orders', { params: params || {} })
}

/** 退回池（D10/M12）：DONE 422；未认领 PENDING 幂等 ok；已签到工单留痕快照先入审计再清空 */
export function releaseToPool(id) {
  return http.post(`/admin/orders/${id}/release-to-pool`, {})
}
