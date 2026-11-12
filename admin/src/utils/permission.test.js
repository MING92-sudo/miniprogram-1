import { describe, expect, it } from 'vitest'
import {
  CAP, ROLE, ROLE_PERMS, CAPABILITY_ROLES, can, canAny, isKnownRole
} from './permission'
import { resolveRoute } from '../router/guard'

/**
 * 管理端权限矩阵回归（docs/14 §6）：矩阵是唯一数据来源，
 * 后端 PermissionMatrix 才是权威，本文件只保证前端门禁与矩阵同码同格。
 */
describe('能力码与角色码', () => {
  it('能力码字符串与后端 PermissionMatrix 逐字一致（顺序即码表顺序）', () => {
    expect(Object.values(CAP)).toEqual([
      'console:read', 'console:write',
      'order:read', 'order:write',
      'fault:read', 'fault:write',
      'record:read', 'report:retry',
      'plan:write', 'template:write',
      'ledger:read', 'ledger:write',
      'employee:read', 'employee:write',
      'contract:read', 'contract:write',
      'useunit:read', 'useunit:write',
      'handover:read', 'handover:write',
      'filing:read', 'filing:write',
      'region:write', 'account:manage'
    ])
  })

  it('角色码 12 个，覆盖三平级部门 + 平台层', () => {
    expect(Object.values(ROLE)).toEqual([
      'SYS_ADMIN', 'ADMIN',
      'INSTALL_MANAGER', 'INSTALL_CLERK', 'INSTALL_WORKER',
      'MAINT_MANAGER', 'MAINT_BIZ', 'LEADER', 'MAINT_QUALITY',
      'WORKER', 'ASSISTANT', 'UNIT_ADMIN'
    ])
    expect(Object.keys(ROLE_PERMS).sort()).toEqual(Object.values(ROLE).sort())
  })

  it('未知角色不在矩阵内', () => {
    expect(isKnownRole('ADMIN')).toBe(true)
    expect(isKnownRole('ROOT')).toBe(false)
    expect(isKnownRole('')).toBe(false)
    expect(isKnownRole(null)).toBe(false)
  })
})

describe('平台角色 ADMIN / SYS_ADMIN', () => {
  // docs/14 §6：ADMIN 为平台兜底，24 项能力全持有；「写」不等于「代签」——
  // 移交/建档的节点签字由 HandoverService.NODE_ROLES 限角色（安装节点仅 INSTALLER）
  it('ADMIN 持有全部能力（平台兜底；代签由 NODE_ROLES 另行限制）', () => {
    Object.values(CAP).forEach((cap) => {
      expect(can(ROLE.ADMIN, cap), `ADMIN 应具备 ${cap}`).toBe(true)
    })
  })

  it('SYS_ADMIN 与 ADMIN 同权', () => {
    Object.values(CAP).forEach((cap) => {
      expect(can(ROLE.SYS_ADMIN, cap), `SYS_ADMIN 应具备 ${cap}`).toBe(true)
    })
    expect(ROLE_PERMS.SYS_ADMIN).toEqual(ROLE_PERMS.ADMIN)
  })
})

describe('小程序端角色不可达管理端能力', () => {
  it('WORKER 全 false', () => {
    Object.values(CAP).forEach((cap) => {
      expect(can(ROLE.WORKER, cap), `WORKER 不应具备 ${cap}`).toBe(false)
    })
  })

  it('ASSISTANT / UNIT_ADMIN 全 false', () => {
    ;[ROLE.ASSISTANT, ROLE.UNIT_ADMIN].forEach((role) => {
      Object.values(CAP).forEach((cap) => {
        expect(can(role, cap), `${role} 不应具备 ${cap}`).toBe(false)
      })
    })
  })
})

