// 本文件仅作 admin 的 ESM 桥（Vite 构建经 commonjsOptions 转换 CJS 源）。
import { allowsNew } from '../../../constants/permission-matrix.js'
export {
  ROLE, ALIAS, SCOPE, canonical, state, allowsNew, canonicalCodes, canonicalRoles, defaultScope
} from '../../../constants/permission-matrix.js'

// 管理端旧 LEADER 只读；规范 MAINT_SUP 仍按矩阵拥有编辑权。
export function canEditElevator(role) {
  return role !== 'LEADER' && allowsNew(role, 'elevator:edit')
}