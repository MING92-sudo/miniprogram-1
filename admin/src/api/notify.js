import http from './client'

/** 订阅消息发送记录（docs/04 A.7；真实推送云端后置） */
export function notifyRecords(params) {
  return http.get('/admin/notify-records', { params })
}