describe('docs/14 §6 关键格子', () => {
  it('安装部', () => {
    // 安装文员：只传资料，不改合同、不进工单域、不写看板
    expect(can(ROLE.INSTALL_CLERK, CAP.CONTRACT_WRITE)).toBe(false)
    expect(can(ROLE.INSTALL_CLERK, CAP.CONTRACT_READ)).toBe(false)
    expect(can(ROLE.INSTALL_CLERK, CAP.FILING_WRITE)).toBe(true)
    // §6「安装文员 R（资料 W）」：资料上传属移交/建档写，后端 P1 矩阵暂缺该角色（已列入遗留问题）
    expect(can(ROLE.INSTALL_CLERK, CAP.HANDOVER_WRITE)).toBe(true)
    expect(can(ROLE.INSTALL_CLERK, CAP.CONSOLE_READ)).toBe(true)
    expect(can(ROLE.INSTALL_CLERK, CAP.CONSOLE_WRITE)).toBe(false)
    expect(can(ROLE.INSTALL_CLERK, CAP.ORDER_READ)).toBe(false)
    expect(can(ROLE.INSTALL_CLERK, CAP.EMPLOYEE_WRITE)).toBe(false)
    expect(can(ROLE.INSTALL_CLERK, CAP.EMPLOYEE_READ)).toBe(true)
    // 安装经理：本部门人员/移交/建档可写，维保域不可见
    expect(can(ROLE.INSTALL_MANAGER, CAP.LEDGER_READ)).toBe(true)
    expect(can(ROLE.INSTALL_MANAGER, CAP.LEDGER_WRITE)).toBe(false)
    expect(can(ROLE.INSTALL_MANAGER, CAP.EMPLOYEE_WRITE)).toBe(true)
    expect(can(ROLE.INSTALL_MANAGER, CAP.HANDOVER_WRITE)).toBe(true)
    expect(can(ROLE.INSTALL_MANAGER, CAP.ORDER_READ)).toBe(false)
    expect(can(ROLE.INSTALL_MANAGER, CAP.REGION_WRITE)).toBe(false)
    // 安装员：现场查验（移交单写），其余管理端能力全无
    expect(can(ROLE.INSTALL_WORKER, CAP.HANDOVER_WRITE)).toBe(true)
    expect(can(ROLE.INSTALL_WORKER, CAP.HANDOVER_READ)).toBe(true)
    expect(can(ROLE.INSTALL_WORKER, CAP.FILING_WRITE)).toBe(false)
    expect(can(ROLE.INSTALL_WORKER, CAP.CONSOLE_READ)).toBe(false)
  })

  it('维保部', () => {
    // 维保经理：部门全权（console:write 例外——后端 P1 保持旧两档，仅平台层可写），区域主数据不归部门
    expect(can(ROLE.MAINT_MANAGER, CAP.CONSOLE_READ)).toBe(true)
    expect(can(ROLE.MAINT_MANAGER, CAP.CONSOLE_WRITE)).toBe(false)
    expect(can(ROLE.MAINT_MANAGER, CAP.REPORT_RETRY)).toBe(true)
    expect(can(ROLE.MAINT_MANAGER, CAP.ACCOUNT_MANAGE)).toBe(true)
    expect(can(ROLE.MAINT_MANAGER, CAP.REGION_WRITE)).toBe(false)
    // 商务：合同/使用单位读写，不见工单与现场作业数据
    expect(can(ROLE.MAINT_BIZ, CAP.CONTRACT_WRITE)).toBe(true)
    expect(can(ROLE.MAINT_BIZ, CAP.CONTRACT_READ)).toBe(true)
    expect(can(ROLE.MAINT_BIZ, CAP.USEUNIT_WRITE)).toBe(true)
    expect(can(ROLE.MAINT_BIZ, CAP.ORDER_WRITE)).toBe(false)
    expect(can(ROLE.MAINT_BIZ, CAP.ORDER_READ)).toBe(false)
    expect(can(ROLE.MAINT_BIZ, CAP.FAULT_READ)).toBe(false)
    expect(can(ROLE.MAINT_BIZ, CAP.EMPLOYEE_READ)).toBe(false)
    // 班组长：只读 + 本组计划调度，不写人员档案
    expect(can(ROLE.LEADER, CAP.CONSOLE_READ)).toBe(true)
    expect(can(ROLE.LEADER, CAP.CONSOLE_WRITE)).toBe(false)
    expect(can(ROLE.LEADER, CAP.PLAN_WRITE)).toBe(true)
    expect(can(ROLE.LEADER, CAP.ORDER_READ)).toBe(true)
    expect(can(ROLE.LEADER, CAP.EMPLOYEE_READ)).toBe(true)
    expect(can(ROLE.LEADER, CAP.EMPLOYEE_WRITE)).toBe(false)
    expect(can(ROLE.LEADER, CAP.TEMPLATE_WRITE)).toBe(false)
    expect(can(ROLE.LEADER, CAP.LEDGER_WRITE)).toBe(false)
    // 质量负责人：读全量 + 模板启停，不得重报、不得改工单
    expect(can(ROLE.MAINT_QUALITY, CAP.REPORT_RETRY)).toBe(false)
    expect(can(ROLE.MAINT_QUALITY, CAP.ORDER_READ)).toBe(true)
    expect(can(ROLE.MAINT_QUALITY, CAP.ORDER_WRITE)).toBe(false)
    expect(can(ROLE.MAINT_QUALITY, CAP.TEMPLATE_WRITE)).toBe(true)
    expect(can(ROLE.MAINT_QUALITY, CAP.LEDGER_READ)).toBe(true)
    expect(can(ROLE.MAINT_QUALITY, CAP.EMPLOYEE_READ)).toBe(false)
    expect(can(ROLE.MAINT_QUALITY, CAP.EMPLOYEE_WRITE)).toBe(false)
    expect(can(ROLE.MAINT_QUALITY, CAP.PLAN_WRITE)).toBe(false)
  })

  it('唯一一格的专属能力：region:write / account:manage / report:retry', () => {
    expect(CAPABILITY_ROLES['region:write']).toEqual(['ADMIN'])
    expect(CAPABILITY_ROLES['account:manage'].sort()).toEqual(['ADMIN', 'MAINT_MANAGER'])
    expect(CAPABILITY_ROLES['report:retry'].sort()).toEqual(['ADMIN', 'MAINT_MANAGER'])
    expect(can(ROLE.INSTALL_MANAGER, CAP.ACCOUNT_MANAGE)).toBe(false)
    expect(can(ROLE.LEADER, CAP.REPORT_RETRY)).toBe(false)
  })

  it('console:read 持有者 = 可登录管理端的角色（RoleCatalog.adminConsole=true）', () => {
    expect([...CAPABILITY_ROLES['console:read']].sort()).toEqual([
      'ADMIN', 'INSTALL_CLERK', 'INSTALL_MANAGER',
      'LEADER', 'MAINT_BIZ', 'MAINT_MANAGER', 'MAINT_QUALITY'
    ])
    // 其余 4 个角色只有小程序端，登录管理端即 403
    expect([ROLE.INSTALL_WORKER, ROLE.WORKER, ROLE.ASSISTANT, ROLE.UNIT_ADMIN]
      .filter((role) => can(role, CAP.CONSOLE_READ))).toEqual([])
  })
})

