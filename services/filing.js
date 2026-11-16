// 外接建档（W2：合同前置 → 资料收集 → 接管查验 → 交接确认 → 归档，docs/04 A.10；状态机见 backend ExternalFilingService）
// 页面只经本模块调接口：资料与签名统一走 services/upload.js，不在页面内发起网络请求（AGENTS §1.1）。
const { get, post } = require('../utils/request')

// 发起外接建档（必须关联已生效合同；创建即进入 DOC_COLLECTING）
function initFiling(data) {
  return post('/filings', data)
}

// 外接建档单详情（顶层单据字段 + docs[] + takeoverChecks[{items[]}] + confirmation）
function getFilingDetail(id) {
  return get('/filings/' + id)
}

// 外接建档单列表（GET /filings?status=&page=&size= → {list,total}）
// 契约保留、小程序暂无入口：列表在管理端「外接建档」视图
function getFilingList(params) {
  return get('/filings', params)
}

// 提交建档资料（docs[]，missing=true 允许缺件待补办；提交后进入 TAKEOVER_CHECKING）
function submitFilingDocs(id, data) {
  return post('/filings/' + id + '/submit-docs', data)
}

// 提交接管查验（FAIL 必须带责任归属；提交后进入 PENDING_CONFIRM）
function submitTakeoverCheck(id, data) {
  return post('/filings/' + id + '/takeover-check', data)
}

// 交接确认（双方签字）→ 归档建电梯 + 预建首保工单（后端同事务、幂等）
function confirmFiling(id, data) {
  return post('/filings/' + id + '/confirm', data)
}

// 使用单位候选（GET /use-units：安装项目经理/文员/维保经理可读，安装员无权限时页面降级手工填写）
function getUseUnits() {
  return get('/use-units').then(function (list) {
    return (list || []).map(function (u) {
      return {
        id: u.id,
        unitName: u.unitName || u.id,
        elevatorAdminister: u.elevatorAdminister || '',
        entityId: u.entityId || ''
      }
    })
  })
}

// 已生效合同候选（GET /contracts → 过滤 ACTIVE 且今天落在合同期内；可按使用单位收敛）
// 注意：合同列表端点当前仅平台层可读（ContractController.requireAdmin），其他岗位由页面降级为手工填写合同 ID
function getActiveContracts(useUnitId) {
  const today = todayText()
  return get('/contracts').then(function (list) {
    return (list || [])
      .filter(function (c) {
        if (c.status !== 'ACTIVE') return false
        if (useUnitId && c.useUnitId && c.useUnitId !== useUnitId) return false
        if (c.startDate && String(c.startDate).slice(0, 10) > today) return false
        if (c.endDate && String(c.endDate).slice(0, 10) < today) return false
        return true
      })
      .map(function (c) {
        return {
          id: c.id,
          contractNo: c.contractNo || c.id,
          useUnitId: c.useUnitId || '',
          startDate: c.startDate ? String(c.startDate).slice(0, 10) : '',
          endDate: c.endDate ? String(c.endDate).slice(0, 10) : ''
        }
      })
  })
}

// 今天（yyyy-MM-dd，GMT+8）：合同有效期与页面日期口径一致
function todayText() {
  const d = new Date()
  const pad = function (n) {
    return (n < 10 ? '0' : '') + n
  }
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
}

module.exports = {
  initFiling,
  getFilingDetail,
  getFilingList,
  submitFilingDocs,
  submitTakeoverCheck,
  confirmFiling,
  getUseUnits,
  getActiveContracts
}
