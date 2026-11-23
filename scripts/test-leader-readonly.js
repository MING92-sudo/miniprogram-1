const assert = require('assert')
const fs = require('fs')
const path = require('path')

const root = path.resolve(__dirname, '..')
const matrix = require('../constants/permission-matrix')
const { ROUTES } = require('../config/routes')

function test(name, run) {
  run()
  console.log('PASS ' + name)
}

test('LEADER is independent, TEAM-scoped and read-only', () => {
  assert.strictEqual(matrix.canonical('LEADER'), 'LEADER')
  assert.strictEqual(matrix.defaultScope('LEADER'), 'TEAM')
  ;['user:create', 'user:disable', 'order:dispatch', 'record:archive', 'record:review', 'plan:generate']
    .forEach((code) => assert.strictEqual(matrix.state('LEADER', code), 'DENY', code))
  ;['project:view', 'record:view', 'order:view_pool', 'stats:view']
    .forEach((code) => assert.notStrictEqual(matrix.state('LEADER', code), 'DENY', code))
})

test('mini program session never aliases LEADER to MAINT_SUP', () => {
  const values = { em_role: 'LEADER', em_roles: ['LEADER'] }
  global.wx = { getStorageSync: (key) => values[key] }
  const auth = require('../utils/auth')
  assert.deepStrictEqual(auth.getSessionRoles(), ['LEADER'])
  assert.strictEqual(auth.hasAnyRole(['MAINT_SUP']), false)
  delete global.wx
})

test('LEADER retains only read page eligibility', () => {
  const readPages = [
    '/pages/maint/home/index', '/pages/order/list/index', '/pages/order/detail/index',
    '/pkg-handover/pages/list/index', '/pkg-handover/pages/detail/index',
    '/pkg-maintenance/pages/orders/index', '/pkg-maintenance/pages/record-detail/index',
    '/pkg-maintenance/pages/plan/index', '/pkg-admin/pages/projects/index',
    '/pkg-admin/pages/contracts/index', '/pkg-admin/pages/users/index', '/pkg-admin/pages/stats/index'
  ]
  readPages.forEach((page) => assert.ok(ROUTES[page].roles.includes('LEADER'), page))
  const writePages = [
    '/pages/order/report/index', '/pkg-maintenance/pages/task/index',
    '/pkg-maintenance/pages/checkin/index', '/pkg-maintenance/pages/checkout/index',
    '/pkg-maintenance/pages/record-sign/index', '/pkg-handover/pages/sign/index'
  ]
  writePages.forEach((page) => assert.ok(!ROUTES[page].roles.includes('LEADER'), page))
})

test('mock and handover pages do not restore supervisor writes', () => {
  const { db } = require('../mock/data')
  assert.deepStrictEqual(db.employees.LEADER.roles, ['LEADER'])
  const detail = fs.readFileSync(path.join(root, 'pkg-handover/pages/detail/index.js'), 'utf8')
  const sign = fs.readFileSync(path.join(root, 'pkg-handover/pages/sign/index.js'), 'utf8')
  assert.ok(!detail.includes('ROLE.LEADER'))
  assert.ok(!sign.includes('ROLE.LEADER'))
})

test('stored leader roles and scope migrate without restoring MAINT_SUP', () => {
  const migration = fs.readFileSync(path.join(root, 'backend/src/main/resources/db/migration/V67.1__leader_readonly.sql'), 'utf8')
  assert.ok(/SELECT\s+'LEADER',\s*'TEAM'/i.test(migration))
  assert.ok(/UPDATE\s+sys_user_role[\s\S]+sys_employee[\s\S]+LEADER/i.test(migration))
  assert.ok(/scope_version\s*=\s*scope_version\s*\+\s*1/i.test(migration))
})

console.log('LEADER read-only checks passed')
