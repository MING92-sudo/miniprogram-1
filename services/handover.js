// 移交单（W1：安装部 → 维保部，docs/04 A.9；状态机与 3 必传卡点见 backend HandoverService）
// 页面只经本模块调接口：资料/整改照片统一走 services/upload.js，不在页面内发起网络请求（AGENTS §1.1）。
const { get, post } = require('../utils/request')

// 发起移交（项目名称必填，创建即进入 DOC_SUBMITTING）
function initHandover(data) {
  return post('/handovers', data)
}

// 移交单详情（GET /handovers/{id}，越权/不存在由后端 1403/1404 拦截）
function getHandoverDetail(id) {
  return get('/handovers/' + id)
}

// 移交单列表（GET /handovers?status=&page=&size= → {list,total}）
function getHandoverList(params) {
  return get('/handovers', params)
}

// 提交 3 必传资料（监督检验报告/维保说明书/整机质量证明，缺一 422）
function submitHandoverDocs(id, data) {
  return post('/handovers/' + id + '/submit-docs', data)
}

// 分配接收工程师（维保部经理，状态仍 INSPECTING）
function assignHandoverReceiver(id, data) {
  return post('/handovers/' + id + '/assign-receiver', data)
}

// 提交现场查验（6 类，安全装置/制动器任一 FAIL → 后端转 RECTIFYING）
function submitHandoverInspect(id, data) {
  return post('/handovers/' + id + '/inspect', data)
}

// 上传整改前后照片（安装部，状态仍 RECTIFYING）
function submitHandoverRectify(id, data) {
  return post('/handovers/' + id + '/rectify', data)
}

// 复验（PASS → 待确认归档；FAIL → 保持整改中）
function recheckHandoverRectify(id, data) {
  return post('/handovers/' + id + '/recheck-rectify', data)
}

// 经理确认归档（后端同事务写电梯档案 + 预建首保工单，重复确认幂等）
function confirmHandover(id, data) {
  return post('/handovers/' + id + '/confirm', data)
}

// 接收工程师候选：复用管理端人员目录 GET /employees（console:read 角色可读），
// 页面无权时由调用方降级为手工填写工号 + 姓名
function getReceiverCandidates() {
  return get('/employees').then(function (list) {
    return (list || [])
      .filter(function (e) {
        return (e.role === 'WORKER' || e.role === 'ASSISTANT') && e.enabled !== false
      })
      .map(function (e) {
        return { id: e.id, name: e.name, roleText: e.roleText || '', groupName: e.groupName || '' }
      })
  })
}

module.exports = {
  initHandover,
  getHandoverDetail,
  getHandoverList,
  submitHandoverDocs,
  assignHandoverReceiver,
  submitHandoverInspect,
  submitHandoverRectify,
  recheckHandoverRectify,
  confirmHandover,
  getReceiverCandidates
}
