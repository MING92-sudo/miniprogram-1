// Mock 拦截器：按 method + 路径模板匹配 services 层请求，返回统一响应的 data 部分
// 路径模板支持 :id 形式参数；handler 内 return 即业务成功，throw {code,message} 即业务失败
const d = require('./data')
const { formatTime, parseTime, distanceMeters } = require('../utils/util')
const config = require('../config/index')

// mock 无 JWT 解析：登录成功后记住当前演示角色，供自助改密等"当前用户"接口使用
let currentRole = 'WORKER'
// 移交/外接建档 mock 状态（模块级，进程重启即清空）
const mockHandovers = []
const mockFilings = []

// 自动排期触发点：真实后端为定时任务（docs/04 A.10.9），读接口一律不触发派单；
// mock 无调度器，仅在模块加载时执行一次，读接口不得再调用 ensureDueOrders
d.ensureDueOrders()

// 分页（page 从 1 开始，size 默认 20，与接口文档 A.0 约定一致）
function paginate(list, query) {
  const page = Math.max(1, Number(query && query.page) || 1)
  const size = Math.max(1, Number(query && query.size) || 20)
  return {
    list: list.slice((page - 1) * size, page * size),
    total: list.length
  }
}

function findOr404(list, id, name) {
  const item = list.find((x) => x.id === id)
  if (!item) throw { code: 1404, message: (name || '记录') + '不存在' }
  return item
}

// C-12：GB/T 10060—2023 表1 ☆重要项（49 项）与试验项（57—74），与后端 HandoverChecklistTemplate 对齐
const HANDOVER_IMPORTANT = new Set([2, 3, 5, 8, 9, 10, 12, 13, 16, 18, 20, 21, 22, 23, 24, 25, 26, 29, 30, 32, 33, 35, 36, 37, 38, 39, 42, 43, 45, 46, 47, 48, 49, 50, 51, 52, 53, 58, 62, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73])
function buildHandoverChecklist() {
  const items = []
  for (let no = 1; no <= 74; no++) {
    items.push({ id: 'hi_' + no, itemNo: no, itemGroup: 'G' + String(Math.min(13, Math.ceil(no / 6))).padStart(2, '0'),
      itemName: '检查项 ' + no, important: HANDOVER_IMPORTANT.has(no), result: null,
      measuredValue: null, failDesc: null, naReason: null, photoFileIds: null })
  }
  return items
}
// 7.2/7.3 五路径判定（与后端 HandoverChecklistService.judge 同口径）
function judgeHandover(impFail, genFail, cond) {
  if (impFail === 0 && genFail === 0) return 'QUALIFIED'
  if (impFail >= 1) return 'DISQUALIFIED'
  if (genFail > 8) return 'DISQUALIFIED'
  const conditional = cond && cond.siteLimited === true && cond.tripartiteAgreed === true && cond.controlMeasures
  if (genFail >= 1 && genFail <= 3 && conditional) return 'CONDITIONAL'
  return 'RECTIFY'
}

