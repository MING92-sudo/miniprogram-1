import { describe, it, expect } from 'vitest'
import { canonical, state, allowsNew, canonicalCodes, canonicalRoles, defaultScope, canEditElevator } from './permission-v2'

describe('permission-v2 mirror', () => {
  it('has 84 canonical codes', () => {
    expect(canonicalCodes().size).toBe(84)
    expect(canonicalCodes().has('handover:view')).toBe(true)
    expect(canonicalCodes().has('contract:view')).toBe(true)
    expect(canonicalCodes().has('record:spotcheck')).toBe(true)
    expect(canonicalCodes().has('user:reset_password')).toBe(true)
  })
  it('has 13 canonical roles', () => {
    expect(canonicalRoles().size).toBe(13)
  })
  it('aliases old codes to canonical', () => {
    expect(canonical('ADMIN')).toBe('PLATFORM_ADMIN')
    expect(canonical('LEADER')).toBe('LEADER')
    expect(canonical('INSTALL_MANAGER')).toBe('INST_PM')
    expect(canonical('UNIT_ADMIN')).toBe('CLIENT_ADMIN')
    expect(canonical('INST_MGR')).toBe('INST_MGR')
    expect(canonical('HACKER')).toBe(null)
    expect(canonical(null)).toBe(null)
  })
  it('LEADER 独立只读，MAINT_SUP 与平台管理员按矩阵可编辑定位', () => {
    expect(canEditElevator('LEADER')).toBe(false)
    expect(canEditElevator('MAINT_SUP')).toBe(true)
    expect(canEditElevator('PLATFORM_ADMIN')).toBe(true)
    ;['user:create', 'user:disable', 'order:dispatch', 'record:archive', 'record:review', 'plan:generate']
      .forEach((code) => expect(state('LEADER', code)).toBe('DENY'))
    ;['project:view', 'record:view', 'order:view_pool', 'stats:view']
      .forEach((code) => expect(state('LEADER', code)).not.toBe('DENY'))
  })
  it('tri-state samples match backend', () => {
    expect(state('INST_MGR', 'handover:review')).toBe('Y')
    expect(state('INST_CLERK', 'handover:review')).toBe('DENY')
    expect(state('INST_CLERK', 'handover:view')).toBe('C')
    expect(state('CLIENT_ADMIN', 'record:view')).toBe('DENY')
    expect(state('MAINT_QUALITY', 'record:spotcheck')).toBe('Y')
    expect(state('MAINT_BIZ', 'client:edit')).toBe('Y')
    expect(state('INSTALLER', 'handover:inspect')).toBe('Y')
    expect(state('PLATFORM_ADMIN', 'config:manage')).toBe(state('SYS_ADMIN', 'config:manage'))
  })
  it('allowsNew requires Y', () => {
    expect(allowsNew('INST_MGR', 'handover:review')).toBe(true)
    expect(allowsNew('INST_CLERK', 'handover:view')).toBe(false)
    expect(allowsNew('MAINT_TECH', 'config:manage')).toBe(false)
  })
  it('fail-closed: unknown role DENY, unknown code throws', () => {
    expect(state('HACKER', 'handover:view')).toBe('DENY')
    expect(state(null, 'handover:view')).toBe('DENY')
    expect(() => state('INST_MGR', 'no:such')).toThrow()
    expect(() => allowsNew('INST_MGR', null)).toThrow()
    expect(() => state('MAINT_MGR', 'filing:view')).toThrow()
    expect(() => state('MAINT_MGR', 'filing:write')).toThrow()
  })
  it('defaultScope mirrors sys_role_scope', () => {
    expect(defaultScope('PLATFORM_ADMIN')).toBe('ALL')
    expect(defaultScope('INST_MGR')).toBe('ORG')
    expect(defaultScope('INST_PM')).toBe('PROJECT')
    expect(defaultScope('MAINT_SUP')).toBe('TEAM')
    expect(defaultScope('LEADER')).toBe('TEAM')
    expect(defaultScope('MAINT_TECH')).toBe('SELF')
    expect(defaultScope('CLIENT_ADMIN')).toBe('ORG_FLAT')
    expect(defaultScope('HACKER')).toBe(null)
  })
  it('用户裁决：SYS_ADMIN 全权限，安装员条件读移交', () => {
    canonicalCodes().forEach((code) => {
      expect(state('SYS_ADMIN', code)).toBe('Y')
    })
    expect(state('INSTALLER', 'handover:view')).toBe('C')
  })
  it('P1 客户能力集：elevator_view/download/sign=Y；2026-11-21 报修重开 report=Y；evaluate/user_manage/invite=DENY，内部码收回', () => {
    expect(state('CLIENT_ADMIN', 'client:sign')).toBe('Y')
    expect(state('CLIENT_SIGNER', 'client:sign')).toBe('Y')
    expect(state('CLIENT_ADMIN', 'client:elevator_view')).toBe('Y')
    expect(state('CLIENT_ADMIN', 'client:download')).toBe('Y')
    expect(state('CLIENT_ADMIN', 'client:report')).toBe('Y')
    expect(state('CLIENT_SIGNER', 'client:report')).toBe('Y')
    // 默认关闭
    expect(state('CLIENT_ADMIN', 'client:user_manage')).toBe('DENY')
    expect(state('CLIENT_ADMIN', 'client:evaluate')).toBe('DENY')
    expect(state('CLIENT_ADMIN', 'client:invite')).toBe('DENY')
    // 内部码全收回（抽样）
    ;['user:view', 'user:create', 'user:disable', 'stats:view', 'stats:export',
      'audit:view', 'role:assign', 'invite:manage', 'order:create', 'order:view_own', 'order:confirm',
      'record:view', 'record:sign', 'record:refuse', 'record:export', 'handover:view', 'handover:export',
      'project:view', 'contract:view', 'elevator:view', 'elevator:qr', 'elevator:export', 'plan:view'].forEach((code) => {
      expect(state('CLIENT_ADMIN', code)).toBe('DENY')
      expect(state('CLIENT_SIGNER', code)).toBe('DENY')
    })
    expect(canonicalCodes().size).toBe(84)
    expect(canonicalRoles().size).toBe(13)
    expect(state('SYS_ADMIN', 'client:user_manage')).toBe('Y')
  })
})