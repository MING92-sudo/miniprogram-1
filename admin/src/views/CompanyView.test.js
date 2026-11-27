import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'

vi.mock('../api/archive', () => ({
  company: vi.fn(),
  updateCompany: vi.fn()
}))

import { company, updateCompany } from '../api/archive'
import CompanyView from './CompanyView.vue'

const PermButtonStub = {
  name: 'PermButton',
  props: ['label'],
  emits: ['click'],
  template: '<button type="button" @click="$emit(\'click\')">{{ label }}</button>'
}

const base = {
  name: '重庆博威电梯有限公司',
  organizationCode: '91500000TEST0001X',
  workMenegerName: '赵敏',
  workMenegerPhone: '13700000088',
  entityId: ''
}

function mountView() {
  return mount(CompanyView, {
    global: {
      stubs: { PageMeta: true, ViewHead: true, PermButton: PermButtonStub }
    }
  })
}

function inputs(wrapper) {
  return wrapper.findAll('.form-grid input').slice(0, 4)
}

function clickButton(wrapper, text) {
  const btn = wrapper.findAll('button').find((b) => b.text().includes(text))
  expect(btn, '按钮不存在: ' + text).toBeTruthy()
  return btn.trigger('click')
}

describe('CompanyView 主体ID同步与只读态', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('已有主体ID时加载为只读，点“编辑”后解锁', async () => {
    company.mockResolvedValue({ ...base, entityId: 'E1' })
    const wrapper = mountView()
    await flushPromises()

    expect(inputs(wrapper).every((i) => i.attributes('disabled') !== undefined)).toBe(true)
    expect(wrapper.text()).toContain('当前档案已记录平台主体ID')
    expect(wrapper.findAll('button').some((b) => b.text().includes('重新同步'))).toBe(true)

    await clickButton(wrapper, '编辑')
    expect(inputs(wrapper).every((i) => i.attributes('disabled') === undefined)).toBe(true)
    expect(wrapper.text()).toContain('保存单位信息')
  })

  it('保存后同步成功则回填主体ID并锁定字段', async () => {
    company.mockResolvedValue({ ...base, entityId: 'E1' })
    updateCompany.mockResolvedValue({ ...base, name: '重庆博威电梯有限公司', entityId: 'E2', platformSync: 'OK' })
    const wrapper = mountView()
    await flushPromises()

    await clickButton(wrapper, '编辑')
    await clickButton(wrapper, '保存单位信息')
    await flushPromises()

    expect(updateCompany).toHaveBeenCalledWith({
      name: base.name,
      organizationCode: base.organizationCode,
      workMenegerName: base.workMenegerName,
      workMenegerPhone: base.workMenegerPhone
    })
    expect(inputs(wrapper).every((i) => i.attributes('disabled') !== undefined)).toBe(true)
    expect(wrapper.findAll('.form-grid input')[4].element.value).toBe('E2')
    expect(wrapper.text()).toContain('当前档案已记录平台主体ID')
  })

  it('平台凭证未配置时按成功提示，不提供无效的重试入口', async () => {
    company.mockResolvedValue(base)
    updateCompany.mockResolvedValue({ ...base, platformSync: 'SKIPPED' })
    const wrapper = mountView()
    await flushPromises()

    await clickButton(wrapper, '保存单位信息')
    await flushPromises()

    expect(inputs(wrapper).every((i) => i.attributes('disabled') === undefined)).toBe(true)
    expect(wrapper.text()).toContain('监管平台凭证未配置')
    expect(wrapper.findAll('button').some((b) => b.text().includes('重试同步主体ID'))).toBe(false)
  })

  it('已有主体ID时同步失败仍提示并可重试，不会静默隐藏', async () => {
    company.mockResolvedValue({ ...base, entityId: 'E1' })
    updateCompany.mockResolvedValue({ ...base, entityId: 'E1', platformSync: 'FAILED' })
    const wrapper = mountView()
    await flushPromises()

    await clickButton(wrapper, '编辑')
    await clickButton(wrapper, '保存单位信息')
    await flushPromises()

    expect(wrapper.text()).toContain('平台同步失败')
    expect(wrapper.findAll('button').some((b) => b.text().includes('重试同步主体ID'))).toBe(true)
  })

  it('同步未命中时保存仍生效，重试会带当前表单值重新保存并同步', async () => {
    company.mockResolvedValue(base)
    updateCompany.mockResolvedValueOnce({ ...base, platformSync: 'NO_MATCH' })
      .mockResolvedValueOnce({ ...base, entityId: 'E3', platformSync: 'OK' })
    const wrapper = mountView()
    await flushPromises()

    await clickButton(wrapper, '保存单位信息')
    await flushPromises()

    expect(inputs(wrapper).every((i) => i.attributes('disabled') === undefined)).toBe(true)
    expect(wrapper.text()).toContain('平台未查询到该单位主体')
    expect(wrapper.text()).toContain('重试同步主体ID')

    await clickButton(wrapper, '重试同步主体ID')
    await flushPromises()

    expect(updateCompany).toHaveBeenCalledTimes(2)
    expect(inputs(wrapper).every((i) => i.attributes('disabled') !== undefined)).toBe(true)
    expect(wrapper.findAll('.form-grid input')[4].element.value).toBe('E3')
  })
})
