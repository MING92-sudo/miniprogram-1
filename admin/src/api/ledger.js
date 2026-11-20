import http from './client'

// M3 台账域 API（组织/项目/邀请/授权/电梯变更/角色矩阵/账号/合同脱敏）
export const listOrg = () => http.get('/org')
export const createOrg = (b) => http.post('/org', b)
export const updateOrg = (id, b) => http.put(`/org/${id}`, b)
// D1 节点成员与调人：GET /org/{id}/members（Task 2）；调人经 PUT /employees/{id} {groupName}（Task 3，目标须为既有 TEAM 节点名，否则 422）
export const orgMembers = (id) => http.get(`/org/${id}/members`)
export const moveEmployee = (id, groupName) => http.put(`/employees/${id}`, { groupName })

export const listProjects = () => http.get('/projects')
export const createProject = (b) => http.post('/projects', b)
export const updateProject = (id, b) => http.put(`/projects/${id}`, b)

export const listInvites = () => http.get('/invites')
export const createInvite = (b) => http.post('/invites', b)
export const withdrawInvite = (id) => http.post(`/invites/${id}/withdraw`, {})

export const listGrants = () => http.get('/grants')
export const createGrant = (b) => http.post('/grants', b)
export const withdrawGrant = (id) => http.post(`/grants/${id}/withdraw`, {})

export const listChanges = () => http.get('/elevator-changes')
export const initiateChange = (b) => http.post('/elevator-changes', b)
export const confirmChange = (id, b) => http.post(`/elevator-changes/${id}/confirm`, b)

export const rolesMatrix = () => http.get('/roles-matrix')

export const listUsers = () => http.get('/users')
export const createUser = (b) => http.post('/users', b)
export const disableUser = (id) => http.post(`/users/${id}/disable`, {})
export const assignRoles = (id, b) => http.post(`/users/${id}/roles`, b)
export const listUseUnits = () => http.get('/use-units')

export const listContractsLedger = () => http.get('/contracts-ledger')