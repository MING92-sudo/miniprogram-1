'use strict'
/** E2E 入口：--only 支持 auth、smoke、flow。 */

const fs = require('fs')
const path = require('path')
const lib = require('./lib')
const { accounts } = require('./seed')
const { runAuthCases } = require('./cases.auth')
const { runSmokeCases } = require('./cases.smoke')
const { runFlowCases } = require('./cases.flow')

const argv = process.argv.slice(2)
const onlyIdx = argv.indexOf('--only')
const only = onlyIdx >= 0 ? argv[onlyIdx + 1] : 'all'

const ctx = { tokens: {}, users: {}, tmp: {} }

function title(s) {
  console.log('\n' + lib.colors.cyan('━'.repeat(64)))
  console.log(lib.colors.cyan('  ' + s))
  console.log(lib.colors.cyan('━'.repeat(64)))
}

async function main() {
  const t0 = Date.now()
  console.log(lib.colors.cyan(`\nE2E 起点 ${lib.BASE}  模式=${only}`))

  // 探活
  const h = await lib.get('/health')
  if (h.code !== 0) {
    console.error(lib.colors.red(`后端不可用：${h.message || h.http}。请先启动 8080。`))
    process.exit(2)
  }
  console.log(lib.colors.green('后端健康 ✓'))

  // 预登录（认证套件会自己再登一次并校验，这里先保证后续套件有 token 可用）
  try {
    for (const [k, a] of Object.entries(accounts)) {
      if (k.startsWith('client')) continue
      const d = await lib.login(a.phone, a.password)
      ctx.tokens[k] = d.token
      ctx.users[k] = d.userInfo
    }
    for (const k of ['clientAdmin', 'clientSigner', 'clientOther']) {
      const d = await lib.login(accounts[k].phone, accounts[k].password)
      ctx.tokens[k] = d.token
      ctx.users[k] = d.userInfo
    }
    console.log(lib.colors.green(`预登录 ${Object.keys(ctx.tokens).length} 个身份 ✓`))
  } catch (e) {
    console.error(lib.colors.red('预登录失败：' + e.message))
    process.exit(2)
  }

  if (only === 'all' || only === 'auth') {
    title('套件 A/B：统一登录与权限矩阵')
    await runAuthCases(ctx)
  }
  if (only === 'all' || only === 'smoke') {
    title('套件 C：全端点 smoke（33 控制器 / 201 路由）')
    await runSmokeCases(ctx)
  }
  if (only === 'all' || only === 'flow') {
    title('套件 F：核心业务流')
    await runFlowCases(ctx)
  }

  const s = lib.printSummary()
  s.durationMs = Date.now() - t0
  s.base = lib.BASE
  s.only = only

  // 落盘报告
  const outDir = path.join(__dirname, 'reports')
  fs.mkdirSync(outDir, { recursive: true })
  const stamp = new Date().toISOString().replace(/[:.]/g, '-')
  fs.writeFileSync(path.join(outDir, `e2e-${stamp}.json`), JSON.stringify({ summary: s, results: lib.results }, null, 2))
  fs.writeFileSync(path.join(outDir, 'latest.json'), JSON.stringify({ summary: s, results: lib.results }, null, 2))
  console.log(lib.colors.gray(`\n报告：scripts/e2e/reports/latest.json  (${(s.durationMs / 1000).toFixed(1)}s)`))

  process.exit(s.fail > 0 ? 1 : 0)
}

main().catch((e) => {
  console.error(lib.colors.red('运行器异常：' + (e && e.stack ? e.stack : e)))
  process.exit(2)
})
