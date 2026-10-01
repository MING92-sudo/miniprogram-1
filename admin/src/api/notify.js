import http from './client'

/** 订阅消息发送记录（真实推送后置） */
export function notifyRecords(params) {
  return http.get('/admin/notify-records', { params })
}
