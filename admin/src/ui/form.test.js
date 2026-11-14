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
  it('submit syncs stale values from modelValue before validating (regression: 密码至少6位)', async () => {
    // 回归：输入绑定在页面 model 上，values 经 watch 异步同步会滞后；
    // 提交前必须以 modelValue 为准同步，否则末位字符未入 values 会误判（如「密码至少 6 位」）。
    const w = mount(Form, { props: { rules, modelValue: { name: '李四' } }, slots: { default: '<input name="name" />' } })
    w.vm.values.name = '' // 模拟 watch 尚未 flush，values 滞后于 modelValue
    await w.find('form').trigger('submit')
    expect(w.emitted('submit')).toBeTruthy()
    expect(w.emitted('submit')[0][0]).toEqual({ name: '李四' })
  })
})