import { stateForRoles } from '../ui/permission-v2'

function hasPerm(roles, code) {
  try {
    const s = stateForRoles(roles, code)
    return s === 'Y' || s === 'C'
  } catch {
    return false
  }
}

export function resolveRoute(to, authState) {
  const isPublic = Boolean(to.meta && to.meta.public)
  if (!isPublic && !authState.token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.path === '/login' && authState.token) {
    return { path: '/dashboard' }
  }
  const roles = Array.isArray(authState.roles) && authState.roles.length ? authState.roles : [authState.role]
  if (!isPublic) {
    const byTab = (to.meta && to.meta.permByTab) || null
    const tab = to.query && typeof to.query.tab === 'string' ? to.query.tab : ''
    if (byTab && tab && byTab[tab]) {
      if (!hasPerm(roles, byTab[tab])) {
        return { path: '/403' }
      }
    } else if (byTab && !tab) {
      const codes = [to.meta.perm, ...Object.values(byTab)].filter(Boolean)
      if (codes.length && !codes.some((c) => hasPerm(roles, c))) {
        return { path: '/403' }
      }
    } else {
      const perm = to.meta && to.meta.perm
      if (perm && !hasPerm(roles, perm)) {
        return { path: '/403' }
      }
    }
  }
  return true
}