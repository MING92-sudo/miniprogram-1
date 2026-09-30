// Mock 拦截器：按 method + 路径模板匹配 services 层请求，返回统一响应的 data 部分
// 路径模板支持 :id 形式参数；handler 内 return 即业务成功，throw {code,message} 即业务失败
const d = require('./data')
const { formatTime, parseTime } = require('../utils/util')
const { MIN_WORK_DURATION_MINUTES } = require('../constants/index')

// 自动排期触发点：真实后端为定时任务扫描 plans 表到期记录；
// mock 在进入工单台/扫码/消息中心时即时检查到期电梯并自动派单
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
  ['GET', '/work-orders', ({ query }) => {
    d.ensureDueOrders()
    return paginate(d.listOrders(query), query)
  }],
  ['GET', '/work-orders/:id', ({ params }) => {
    const order = d.getOrder(params.id)
    if (!order) throw { code: 1404, message: '工单不存在' }
    return order
  }],
  ['POST', '/work-orders/resolve-by-elevator', ({ body }) => {
    d.ensureDueOrders()
    const el = d.getElevatorByCode(body.elevatorCode)
    if (!el) throw { code: 1404, message: '未识别的电梯二维码' }
    const order = d.db.orders.find((o) => o.elevatorId === el.id && o.status !== 'DONE')
    if (!order) throw { code: 1404, message: '该电梯暂无进行中的工单' }
    return d.getOrder(order.id)
  }],
  ['POST', '/work-orders/:id/checkin', ({ params, body }) => {
    const o = findOr404(d.db.orders, params.id, '工单')
    if (o.status !== 'PENDING') throw { code: 1003, message: '当前状态不允许签到' }
    // 双人作业：配合人员签到必须携带主维保动态码（docs/04 A.2）
    if (body.role === 'ASSISTANT') {
      if (!body.dynamicCode) throw { code: 422, message: '配合人员签到必须携带双人动态码' }
      if (body.dynamicCode !== '888888') throw { code: 1003, message: '动态码错误（演示环境固定为 888888）' }
    }
    d.markCheckin(o.id, body)
    return {
      checkinId: 'chk_' + Date.now(),
      distance: 35.6,
      threshold: 200,
      passed: true,
      geoStatus: 'PROVIDED'
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
    // 关键项（试验/测试/校验/检测类，TSG 注A-2）无论结果如何强制照片留证
    if (item.isKey && item.photoRequired && !(body.photoFileIds || []).length && !(body.photoUrls || []).length) {
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
  ['POST', '/work-orders/:id/checkout', ({ params, body }) => {
    const o = findOr404(d.db.orders, params.id, '工单')
    if (o.status !== 'PROCESSING') throw { code: 1003, message: '请先完成签到' }
    // 周期性"本次无需执行"条目不计入未完成项（docs/03 §7 验收）
    const mustRun = o.checklist.filter((i) => !i.notInThisRun)
    const unfinished = mustRun.filter((i) => !i.result).length
    if (unfinished > 0) throw { code: 1003, message: '还有 ' + unfinished + ' 项检查未填写' }
    // 关键项照片留证校验（TSG 注A-2）
    const keyNoPhoto = mustRun.filter(
      (i) => i.isKey && i.photoRequired && i.result && !((i.photoFileIds || []).length || (i.photos || []).length)
    )
    if (keyNoPhoto.length) {
      throw { code: 422, message: '关键项「' + keyNoPhoto[0].name + '」须至少附 1 张照片留证（TSG 注A-2），无法签退' }
    }
    // 业务规则：签到—签退间隔不少于 30 分钟（业主补充规则，constants 可配）
    const minutes = (Date.now() - parseTime(o.checkinTime)) / 60000
    if (minutes < MIN_WORK_DURATION_MINUTES) {
      throw {
        code: 422,
        message: '作业时长不足 30 分钟（当前 ' + Math.floor(minutes) + ' 分钟），请继续作业后再签退'
      }
    }
    const r = d.markCheckout(o.id, body)
    // 响应对齐 docs/04 A.2 checkout
    return {
      workOrderId: o.id,
      duration: r.duration,
      originalRecordId: r.originalRecordId,
      reportStatus: r.reportStatus
    }
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
  ['GET', '/inspects/template', () => {
    // 自行检查项 = 年度维保项并集（表A-1~A-4 累计 76 项，docs/01 §3.17"不少于年度维保项"）
    const items = []
    d.FREQ_CHAIN.OY.forEach(function (freq) {
      d.APPENDIX_A_TPL[freq].forEach(function (it) {
        items.push(it)
      })
    })
    return { items: items }
  }],
  ['POST', '/inspects', ({ body }) => d.createInspect(body)],

  // ── 消息 ──
  ['GET', '/messages', ({ query }) => {
    d.ensureDueOrders()
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
  const query = parseQuery(qIdx > -1 ? raw.slice(qIdx + 1) : '')

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
