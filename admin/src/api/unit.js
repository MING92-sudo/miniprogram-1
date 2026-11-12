import http from './client'

/**
 * 使用单位门户账号（Task 9/14）：管理端开通/重置，返回一次性初始口令与邀请码。
 * 门户身份与员工账号分离（use_unit 账号），见 docs/14 §3。
 */
export function createUnitAccount(body) {
  return http.post('/unit/accounts', body)
}
