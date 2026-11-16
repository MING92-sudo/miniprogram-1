import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import PermButton from './PermButton.vue'

describe('PermButton（原型 actBtn 三态：Y 可用 / C 置灰+tooltip / DENY 隐藏）', () => {
  beforeEach(() => setActivePinia(createPinia()))
  it('Y renders enabled btn and emits click', async () => {
    const w = mount(PermButton, { props: { code: 'handover:review', role: 'INST_MGR', label: '审核' } })
    expect(w.find('button').attributes('disabled')).toBeUndefined()
    expect(w.find('.btn').exists()).toBe(true)
    await w.find('button').trigger('click')
    expect(w.emitted('click')).toBeTruthy()
  })
  it('DENY renders nothing (前端隐藏不作为安全边界)', () => {
    const w = mount(PermButton, { props: { code: 'handover:review', role: 'INST_CLERK', label: '审核' } })
    expect(w.find('button').exists()).toBe(false)
  })
  it('C is disabled with aria-disabled and perm-wrap tooltip', async () => {
    const w = mount(PermButton, { props: { code: 'handover:view', role: 'INST_CLERK', label: '查看' } })
    const b = w.find('button')
    expect(b.attributes('aria-disabled')).toBe('true')
    expect(b.attributes('disabled')).toBeDefined()
    expect(w.find('.perm-wrap').attributes('data-tip')).toContain('handover:view')
    await b.trigger('click')
    expect(w.emitted('click')).toBeFalsy()
  })
  it('variant and size map to prototype btn classes', () => {
    const w = mount(PermButton, { props: { code: 'handover:review', role: 'INST_MGR', label: '审核', variant: 'danger', size: 'xs', icon: 'ban' } })
    expect(w.find('.btn.btn-danger.btn-xs').exists()).toBe(true)
    expect(w.find('use').attributes('href')).toBe('#i-ban')
  })
})