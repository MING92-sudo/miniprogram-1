import http from './client'

/** 急修单（docs/04 A.3）：列表/详情只读，闭环签字在小程序使用单位端完成 */
export function list(params) {
  return http.get('/faults', { params })
}

export function get(id) {
  return http.get(`/faults/${id}`)
}
