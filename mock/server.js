// Mock 拦截器：按 method + 路径模板匹配 services 层请求，返回统一响应的 data 部分
// 路径模板支持 :id 形式参数；handler 内 return 即业务成功，throw {code,message} 即业务失败
const d = require('./data')
const { formatTime, parseTime } = require('../utils/util')
const config = require('../config/index')

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
    return { token: 'mock-token-' + Date.now(), userInfo: user, role: roleKey }
  }],
  // 登录后绑定微信：wx.login code → 服务端换 openid 并与账号关联（mock 直接返回成功）
  ['POST', '/auth/bind-wechat', ({ body }) => {
    if (!body.code) throw { code: 422, message: '缺少微信 code' }
    return { ok: true, openid: 'mock_openid_' + Date.now() }
  }],
  ['POST', '/auth/wx-login', ({ body }) => {
    const role = d.db.employees[body.role] ? body.role : 'WORKER'
    const user = d.db.employees[role]
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
  ['POST', '/work-orders/resolve-by-elevator', ({ body }) => {
    const el = d.getElevatorByCode(body.elevatorCode)
    if (!el) throw { code: 1404, message: '未识别的电梯二维码' }
    const order = d.db.orders.find((o) => o.elevatorId === el.id && o.status !== 'DONE')
    if (!order) throw { code: 1404, message: '该电梯暂无进行中的工单' }
    return d.getOrder(order.id)
  }],
  // 签到取证令牌（拍照前调用）：服务端做地理围栏校验并签发令牌
  ['POST', '/work-orders/:id/evidence', ({ params, body }) => {
    const o = findOr404(d.db.orders, params.id, '工单')
    if (o.status !== 'PENDING') throw { code: 1003, message: '当前状态不允许签到' }
    const el = d.getElevator(o.elevatorId)
    const elLocated = !!(el && typeof el.lat === 'number' && typeof el.lng === 'number')
    const lat = Number(body.latitude)
    const lng = Number(body.longitude)
    const clientLocated = !isNaN(lat) && !isNaN(lng)
    if (!elLocated) {
      return d.issueEvidence(params.id, 0, 0, '', d.CHECKIN_DISTANCE_LIMIT_M)
    }
    if (!clientLocated) {
      throw {
        code: 1001,
        message: '未获取到定位，无法验证作业地点，请开启定位后重试',
        data: { distance: '', threshold: d.CHECKIN_DISTANCE_LIMIT_M, appealable: true }
      }
    }
    const distance = d.distanceMeters(lat, lng, el.lat, el.lng)
    if (distance > d.CHECKIN_DISTANCE_LIMIT_M) {
      throw {
        code: 1001,
        message: '签到位置超出允许范围（' + Math.round(distance) + ' 米 > ' +
          d.CHECKIN_DISTANCE_LIMIT_M + ' 米），请提交申诉',
        data: { distance: Math.round(distance), threshold: d.CHECKIN_DISTANCE_LIMIT_M, appealable: true }
      }
    }
    return d.issueEvidence(params.id, lat, lng, String(Math.round(distance)), d.CHECKIN_DISTANCE_LIMIT_M)
  }],
  // 检查项拍照取证令牌（拍照前调用）：绑定工单+检查项，工单须已签到
  ['POST', '/work-orders/:id/evidence/shot', ({ params, body }) => {
    const o = findOr404(d.db.orders, params.id, '工单')
    if (o.status !== 'PROCESSING') throw { code: 1003, message: '请先完成签到后再拍摄检查项照片' }
    const itemId = String(body.itemId == null ? '' : body.itemId)
    if (!itemId) throw { code: 422, message: '缺少检查项 id' }
    if (!(o.checklist || []).some((i) => i.id === itemId)) {
      throw { code: 1404, message: '检查项不存在' }
    }
    const el = d.getElevator(o.elevatorId)
    const elLocated = !!(el && typeof el.lat === 'number' && typeof el.lng === 'number')
    const lat = Number(body.latitude)
    const lng = Number(body.longitude)
    if (elLocated && (isNaN(lat) || isNaN(lng))) {
      throw {
        code: 1001,
        message: '未获取到定位，无法验证作业地点，请开启定位后重试',
        data: { distance: '', threshold: d.CHECKIN_DISTANCE_LIMIT_M, appealable: true }
      }
    }
    let distanceText = ''
    if (elLocated) {
      const distance = d.distanceMeters(lat, lng, el.lat, el.lng)
      if (distance > d.CHECKIN_DISTANCE_LIMIT_M) {
        throw {
          code: 1001,
          message: '拍摄位置超出允许范围（' + Math.round(distance) + ' 米 > ' +
            d.CHECKIN_DISTANCE_LIMIT_M + ' 米），请提交申诉',
          data: { distance: Math.round(distance), threshold: d.CHECKIN_DISTANCE_LIMIT_M, appealable: true }
        }
      }
      distanceText = String(Math.round(distance))
    }
    return d.issueEvidence(params.id, elLocated ? lat : 0, elLocated ? lng : 0, distanceText,
      d.CHECKIN_DISTANCE_LIMIT_M, itemId, d.EVIDENCE_TTL_SHOT_MS)
  }],
  // 签名取证令牌（签署前调用）：绑定工单 + 签名角色 + 服务端时间
  ['POST', '/work-orders/:id/evidence/sign', ({ params, body }) => {
    const o = findOr404(d.db.orders, params.id, '工单')
    if (o.status !== 'PROCESSING') throw { code: 1003, message: '请先完成签到后再签署' }
    const role = body.role === 'ASSISTANT' ? 'ASSISTANT' : 'PRINCIPAL'
    return d.issueEvidence(params.id, 0, 0, '', 0, null, d.EVIDENCE_TTL_SHOT_MS, 'sign', role)
  }],
  ['POST', '/work-orders/:id/checkin', ({ params, body }) => {
    const o = findOr404(d.db.orders, params.id, '工单')
    if (o.status !== 'PENDING') throw { code: 1003, message: '当前状态不允许签到' }
    // 双人作业：配合人员签到必须携带主维保动态码（docs/04 A.2）
    if (body.role === 'ASSISTANT') {
      if (!body.dynamicCode) throw { code: 422, message: '配合人员签到必须携带双人动态码' }
      if (body.dynamicCode !== '888888') throw { code: 1003, message: '动态码错误（演示环境固定为 888888）' }
    }
    // 取证令牌：验签 + 一次性核销，坐标与时间一律取令牌内值
    const evidence = d.verifyEvidence(body.evidenceToken, params.id, null, true)
    d.markCheckin(o.id, body, {
      distance: evidence.distance,
      geoStatus: 'EVIDENCE_VERIFIED'
    })
    return {
      checkinId: 'chk_' + Date.now(),
      threshold: d.CHECKIN_DISTANCE_LIMIT_M,
      passed: true,
      geoStatus: 'EVIDENCE_VERIFIED'
    }
  }],
  ['POST', '/work-orders/:id/dynamic-code/verify', ({ body }) => {
    // 演示约定：双人动态码固定 888888
    if (body.code !== '888888') throw { code: 1003, message: '动态码错误（演示环境固定为 888888）' }
    return { ok: true }
  }],
  ['GET', '/work-orders/:id/checklist', ({ params }) => {
    const o = findOr404(d.db.orders, params.id, '工单')
    return { checklistId: o.id, items: o.checklist }
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
    // 现场照片取证：令牌与 photoFileIds 按下标一一对应，缺失即拒绝（fail closed）
    const shotFileIds = body.photoFileIds || []
    const shotTokens = body.photoEvidence || []
    const verifiedPhotos = []
    for (let i = 0; i < shotFileIds.length; i++) {
      const tk = shotTokens[i] && shotTokens[i].evidenceToken
      if (!tk) continue
      const pl = d.verifyEvidence(tk, params.id, params.itemId, false)
      verifiedPhotos.push({
        fileId: String(shotFileIds[i]),
        shotAt: formatTime(new Date(pl.st * 1000)),
        latitude: pl.lat,
        longitude: pl.lng
      })
    }
    if (shotFileIds.length > 0 && verifiedPhotos.length < shotFileIds.length) {
      throw {
        code: 422,
        message: '现场照片缺少取证令牌（' + verifiedPhotos.length + '/' + shotFileIds.length +
          ' 张已核验），请重新拍照'
      }
    }
    const updated = d.updateChecklistItem(params.id, params.itemId, {
      result: body.result,
      value: body.value != null ? Number(body.value) : null,
      valueText: body.valueText || '',
      abnormalDesc: body.abnormalDesc || '',
      skipReason: body.skipReason || '',
      problemCode: body.problemCode || '',
      photos: verifiedPhotos.map(function (p) { return 'mock://' + p.fileId }),
      photoFileIds: shotFileIds,
      photoEvidence: verifiedPhotos,
      // 权威取证时间取服务端签发的拍摄时间，而非客户端 recordedAt
      recordedAt: verifiedPhotos.length ? verifiedPhotos[0].shotAt : (body.recordedAt || formatTime())
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
  ['POST', '/work-orders/:id/checkout', ({ params, body }) => {
    const o = findOr404(d.db.orders, params.id, '工单')
    if (o.status !== 'PROCESSING') throw { code: 1003, message: '请先完成签到' }
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
    const abnormalUncoded = mustRun.filter(function (i) {
      return i.result === 'ABNORMAL' && !i.problemCode
    })
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
    // 签名取证：令牌必填且角色绑定；签名图只接受 fileId（URL 由服务端按 fileId 反查）
    const sigEv = body.signatureEvidence || {}
    if (!sigEv.principal) {
      throw { code: 422, message: '缺少主维保人员签名取证令牌，请重新签署' }
    }
    d.verifyEvidence(sigEv.principal, params.id, null, false, 'sign', 'PRINCIPAL')
    const hasAssistant = !!(o.assistantName && o.assistantName !== '')
    if (hasAssistant) {
      if (!sigEv.assistant) {
        throw { code: 422, message: '缺少配合人员签名取证令牌，请重新签署' }
      }
      d.verifyEvidence(sigEv.assistant, params.id, null, false, 'sign', 'ASSISTANT')
    }
    if (!body.signatureFileId) throw { code: 422, message: '缺少主维保人员签名图' }
    let assistantSignatureUrl = ''
    if (hasAssistant) {
      if (!body.assistantSignatureFileId) throw { code: 422, message: '缺少配合人员签名图' }
      assistantSignatureUrl = 'mock://' + body.assistantSignatureFileId
    }
    const r = d.markCheckout(o.id, Object.assign({}, body, {
      signatureUrl: 'mock://' + body.signatureFileId,
      assistantSignatureUrl
    }))
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
    if (r.confirmStatus === 'CONFIRMED') return { ok: true, already: true }
    if (!body.signatureFileId) throw { code: 422, message: '请先完成签名并上传' }
    r.confirmStatus = 'CONFIRMED'
    r.satisfaction = Number(body.satisfaction) || 0
    r.signatureFileId = body.signatureFileId
    r.signatureUrl = 'mock://' + body.signatureFileId
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
    if (!body.signatureFileId) {
      throw { code: 422, message: '请先完成签名并上传' }
    }
    r.confirmStatus = 'CONFIRMED'
    r.satisfaction = Number(body.satisfaction) || 0
    r.signatureFileId = body.signatureFileId
    r.signatureUrl = 'mock://' + body.signatureFileId
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
  ['POST', '/files/upload', () => ({ fileId: 'mock_file_' + Date.now(), url: '' })]
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
