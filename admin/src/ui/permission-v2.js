// 本文件仅作 admin 的 ESM 桥（Vite 构建经 commonjsOptions 转换 CJS 源）。
import { allowsNew, state } from '../../../constants/permission-matrix.js'
export {
  ROLE, ALIAS, SCOPE, canonical, state, allowsNew, canonicalCodes, canonicalRoles, defaultScope
} from '../../../constants/permission-matrix.js'

// 多角色权限并集：任一 Y→Y，否则任一 C→C，否则 DENY；空角色集拒绝，未知码经 state 抛出（fail-closed）。
export function stateForRoles(roles, code) {
  const list = Array.isArray(roles) ? roles : []
  if (!list.length) {
    state(null, code)
    return 'DENY'
  }
  let out = 'DENY'
  for (const role of list) {
    const s = state(role, code)
    if (s === 'Y') return 'Y'
    if (s === 'C') out = 'C'
  }
  return out
}

// 管理端旧 LEADER 只读；规范 MAINT_SUP 仍按矩阵拥有编辑权。
export function canEditElevator(role) {
  return role !== 'LEADER' && allowsNew(role, 'elevator:edit')
}