describe('write ⇒ read', () => {
  it('持有 X:write 的角色必然持有 X:read', () => {
    Object.keys(CAPABILITY_ROLES)
      .filter((cap) => cap.endsWith(':write'))
      .forEach((cap) => {
        const readCap = cap.replace(/:write$/, ':read')
        CAPABILITY_ROLES[cap].forEach((role) => {
          expect(can(role, readCap), `${role} 有 ${cap} 但缺 ${readCap}`).toBe(true)
        })
      })
  })

  it('读能力不隐含写能力', () => {
    expect(can(ROLE.LEADER, CAP.ORDER_READ)).toBe(true)
    expect(can(ROLE.LEADER, CAP.ORDER_WRITE)).toBe(false)
    expect(can(ROLE.MAINT_BIZ, CAP.CONTRACT_READ)).toBe(true)
    expect(can(ROLE.MAINT_MANAGER, CAP.CONTRACT_WRITE)).toBe(false)
  })

  it('ROLE_PERMS 只声明矩阵原值，补齐在读判定里完成', () => {
    expect(ROLE_PERMS.INSTALL_WORKER).toEqual(['handover:read', 'handover:write'])
    // 安装文员：资料上传属移交/建档写；filing:read 与 filing:write 均显式声明（§6 文员可读建档单）
    expect(ROLE_PERMS.INSTALL_CLERK).toContain('filing:read')
    expect(can(ROLE.INSTALL_CLERK, CAP.FILING_READ)).toBe(true)
    // 模板管理矩阵只有 W，没有 template:read 行
    expect(ROLE_PERMS.MAINT_QUALITY).not.toContain('template:read')
    expect(can(ROLE.MAINT_QUALITY, 'template:read')).toBe(true)
  })
})

