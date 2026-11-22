import { state } from '../ui/permission-v2'

function hasPerm(role, code) {
  try {
    const s = state(role, code)
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
  if (!isPublic) {
    const byTab = (to.meta && to.meta.permByTab) || null
    const tab = to.query && typeof to.query.tab === 'string' ? to.query.tab : ''
    if (byTab && tab && byTab[tab]) {
      if (!hasPerm(authState.role, byTab[tab])) {
        return { path: '/403' }
      }
    } else if (byTab && !tab) {
      const codes = [to.meta.perm, ...Object.values(byTab)].filter(Boolean)
      if (codes.length && !codes.some((c) => hasPerm(authState.role, c))) {
        return { path: '/403' }
      }
    } else {
      const perm = to.meta && to.meta.perm
      if (perm && !hasPerm(authState.role, perm)) {
        return { path: '/403' }
      }
    }
  }
  return true
}