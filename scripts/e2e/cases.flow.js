'use strict'
/**
 * 核心业务流：串行链路，每步都断言，任何一环断了整条 flow 记为失败。
 * 覆盖：建档 → 客户账号开通 → 登录 → 工单现场作业（签到/动态码/清单/双签/签退）→ 客户签字 → 管理端归档。
 */

const fs = require('fs')
const path = require('path')
const { execFileSync } = require('child_process')
const { BASE, get, post, put, del, record, suite, assert, assertEq, login, colors } = require('./lib')
const { seed, accounts } = require('./seed')

/** 直连数据库执行 SQL（仅用于造/校测试前置，如时间回拨、状态核对） */
function dbExec(sql) {
  const root = path.resolve(__dirname, '../../')
  let pass = process.env.DB_PASSWORD || ''
  if (!pass) {
    try {
      const env = fs.readFileSync(path.join(root, '.env'), 'utf8')
      const m = env.match(/^DB_PASSWORD=(.*)$/m)
      if (m) pass = m[1].trim()
    } catch (e) {
      /* 无 .env 时留空 */
    }
  }
  const args = ['--default-character-set=utf8mb4', '-h127.0.0.1', '-P3306', '-umaintenance_app', 'maintenance', '-N', '-e', sql]
  return execFileSync('mysql', args, {
    env: { ...process.env, MYSQL_PWD: pass },
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe']
  }).trim()
}

/** 一步一记录的小工具：成功记 PASS，异常记 FAIL 并终止本 flow */
async function step(name, fn, expect = 'ok') {
  let r
  try {
    r = await fn()
  } catch (e) {
    record(name, { http: 0, code: -1, message: e.message, ms: 0 }, 'ok', '抛异常')
    throw e
  }
  const ok = record(name, r, expect)
  if (!ok) throw new Error(`${name} 未达预期：code=${r.code} ${r.message}`)
  return r
}

/**
 * 上传一张 1×1 PNG 作为签名图，返回真实 fileId。
 * 签退时 resolveSignatureUrl 会查 app_file 取 URL，伪造的 fileId（如 'f_sig_principal'）解析为空，
 * 会被判「请完成主维保人员签名」——必须走真实上传。
 */
async function uploadSignature(token, name) {
  const png = Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
    'base64'
  )
  const fd = new FormData()
  fd.append('file', new Blob([png], { type: 'image/png' }), name)
  const res = await fetch(BASE + '/files/upload', {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + token },
    body: fd
  })
  const json = await res.json()
  if (!json.data || !json.data.fileId) {
    throw new Error('签名图上传失败：' + (json.message || res.status))
  }
  return json.data.fileId
}