describe('非法入参 fail-closed', () => {
  it('未知角色返回 false，不放行', () => {
    expect(can('ROOT', CAP.CONSOLE_READ)).toBe(false)
    expect(can('admin', CAP.CONSOLE_READ)).toBe(false)
    expect(can('', CAP.CONSOLE_READ)).toBe(false)
    expect(can(null, CAP.CONSOLE_READ)).toBe(false)
    expect(can(undefined, CAP.CONSOLE_READ)).toBe(false)
  })

  it('未知能力返回 false', () => {
    expect(can(ROLE.ADMIN, 'order:delete')).toBe(false)
    expect(can(ROLE.ADMIN, 'console')).toBe(false)
    expect(can(ROLE.ADMIN, '')).toBe(false)
    expect(can(ROLE.ADMIN, null)).toBe(false)
  })

  it('canAny：任一命中即 true，空数组/未知角色为 false', () => {
    expect(canAny(ROLE.LEADER, [CAP.REGION_WRITE, CAP.PLAN_WRITE])).toBe(true)
    expect(canAny(ROLE.LEADER, [CAP.REGION_WRITE, CAP.ACCOUNT_MANAGE])).toBe(false)
    expect(canAny(ROLE.LEADER, [])).toBe(false)
    expect(canAny('ROOT', [CAP.CONSOLE_READ])).toBe(false)
  })
})

describe('路由 meta.perm 守卫（docs/14 §9.4）', () => {
  const guarded = (perm) => ({ path: '/orders', meta: { title: '工单列表', perm }, fullPath: '/orders' })

  it('有 token 但角色缺能力 → /403', () => {
    expect(resolveRoute(guarded('order:read'), { token: 'jwt', role: ROLE.MAINT_BIZ }))
      .toEqual({ path: '/403' })
    expect(resolveRoute(guarded('order:read'), { token: 'jwt', role: ROLE.INSTALL_CLERK }))
      .toEqual({ path: '/403' })
  })

  it('有 token 且能力匹配 → 放行', () => {
    expect(resolveRoute(guarded('order:read'), { token: 'jwt', role: ROLE.LEADER })).toBe(true)
    expect(resolveRoute(guarded('console:read'), { token: 'jwt', role: ROLE.ADMIN })).toBe(true)
  })

  it('无 token 仍先跳登录（守卫原有语义不变）', () => {
    const target = resolveRoute(guarded('order:read'), { token: '' })
    expect(target.path).toBe('/login')
    expect(target.query.redirect).toBe('/orders')
  })

  it('/403 与会话路径不被二次拦截', () => {
    expect(resolveRoute({ path: '/403', meta: { public: true }, fullPath: '/403' },
      { token: 'jwt', role: ROLE.WORKER })).toBe(true)
    expect(resolveRoute({ path: '/login', meta: { public: true }, fullPath: '/login' },
      { token: 'jwt', role: ROLE.ADMIN })).toEqual({ path: '/dashboard' })
  })
})
