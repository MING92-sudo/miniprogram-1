import { describe, it, expect } from 'vitest'
import { canonical, state, allowsNew, canonicalCodes, canonicalRoles, defaultScope } from './permission-v2'

describe('permission-v2 mirror', () => {
  it('has 86 canonical codes', () => {
    expect(canonicalCodes().size).toBe(86)
    expect(canonicalCodes().has('handover:view')).toBe(true)
    expect(canonicalCodes().has('record:spotcheck')).toBe(true)
    expect(canonicalCodes().has('user:reset_password')).toBe(true)
  })
  it('has 13 canonical roles', () => {
    expect(canonicalRoles().size).toBe(13)
  })
  it('aliases old codes to canonical', () => {
    expect(canonical('ADMIN')).toBe('PLATFORM_ADMIN')
    expect(canonical('LEADER')).toBe('MAINT_SUP')
    expect(canonical('INSTALL_MANAGER')).toBe('INST_PM')
    expect(canonical('UNIT_ADMIN')).toBe('CLIENT_ADMIN')
    expect(canonical('INST_MGR')).toBe('INST_MGR')
    expect(canonical('HACKER')).toBe(null)
    expect(canonical(null)).toBe(null)
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
  })
  it('defaultScope mirrors sys_role_scope', () => {
    expect(defaultScope('PLATFORM_ADMIN')).toBe('ALL')
    expect(defaultScope('INST_MGR')).toBe('ORG')
    expect(defaultScope('INST_PM')).toBe('PROJECT')
    expect(defaultScope('MAINT_SUP')).toBe('TEAM')
    expect(defaultScope('MAINT_TECH')).toBe('SELF')
    expect(defaultScope('CLIENT_ADMIN')).toBe('ORG_FLAT')
    expect(defaultScope('HACKER')).toBe(null)
  })
})