'use strict'
/**
 * 全端点 smoke：33 个 Controller、201 条路由逐条调用。
 * 判据：不得出现 5xx / 网络错误；读接口尽量要求 code=0，写接口允许业务拒绝。
 * 破坏性操作一律作用在本脚本新建的临时实体上，收尾删除。
 */

const { get, post, put, del, patch, record, suite, assert, assertEq, call, login, colors } = require('./lib')
const { seed, accounts } = require('./seed')

/** 令牌健康探针：在套件边界确认 admin 令牌仍有效，便于定位「中途 401」 */
async function probe(ctx, tag) {
  const r = await get('/auth/me', ctx.tokens.admin)
  if (r.code !== 0) {
    console.log(colors.yellow(`  ! [${tag}] admin 令牌已失效：code=${r.code} ${r.message}`))
    return false
  }
  return true
}

async function runSmokeCases(ctx) {
  const T = ctx.tokens
  const admin = T.admin
  const tech = T.maintTech
  const client = T.clientAdmin
  const tmp = ctx.tmp

  // ══════════ 0. 造临时数据（后续写端点依赖） ══════════
  suite('C0. 测试数据准备')
  {
    const r = await post('/use-units', {
      unitName: 'E2E临时使用单位',
      organizationCode: 'E2E' + Date.now(),
      unitPrincipal: '测试负责人',
      unitPrincipalPhone: '13900001111',
      elevatorAdminister: '测试管理员',
      elevatorAdministerPhone: '13900002222',
      emergencyPhone: '119'
    }, admin)
    if (record('创建临时使用单位', r, 'ok')) tmp.unitId = r.data.id
  }
  {
    const r = await post('/employees', {
      name: 'E2E临时工',
      phone: '13900003333',
      role: 'WORKER',
      password: '12345678',
      groupName: '维保一班'
    }, admin)
    if (record('创建临时员工', r, 'ok')) tmp.employeeId = r.data.id
  }
  {
    const r = await post('/elevators', {
      elevatorCode: 'E2E' + Date.now().toString().slice(-8),
      deviceCode: 'DEV-E2E-001',
      registrationCode: 'REG-E2E-001',
      insideNumber: 'E2E-1',
      factoryNumber: 'FAC-E2E-001',
      elevatorName: 'E2E测试电梯',
      useUnitId: tmp.unitId || seed.useUnits.uu1,
      projectName: 'E2E测试项目'
    }, admin)
    if (record('创建临时电梯', r, 'ok')) tmp.elevatorId = r.data.id
  }

  // ══════════ 1. AdminArchiveController ══════════
  await probe(ctx, 'C1')
  suite('C1. 档案管理 /company /use-units /employees /elevators')
  record('GET /company', await get('/company', admin), 'ok')
  record('PUT /company', await put('/company', { name: '重庆博威电梯有限公司' }, admin), 'ok')
  record('GET /use-units', await get('/use-units', admin), 'ok')
  if (tmp.unitId) {
    record('PUT /use-units/{id}', await put(`/use-units/${tmp.unitId}`, { unitPrincipal: '测试负责人2' }, admin), 'ok')
  }
  record('GET /employees', await get('/employees', admin), 'ok')
  if (tmp.employeeId) {
    record('PUT /employees/{id}', await put(`/employees/${tmp.employeeId}`, { name: 'E2E临时工改名' }, admin), 'ok')
    record('PUT /admin/employees/{id}/enabled', await put(`/admin/employees/${tmp.employeeId}/enabled`, { enabled: true }, admin), 'ok')
    record('POST /employees/{id}/reset-password', await post(`/employees/${tmp.employeeId}/reset-password`, {}, admin), 'ok')
  }
  if (tmp.elevatorId) {
    record('PUT /elevators/{id}', await put(`/elevators/${tmp.elevatorId}`, { elevatorName: 'E2E测试电梯改名' }, admin), 'ok')
    record('POST /elevators/{id}/qrcode', await post(`/elevators/${tmp.elevatorId}/qrcode`, {}, admin), 'any')
  }
  record('POST /elevators/batch-assign-workers', await post('/elevators/batch-assign-workers', { elevatorIds: [seed.elevators.el1], workerName: '李振国' }, admin), 'any')
  record('POST /elevators/batch-geo', await post('/elevators/batch-geo', { elevatorIds: [seed.elevators.el1] }, admin), 'any')
  record('POST /company/sync-entity-id', await post('/company/sync-entity-id', {}, admin), 'any')
  if (tmp.unitId) {
    record('POST /use-units/{id}/sync-entity-id', await post(`/use-units/${tmp.unitId}/sync-entity-id`, {}, admin), 'any')
  }

  await probe(ctx, 'C2')
  // ══════════ 2. AdminController / AdminMigrationIssueController / AdminNotifyController ══════════
  suite('C2. 管理端看板与治理 /admin/**')
  record('GET /admin/dashboard', await get('/admin/dashboard', admin), 'ok')
  record('GET /admin/elevators', await get('/admin/elevators', admin), 'ok')
  record('GET /admin/records', await get('/admin/records', admin), 'ok')
  record('GET /admin/stats', await get('/admin/stats', admin), 'ok')
  record('GET /admin/migration-issues', await get('/admin/migration-issues', admin), 'ok')
  record('POST /admin/migration-issues/{id}/resolve', await post('/admin/migration-issues/mi_none/resolve', { resolution: 'SKIPPED', note: 'e2e' }, admin), 'any')
  record('GET /admin/notify-records', await get('/admin/notify-records', admin), 'ok')

  await probe(ctx, 'C3')
  // ══════════ 3. AdminRecordController / AdminScheduleController / AdminTemplateController ══════════
  suite('C3. 记录导出 / 排班 / 清单模板')
  record('GET /admin/records/{id}/export-pdf', await get(`/admin/records/${seed.records.ur1}/export-pdf`, admin), 'any')
  record('GET /admin/plans', await get('/admin/plans', admin), 'ok')
  record('POST /admin/plans/generate', await post('/admin/plans/generate', { month: '2026-12' }, admin), 'any')
  // 计划相关端点必须带 planId（缺参应 400 而不是 500）；有数据时用真实 id
  const planList = await get('/admin/plans', admin)
  const planArr = (planList.data && (planList.data.items || planList.data.list || planList.data)) || []
  const planId = (Array.isArray(planArr) && planArr[0] && planArr[0].id) || 'pl_none'
  record('GET /admin/plans/schedule-suggestion', await get(`/admin/plans/schedule-suggestion?planId=${planId}`, admin), 'any')
  record('GET /admin/plans/conflicts', await get(`/admin/plans/conflicts?planId=${planId}`, admin), 'any')
  record('GET /admin/plans/delays', await get('/admin/plans/delays', admin), 'any')
  record('GET /admin/plans/{id}', await get(`/admin/plans/${planId}`, admin), 'any')
  record('PUT /admin/plans/{id}/assign', await put(`/admin/plans/${planId}/assign`, { principalId: seed.employees.maintTech }, admin), 'any')
  record('POST /admin/plans/{id}/delay', await post(`/admin/plans/${planId}/delay`, { delayDays: 1, reason: 'e2e' }, admin), 'any')
  record('PUT /admin/plans/delays/{id}/decide', await put('/admin/plans/delays/dl_none/decide', { approved: true }, admin), 'any')
  record('POST /admin/orders/{id}/transfer', await post(`/admin/orders/${seed.workOrders.wo1}/transfer`, { toWorker: '李振国', reason: 'e2e' }, admin), 'any')
  record('GET /admin/templates', await get('/admin/templates', admin), 'ok')
  record('POST /admin/templates', await post('/admin/templates', { name: 'E2E模板', itemGroup: '半月保' }, admin), 'any')
  record('PUT /admin/templates/{id}', await put(`/admin/templates/${seed.templates.tpl1}`, { name: '机房、滑轮间环境' }, admin), 'any')
  record('PUT /admin/templates/{id}/enabled', await put(`/admin/templates/${seed.templates.tpl1}/enabled`, { enabled: true }, admin), 'any')

  await probe(ctx, 'C4')
  // ══════════ 4. ClientAccountController（客户账号）══════════
  suite('C4. 客户账号 /use-units/{id}/accounts')
  record('GET /use-units/{id}/accounts', await get(`/use-units/${seed.useUnits.uu1}/accounts`, admin), 'ok')
  let tmpAccountId = null
  {
    const r = await post(`/use-units/${seed.useUnits.uu2}/accounts`, {
      name: 'E2E客户', phone: '139' + String(Date.now()).slice(-8), role: 'CLIENT_ADMIN'
    }, admin)
    if (record('POST /use-units/{id}/accounts', r, 'ok')) tmpAccountId = r.data.id
  }
  if (tmpAccountId) {
    const u = seed.useUnits.uu2
    record('PUT /use-units/{unitId}/accounts/{accountId}', await put(`/use-units/${u}/accounts/${tmpAccountId}`, { name: 'E2E客户改名' }, admin), 'ok')
    record('POST .../accounts/{id}/reset-password', await post(`/use-units/${u}/accounts/${tmpAccountId}/reset-password`, {}, admin), 'ok')
    record('POST .../accounts/{id}/bind-wechat', await post(`/use-units/${u}/accounts/${tmpAccountId}/bind-wechat`, { code: 'e2e' }, admin), 'any')
    record('PATCH .../accounts/{id}/disable', await patch(`/use-units/${u}/accounts/${tmpAccountId}/disable`, {}, admin), 'ok')
    record('PATCH .../accounts/{id}/enable', await patch(`/use-units/${u}/accounts/${tmpAccountId}/enable`, {}, admin), 'ok')
    record('POST .../accounts/{id}/restore-grace', await post(`/use-units/${u}/accounts/${tmpAccountId}/restore-grace`, {}, admin), 'any')
  }

  // ══════════ 5. ClientAuthController ══════════
  suite('C5. 客户认证 /auth/client/**')
  record('GET /auth/client/me（客户令牌）', await get('/auth/client/me', client), 'ok')
  record('POST /auth/client/select-unit（无 sel 令牌）', await post('/auth/client/select-unit', { useUnitId: seed.useUnits.uu1 }), 'biz')
  record('POST /auth/client/change-password（错误原口令）', await post('/auth/client/change-password', { oldPassword: 'wrong', newPassword: '123456' }, client), 'biz')

  // ── 认证端点剩余 4 条（统一登录改造后仍在线，逐条覆盖）──
  record('POST /auth/bind-wechat（微信未配置）', await post('/auth/bind-wechat', { code: 'e2e' }, tech), 'any')
  record('POST /auth/change-password（错误原口令）', await post('/auth/change-password', { oldPassword: 'wrong', newPassword: '123456' }, tech), 'biz')
  // 客户主体走同两个「员工端点」时必须按主体分流：返回 401 会让前端 request 层清登录态，
  // 表现为客户在个人中心改密/绑微信后被踢回登录页（BUG-1 同类，2026-11-20 静态排查修）。
  record('POST /auth/change-password（客户令牌·错误原口令应为 422 不是 401）',
    await post('/auth/change-password', { oldPassword: 'wrong', newPassword: '12345678' }, client), 422)
  record('POST /auth/bind-wechat（客户令牌不应 401）', await post('/auth/bind-wechat', { code: 'e2e' }, client), 'any')
  // 白名单已收窄到精确电梯读端点；移交档案属 handover:view，客户角色无权
  record('GET /elevators/{id}/archive（客户令牌）', await get(`/elevators/${seed.elevators.el1}/archive`, client), 403)
  // 统一登录改造后的遗留通道已于 2026-11-20 删除（角色切换收敛为多角色并集），此处锁 404：
  // 任何人把它们加回来都会在这里变红，必须重新论证「登录后自铸角色令牌」的必要性。
  record('POST /auth/bind-employee（遗留通道已删除）', await post('/auth/bind-employee', { role: 'LEADER' }, tech), 404)
  record('POST /auth/bind-use-unit（遗留通道已删除）', await post('/auth/bind-use-unit', {}, tech), 404)

  await probe(ctx, 'C6')
  // ══════════ 6. Contract / ContractLedger ══════════
  suite('C6. 维保合同 /contracts')
  record('GET /contracts', await get('/contracts', admin), 'ok')
  record('GET /contracts/{id}', await get(`/contracts/${seed.contracts.ct1}`, admin), 'ok')
  record('POST /contracts', await post('/contracts', { contractNo: 'E2E-CT-1', useUnitId: seed.useUnits.uu1, startDate: '2026-01-01', endDate: '2027-01-01' }, admin), 'any')
  record('GET /contracts-ledger', await get('/contracts-ledger', admin), 'ok')

  // ══════════ 7. Count / DataDomain ══════════
  suite('C7. 计数与数据域 /api/count /stats /audit /settings')
  record('GET /api/count（白名单）', await get('/api/count'), 'any')
  record('POST /api/count（白名单）', await post('/api/count', { count: 1 }), 'any')
  record('GET /stats', await get('/stats', admin), 'ok')
  record('GET /audit', await get('/audit', admin), 'ok')
  record('GET /settings', await get('/settings', admin), 'ok')
  record('PUT /settings/{key}', await put('/settings/e2e_probe', { value: '1' }, admin), 'any')
  record('GET /export-approvals', await get('/export-approvals', admin), 'ok')
  record('POST /export-approvals', await post('/export-approvals', { scope: 'RECORDS', reason: 'e2e' }, admin), 'any')
  record('POST /export-approvals/{id}/approve', await post(`/export-approvals/${seed.approvals.ea1}/approve`, {}, admin), 'any')

  await probe(ctx, 'C8')
  // ══════════ 8. Directory（故障/救援/演练/检查/消息/知识）══════════
  suite('C8. 现场记录 /faults /rescues /drills /inspects /messages')
  record('GET /faults', await get('/faults', admin), 'ok')
  record('GET /faults/{id}', await get(`/faults/${seed.faults.ft1}`, tech), 'ok')
  record('POST /faults', await post('/faults', { elevatorId: seed.elevators.el1, desc: 'E2E测试故障', level: 'NORMAL' }, tech), 'any')
  record('PUT /faults/{id}', await put(`/faults/${seed.faults.ft1}`, { desc: 'E2E更新' }, tech), 'any')
  record('POST /faults/{id}/grab', await post(`/faults/${seed.faults.ft1}/grab`, { lat: 29.56, lng: 106.55 }, tech), 'any')
  record('POST /faults/{id}/arrive', await post(`/faults/${seed.faults.ft1}/arrive`, { lat: 29.56, lng: 106.55 }, tech), 'any')
  record('POST /faults/{id}/submit', await post(`/faults/${seed.faults.ft1}/submit`, { pass: true, reason: 'e2e' }, tech), 'any')
  record('POST /faults/{id}/confirm', await post(`/faults/${seed.faults.ft1}/confirm`, { pass: true }, tech), 'any')
  record('POST /faults/{id}/cancel', await post(`/faults/${seed.faults.ft1}/cancel`, { reason: 'e2e' }, tech), 'any')
  record('POST /faults/{id}/evaluate', await post(`/faults/${seed.faults.ft1}/evaluate`, { score: 5 }, client), 'any')
  record('POST /faults/{id}/close', await post(`/faults/${seed.faults.ft1}/close`, {}, admin), 'any')
  record('GET /admin/faults/{id}/export-pdf', await get(`/admin/faults/${seed.faults.ft1}/export-pdf`, admin), 'any')
  record('POST /admin/faults/{id}/dispatch', await post(`/admin/faults/${seed.faults.ft1}/dispatch`, { workerId: seed.employees.maintTech }, admin), 'any')
  record('POST /faults/{id}/proxy', await post(`/faults/${seed.faults.ft1}/proxy`, { targetId: seed.employees.worker }, tech), 'any')
  // 已裁合规矩阵台账模块（docs/01 §10.2）：APP_LEGACY_MODULES_ENABLED 默认关闭 → 一律 403
  record('GET /rescues (已裁停用)', await get('/rescues', tech), 'forbidden')
  record('GET /rescues/{id} (已裁停用)', await get(`/rescues/${seed.rescues.rs1}`, tech), 'forbidden')
  record('POST /rescues (已裁停用)', await post('/rescues', { elevatorId: seed.elevators.el1, desc: 'E2E救援' }, tech), 'forbidden')
  record('GET /drills (已裁停用)', await get('/drills', admin), 'forbidden')
  record('POST /drills (已裁停用)', await post('/drills', { title: 'E2E演练', date: '2026-12-01' }, admin), 'forbidden')
  record('GET /inspects (已裁停用)', await get('/inspects', admin), 'forbidden')
  record('GET /inspects/template (已裁停用)', await get('/inspects/template', admin), 'forbidden')
  record('POST /inspects (已裁停用)', await post('/inspects', { elevatorId: seed.elevators.el1 }, admin), 'forbidden')
  record('GET /messages', await get('/messages', tech), 'ok')
  record('GET /messages/unread-count', await get('/messages/unread-count', tech), 'ok')
  record('POST /messages/{id}/read', await post(`/messages/${seed.messages.msg1}/read`, {}, tech), 'any')
  record('POST /messages/read-all', await post('/messages/read-all', {}, tech), 'any')
  record('GET /knowledge (已裁停用)', await get('/knowledge', tech), 'forbidden')

  // ══════════ 9. Elevator ══════════
  suite('C9. 电梯 /elevators')
  record('GET /elevators', await get('/elevators', tech), 'ok')
  record('GET /elevators/by-code', await get('/elevators/by-code?code=TSCQ5001120001', tech), 'any')
  record('GET /elevators/{id}/profile', await get(`/elevators/${seed.elevators.el1}/profile`, tech), 'ok')
  record('PUT /elevators/{id}/project-name', await put(`/elevators/${seed.elevators.el1}/project-name`, { projectName: '世纪大厦' }, tech), 'any')
  record('PUT /elevators/{id}/local', await put(`/elevators/${seed.elevators.el1}/local`, { location: 'E2E位置' }, tech), 'any')
  record('POST /elevators/{id}/dispatch', await post(`/elevators/${seed.elevators.el1}/dispatch`, {}, admin), 'any')

  await probe(ctx, 'C10')
  // ══════════ 10. ExternalFiling ══════════
  suite('C10. 外部备案 /filings')
  record('GET /filings', await get('/filings', admin), 'ok')
  record('GET /filings/{id}', await get(`/filings/${seed.filings ? seed.filings.id : 'ef_oQyWIMI5TNKHVeMobYtpqw'}`, admin), 'any')
  record('POST /filings', await post('/filings', { elevatorId: seed.elevators.el1 }, admin), 'any')
  record('POST /filings/{id}/submit-docs', await post('/filings/ef_oQyWIMI5TNKHVeMobYtpqw/submit-docs', {}, admin), 'any')
  record('POST /filings/{id}/takeover-check', await post('/filings/ef_oQyWIMI5TNKHVeMobYtpqw/takeover-check', {}, admin), 'any')
  record('POST /filings/{id}/confirm', await post('/filings/ef_oQyWIMI5TNKHVeMobYtpqw/confirm', {}, admin), 'any')

  // ══════════ 11. File ══════════
  suite('C11. 文件 /files')
  record('POST /files/sts', await post('/files/sts', { dir: 'record' }, tech), 'any')
  record('GET /files/{id}（无签名）', await get('/files/none', tech), 'any')
  // multipart 上传用 JSON 探测：应被拒但不是 5xx
  record('POST /files/upload（非 multipart）', await post('/files/upload', {}, tech), 'any')
  // 客户令牌只有 GET /files/*（时效签名），写上传不在白名单 → 403。
  // 影响：客户门户签字页无法自行上传签名图，只能由员工侧上传或内联 signatureUrl（已在报告登记）。
  record('POST /files/upload（客户令牌越权写）', await post('/files/upload', {}, client), 403)

  await probe(ctx, 'C12')
  // ══════════ 12. Handover ══════════
  suite('C12. 安装移交 /handovers')
  record('GET /handovers', await get('/handovers', admin), 'ok')
  record('GET /handovers/{id}', await get(`/handovers/${seed.handovers.ho1}`, admin), 'ok')
  record('POST /handovers', await post('/handovers', { elevatorId: seed.elevators.el1 }, admin), 'any')
  record('POST /handovers/{id}/submit-docs', await post(`/handovers/${seed.handovers.ho1}/submit-docs`, {}, admin), 'any')
  record('POST /handovers/{id}/assign-receiver', await post(`/handovers/${seed.handovers.ho1}/assign-receiver`, { receiverId: seed.employees.instMgr }, admin), 'any')
  record('POST /handovers/{id}/inspect', await post(`/handovers/${seed.handovers.ho1}/inspect`, {}, admin), 'any')
  record('POST /handovers/{id}/rectify', await post(`/handovers/${seed.handovers.ho1}/rectify`, {}, admin), 'any')
  record('POST /handovers/{id}/recheck-rectify', await post(`/handovers/${seed.handovers.ho1}/recheck-rectify`, {}, admin), 'any')
  record('POST /handovers/{id}/confirm', await post(`/handovers/${seed.handovers.ho1}/confirm`, {}, admin), 'any')
  record('POST /handovers/{id}/sign', await post(`/handovers/${seed.handovers.ho1}/sign`, {}, admin), 'any')
  record('POST /handovers/{id}/review', await post(`/handovers/${seed.handovers.ho1}/review`, { pass: true }, admin), 'any')
  record('POST /handovers/{id}/void', await post(`/handovers/${seed.handovers.ho1}/void`, { reason: 'e2e' }, admin), 'any')

  // ══════════ 13. HandoverChecklist ══════════
  suite('C13. 移交清单 /handovers/{id}/checklist')
  record('GET /handovers/{id}/checklist', await get(`/handovers/${seed.handovers.ho1}/checklist`, admin), 'any')
  record('POST /handovers/{id}/checklist/submit', await post(`/handovers/${seed.handovers.ho1}/checklist/submit`, { items: [] }, admin), 'any')
  record('GET /handovers/{id}/rectifications', await get(`/handovers/${seed.handovers.ho1}/rectifications`, admin), 'any')
  record('POST .../rectifications/{rectId}/rectify', await post(`/handovers/${seed.handovers.ho1}/rectifications/${seed.handoverRects.hr1}/rectify`, { photos: [] }, admin), 'any')
  record('POST .../rectifications/{rectId}/recheck', await post(`/handovers/${seed.handovers.ho1}/rectifications/${seed.handoverRects.hr1}/recheck`, { pass: true }, admin), 'any')
  record('POST /handovers/{id}/na-approve', await post(`/handovers/${seed.handovers.ho1}/na-approve`, {}, admin), 'any')
  record('GET /handovers/{id}/export-pdf', await get(`/handovers/${seed.handovers.ho1}/export-pdf`, admin), 'any')
  record('GET /elevators/{id}/archive', await get(`/elevators/${seed.elevators.el1}/archive`, admin), 'any')
  record('GET /handovers/{id}/archive', await get(`/handovers/${seed.handovers.ho1}/archive`, admin), 'any')

  await probe(ctx, 'C14')
  // ══════════ 14. Ledger（组织/项目/邀请/授权/变更）══════════
  suite('C14. 台账 /org /projects /invites /grants /elevator-changes')
  record('GET /org', await get('/org', admin), 'ok')
  record('POST /org', await post('/org', { name: 'E2E班组', type: 'TEAM', parentId: seed.orgs.deptMaint }, admin), 'any')
  record('PUT /org/{id}', await put(`/org/${seed.orgs.teamMaint1}`, { name: '维保一班' }, admin), 'any')
  record('GET /projects', await get('/projects', admin), 'ok')
  record('POST /projects', await post('/projects', { name: 'E2E项目', maintOrgId: seed.orgs.deptMaint }, admin), 'any')
  record('PUT /projects/{id}', await put(`/projects/${seed.projects.pj1}`, { name: '世纪大厦' }, admin), 'any')
  record('GET /invites', await get('/invites', admin), 'ok')
  record('POST /invites', await post('/invites', { phone: '13900005555', role: 'WORKER' }, admin), 'any')
  record('POST /invites/{id}/withdraw', await post(`/invites/${seed.invites.iv1}/withdraw`, {}, admin), 'any')
  record('GET /grants', await get('/grants', admin), 'ok')
  record('POST /grants', await post('/grants', { granteeId: seed.employees.worker }, admin), 'any')
  record('POST /grants/{id}/withdraw', await post(`/grants/${seed.grants.gr1}/withdraw`, {}, admin), 'any')
  record('GET /elevator-changes', await get('/elevator-changes', admin), 'ok')
  record('POST /elevator-changes', await post('/elevator-changes', { elevatorId: seed.elevators.el1, field: 'projectName', newValue: 'E2E' }, admin), 'any')
  record('POST /elevator-changes/{id}/confirm', await post('/elevator-changes/ec_none/confirm', {}, admin), 'any')
  record('GET /roles-matrix', await get('/roles-matrix', admin), 'any')

  // ══════════ 15. Location / Calendar ══════════
  suite('C15. 地理与日历 /location /maintenance-calendar')
  record('GET /location/reverse（白名单）', await get('/location/reverse?lat=29.56&lng=106.55'), 'any')
  record('GET /maintenance-calendar', await get('/maintenance-calendar', tech), 'ok')

  await probe(ctx, 'C16')
  // ══════════ 16. Platform（外部平台）══════════
  suite('C16. 监管平台 /platform')
  record('GET /platform/workers（未配凭证=2001 属环境限制）', await get('/platform/workers', admin), 'any')
  record('POST /platform/sync', await post('/platform/sync', {}, admin), 'any')
  record('POST /platform/workers/backfill', await post('/platform/workers/backfill', {}, admin), 'any')
  record('POST /platform/query/elevator', await post('/platform/query/elevator', { regCode: 'TSCQ5001120001' }, admin), 'any')
  record('POST /platform/records/{id}/reupload', await post(`/platform/records/${seed.records.ur1}/reupload`, {}, admin), 'any')
  record('POST /platform/register/service', await post('/platform/register/service', { useUnitName: '重庆世纪物业管理有限公司' }, admin), 'any')
  record('POST /platform/register/worker', await post('/platform/register/worker', { workManName: '李振国', workManIdCard: '500101199001011234' }, admin), 'any')

  await probe(ctx, 'C17')
  // ══════════ 17. Record / RegAdmin / Region ══════════
  suite('C17. 记录详情 / 注册上报 / 区域')
  record('GET /records/{id}', await get(`/records/${seed.records.ur1}`, tech), 'ok')
  record('GET /reg/upload-logs', await get('/reg/upload-logs', admin), 'ok')
  record('GET /reg/sync-status', await get('/reg/sync-status', admin), 'ok')
  record('GET /regions', await get('/regions', admin), 'ok')
  let tmpRegionId = null
  {
    const r = await post('/regions', { name: 'E2E测试区', code: 'E2E' + Date.now().toString().slice(-6) }, admin)
    if (record('POST /regions', r, 'ok')) tmpRegionId = r.data.id
  }
  if (tmpRegionId) {
    record('PUT /regions/{id}', await put(`/regions/${tmpRegionId}`, { name: 'E2E测试区改名' }, admin), 'ok')
  }
  record('PUT /elevators/{id}/region', await put(`/elevators/${seed.elevators.el1}/region`, { regionId: seed.regions.rg1 }, admin), 'any')
  record('PUT /employees/{id}/region', await put(`/employees/${seed.employees.worker}/region`, { regionId: seed.regions.rg1 }, admin), 'any')

  // ══════════ 18. UnitAccount（旧门户）══════════
  suite('C18. 使用单位门户 /unit/**')
  record('GET /unit/elevators（客户）', await get('/unit/elevators', client), 'ok')
  record('GET /unit/records/list（客户）', await get('/unit/records/list', client), 'ok')
  record('GET /unit/pending-signs（客户）', await get('/unit/pending-signs', client), 'ok')
  record('GET /unit/faults（客户）', await get('/unit/faults', client), 'ok')
  record('POST /unit/faults（客户报修）', await post('/unit/faults', { elevatorId: seed.elevators.el1, desc: 'E2E客户报修' }, client), 'any')
  record('POST /unit/accounts（建账号）', await post('/unit/accounts', { name: 'E2E', phone: '13900006666' }, client), 'any')
  record('POST /unit/login（旧通道）', await post('/unit/login', { phone: '13900006666', password: 'x' }), 'any')
  record('POST /unit/login-by-wechat', await post('/unit/login-by-wechat', { code: 'x' }), 'any')
  record('POST /unit/bind-wechat', await post('/unit/bind-wechat', { code: 'x' }), 'any')
  record('POST /unit/account/password', await post('/unit/account/password', { oldPassword: 'x', newPassword: 'y' }, client), 'any')

  // ══════════ 19. UnitRecord（客户签字）══════════
  suite('C19. 客户签字 /unit/records')
  record('GET /unit/records/pending', await get('/unit/records/pending', client), 'ok')
  record('GET /unit/records/{id}', await get(`/unit/records/${seed.records.ur1}`, client), 'any')
  record('GET /unit/records/{id}/sign-view', await get(`/unit/records/${seed.records.ur1}/sign-view`, client), 'any')
  record('POST /unit/records/{id}/confirm', await post(`/unit/records/${seed.records.ur1}/confirm`, {}, client), 'any')
  record('POST /unit/records/{id}/confirm-by-token', await post(`/unit/records/${seed.records.ur1}/confirm-by-token`, {}, client), 'any')
  record('POST /unit/records/{id}/refuse-by-token', await post(`/unit/records/${seed.records.ur1}/refuse-by-token`, { reason: 'e2e' }, client), 'any')

  await probe(ctx, 'C20')
  // ══════════ 20. User ══════════
  suite('C20. 账号目录 /users')
  record('GET /users', await get('/users', admin), 'ok')
  record('POST /users', await post('/users', { name: 'E2E目录账号', phone: '139' + String(Date.now()).slice(-8), roles: ['WORKER'] }, admin), 'ok')
  record('POST /users/{id}/disable', await post('/users/emp_none/disable', {}, admin), 'any')
  record('POST /users/{id}/roles', await post(`/users/${seed.employees.worker}/roles`, { roles: ['WORKER'] }, admin), 'ok')

  await probe(ctx, 'C21')
  // ══════════ 21. WorkDomain / WorkOrder ══════════
  suite('C21. 作业域 /records /work-orders')
  record('POST /records/{id}/amend', await post(`/records/${seed.records.ur1}/amend`, { reason: 'E2E修正' }, tech), 'any')
  record('GET /work-orders', await get('/work-orders', tech), 'ok')
  record('GET /work-orders/{id}', await get(`/work-orders/${seed.workOrders.wo1}`, tech), 'ok')
  record('GET /work-orders/resolve-by-elevator', await get('/work-orders/resolve-by-elevator?elevatorCode=TSCQ5001120001', tech), 'any')
  record('GET /work-orders/{id}/checklist', await get(`/work-orders/${seed.workOrders.wo1}/checklist`, tech), 'any')
  record('POST /work-orders/{id}/checkin', await post(`/work-orders/${seed.workOrders.wo1}/checkin`, { lat: 29.56, lng: 106.55 }, tech), 'any')
  record('POST /work-orders/{id}/dynamic-code', await post(`/work-orders/${seed.workOrders.wo1}/dynamic-code`, {}, tech), 'any')
  record('POST /work-orders/{id}/dynamic-code/verify', await post(`/work-orders/${seed.workOrders.wo1}/dynamic-code/verify`, { code: '000000' }, tech), 'any')
  record('POST /work-orders/{id}/checklist/{itemId}', await post(`/work-orders/${seed.workOrders.wo1}/checklist/it_none`, { result: 'OK' }, tech), 'any')
  record('POST .../checklist/{itemId}/run-this-time', await post(`/work-orders/${seed.workOrders.wo1}/checklist/it_none/run-this-time`, {}, tech), 'any')
  record('POST /work-orders/{id}/signature', await post(`/work-orders/${seed.workOrders.wo1}/signature`, { fileId: 'f_none' }, tech), 'any')
  record('POST /work-orders/{id}/signature-refuse', await post(`/work-orders/${seed.workOrders.wo1}/signature-refuse`, { reason: 'e2e' }, client), 'any')
  record('POST /work-orders/{id}/review', await post(`/work-orders/${seed.workOrders.wo1}/review`, { pass: true }, admin), 'any')
  record('POST /work-orders/{id}/sign-request', await post(`/work-orders/${seed.workOrders.wo1}/sign-request`, {}, tech), 'any')
  record('POST /work-orders/{id}/checkout', await post(`/work-orders/${seed.workOrders.wo1}/checkout`, {}, tech), 'any')
  record('GET /work-orders/{id}/archive', await get(`/work-orders/${seed.workOrders.wo1}/archive`, tech), 'any')

  await probe(ctx, 'C22')
  // ══════════ 22. 清理临时数据 ══════════
  suite('C22. 临时数据清理')
  if (tmp.elevatorId) record('DELETE /elevators/{id}（临时）', await del(`/elevators/${tmp.elevatorId}`, admin), 'ok')
  if (tmp.employeeId) record('DELETE /employees/{id}（临时）', await del(`/employees/${tmp.employeeId}`, admin), 'ok')
  if (tmpRegionId) record('DELETE /regions/{id}（临时）', await del(`/regions/${tmpRegionId}`, admin), 'ok')
  if (tmp.unitId) record('DELETE /use-units/{id}（临时）', await del(`/use-units/${tmp.unitId}`, admin), 'ok')
}

module.exports = { runSmokeCases }
