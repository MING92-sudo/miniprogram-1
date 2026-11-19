import http from './client'

/** 概览看板：工单待办 / 上报统计 / 平台同步 / 7 日趋势（docs/04 A.10.1） */
export const dashboard = () => http.get('/admin/dashboard')

/** 统计报表：完成率 / 隐患分布 / 人员与电梯工作量（docs/04 A.10.2） */
export const stats = (days) => http.get('/admin/stats', { params: { days } })

/** 记录单列表（含上报状态，分页 + 筛选，docs/04 A.10.3） */
export const records = (params) => http.get('/admin/records', { params })

/** 订阅消息发送记录（docs/04 A.7） */
export const notifyRecords = (params) => http.get('/admin/notify-records', { params })

/** 监管同步看板（docs/04 A.3） */
export const syncStatus = () => http.get('/reg/sync-status')
