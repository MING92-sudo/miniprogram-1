import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'

vi.mock('../api/client', () => ({
  default: { get: vi.fn() },
  onUnauthorized: vi.fn()
}))

import http from '../api/client'
import TemplateManageView from './TemplateManageView.vue'

describe('TemplateManageView 检查项字段映射', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    http.get.mockResolvedValue({
      list: [{
        id: 'item-1',
        templateType: 'MAINT',
        appendix: 'A',
        categoryScope: '曳引与强制驱动',
        freq: 'HM',
        itemCode: 'A-01',
        seq: 1,
        name: '机房环境检查',
        judgeType: 'PASS_FAIL',
        isKey: true,
        photoRequired: false,
        enabled: true
      }],
      total: 1
    })
  })

  it('按逐条检查项结构渲染品种、附件和项目编号', async () => {
    const wrapper = mount(TemplateManageView, { global: { stubs: { PageMeta: true } } })
    await flushPromises()

    expect(http.get).toHaveBeenCalledWith('/admin/templates')
    expect(wrapper.text()).toContain('机房环境检查')
    expect(wrapper.text()).toContain('曳引与强制驱动')
    expect(wrapper.text()).toContain('A-01')
    expect(wrapper.findAll('thead th').some(th => th.text() === '附件')).toBe(true)
    expect(wrapper.find('tbody').text()).toContain('A')
  })
})
