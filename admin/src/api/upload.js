import http from './client'

/** 管理端文件上传（PDF 报告等）：复用 /files/upload 代理上传（COS/本地），返回 { fileId, url } */
export function uploadFile(file) {
  const fd = new FormData()
  fd.append('file', file)
  return http.post('/files/upload', fd, { timeout: 120000 })
}
