'use strict'
/**
 * 认证与权限矩阵：统一登录分流、双主体边界、越权拒绝。
 * 每个 case 都是对"一个通道 + 一种身份 + 一个结论"的验证。
 */

const { get, post, record, suite, login, assert, assertEq } = require('./lib')
const { accounts, seed } = require('./seed')

/**
 * @param {object} ctx 上下文，写回 tokens
 */
async function runAuthCases(ctx) {
  suite('A. 认证与登录分流')

  // ── A1 健康检查（白名单，无令牌）──
  {
    const r = await get('/health')
    const ok = record('GET /health 无令牌可访问', r, 'ok')
    if (ok) assertEq(r.data && r.data.status, 'UP', 'health.data.status')
  }

  // ── A2 员工通道：各角色均可登录，且 principalType=EMPLOYEE ──
  const staffRoles = [
    ['admin', accounts.admin],
    ['maintMgr', accounts.maintMgr],
    ['maintTech', accounts.maintTech],
    ['leader', accounts.leader],
    ['worker', accounts.worker],
    ['worker2', accounts.worker2],
    ['instMgr', accounts.instMgr],
    ['instPm', accounts.instPm],
    ['instClerk', accounts.instClerk],
    ['installer', accounts.installer]
  ]
  for (const [key, acc] of staffRoles) {
    const r = await post('/auth/login', { phone: acc.phone, password: acc.password })
    const ok = record(`员工登录 ${acc.role} (${acc.phone})`, r, 'ok')
    if (ok) {
      ctx.tokens[key] = r.data.token
      ctx.users[key] = r.data.userInfo
      try {
        assertEq(r.data.principalType, 'EMPLOYEE', 'principalType')
        assert(Array.isArray(r.data.roles) && r.data.roles.length > 0, 'roles[] 非空')
        assert(Array.isArray(r.data.perms), 'perms[] 是数组')
        assert(typeof r.data.home === 'string' && r.data.home.startsWith('/'), 'home 是路径')
      } catch (e) {
        record(`  结构校验 ${acc.role}`, { http: 200, code: 500, message: e.message, ms: 0 }, 'ok')
      }
    }
  }

  // ── A3 客户通道：并入同一入口，principalType=CUSTOMER ──
  const clientCases = [
    ['clientAdmin', accounts.clientAdmin, 'CLIENT_ADMIN', seed.useUnits.uu1],
    ['clientSigner', accounts.clientSigner, 'CLIENT_SIGNER', seed.useUnits.uu1],
    ['clientSigner2', accounts.clientSigner2, 'CLIENT_SIGNER', seed.useUnits.uu1],
    ['clientOther', accounts.clientOther, 'CLIENT_ADMIN', seed.useUnits.uu2]
  ]
  for (const [key, acc, role, unitId] of clientCases) {
    const r = await post('/auth/login', { phone: acc.phone, password: acc.password })
    const ok = record(`客户登录 ${role} (${acc.phone})`, r, 'ok')
    if (ok) {
      ctx.tokens[key] = r.data.token
      ctx.users[key] = r.data.userInfo
      try {
        assertEq(r.data.principalType, 'CUSTOMER', 'principalType')
        assertEq(r.data.role, role, 'role')
        assertEq(r.data.userInfo && r.data.userInfo.useUnitId, unitId, 'useUnitId')
        assert(typeof r.data.home === 'string', 'home 存在')
      } catch (e) {
        record(`  客户结构校验 ${role}`, { http: 200, code: 500, message: e.message, ms: 0 }, 'ok')
      }
    }
  }

  // ── A4 登录失败路径 ──
  suite('A. 认证与权限矩阵')
  record('错误口令 401', await post('/auth/login', { phone: accounts.admin.phone, password: 'wrong-pass' }), 401)
  record('不存在账号 401', await post('/auth/login', { phone: '13900000000', password: '123456' }), 401)
  record(
    '空手机号被拒',
    await post('/auth/login', { phone: '', password: '123456' }),
    'biz'
  )
  record('口令为空被拒', await post('/auth/login', { phone: accounts.admin.phone, password: '' }), 'biz')

  // 客户账号走管理端登录必须 403（管理端不允许客户主体）
  record(
    '客户账号走管理端 403',
    await post('/auth/login', { phone: accounts.clientAdmin.phone, password: accounts.clientAdmin.password, client: 'admin' }),
    403
  )
  // 维保人员走管理端也应 403（无 console 权限）
  record(
    '维保人员走管理端 403',
    await post('/auth/login', { phone: accounts.maintTech.phone, password: accounts.maintTech.password, client: 'admin' }),
    403
  )

  // 微信登录 code 无效 → 业务拒绝（非 5xx）
  record('微信登录 code 无效被拒', await post('/auth/wx-login', { code: 'invalid-code' }), 'biz')
  record('微信重置口令 code 无效被拒', await post('/auth/reset-password-by-wechat', { code: 'invalid-code' }), 'biz')

  // ── A5 令牌校验 ──
  suite('B. 令牌与越权矩阵')
  record('无令牌访问 /admin/dashboard → 401', await get('/admin/dashboard'), 401)
  record('伪造令牌 → 401', await get('/admin/dashboard', 'not-a-valid-token'), 401)
  record('格式错误令牌 → 401', await get('/admin/dashboard', 'abc.def.ghi'), 401)

  // 员工令牌：本人信息与菜单
  record('GET /auth/me（管理员）', await get('/auth/me', ctx.tokens.admin), 'ok')
  // 菜单下发只剩 /me/menus 一个入口：旧 GET /auth/menus（MenuService）已删除，重加会在此处变红
  record('GET /auth/menus（旧端点已删除）', await get('/auth/menus', ctx.tokens.admin), 404)
  record('GET /me/menus（管理员）', await get('/me/menus', ctx.tokens.admin), 'ok')
  record('GET /home/summary（管理员）', await get('/home/summary', ctx.tokens.admin), 'ok')

  // 越权：维保人员不得进管理端
  record('维保人员 GET /admin/dashboard → 403', await get('/admin/dashboard', ctx.tokens.maintTech), 'forbidden')
  record('维保人员 GET /users → 403', await get('/users', ctx.tokens.maintTech), 'forbidden')
  record('维保人员 POST /use-units → 403', await post('/use-units', { unitName: 'x' }, ctx.tokens.maintTech), 'forbidden')
  record('维保人员 DELETE /employees/emp_1 → 403', await require('./lib').del('/employees/emp_1', ctx.tokens.maintTech), 'forbidden')
  // 班组长可登录管理端**只读**（PermissionMatrix.CONSOLE_READ 含 LEADER，docs/14 §3），但不得写档
  record('班组长 GET /admin/dashboard（只读放行）', await get('/admin/dashboard', ctx.tokens.leader), 'ok')
  record('班组长 POST /use-units → 403（写仅平台层）', await post('/use-units', { unitName: 'x' }, ctx.tokens.leader), 'forbidden')

  // 越权：客户令牌不得进管理端、不得进员工域
  record('客户令牌 GET /admin/dashboard → 403', await get('/admin/dashboard', ctx.tokens.clientAdmin), 'forbidden')
  record('客户令牌 GET /users → 403', await get('/users', ctx.tokens.clientAdmin), 'forbidden')
  record('客户令牌 GET /work-orders → 403', await get('/work-orders', ctx.tokens.clientAdmin), 'forbidden')

  // 客户令牌：只能看本单位
  record('客户令牌 GET /unit/elevators', await get('/unit/elevators', ctx.tokens.clientAdmin), 'ok')
  record('客户令牌 GET /auth/client/me', await get('/auth/client/me', ctx.tokens.clientAdmin), 'ok')
  record('客户令牌 GET /unit/pending-signs', await get('/unit/pending-signs', ctx.tokens.clientAdmin), 'ok')
  record('客户令牌 GET /unit/faults', await get('/unit/faults', ctx.tokens.clientAdmin), 'ok')
  record('客户令牌 GET /unit/records/list', await get('/unit/records/list', ctx.tokens.clientAdmin), 'ok')

  // 员工令牌访问客户端点：应被拒（客户域端点要求客户主体）
  record('员工令牌 GET /auth/client/me → 拒绝', await get('/auth/client/me', ctx.tokens.admin), 'biz')

  // 单端会话：登出后旧令牌失效
  const tmp = await login(accounts.worker.phone, accounts.worker.password)
  record('临时令牌 GET /home/summary', await get('/home/summary', tmp.token), 'ok')
  record('POST /auth/logout', await post('/auth/logout', {}, tmp.token), 'ok')
  record('登出后旧令牌 → 401', await get('/home/summary', tmp.token), 401)
}

module.exports = { runAuthCases }
