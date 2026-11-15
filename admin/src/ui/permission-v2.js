// 权限矩阵镜像已移至仓库根 constants/permission-matrix.js（小程序 mock/Node/admin 单一事实源）。
// 本文件仅作 admin 的 ESM 桥（Vite 构建经 commonjsOptions 转换 CJS 源）。
export {
  ROLE, ALIAS, SCOPE, canonical, state, allowsNew, canonicalCodes, canonicalRoles, defaultScope
} from '../../../constants/permission-matrix.js'