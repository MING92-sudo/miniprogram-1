import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import EmptyState from './EmptyState.vue'
import LoadingSkeleton from './LoadingSkeleton.vue'
import ErrorState from './ErrorState.vue'
import ForbiddenState from './ForbiddenState.vue'

const routerMock = { global: { mocks: { $router: { push: () => {} } } } }

describe('four states（原型 §7 对齐）', () => {
  it('EmptyState shows chips and emits clear', async () => {
    const w = mount(EmptyState, { props: { chips: ['状态: ACTIVE'], clearable: true } })
    expect(w.text()).toContain('状态: ACTIVE')
    expect(w.find('.chip.on').exists()).toBe(true)
    await w.find('.st-acts .btn').trigger('click')
    expect(w.emitted('clear')).toBeTruthy()
  })
  it('LoadingSkeleton renders requested rows', () => {
    const w = mount(LoadingSkeleton, { props: { rows: 4 } })
    expect(w.findAll('.skel.row').length).toBe(4)
  })
  it('ErrorState shows reason and emits retry', async () => {
    const w = mount(ErrorState, { props: { reason: '网络超时' } })
    expect(w.text()).toContain('网络超时')
    expect(w.find('.err-state').exists()).toBe(true)
    await w.find('.st-acts .btn-primary').trigger('click')
    expect(w.emitted('retry')).toBeTruthy()
  })
  it('ErrorState sim variant emits sim-off', async () => {
    const w = mount(ErrorState, { props: { reason: 'GET /admin/x (mock) · 503', sim: true } })
    const btns = w.findAll('.st-acts .btn')
    await btns[1].trigger('click')
    expect(w.emitted('sim-off')).toBeTruthy()
  })
  it('ForbiddenState shows required code + allowed roles + current role', () => {
    const w = mount(ForbiddenState, { props: { code: 'contract:view', allowedRoles: ['MAINT_MGR'], currentRole: 'INST_MGR' } }, routerMock)
    expect(w.text()).toContain('contract:view')
    expect(w.text()).toContain('维保部经理')
    expect(w.text()).toContain('安装部主管')
    expect(w.find('.code-big').text()).toBe('403')
  })
  it('ForbiddenState unknown renders 404', () => {
    const w = mount(ForbiddenState, { props: { unknown: true } }, routerMock)
    expect(w.find('.code-big.sig').text()).toBe('404')
  })
})