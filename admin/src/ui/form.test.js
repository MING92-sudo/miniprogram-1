import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import Form from './Form.vue'

const rules = { name: [(v) => (v ? '' : '姓名必填')] }

describe('Form', () => {
  it('shows field error after submit when invalid', async () => {
    const w = mount(Form, { props: { rules, modelValue: { name: '' } }, slots: { default: '<input name="name" /><span>{{ errors.name }}</span>' } })
    await w.find('form').trigger('submit')
    expect(w.text()).toContain('姓名必填')
    expect(w.emitted('submit')).toBeFalsy()
  })
  it('emits submit when valid', async () => {
    const w = mount(Form, { props: { rules, modelValue: { name: '张三' } }, slots: { default: '<input name="name" />' } })
    await w.find('form').trigger('submit')
    expect(w.emitted('submit')).toBeTruthy()
  })
})