const routes = [
  // ── 鉴权 ──
  // 账号密码登录（用户需求：账号由维保单位系统分配，手机号为账号）
  ['POST', '/auth/login', ({ body }) => {
    const phone = String(body.phone || '').trim()
    const password = String(body.password || '')
    let roleKey = null
    let user = null
    Object.keys(d.db.employees).forEach(function (k) {
      if (d.db.employees[k].account === phone) { roleKey = k; user = d.db.employees[k] }
    })
    if (!user) throw { code: 401, message: '账号不存在，请联系维保单位管理员分配' }
    if (user.password !== password) throw { code: 401, message: '账号或密码错误' }
    currentRole = roleKey
    return { token: 'mock-token-' + Date.now(), userInfo: user, role: roleKey }
  }],
  // 自助改密（docs/04 A.0.1；与后端 AuthController 同口径：校验原密码、新密码 ≥6 位且不同）
  ['POST', '/auth/change-password', ({ body }) => {
    const user = d.db.employees[currentRole] || d.db.employees.WORKER
    const oldPassword = String((body && body.oldPassword) || '')
    const newPassword = String((body && body.newPassword) || '')
    if (!user || user.password !== oldPassword) throw { code: 401, message: '原密码不正确' }
    if (newPassword.length < 6) throw { code: 422, message: '新密码至少 6 位' }
    if (newPassword === oldPassword) throw { code: 422, message: '新密码不能与原密码相同' }
    user.password = newPassword
    return { ok: true }
  }],
  // 登录后绑定微信：wx.login code → 服务端换 openid 并与账号关联（mock 直接返回成功）
  ['POST', '/auth/bind-wechat', ({ body }) => {
    if (!body.code) throw { code: 422, message: '缺少微信 code' }
    return { ok: true, openid: 'mock_openid_' + Date.now() }
  }],
  ['POST', '/auth/wx-login', ({ body }) => {
    const role = d.db.employees[body.role] ? body.role : 'WORKER'
    const user = d.db.employees[role]
    currentRole = role
    return { token: 'mock-token-' + Date.now(), userInfo: user, role }
  }],
  ['POST', '/auth/bind-employee', ({ body }) => {
    const role = body.role === 'LEADER' ? 'LEADER' : 'WORKER'
    return { token: 'mock-token-' + Date.now(), userInfo: d.db.employees[role], role }
  }],
  ['POST', '/auth/bind-use-unit', () => (
    { token: 'mock-token-' + Date.now(), userInfo: d.db.employees.UNIT_ADMIN, role: 'UNIT_ADMIN' }
  )],
  ['POST', '/auth/logout', () => ({ ok: true })],

  // ── 工单 ──
  ['GET', '/home/summary', () => d.getHomeSummary()],
  ['GET', '/elevators', () => d.listElevators()],
  // 扫码回填电梯信息（急修单用，docs/04 A.0.1）
  ['GET', '/elevators/by-code', ({ query }) => {
    const el = d.getElevatorByCode(String((query && query.code) || '').trim())
    if (!el) throw { code: 1404, message: '电梯不存在，请核对编号' }
    if (el.status === 'INACTIVE') throw { code: 422, message: '该电梯已停用，贴梯二维码失效（docs/09 §6.6）' }
    return {
      id: el.id,
      elevatorCode: el.elevatorCode,
      elevatorName: el.elevatorName,
      location: el.location,
      regCode: el.regCode,
      deviceCode: el.deviceCode,
      model: el.model,
      category: el.category
    }
  }],
  ['GET', '/work-orders', ({ query }) => paginate(d.listOrders(query), query)],
  ['GET', '/work-orders/:id', ({ params }) => {
    const order = d.getOrder(params.id)
    if (!order) throw { code: 1404, message: '工单不存在' }
    return order
  }],
  ['GET', '/elevators/:id/profile', ({ params }) => {
    const p = d.getElevatorProfile(params.id)
    if (!p) throw { code: 1404, message: '电梯不存在' }
    return p
  }],
  // 维保人员现场更正项目名称（小区名称，管理端可能写错；docs/04 A.2.1）
  ['PUT', '/elevators/:id/project-name', ({ params, body }) => {
    const el = d.getElevator(params.id)
    if (!el) throw { code: 1404, message: '电梯不存在' }
    const v = String((body && body.projectName) || '').trim()
    if (!v) throw { code: 422, message: '项目名称不能为空' }
    el.projectName = v
    el.elevatorName = v // 电梯名称 = 项目名称（小区），合并
    return { ok: true }
  }],
  // 维保人员现场维护设备本地信息（docs/04 A.2.1；不开放经纬度/使用单位负责人）
  ['PUT', '/elevators/:id/local', ({ params, body }) => {
    const el = d.getElevator(params.id)
    if (!el) throw { code: 1404, message: '电梯不存在' }
    const fields = ['brand', 'manufacturer', 'productNo', 'driveMode',
      'ratedLoad', 'ratedLoadUnit', 'ratedSpeed', 'ratedSpeedUnit', 'stationsDoors', 'nextCheckDate', 'nextMaintenanceDate']
    let any = false
    fields.forEach(function (f) {
      if (body && body[f] !== undefined) {
        el[f] = body[f]
        any = true
      }
    })
    // 安装地址：profile 返回 local.address，落库字段为 location
    if (body && body.address !== undefined) {
      el.location = String(body.address || '')
      any = true
    }
    // 项目名称：空校验 + 同步电梯名称（电梯名称 = 项目名称，合并）
    if (body && body.projectName !== undefined) {
      const v = String(body.projectName || '').trim()
      if (!v) throw { code: 422, message: '项目名称不能为空' }
      el.projectName = v
      el.elevatorName = v
      any = true
    }
    if (!any) throw { code: 422, message: '无可更新的设备信息' }
    return { ok: true }
  }],
  // Minor7：只读查询改 GET（与后端 2026-10-05 整改一致）
  ['GET', '/work-orders/resolve-by-elevator', ({ query }) => {
    const el = d.getElevatorByCode(query.elevatorCode)
    if (!el) throw { code: 1404, message: '未识别的电梯二维码' }
    const order = d.db.orders.find((o) => o.elevatorId === el.id && o.status !== 'DONE')
    if (!order) throw { code: 1404, message: '该电梯暂无进行中的工单' }
    return d.getOrder(order.id)
  }],
  ['POST', '/work-orders/:id/checkin', ({ params, body }) => {
    const o = findOr404(d.db.orders, params.id, '工单')
    if (o.status === 'DONE') throw { code: 1003, message: '当前状态不允许签到' }
    const assistant = body.role === 'ASSISTANT'
    // 主维保与配合人员分别签到，各自一条留痕（docs/01 §3.7.2）
    if (assistant ? o.assistantCheckinExtra : o.checkinExtra) {
      throw { code: 1003, message: (assistant ? '配合人员' : '主维保人员') + '已签到，不可重复签到' }
    }
    const lat = Number(body.latitude)
    const lng = Number(body.longitude)
    if (!isFinite(lat) || !isFinite(lng)) {
      throw { code: 422, message: '签到必须携带定位坐标；定位失败请重试（严禁伪造坐标）' }
    }
    // 配合人员须携带主维保生成、未过期且未使用的动态码（一次性消费）
    if (assistant) d.consumeDynamicCode(o, body.dynamicCode)
    // 定位校验：与后端同口径计算距离；mock 只回显不拦截，拦截口径以后端为准（docs/04 A.0.1 说明）
    const el = d.getElevator(o.elevatorId)
    const threshold = (el && el.checkinThreshold) || 200
    const raw = (el && el.lat && el.lng) ? distanceMeters(lat, lng, el.lat, el.lng) : -1
    const distance = raw < 0 ? -1 : Math.round(raw * 10) / 10
    const geoStatus = distance < 0 ? 'UNKNOWN' : 'PROVIDED'
    d.markCheckin(o.id, body, { distance: distance, threshold: threshold, geoStatus: geoStatus })
    return {
      checkinId: 'chk_' + Date.now(),
      role: assistant ? 'ASSISTANT' : 'PRINCIPAL',
      distance: distance < 0 ? null : distance,
      threshold: distance < 0 ? null : threshold,
      passed: true,
      geoStatus: geoStatus,
      geoBackfilled: false
    }
  }],
  // 主维保生成双人动态码（每 5 秒刷新、60 秒有效，docs/03 §3.2 项6）
  ['POST', '/work-orders/:id/dynamic-code', ({ params }) => {
    const o = findOr404(d.db.orders, params.id, '工单')
    if (o.status === 'DONE') throw { code: 1003, message: '当前状态不允许生成动态码' }
    return d.issueDynamicCode(o.id)
  }],
  ['POST', '/work-orders/:id/dynamic-code/verify', ({ params, body }) => {
    const o = findOr404(d.db.orders, params.id, '工单')
    d.requireDynamicCode(o, body.code)
    return { ok: true }
  }],
  ['GET', '/work-orders/:id/checklist', ({ params }) => {
    const o = findOr404(d.db.orders, params.id, '工单')
    const el = d.getElevator(o.elevatorId) || {}
    return {
      checklistId: o.id,
      workTypeCode: o.workTypeCode || '',
      workType: o.workType || '',
      elevatorName: el.elevatorName || '',
      projectName: el.projectName || '',
      items: o.checklist
    }
  }],
  ['POST', '/work-orders/:id/checklist/:itemId', ({ params, body }) => {
    const o = findOr404(d.db.orders, params.id, '工单')
    const item = o.checklist.find((i) => i.id === params.itemId)
    if (!item) throw { code: 1404, message: '检查项不存在' }
    // 服务端校验（docs/04 A.2）
    if (body.result === 'NA' && !body.skipReason) {
      throw { code: 422, message: '「' + item.name + '」标记不适用时必须填写跳过原因（TSG 注 A-1）' }
    }
    if (body.result === 'ABNORMAL' && !body.abnormalDesc) {
      throw { code: 422, message: '「' + item.name + '」为异常时必须填写异常描述' }
    }
    if (body.result === 'ABNORMAL' && !(body.photoFileIds || []).length && !(body.photoUrls || []).length) {
      throw { code: 422, message: '「' + item.name + '」为异常时必须至少附 1 张照片' }
    }
    // 异常项必须显式记录隐患判定（S0—S7）；S0 表示该项异常但不构成严重事故隐患
    if (body.result === 'ABNORMAL') {
      const pc = String(body.problemCode == null ? '' : body.problemCode).trim()
      if (!pc) {
        throw { code: 422, message: '「' + item.name + '」为异常时必须记录隐患判定（S0—S7），不得留空' }
      }
      if (['S0', 'S1', 'S2', 'S3', 'S4', 'S5', 'S6', 'S7'].indexOf(pc) < 0) {
        throw { code: 422, message: '「' + item.name + '」隐患码非法：' + pc + '（取值范围 S0—S7）' }
      }
    }
    // 关键项（试验/测试/校验/检测类，TSG 注A-2）执行时须照片留证；
    // 结果为"不适用"（NA，如该电梯无此部件）时豁免——部件不存在无从拍照（docs/08 BUG 修复）
    // ⚠ 必须用 body.result（本次选择），item.result 是旧状态：新填项为空串，恒 !== 'NA'，导致 BUG 复现
    if (item.isKey && item.photoRequired && body.result !== 'NA' &&
        !(body.photoFileIds || []).length && !(body.photoUrls || []).length) {
      throw { code: 422, message: '关键项「' + item.name + '」为试验/测试/校验/检测类，必须至少附 1 张照片留证（TSG 注A-2）' }
    }
    const updated = d.updateChecklistItem(params.id, params.itemId, {
      result: body.result,
      value: body.value != null ? Number(body.value) : null,
      valueText: body.valueText || '',
      abnormalDesc: body.abnormalDesc || '',
      skipReason: body.skipReason || '',
      problemCode: body.problemCode || '',
      photos: body.photoUrls || [], // mock 演示回显（本地路径）；真实后端只落 photoFileIds
      photoFileIds: body.photoFileIds || [],
      recordedAt: body.recordedAt || formatTime()
    })
    if (!updated) throw { code: 1404, message: '检查项不存在' }
    return { ok: true, itemId: params.itemId }
  }],
  // 周期性条目"本次仍要执行"（docs/03 §6.1：灰显不阻断，允许人工点选执行）
  ['POST', '/work-orders/:id/checklist/:itemId/run-this-time', ({ params }) => {
    const o = findOr404(d.db.orders, params.id, '工单')
    const item = o.checklist.find((i) => i.id === params.itemId)
    if (!item) throw { code: 1404, message: '检查项不存在' }
    item.notInThisRun = false
    return { ok: true }
  }],
  ['POST', '/work-orders/:id/signature', ({ params, body }) => {
    const o = findOr404(d.db.orders, params.id, '工单')
    if (o.status !== 'PROCESSING') throw { code: 1003, message: '当前状态不允许签字（请先完成签到）' }
    const role = body.role === 'ASSISTANT' ? 'ASSISTANT' : 'PRINCIPAL'
    if (role === 'ASSISTANT') {
      o.assistantSignatureFileId = body.signatureFileId || ''
      o.assistantSignatureUrl = body.signatureUrl || ''
    } else {
      o.workerSignatureFileId = body.signatureFileId || ''
      o.workerSignatureUrl = body.signatureUrl || ''
    }
    return { ok: true, role }
  }],
  ['POST', '/work-orders/:id/checkout', ({ params, body }) => {
    const o = findOr404(d.db.orders, params.id, '工单')
    if (o.status !== 'PROCESSING') throw { code: 1003, message: '请先完成签到' }
    // 签字已在各自手机完成（docs/01 §3.7.5）：签退前校验双签名齐全（后端同口径）
    if (!o.workerSignatureFileId) throw { code: 422, message: '请完成主维保签字' }
    if (o.assistantName && o.assistantName !== '无' && !o.assistantSignatureFileId) {
      throw { code: 422, message: '请完成配合人员签字（双人签字）' }
    }
    // 周期性"本次无需执行"条目不计入未完成项（docs/03 §7 验收）
    const mustRun = o.checklist.filter((i) => !i.notInThisRun)
    const unfinished = mustRun.filter((i) => !i.result).length
    if (unfinished > 0) throw { code: 1003, message: '还有 ' + unfinished + ' 项检查未填写' }
    // 关键项照片留证校验（TSG 注A-2）
    // "不适用"（NA）豁免：部件不存在的关键项无须照片（docs/08 BUG 修复）
    const keyNoPhoto = mustRun.filter(
      (i) => i.isKey && i.photoRequired && i.result && i.result !== 'NA' &&
        !((i.photoFileIds || []).length || (i.photos || []).length)
    )
    if (keyNoPhoto.length) {
      throw { code: 422, message: '关键项「' + keyNoPhoto[0].name + '」须至少附 1 张照片留证（TSG 注A-2），无法签退' }
    }
    // 兜底防御：异常项缺隐患判定时拒绝签退，不得静默报 S0（与后端同一口径）
    const abnormalUncoded = mustRun.filter((i) => i.result === 'ABNORMAL' && !i.problemCode)
    if (abnormalUncoded.length) {
      throw { code: 1003, message: '检查项「' + abnormalUncoded[0].name + '」为异常但未记录隐患判定，无法签退' }
    }
    // 业务规则：签到—签退间隔不少于 N 分钟（config.minWorkDurationMinutes，业主补充规则）
    const minutes = (Date.now() - parseTime(o.checkinTime)) / 60000
    if (minutes < config.minWorkDurationMinutes) {
      throw {
        code: 422,
        message: '作业时长不足 ' + config.minWorkDurationMinutes + ' 分钟（当前 ' + Math.floor(minutes) + ' 分钟），请继续作业后再签退'
      }
    }
    const r = d.markCheckout(o.id, body)
    // 响应对齐 docs/04 A.2 checkout
    return {
      workOrderId: o.id,
      duration: r.duration,
      originalRecordId: r.originalRecordId,
      reportStatus: r.reportStatus,
      // 签名确认链接参数（用户需求：完成后生成链接，安全管理员远程签字或本机代签）
      recordId: r.id,
      shareToken: r.shareToken
    }
  }],

  // ── 平台写链路（P3）：手动重报 2.6，仅 FAILED 记录（AGENTS §2.3 不自动重试）──
  ['POST', '/platform/records/:id/reupload', ({ params }) => {
    const rec = d.reuploadRecord(params.id)
    if (!rec) throw { code: 1404, message: '维保记录不存在' }
    return { ok: true, id: rec.id, reportStatus: rec.reportStatus }
  }],

  // ── 救援 ──
  ['POST', '/rescues', ({ body }) => d.createRescue(body)],
  ['GET', '/rescues', ({ query }) => paginate(d.db.rescues, query)],
  ['GET', '/rescues/:id', ({ params }) => findOr404(d.db.rescues, params.id, '救援记录')],

  // ── 故障 ──
  ['POST', '/faults', ({ body }) => d.createFault(body)],
  ['GET', '/faults', ({ query }) => {
    let list = d.db.faults
    if (query && query.status) {
      list = list.filter((f) => f.status === query.status)
    }
    return paginate(list, query)
  }],
  ['GET', '/faults/:id', ({ params }) => findOr404(d.db.faults, params.id, '故障记录')],
  ['POST', '/faults/:id/close', ({ params, body }) => {
    const f = d.closeFault(params.id, body)
    if (!f) throw { code: 1404, message: '故障记录不存在' }
    return { ok: true }
  }],
  ['POST', '/admin/faults/:id/dispatch', ({ params, body }) =>
    d.dispatchFault(params.id, body && body.workerId)],

  // ── 合规台账（TSG 法定项，数据暂存本地不上报平台）──
  ['GET', '/drills', () => d.listDrills()],
  ['POST', '/drills', ({ body }) => d.createDrill(body)],
  ['GET', '/inspects', () => d.listInspects()],
  ['GET', '/inspects/template', ({ query }) => {
    // 自行检查项 = 该电梯品种对应附件的年度维保项并集；不同品种分别取 A/B/C/D（docs/01 §3.17）
    const el = query && query.elevatorId ? d.getElevator(query.elevatorId) : null
    const appendix = d.CATEGORY_APPENDIX[el && el.category] || 'A'
    const items = []
    d.FREQ_CHAIN.OY.forEach(function (freq) {
      d.APPENDIX_TPLS[appendix][freq].forEach(function (it) {
        items.push(it)
      })
    })
    return { appendix: appendix, items: items }
  }],
  ['POST', '/inspects', ({ body }) => d.createInspect(body)],

  // ── 消息 ──
  ['GET', '/messages', ({ query }) => {
    const list = d.db.messages.slice().sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1))
    return paginate(list, query)
  }],
  ['GET', '/messages/unread-count', () => (
    { count: d.db.messages.filter((m) => !m.read).length }
  )],
  ['POST', '/messages/:id/read', ({ params }) => {
    const m = findOr404(d.db.messages, params.id, '消息')
    m.read = true
    return { ok: true }
  }],
  ['POST', '/messages/read-all', () => {
    d.db.messages.forEach((m) => { m.read = true })
    return { ok: true }
  }],

  // ── 使用单位确认 ──
  ['GET', '/unit/records/pending', ({ query }) => (
    paginate(d.db.unitRecords.filter((r) => r.confirmStatus === 'PENDING'), query)
  )],
  ['GET', '/unit/records/:id', ({ params }) => findOr404(d.db.unitRecords, params.id, '维保记录')],
  ['POST', '/unit/records/:id/confirm', ({ params, body }) => {
    const r = findOr404(d.db.unitRecords, params.id, '维保记录')
    r.confirmStatus = 'CONFIRMED'
    r.satisfaction = body.satisfaction || 0
    r.signatureFileId = body.signatureFileId || ''
    r.signatureUrl = body.signatureUrl || '' // mock 演示回显
    return { ok: true }
  }],
  // 签名链接确认（用户需求：无需登录，凭一次性令牌远程签字或本机代签）
  ['GET', '/unit/records/:id/sign-view', ({ params, query }) => {
    const r = findOr404(d.db.unitRecords, params.id, '维保记录')
    if (!query.token || query.token !== r.shareToken) {
      throw { code: 401, message: '确认链接无效或已失效' }
    }
    return {
      confirmed: r.confirmStatus === 'CONFIRMED',
      elevatorName: r.elevatorName || '',
      elevatorCode: r.elevatorCode || '',
      workType: r.workType || '',
      workerName: r.workerName || '',
      assistantName: r.assistantName || '',
      checkinTime: r.checkinTime || '',
      checkoutTime: r.checkoutTime || '',
      duration: r.duration || '',
      itemTotal: (r.items || []).length,
      photoCount: (r.photos || []).length,
      satisfaction: r.satisfaction,
      signatureUrl: r.signatureUrl || ''
    }
  }],
  ['POST', '/unit/records/:id/confirm-by-token', ({ params, query, body }) => {
    const r = findOr404(d.db.unitRecords, params.id, '维保记录')
    if (!query.token || query.token !== r.shareToken) {
      throw { code: 401, message: '确认链接无效或已失效' }
    }
    if (r.confirmStatus === 'CONFIRMED') {
      return { ok: true, already: true }
    }
    if (!body.signatureUrl && !body.signatureFileId) {
      throw { code: 422, message: '请先完成签名' }
    }
    r.confirmStatus = 'CONFIRMED'
    r.satisfaction = Number(body.satisfaction) || 0
    r.signatureFileId = body.signatureFileId || ''
    r.signatureUrl = body.signatureUrl || ''
    return { ok: true }
  }],

  // ── 知识库 ──
  ['GET', '/knowledge', ({ query }) => {
    let list = d.db.knowledge
    if (query && query.keyword) {
      const k = String(query.keyword).toLowerCase()
      list = list.filter((x) => x.title.toLowerCase().indexOf(k) > -1)
    }
    return { list, total: list.length }
  }],

  // ── 文件（真实上传走 wx.uploadFile，此路由兜底）──
  ['POST', '/files/upload', () => ({ fileId: 'mock_file_' + Date.now(), url: '' })],
  // ── 移交单 / 外接建档 / 使用单位门户 / 维保日历（Task 12/13/14 最小 mock）──
  ['GET', '/handovers', ({ query }) => paginate(mockHandovers.filter((h) => !query.status || h.status === query.status), query)],
  ['POST', '/handovers', ({ body }) => {
    const o = Object.assign({ id: 'ho_' + Date.now(), handoverNo: 'HO' + Date.now(), status: 'DOC_SUBMITTING',
      createdAt: formatTime(new Date()), checks: [], rectifications: [], signs: [] }, body)
    mockHandovers.unshift(o)
    return o
  }],
  ['GET', '/handovers/:id', ({ params }) => findOr404(mockHandovers, params.id, '移交单')],
  ['POST', '/handovers/:id/submit-docs', ({ params, body }) => {
    const o = findOr404(mockHandovers, params.id, '移交单')
    Object.assign(o, body, { status: 'INSPECTING' })
    return o
  }],
  ['POST', '/handovers/:id/inspect', ({ params, body }) => {
    const o = findOr404(mockHandovers, params.id, '移交单')
    const items = (body.items || []).map((it, i) => Object.assign({ id: 'hi_' + i }, it))
    o.checks = [{ id: 'hc_' + Date.now(), checkerName: body.checkerName, checkedAt: formatTime(new Date()), items }].concat(o.checks || [])
    const force = items.some((it) => (it.itemType === 'SAFETY_DEVICE' || it.itemType === 'BRAKE') && it.result === 'FAIL')
    o.status = force ? 'RECTIFYING' : 'PENDING_CONFIRM'
    return o
  }],
  ['POST', '/handovers/:id/rectify', ({ params, body }) => {
    const o = findOr404(mockHandovers, params.id, '移交单')
    o.rectifications = (o.rectifications || []).concat((body.rectifications || []).map((r, i) => Object.assign({ id: 'hr_' + Date.now() + i }, r)))
    return o
  }],
  ['POST', '/handovers/:id/recheck-rectify', ({ params, body }) => {
    const o = findOr404(mockHandovers, params.id, '移交单')
    o.rectifications = (o.rectifications || []).map((r) => Object.assign({}, r, { recheckResult: body.result }))
    o.status = body.result === 'PASS' ? 'PENDING_CONFIRM' : 'RECTIFYING'
    return o
  }],
  ['POST', '/handovers/:id/confirm', ({ params, body }) => {
    const o = findOr404(mockHandovers, params.id, '移交单')
    Object.assign(o, body, { status: 'ARCHIVED', confirmedAt: formatTime(new Date()) })
    return o
  }],
  // ── C-12：74 项移交检查清单 / 整改清单 / 归档目录（docs/15、docs/04 A.9）──
  ['GET', '/handovers/:id/checklist', ({ params }) => {
    const o = findOr404(mockHandovers, params.id, '移交单')
    if (!o.checklist74) o.checklist74 = buildHandoverChecklist()
    return { check: { id: 'hc_' + o.id, handoverId: o.id, totalItems: 74, conclusion: o.checkConclusion || null,
      passCount: (o.checkCounts && o.checkCounts.pass) || 0, failCount: (o.checkCounts && o.checkCounts.fail) || 0,
      naCount: (o.checkCounts && o.checkCounts.na) || 0, importantFailCount: (o.checkCounts && o.checkCounts.impFail) || 0 }, items: o.checklist74 }
  }],
  ['POST', '/handovers/:id/checklist/submit', ({ params, body }) => {
    const o = findOr404(mockHandovers, params.id, '移交单')
    if (!o.checklist74) o.checklist74 = buildHandoverChecklist()
    const sub = {}
    ;(body.items || []).forEach((s) => { sub[s.itemNo] = s })
    let pass = 0, fail = 0, na = 0, impFail = 0
    const fails = []
    o.checklist74.forEach((it) => {
      const s = sub[it.itemNo] || {}
      if (!s.result) throw { code: 422, message: '第 ' + it.itemNo + ' 项未填结果' }
      it.result = s.result
      it.measuredValue = s.measuredValue || null
      it.failDesc = s.failDesc || null
      it.naReason = s.naReason || null
      it.photoFileIds = s.photoFileIds || null
      if (it.result === 'FAIL') {
        if (!it.failDesc) throw { code: 422, message: '第 ' + it.itemNo + ' 项不合格须填描述' }
        if ((it.important || it.itemNo >= 57) && !it.photoFileIds) throw { code: 422, message: '第 ' + it.itemNo + ' 项不合格须附照片' }
        fail++; if (it.important) impFail++; fails.push(it)
      } else if (it.result === 'NA') {
        if (!it.naReason) throw { code: 422, message: '第 ' + it.itemNo + ' 项不适用须选原因码' }
        na++
      } else if (it.result === 'PASS') {
        if (it.itemNo >= 57 && !it.measuredValue) throw { code: 422, message: '第 ' + it.itemNo + ' 项试验须填实测值' }
        pass++
      } else throw { code: 422, message: '第 ' + it.itemNo + ' 项结果须 PASS/FAIL/NA' }
    })
    const conclusion = judgeHandover(impFail, fail - impFail, body.conditional)
    o.checkConclusion = conclusion
    o.checkCounts = { pass, fail, na, impFail }
    if (conclusion === 'RECTIFY' || conclusion === 'DISQUALIFIED') {
      o.rectifications = fails.map((f, i) => ({ id: 'hr_' + Date.now() + i, handoverId: o.id, itemNo: f.itemNo,
        itemType: f.itemGroup, respParty: '安装单位', source: 'INSPECT', voided: false,
        beforePhotoFileId: f.photoFileIds, recheckResult: null }))
      o.status = 'RECTIFYING'
    } else {
      o.status = 'PENDING_CONFIRM'
    }
    return o
  }],
  ['GET', '/handovers/:id/rectifications', ({ params }) => {
    const o = findOr404(mockHandovers, params.id, '移交单')
    return (o.rectifications || []).slice().sort((a, b) => (a.itemNo || 0) - (b.itemNo || 0))
  }],
  ['POST', '/handovers/:id/rectifications/:rectId/rectify', ({ params, body }) => {
    const o = findOr404(mockHandovers, params.id, '移交单')
    const r = (o.rectifications || []).find((x) => x.id === params.rectId)
    if (!r) throw { code: 1404, message: '整改记录不存在' }
    Object.assign(r, { rectMeasure: body.rectMeasure, afterPhotoFileId: body.afterPhotoFileId,
      rectifierName: body.rectifierName, rectifiedAt: formatTime(new Date()) })
    return r
  }],
  ['POST', '/handovers/:id/rectifications/:rectId/recheck', ({ params, body }) => {
    const o = findOr404(mockHandovers, params.id, '移交单')
    const r = (o.rectifications || []).find((x) => x.id === params.rectId)
    if (!r) throw { code: 1404, message: '整改记录不存在' }
    Object.assign(r, { recheckResult: body.result, recheckerId: body.recheckerId,
      recheckerName: body.recheckerName, recheckedAt: formatTime(new Date()) })
    const all = (o.rectifications || []).filter((x) => !x.voided)
    const allPass = all.length > 0 && all.every((x) => x.recheckResult === 'PASS')
    o.status = allPass ? 'PENDING_CONFIRM' : 'RECTIFYING'
    return o
  }],
  ['POST', '/handovers/:id/na-approve', ({ params, body }) => {
    const o = findOr404(mockHandovers, params.id, '移交单')
    const s = { id: 'hs_' + Date.now(), handoverId: o.id, nodeType: 'NA_APPROVE',
      actorId: body.approverId, actorName: body.approverName, action: 'APPROVE', at: formatTime(new Date()) }
    o.signs = (o.signs || []).concat(s)
    return s
  }],
  ['GET', '/handovers/:id/export-pdf', ({ params, query }) => {
    findOr404(mockHandovers, params.id, '移交单')
    return { form: (query && query.form) || 'CHECK', fileUrl: '', note: 'mock 不生成真实 PDF，真实端点返回二进制 application/pdf' }
  }],
  ['GET', '/elevators/:id/archive', ({ params }) => {
    const cats = [['01', 'CHECKLIST'], ['02', 'RECTIFICATION'], ['03', 'DOCS'], ['04', 'PHOTOS'],
      ['05', 'CONFIRMATION'], ['06', 'FIRST_ORDER'], ['07', 'PENDING_DOCS']]
    return cats.map((c, i) => ({ id: 'ha_' + params.id + '_' + c[0], handoverId: params.id,
      category: c[1], seq: i + 1, sha256: 'mock_sha256_' + c[0], archivedAt: formatTime(new Date()) }))
  }],
  ['GET', '/filings', ({ query }) => paginate(mockFilings.filter((f) => !query.status || f.status === query.status), query)],
  ['POST', '/filings', ({ body }) => {
    const o = Object.assign({ id: 'ef_' + Date.now(), filingNo: 'EF' + Date.now(), status: 'DOC_COLLECTING',
      createdAt: formatTime(new Date()), docs: [], takeoverChecks: [], confirmation: null }, body)
    mockFilings.unshift(o)
    return o
  }],
  ['GET', '/filings/:id', ({ params }) => findOr404(mockFilings, params.id, '外接建档单')],
  ['POST', '/filings/:id/submit-docs', ({ params, body }) => {
    const o = findOr404(mockFilings, params.id, '外接建档单')
    o.docs = (body.docs || []).map((x, i) => Object.assign({ id: 'fd_' + i }, x))
    o.status = 'TAKEOVER_CHECKING'
    return o
  }],
  ['POST', '/filings/:id/takeover-check', ({ params, body }) => {
    const o = findOr404(mockFilings, params.id, '外接建档单')
    o.takeoverChecks = [{ id: 'tc_' + Date.now(), checkerName: body.checkerName, checkedAt: formatTime(new Date()), items: (body.items || []).map((it, i) => Object.assign({ id: 'ti_' + i }, it)) }]
    o.status = 'PENDING_CONFIRM'
    return o
  }],
  ['POST', '/filings/:id/confirm', ({ params, body }) => {
    const o = findOr404(mockFilings, params.id, '外接建档单')
    o.confirmation = Object.assign({ id: 'hcn_' + Date.now(), at: formatTime(new Date()) }, body)
    o.status = 'ARCHIVED'
    return o
  }],
  ['POST', '/unit/accounts', () => ({ account: 'U' + Date.now(), initialPassword: 'Mock12345678', inviteCode: 'MOCK2345' })],
  ['POST', '/unit/login', ({ body }) => {
    if (!body.account || !body.password) throw { code: 422, message: '请填写账号与密码' }
    const unit = (d.db.useUnits || [])[0] || { id: 'uu_1', unitName: '演示使用单位' }
    return { token: 'mock-unit-token', account: body.account, useUnitId: unit.id, unitName: unit.unitName, role: 'UNIT_ADMIN', wechatBound: false }
  }],
  ['POST', '/unit/bind-wechat', () => ({ bound: true })],
  ['POST', '/unit/login-by-wechat', () => {
    const unit = (d.db.useUnits || [])[0] || { id: 'uu_1', unitName: '演示使用单位' }
    return { token: 'mock-unit-token', useUnitId: unit.id, unitName: unit.unitName, role: 'UNIT_ADMIN', wechatBound: true }
  }],
  ['POST', '/unit/account/password', ({ body }) => {
    if (!body.oldPassword) throw { code: 422, message: '原口令不正确' }
    if (!body.newPassword || String(body.newPassword).length < 6) throw { code: 422, message: '新口令至少 6 位' }
    return { ok: true }
  }],
  ['GET', '/unit/elevators', () => {
    const unit = (d.db.useUnits || [])[0] || {}
    return (d.db.elevators || []).filter((el) => el.useUnitId === unit.id && el.status !== 'INACTIVE').map((el) => ({
      id: el.id, elevatorCode: el.elevatorCode, elevatorName: el.elevatorName, projectName: el.projectName,
      location: el.location, insideNumber: el.insideNumber, regCode: el.regCode, deviceCode: el.deviceCode
    }))
  }],
  ['GET', '/unit/records/list', () => (d.db.records || []).slice(0, 50).map((r) => ({
    id: r.id, elevatorCode: r.elevatorCode, elevatorName: r.elevatorName, workType: r.workType,
    workerName: r.workerName, checkinTime: r.checkinTime, checkoutTime: r.checkoutTime,
    duration: r.duration, confirmStatus: r.confirmStatus || 'PENDING' }))],
  ['GET', '/unit/pending-signs', () => (d.db.records || []).filter((r) => r.confirmStatus !== 'CONFIRMED').slice(0, 50).map((r) => ({
    id: r.id, elevatorCode: r.elevatorCode, elevatorName: r.elevatorName, checkoutTime: r.checkoutTime,
    confirmStatus: r.confirmStatus || 'PENDING' }))],
  ['GET', '/unit/faults', () => (d.db.faults || []).slice(0, 50)],
  ['POST', '/unit/faults', ({ body }) => d.createFault(body)],
  ['GET', '/maintenance-calendar', ({ query }) => ({ from: query.from || '', to: query.to || '', entries: [] })]
]

