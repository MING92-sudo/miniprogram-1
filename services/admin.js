// 管理统计域（M6，pkg-admin 只读投影 + 配置）：复用管理端 M3/M5 端点（MultiRoleGate 后端强校验）。
// 小程序仅只读投影 + 平台配置；写操作（项目/合同/账号/角色编辑）在管理端，小程序 perm-button 一档禁用。
const { get, put } = require('../utils/request')

function getProjects(params) { return get('/projects', params) }
function getContracts(params) { return get('/contracts', params) }
function getUsers(params) { return get('/users', params) }
function getRolesMatrix() { return get('/roles-matrix') }
function getStats() { return get('/stats') }
function getAudit(params) { return get('/audit', params) }
function getSettings() { return get('/settings') }
function updateSetting(key, value) { return put('/settings/' + key, { value }) }

module.exports = { getProjects, getContracts, getUsers, getRolesMatrix, getStats, getAudit, getSettings, updateSetting }