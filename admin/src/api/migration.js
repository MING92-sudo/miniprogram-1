import http from './client'

export function listMigrationIssues(params) {
  return http.get('/admin/migration-issues', { params })
}

/** 单条人工裁定（config:manage）：POST /admin/migration-issues/{id}/resolve → { ok: true } */
export function resolveMigrationIssue(id, body) {
  return http.post('/admin/migration-issues/' + id + '/resolve', body)
}
