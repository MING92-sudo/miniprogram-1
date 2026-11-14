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

// ── C-12：GB/T 10060—2023 74 项移交检查清单（docs/15、docs/04 A.9）──

// 74 项检查清单（进入查验中生成草稿，幂等）→ {check, items[74]}
function getHandoverChecklist(id) {
  return get('/handovers/' + id + '/checklist')
}

// 提交查验（74 项齐备 + 7.2/7.3 判定 + 1:1 整改，同事务）；data={items, conditional?}
function submitHandoverChecklist(id, data) {
  return post('/handovers/' + id + '/checklist/submit', data)
}

// 整改清单（按 itemNo 升序，1:1 映射检查项，不可增删）
function getHandoverRectifications(id) {
  return get('/handovers/' + id + '/rectifications')
}

// 安装部逐条上传整改（rectMeasure/afterPhotoFileId/rectifierName），状态仍整改中
function rectifyHandoverItem(id, rectId, data) {
  return post('/handovers/' + id + '/rectifications/' + rectId + '/rectify', data)
}

// 逐条复验（全部 PASS 才回待确认）；data={result, recheckerId, recheckerName}
function recheckHandoverItem(id, rectId, data) {
  return post('/handovers/' + id + '/rectifications/' + rectId + '/recheck', data)
}

// ☆项置 NA 的维保部经理审批（写 handover_sign 节点 NA_APPROVE）
function approveHandoverNa(id, data) {
  return post('/handovers/' + id + '/na-approve', data)
}

// 三张表单 PDF 导出（form=CHECK|RECT|INDEX）；返回二进制下载链接由调用方处理
function getHandoverExportPdfUrl(id, form) {
  return '/handovers/' + id + '/export-pdf?form=' + (form || 'CHECK')
}

// 一梯一档归档目录（7 条 + SHA-256，幂等）
function getElevatorArchive(id) {
  return get('/elevators/' + id + '/archive')
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
  getReceiverCandidates,
  getHandoverChecklist,
  submitHandoverChecklist,
  getHandoverRectifications,
  rectifyHandoverItem,
  recheckHandoverItem,
  approveHandoverNa,
  getHandoverExportPdfUrl,
  getElevatorArchive
}
