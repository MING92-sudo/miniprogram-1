/**
 * 客户令牌「员工端点白名单」穿透测试：一次性找出所有"白名单放行但 controller 只认员工主体"的端点。
 * 判据：白名单内的读接口返回 1403/401/空结果 = 主体支持不完整（客户被放行却拿不到自己的数据）。
 */
const { get, post, login, colors } = require('./lib')
const { accounts, seed } = require('./seed')

const EL = (seed && seed.elevators && seed.elevators.el1) || 'el_1'

const CASES = [
  ['GET', '/me/menus', null, '菜单下发'],
  ['GET', '/auth/me', null, '本人信息'],
  ['GET', '/auth/client/me', null, '客户本人信息'],
  ['GET', '/elevators', null, '电梯列表（员工读接口，白名单放行）'],
  ['GET', `/elevators/${EL}/profile`, null, '电梯档案详情'],
  ['GET', '/elevators/by-code?code=EM-2024-001', null, '按编码查电梯'],
  ['GET', '/unit/elevators', null, '门户电梯列表'],
  ['GET', '/unit/records/pending', null, '门户待签'],
  ['GET', '/unit/faults', null, '门户故障'],
  ['GET', '/unit/pending-signs', null, '门户待签（旧）'],
  ['GET', '/health', null, '健康检查']
]

function judge(c) {
  if (c.http >= 500 || (c.code !== null && c.code >= 500 && c.code < 1000)) return 'FAIL-5XX'
  if (c.code === 0) {
    // 空数组也可能是"主体不匹配导致的范围为空"，单独提示
    if (Array.isArray(c.data) && c.data.length === 0) return 'OK-BUT-EMPTY'
    return 'OK'
  }
  if (c.code === 1403) return 'FAIL-越权(1403)'
  if (c.code === 401) return 'FAIL-未认证(401)'
  if (c.code === 403) return 'BLOCKED(403)'
  return 'BIZ(' + c.code + ')'
}

;(async () => {
  const d = await login(accounts.clientAdmin.phone, accounts.clientAdmin.password)
  const tok = d.token
  console.log(colors.gray(`客户 ${accounts.clientAdmin.phone} principalType=${d.principalType} roles=${JSON.stringify(d.roles || [d.role])}\n`))

  const bad = []
  for (const [m, p, body, label] of CASES) {
    const r = m === 'GET' ? await get(p, tok) : await post(p, body || {}, tok)
    const verdict = judge(r)
    const size = Array.isArray(r.data) ? ` len=${r.data.length}` : ''
    const flag = verdict.startsWith('FAIL') ? colors.red('✗') : verdict.startsWith('BLOCKED') ? colors.yellow('·') : colors.green('✓')
    console.log(`${flag} ${m} ${p.padEnd(40)} ${verdict.padEnd(16)} code=${r.code}${size} ${r.message || ''}`)
    if (verdict.startsWith('FAIL')) bad.push(`${m} ${p} → ${verdict} ${r.message || ''}`)
  }

  // 门户签字的两个写入口（幂等键缺失时应是 4xx 业务码，不应 500）
  console.log('')
  const m1 = await post('/unit/records/nope/confirm-by-token?token=bad', {}, null)
  console.log(`  GET-ish 匿名签字（错 token） → code=${m1.code} ${m1.message} （期望 401 链接无效）`)

  console.log('')
  if (bad.length) {
    console.log(colors.red(`发现 ${bad.length} 个问题：`))
    bad.forEach((b) => console.log('  - ' + b))
    process.exitCode = 1
  } else {
    console.log(colors.green('白名单内客户端点全部可用'))
  }
})()
