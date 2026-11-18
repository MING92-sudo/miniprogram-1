import http from './client'

/** 管理端·迁移核对清单（权限 P2 T7）：GET /admin/migration-issues → { list, total, pending } */
export function listMigrationIssues(params) {
  return http.get('/admin/migration-issues', { params })
}

/** 单条人工裁定（config:manage）：POST /admin/migration-issues/{id}/resolve → { ok: true } */
export function resolveMigrationIssue(id, body) {
  return http.post('/admin/migration-issues/' + id + '/resolve', body)
}
