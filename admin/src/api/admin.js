import http from './client'

export const dashboard = () => http.get('/admin/dashboard')

export const stats = (days) => http.get('/admin/stats', { params: { days } })

export const records = (params) => http.get('/admin/records', { params })

export const notifyRecords = (params) => http.get('/admin/notify-records', { params })

export const syncStatus = () => http.get('/reg/sync-status')