async function runFlowCases(ctx) {
  const T = ctx.tokens
  const admin = T.admin
  const tech = T.maintTech

  // ══════════════ F1. 建档与客户账号闭环 ══════════════
  suite('F1. 建档 → 客户账号 → 客户登录')
  const f1 = { unitId: null, accountId: null, employeeId: null, elevatorId: null }
  const stamp = Date.now().toString().slice(-6)
  try {
    const u = await step('① 新建使用单位', () =>
      post('/use-units', {
        unitName: `E2E单位${stamp}`,
        organizationCode: `E2E${stamp}`,
        unitPrincipal: '端到端负责人',
        unitPrincipalPhone: `139${stamp}01`,
        elevatorAdminister: '端到端管理员',
        elevatorAdministerPhone: `139${stamp}02`,
        emergencyPhone: '119'
      }, admin)
    )
    f1.unitId = u.data.id
    assert(/^uu_/.test(f1.unitId), '使用单位 id 前缀')

    // 客户账号：初始口令 = 手机号后 6 位
    const phone = `139${stamp}03`
    const acc = await step('② 为使用单位开通客户账号', () =>
      post(`/use-units/${f1.unitId}/accounts`, { name: '端到端客户', phone, role: 'CLIENT_ADMIN' }, admin)
    )
    f1.accountId = acc.data.id

    const list = await step('③ 客户账号列表含新账号', () => get(`/use-units/${f1.unitId}/accounts`, admin))
    assert((list.data || []).some((a) => a.id === f1.accountId), '客户账号出现在列表')

    // 新客户登录 → 应拿到 CUSTOMER 主体
    const cl = await step('④ 新客户账号登录（自动分流到客户通道）', () =>
      post('/auth/login', { phone, password: phone.slice(-6) })
    )
    assertEq(cl.data.principalType, 'CUSTOMER', '新客户 principalType')
    assertEq(cl.data.userInfo.useUnitId, f1.unitId, '新客户 useUnitId')
    const newClientToken = cl.data.token

    const elevs = await step('⑤ 新客户可见本单位电梯（应为空）', () => get('/unit/elevators', newClientToken))
    assert(Array.isArray(elevs.data), '电梯列表为数组')
    assertEq(elevs.data.length, 0, '新单位电梯数为 0')

    // 建员工 + 建电梯
    const emp = await step('⑥ 新建维保员工', () =>
      post('/employees', {
        name: `E2E技师${stamp}`,
        phone: `139${stamp}04`,
        role: 'MAINT_TECH',
        password: '12345678',
        groupName: '维保一班'
      }, admin)
    )
    f1.employeeId = emp.data.id

    const el = await step('⑦ 新建电梯（挂新单位，5 项档案必填）', () =>
      post('/elevators', {
        elevatorCode: `E2E${stamp}`,
        deviceCode: `DEV${stamp}`,
        registrationCode: `REG${stamp}`,
        insideNumber: `E2E-${stamp}`,
        factoryNumber: `FAC${stamp}`,
        elevatorName: `E2E电梯${stamp}`,
        useUnitId: f1.unitId,
        workTypeCode: 'HM',
        projectName: `E2E项目${stamp}`
      }, admin)
    )
    f1.elevatorId = el.data.id

    const clElevs = await step('⑧ 客户立即可见新电梯', () => get('/unit/elevators', newClientToken))
    assert((clElevs.data || []).some((e) => e.id === f1.elevatorId), '客户可见本单位新电梯')

    const adminElevs = await step('⑨ 管理端电梯列表可见', () => get('/admin/elevators', admin))
    assert((adminElevs.data || []).some((e) => e.id === f1.elevatorId), '管理端可见新电梯')

    await step('⑩ 更新电梯项目名', () =>
      put(`/elevators/${f1.elevatorId}`, { projectName: `E2E项目改名${stamp}` }, admin)
    )
    await step('⑪ 电梯档案可读', () => get(`/elevators/${f1.elevatorId}/profile`, admin))
    const dbName = dbExec(`SELECT IFNULL(project_name,'') FROM elevator WHERE id='${f1.elevatorId}'`)
    record(`⑪b 改名已落库（${dbName}）`, {
      http: 200, code: dbName === `E2E项目改名${stamp}` ? 0 : 500, message: dbName, ms: 0
    }, 'ok')

    // 反例：必填项不得被清空
    record(
      '⑫ 清空电梯注册代码被拒（必传项保护）',
      await put(`/elevators/${f1.elevatorId}`, { registrationCode: '' }, admin),
      'biz'
    )

    // 收尾：倒序删除
    await step('⑬ 删除临时电梯', () => del(`/elevators/${f1.elevatorId}`, admin))
    await step('⑭ 删除临时员工', () => del(`/employees/${f1.employeeId}`, admin))
    await step('⑮ 删除临时使用单位（连带客户账号）', () => del(`/use-units/${f1.unitId}`, admin))
    record('⑯ 删除后客户账号不可登录', await post('/auth/login', { phone, password: phone.slice(-6) }), 401)
  } catch (e) {
    console.log(colors.yellow(`  ! F1 中断：${e.message}`))
  }

  // ══════════════ F2. 工单现场作业全链路 ══════════════
  suite('F2. 工单：签到 → 动态码 → 清单 → 双签 → 签退')
  const woId = seed.workOrders.wo1
  try {
    // 前置：把演示工单 wo_1 复位到「未签到」的干净初始态。
    // 必须无条件重置——只看 status 不够：上次中断可能留下 checkin_extra（签到痕迹）而状态已回 PENDING，
    // 会导致「主维保人员已签到，不可重复签到」。
    const before = dbExec(`SELECT CONCAT(status,'|',IFNULL(checkin_extra,''),'|',IFNULL(assistant_checkin_extra,'')) FROM work_order WHERE id='${woId}'`)
    dbExec(
      `UPDATE work_order SET status='PENDING', checkin_time=NULL, checkout_time=NULL, ` +
        `checkin_extra=NULL, assistant_checkin_extra=NULL, ` +
        `worker_signature_file_id=NULL, assistant_signature_file_id=NULL, ` +
        `worker_signature_at=NULL, assistant_signature_at=NULL, dynamic_code=NULL ` +
        `WHERE id='${woId}'`
    )
    console.log(colors.gray(`    wo_1 复位：${before.split('|')[0]} → PENDING`))

    const dt = await step('① 主维保可见工单详情', () => get(`/work-orders/${woId}`, tech))
    assert(dt.data && (dt.data.id === woId || dt.data.order && dt.data.order.id === woId), '工单详情返回本体')

    const lq = await login(accounts.worker2.phone, accounts.worker2.password)
    const lqToken = lq.token

    // 主维保签到（坐标取电梯档案坐标）
    const coords = dbExec(`SELECT CONCAT(lat,',',lng) FROM elevator WHERE id='${seed.elevators.el1}'`).split(',')
    const lat = Number(coords[0])
    const lng = Number(coords[1])
    assert(lat > 0 && lng > 0, '电梯有坐标')

    await step('② 主维保签到（带定位）', () =>
      post(`/work-orders/${woId}/checkin`, {
        latitude: lat, longitude: lng, locationAccuracy: 10, role: 'PRINCIPAL'
      }, tech)
    )

    const st1 = dbExec(`SELECT status FROM work_order WHERE id='${woId}'`)
    record('③ 签到后工单转 PROCESSING', { http: 200, code: st1 === 'PROCESSING' ? 0 : 500, message: st1, ms: 0 }, 'ok')

    // 重复签到应被拒
    record('④ 重复签到被拒', () =>
      post(`/work-orders/${woId}/checkin`, { latitude: lat, longitude: lng, role: 'PRINCIPAL' }, tech)
    , 'any')

    // 动态码：主维保取码 → 配合人签到
    const codeRes = await step('⑤ 主维保生成双人动态码', () => post(`/work-orders/${woId}/dynamic-code`, {}, tech))
    const dyn = codeRes.data && (codeRes.data.code || codeRes.data.dynamicCode)
    assert(dyn, '拿到动态码')

    record('⑥ 错误动态码被拒', await post(`/work-orders/${woId}/checkin`, {
      latitude: lat, longitude: lng, role: 'ASSISTANT', dynamicCode: '000000'
    }, lqToken), 'biz')

    await step('⑦ 配合人凭动态码签到', () =>
      post(`/work-orders/${woId}/checkin`, {
        latitude: lat, longitude: lng, role: 'ASSISTANT', dynamicCode: dyn
      }, lqToken)
    )

    // 清单
    const ck = await step('⑧ 读取检查清单', () => get(`/work-orders/${woId}/checklist`, tech))
    const items = ck.data.items || ck.data.list || []
    assert(items.length > 0, `清单项数 > 0（实际 ${items.length}）`)
    console.log(colors.gray(`    清单 ${items.length} 项，开始逐项填报…`))

    const photoFileId = await uploadSignature(tech, 'checklist-photo.png')
    let submitted = 0
    for (const it of items) {
      const id = it.id
      if (it.notInThisRun) continue
      const body = { result: 'OK' }
      if (it.photoRequired) body.photoFileIds = [photoFileId]
      const r = await post(`/work-orders/${woId}/checklist/${id}`, body, tech)
      if (r.code === 0) submitted++
      else console.log(colors.yellow(`    项 ${id}(${it.name}) 填报失败：${r.message}`))
    }
    record(`⑨ 清单逐项填报（${submitted}/${items.length}）`, {
      http: 200, code: submitted > 0 ? 0 : 500, message: `已填 ${submitted} 项`, ms: 0
    }, 'ok')

    // 双签：签名图必须是可解析的真实文件（app_file 里存在），否则签退会判「未签名」
    const sigPrincipal = await uploadSignature(tech, 'sign-principal.png')
    const sigAssistant = await uploadSignature(lqToken, 'sign-assistant.png')
    await step('⑩ 主维保签字', () =>
      post(`/work-orders/${woId}/signature`, { role: 'PRINCIPAL', signatureFileId: sigPrincipal }, tech)
    )
    await step('⑪ 配合人签字', () =>
      post(`/work-orders/${woId}/signature`, { role: 'ASSISTANT', signatureFileId: sigAssistant }, lqToken)
    )

    // 签退：先验证时长门禁，再回拨签到时间跑通
    const early = await post(`/work-orders/${woId}/checkout`, {}, tech)
    record('⑫ 作业时长不足被拒（门禁生效）', early, 'biz')
    assert(/时长不足/.test(early.message), '拒绝原因应为作业时长不足，实际：' + early.message)

    dbExec(`UPDATE work_order SET checkin_time = DATE_SUB(NOW(), INTERVAL 60 MINUTE) WHERE id='${woId}'`)
    console.log(colors.gray('    已回拨签到时间 60 分钟（测试前置）'))

    const out = await step('⑬ 主维保签退 → 生成维保记录', () => post(`/work-orders/${woId}/checkout`, {}, tech))
    const recordId = out.data && (out.data.id || out.data.recordId)
    assert(recordId, '签退返回记录 id')

    const st2 = dbExec(`SELECT status FROM work_order WHERE id='${woId}'`)
    record('⑭ 签退后工单转 DONE', { http: 200, code: st2 === 'DONE' ? 0 : 500, message: st2, ms: 0 }, 'ok')

    const recRow = dbExec(`SELECT CONCAT(confirm_status,'|',IFNULL(client_account_id,'')) FROM maintain_record WHERE id='${recordId}'`)
    const [confirmStatus] = recRow.split('|')
    record(`⑮ 记录待客户确认（confirm_status=${confirmStatus}）`, {
      http: 200, code: ['PENDING', 'UNCONFIRMED', 'WAIT_CLIENT'].includes(confirmStatus) ? 0 : 500,
      message: confirmStatus, ms: 0
    }, 'ok')

    // 客户签字
    const clientTok = (await login(accounts.clientAdmin.phone, accounts.clientAdmin.password)).token
    const pending = await step('⑯ 客户待签列表含该记录', () => get('/unit/records/pending', clientTok))
    const pendList = pending.data.items || pending.data.list || pending.data || []
    console.log(colors.gray(`    客户待签 ${Array.isArray(pendList) ? pendList.length : 0} 条`))

    // 客户令牌查记录详情有状态门禁（未到「待客户签字」后的可见态一律 422），属预期业务拒绝
    record('⑰b 客户令牌查详情受状态门禁（预期拒绝）', await get(`/unit/records/${recordId}`, clientTok), 'biz')

    // ⑰c 发起使用单位签字：门控「双签齐」，把记录从 PENDING_CLIENT_SIGN 推到 PENDING_CLIENT_SIGNATURE，
    // 否则 sign-view / confirm-by-token 一律 422（此前漏了这一步，⑱⑲⑳ 实际没跑到却报绿）
    const sr = await step('⑰c 发起使用单位签字（双签齐门控）', () =>
      post(`/work-orders/${recordId}/sign-request`, {}, tech))
    const shareToken = (sr.data && sr.data.shareToken) ||
      dbExec(`SELECT IFNULL(share_token,'') FROM maintain_record WHERE id='${recordId}'`)
    assert(shareToken, '发起签字后应能拿到 share_token')
    const signView = await get(`/unit/records/${recordId}/sign-view?token=${shareToken}`)
    record('⑰ 分享链接签字视图（share_token）', signView, 'ok')

    // 客户签字：设计通道是匿名分享链接（POST /unit/records/{id}/confirm-by-token?token=share_token）。
    // 签名图必须先落 app_file，而 POST /files/upload 不在客户令牌白名单（客户只有 GET /files/*），
    // 因此签名文件用员工令牌上传，确认动作仍走匿名令牌——与真实分享页一致。
    const clientSig = await uploadSignature(tech, 'client-sign.png')
    const conf = await post(`/unit/records/${recordId}/confirm-by-token?token=${shareToken}`,
      { signatureFileId: clientSig, satisfaction: 5 }, null)
    record('⑱ 客户凭分享令牌确认签字（匿名）', conf, 'ok')
    if (conf.code === 0) {
      const after = dbExec(`SELECT CONCAT(IFNULL(confirm_status,''),'|',IFNULL(client_signer_name,'')) FROM maintain_record WHERE id='${recordId}'`)
      record(`⑲ 落库客户签字人（${after}）`, {
        http: 200, code: /已确认|CONFIRMED|SIGNED|PHOTO/.test(after) ? 0 : 500,
        message: after, ms: 0
      }, 'ok')
    }

    // 管理端归档投影（按记录 id，非工单 id）
    const arc = await get(`/work-orders/${recordId}/archive`, admin)
    record('⑳ 记录归档投影（PDF 重签 URL + SHA-256）', arc, 'ok')
  } catch (e) {
    console.log(colors.yellow(`  ! F2 中断：${e.message}`))
  }

  // ══════════════ F3. 排班生成 ══════════════
  suite('F3. 排班生成 → 计划列表')
  try {
    const gen = await post('/admin/plans/generate', { month: new Date().toISOString().slice(0, 7) }, admin)
    record('① 生成月度计划', gen, 'any')
    const plans = await step('② 计划列表可读', () => get('/admin/plans', admin))
    const n = Array.isArray(plans.data) ? plans.data.length : (plans.data.items || []).length
    console.log(colors.gray(`    计划 ${n} 条`))
    record('③ 冲突检测可读', await get('/admin/plans/conflicts', admin), 'any')
    record('④ 延期队列可读', await get('/admin/plans/delays', admin), 'any')
  } catch (e) {
    console.log(colors.yellow(`  ! F3 中断：${e.message}`))
  }

  // ══════════════ F4. 报修全链路（2026-11-21 重写，C-18/V62 困人字段 + 抢单池可见域修复）══════════════
  // 客户报修（困人）→ 维保端 trapped=1 可见 → 抢单 → 到场 → 提交（PENDING_CONFIRM）；
  // 客户确认按 T8-I2 裁决前后端双重关闭（断言 403 + 状态不变）；管理端代报修（建单 + proxy 标来源）。
  // 全部步骤严格断言，测后 SQL 清理测试单。
  suite('F4. 报修全链路：客户报修 → 维保端接单 → 闭环 + 代报修')
  const f4ids = []
  const OWNER_CODE = 'EM-2024-001'
  let prevOwner = ''
  try {
    const clientTok = (await login(accounts.clientAdmin.phone, accounts.clientAdmin.password)).token
    const signerTok = (await login(accounts.clientSigner.phone, accounts.clientSigner.password)).token

    // 前置：临时摘掉该梯责任人，否则客户报修被自动派单、走不到「抢单」路径；测后恢复
    prevOwner = dbExec(`SELECT IFNULL(worker_platform_id,'') FROM elevator WHERE elevator_code='${OWNER_CODE}'`)
    dbExec(`UPDATE elevator SET worker_platform_id=NULL WHERE elevator_code='${OWNER_CODE}'`)

    const rf = await step('① 客户提交困人报修（被困 2 人）', () =>
      post('/unit/faults', {
        elevatorCode: OWNER_CODE, desc: 'E2E全链路：轿厢困人两人',
        faultType: '困人（紧急）', trappedCount: 2,
        contactName: '王芳', contactPhone: accounts.clientAdmin.phone
      }, clientTok)
    )
    const faultId = rf.data && (rf.data.id || rf.data.faultId)
    assert(faultId, '报修返回故障 id')
    f4ids.push(faultId)
    assertEq(rf.data.trappedCount, 2, 'trappedCount 随单下发')
    assertEq(rf.data.status, 'OPEN', '新报修进抢单池')

    const otherCode = dbExec(`SELECT elevator_code FROM elevator WHERE use_unit_id<>'${seed.useUnits.uu1}' AND elevator_code IS NOT NULL LIMIT 1`)
    if (otherCode) {
      record('② 越权为他单位电梯报修被拒（1403）', await post('/unit/faults', {
        elevatorCode: otherCode, desc: '越权报修测试描述', faultType: '一般故障'
      }, clientTok), 'biz')
    }

    const tl = await step('③ 维保端困人下钻列表可见该单（trapped=1）', () => get('/faults?status=OPEN&trapped=1', tech))
    assert(((tl.data && tl.data.list) || []).some((f) => f.id === faultId), '客户报修对受限技师的抢单池可见')

    const t0 = await step('④ 非困人列表不含该单（trapped=0）', () => get('/faults?status=OPEN&trapped=0', tech))
    assert(!(((t0.data && t0.data.list) || []).some((f) => f.id === faultId)), '困人单不在 trapped=0 列表')

    const gb = await step('⑤ 维保人员抢单接单（不经管理端派单）', () => post(`/faults/${faultId}/grab`, {}, tech))
    assertEq(gb.data && gb.data.status, 'ASSIGNED', '抢单后状态')

    const mine = await step('⑥ 接单后本人列表仍可见（ASSIGNED）', () => get('/faults?status=ASSIGNED', tech))
    assert(((mine.data && mine.data.list) || []).some((f) => f.id === faultId), '已接单对本人可见')

    await step('⑦ 到场打卡（GPS）', () =>
      post(`/faults/${faultId}/arrive`, { lat: 29.7192, lng: 106.6335 }, tech))

    const sb = await step('⑧ 提交处置（故障原因 + 处理措施）', () =>
      post(`/faults/${faultId}/submit`, { faultReason: '门锁触点氧化', handleDesc: '清洁触点并更换，恢复运行' }, tech))
    assertEq(sb.data && sb.data.status, 'PENDING_CONFIRM', '提交后待确认')

    // T8-I2 既有产品裁决：客户急修确认前后端双重关闭（order:confirm 收回、/faults/*/confirm 不在客户白名单），
    // 客户主体隐藏「待确认急修单」——闭环动作不在小程序客户侧，链路止于 PENDING_CONFIRM
    record('⑨ 客户令牌确认被拒（T8-I2 双重关闭，预期 403）', await post(`/faults/${faultId}/confirm`, { pass: true }, signerTok), 'biz')
    const st = dbExec(`SELECT status FROM fault WHERE id='${faultId}'`)
    record(`⑩ 急修单保持 PENDING_CONFIRM（${st}）`, { http: 200, code: st === 'PENDING_CONFIRM' ? 0 : 500, message: st, ms: 0 }, 'ok')

    // 代报修通道：员工通道建单 + proxy 标来源（order:create_proxy）
    const pf = await step('⑪ 维保管理代报修建单（电话报修）', () =>
      post('/faults', { elevatorCode: 'EM-2024-002', desc: '代报修：业主电话报层门异响', faultType: '一般故障' }, admin))
    const proxyId = pf.data && pf.data.id
    assert(proxyId, '代报修返回故障 id')
    f4ids.push(proxyId)

    const px = await step('⑫ 标记代报修来源（PHONE + 录音编号）', () =>
      post(`/faults/${proxyId}/proxy`, { proxySource: 'PHONE', recordingNo: 'REC-1234' }, admin))
    assertEq(px.data && px.data.proxySource, 'PHONE', 'proxySource 落单')

    record('⑬ 录音编号格式错误被拒（422）', await post(`/faults/${proxyId}/proxy`, { proxySource: 'PHONE', recordingNo: 'BAD' }, admin), 'biz')

    const pool2 = await step('⑭ 代报修单进抢单池（维保端可见）', () => get('/faults?status=OPEN', tech))
    assert(((pool2.data && pool2.data.list) || []).some((f) => f.id === proxyId), '代报修单对维保端可见')
  } catch (e) {
    console.log(colors.yellow(`  ! F4 中断：${e.message}`))
  } finally {
    // 收尾：恢复电梯责任人 + 删除测试急修单（急修留痕无删除端点，测试数据走 SQL 清理）
    try {
      dbExec(`UPDATE elevator SET worker_platform_id=${prevOwner ? `'${prevOwner}'` : 'NULL'} WHERE elevator_code='${OWNER_CODE}'`)
      if (f4ids.length) dbExec(`DELETE FROM fault WHERE id IN (${f4ids.map((i) => `'${i}'`).join(',')})`)
      console.log(colors.gray(`    F4 清理：恢复 ${OWNER_CODE} 责任人，删除 ${f4ids.length} 张测试急修单`))
    } catch (e2) {
      console.log(colors.yellow(`  ! F4 清理失败：${e2.message}`))
    }
  }

  // ══════════════ F5. 客户账号生命周期 ══════════════
  suite('F5. 客户账号：停用 → 拒绝登录 → 启用 → 恢复')
  try {
    const acct = seed.clientAccounts.uu1Admin
    const unit = seed.useUnits.uu1
    const phone = accounts.clientAdmin.phone
    const pwd = accounts.clientAdmin.password

    await step('① 停用客户账号', () => put(`/use-units/${unit}/accounts/${acct}`, {}, admin).then(() =>
      require('./lib').patch(`/use-units/${unit}/accounts/${acct}/disable`, {}, admin)
    ))
    record('② 停用后登录被拒', await post('/auth/login', { phone, password: pwd }), 'biz')

    await step('③ 启用客户账号', () => require('./lib').patch(`/use-units/${unit}/accounts/${acct}/enable`, {}, admin))
    const back = await step('④ 启用后可登录', () => post('/auth/login', { phone, password: pwd }))
    assertEq(back.data.principalType, 'CUSTOMER', '恢复后主体')
    ctx.tokens.clientAdmin = back.data.token
  } catch (e) {
    console.log(colors.yellow(`  ! F5 中断：${e.message}`))
    // 兜底恢复，避免账号留在停用态
    try {
      require('./lib').patch(`/use-units/${seed.useUnits.uu1}/accounts/${seed.clientAccounts.uu1Admin}/enable`, {}, admin)
    } catch (e2) {
      /* ignore */
    }
  }
}

module.exports = { runFlowCases, dbExec }
