import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import PermButton from './PermButton.vue'

describe('PermButton two-tier gate', () => {
  beforeEach(() => setActivePinia(createPinia()))
  it('Y renders enabled and emits click', async () => {
    const w = mount(PermButton, { props: { code: 'handover:review', role: 'INST_MGR', label: '审核' } })
    expect(w.find('button').attributes('disabled')).toBeUndefined()
    await w.find('button').trigger('click')
    expect(w.emitted('click')).toBeTruthy()
  })
  it('DENY is native disabled with Chinese permission title', () => {
    const w = mount(PermButton, { props: { code: 'handover:review', role: 'INST_CLERK', label: '审核' } })
    const b = w.find('button')
    expect(b.attributes('disabled')).toBeDefined()
    expect(b.attributes('title')).toContain('审核移交')
  })
  it('C is aria-disabled, focusable, click intercepted', async () => {
    const w = mount(PermButton, { props: { code: 'handover:view', role: 'INST_CLERK', label: '查看' } })
    const b = w.find('button')
    expect(b.attributes('aria-disabled')).toBe('true')
    expect(b.attributes('disabled')).toBeUndefined()
    await b.trigger('click')
    expect(w.emitted('click')).toBeFalsy()
    expect(w.emitted('blocked')).toBeTruthy()
  })
})