import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import EmptyState from './EmptyState.vue'
import LoadingSkeleton from './LoadingSkeleton.vue'
import ErrorState from './ErrorState.vue'
import ForbiddenState from './ForbiddenState.vue'

describe('four states', () => {
  it('EmptyState shows chips and emits clear', async () => {
    const w = mount(EmptyState, { props: { chips: ['状态: ACTIVE'], clearable: true } })
    expect(w.text()).toContain('状态: ACTIVE')
    await w.find('.empty-clear').trigger('click')
    expect(w.emitted('clear')).toBeTruthy()
  })
  it('LoadingSkeleton renders requested rows', () => {
    const w = mount(LoadingSkeleton, { props: { rows: 4 } })
    expect(w.findAll('.sk-row').length).toBe(4)
  })
  it('ErrorState shows reason and emits retry', async () => {
    const w = mount(ErrorState, { props: { reason: '网络超时' } })
    expect(w.text()).toContain('网络超时')
    await w.find('.err-retry').trigger('click')
    expect(w.emitted('retry')).toBeTruthy()
  })
  it('ForbiddenState shows code + allowed roles + current role', () => {
    const w = mount(ForbiddenState, { props: { code: 'contract:view', allowedRoles: ['MAINT_MGR'], currentRole: 'INST_MGR' } })
    expect(w.text()).toContain('contract:view')
    expect(w.text()).toContain('MAINT_MGR')
    expect(w.text()).toContain('INST_MGR')
  })
})