const { get, put } = require('../../utils/request')

function getProjects(params) { return get('/projects', params) }
function getContracts(params) { return get('/contracts', params) }
function getUsers(params) { return get('/users', params) }
function getRolesMatrix() { return get('/roles-matrix') }
function getStats() { return get('/stats') }
function getAudit(params) { return get('/audit', params) }
function getSettings() { return get('/settings') }
function updateSetting(key, value) { return put('/settings/' + key, { value }) }

module.exports = { getProjects, getContracts, getUsers, getRolesMatrix, getStats, getAudit, getSettings, updateSetting }