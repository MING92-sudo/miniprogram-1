import { describe, it, expect } from 'vitest'
import {
  statusLabel, roleLabel, roleCodeFromName, scopeLabel, permLabel,
  workTypeLabel, orgTypeLabel, channelLabel, enabledLabel, inviteTypeLabel,
  triStateSymbol, formatCell
} from './labels'

describe('中文标签层', () => {
  it('状态码中文化，未知值原样返回', () => {
    expect(statusLabel('INSPECTING')).toBe('查验中')
    expect(statusLabel('ARCHIVED')).toBe('已归档')
    expect(statusLabel('PASS')).toBe('合格')
    expect(statusLabel('已是中文')).toBe('已是中文')
    expect(statusLabel('')).toBe('')
  })
  it('角色码与旧码别名中文化', () => {
    expect(roleLabel('MAINT_TECH')).toBe('维保技术员')
    expect(roleLabel('WORKER')).toBe('维保技术员')
    expect(roleLabel('PLATFORM_ADMIN')).toBe('平台管理员')
  })
  it('中文角色名反查规范码（兼容直接填码）', () => {
    expect(roleCodeFromName('维保技术员')).toBe('MAINT_TECH')
    expect(roleCodeFromName('维保主管')).toBe('MAINT_SUP')
    expect(roleCodeFromName('MAINT_TECH')).toBe('MAINT_TECH')
    expect(roleCodeFromName('WORKER')).toBe('MAINT_TECH')
  })
  it('数据范围/权限码/维保类别/组织类型/渠道中文化', () => {
    expect(scopeLabel('ALL')).toBe('全部')
    expect(scopeLabel('TEAM')).toBe('本班组')
    expect(permLabel('handover:inspect')).toBe('现场查验')
    expect(workTypeLabel('HM')).toBe('半月维保')
    expect(workTypeLabel('OY')).toBe('年度维保')
    expect(orgTypeLabel('DEPT')).toBe('部门')
    expect(channelLabel('PHONE')).toBe('电话')
    expect(inviteTypeLabel('INVITE')).toBe('邀请码')
  })
  it('启用布尔与三态符号', () => {
    expect(enabledLabel(true)).toBe('启用')
    expect(enabledLabel(false)).toBe('停用')
    expect(triStateSymbol('Y')).toBe('✓')
    expect(triStateSymbol('C')).toBe('◐')
    expect(triStateSymbol('DENY')).toBe('✕')
  })
  it('formatCell 自动中文化常见枚举列', () => {
    expect(formatCell({ key: 'status' }, 'RECTIFYING')).toBe('整改中')
    expect(formatCell({ key: 'reportStatus' }, 'FAILED')).toBe('上报失败')
    expect(formatCell({ key: 'workTypeCode' }, 'TM')).toBe('季度维保')
    expect(formatCell({ key: 'permCode' }, 'order:dispatch')).toBe('派单')
    expect(formatCell({ key: 'enabled' }, true)).toBe('启用')
    expect(formatCell({ key: 'roles' }, 'MAINT_TECH,MAINT_SUP')).toBe('维保技术员、维保主管')
    expect(formatCell({ key: 'roles' }, ['MAINT_MGR'])).toBe('维保部经理')
    expect(formatCell({ key: 'name' }, '张三')).toBe('张三')
    expect(formatCell({ key: 'type', format: orgTypeLabel }, 'UNIT')).toBe('使用单位')
  })
})