// ── 路径匹配 ──

function matchPath(pattern, path) {
  const ps = pattern.split('/').filter(Boolean)
  const xs = path.split('/').filter(Boolean)
  if (ps.length !== xs.length) return null
  const params = {}
  for (let i = 0; i < ps.length; i++) {
    if (ps[i].charAt(0) === ':') {
      params[ps[i].slice(1)] = decodeURIComponent(xs[i])
    } else if (ps[i] !== xs[i]) {
      return null
    }
  }
  return params
}

function parseQuery(queryStr) {
  const query = {}
  if (!queryStr) return query
  queryStr.split('&').forEach((kv) => {
    const idx = kv.indexOf('=')
    if (idx > -1) query[kv.slice(0, idx)] = decodeURIComponent(kv.slice(idx + 1))
  })
  return query
}

function mockRequest(options) {
  const method = String(options.method || 'GET').toUpperCase()
  const raw = String(options.url || '')
  const qIdx = raw.indexOf('?')
  const path = qIdx > -1 ? raw.slice(0, qIdx) : raw
  // 修复：GET 的筛选参数在 options.data（wx.request 约定），必须与 URL 查询串合并，
  // 否则 status/due/keyword 等永远到不了 listOrders（BUG：状态筛选不过滤）
  const query = Object.assign(
    {},
    (options.data && typeof options.data === 'object') ? options.data : {},
    parseQuery(qIdx > -1 ? raw.slice(qIdx + 1) : '')
  )

  return new Promise((resolve, reject) => {
    // 模拟 150ms 网络延迟，便于观察 loading 态
    setTimeout(() => {
      for (let i = 0; i < routes.length; i++) {
        const r = routes[i]
        if (r[0] !== method) continue
        const params = matchPath(r[1], path)
        if (!params) continue
        try {
          resolve(r[2]({ params, query, body: options.data || {} }))
        } catch (e) {
          reject(e && e.code ? e : { code: 500, message: (e && e.message) || 'Mock 内部错误' })
        }
        return
      }
      reject({ code: 404, message: 'Mock 接口未实现: ' + method + ' ' + path })
    }, 150)
  })
}

module.exports = { mockRequest }
