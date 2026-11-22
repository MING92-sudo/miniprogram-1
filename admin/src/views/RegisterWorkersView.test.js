import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'

vi.mock('../api/client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn()
  },
  onUnauthorized: vi.fn()
}))

import http from '../api/client'
import RegisterWorkersView from './RegisterWorkersView.vue'

const platformRows = [
  {
    id: 'platform-1',
    workManName: '张伟',
    workManCertificate: 'CQ3601030001',
    workManPhone: '13800000001',
    workStat: 'normal'
  },
  {
    id: 'platform-2',
    workManName: '李强',
    workManCertificate: 'CQ3601030003',
    workManPhone: '13800000002',
    workStat: 'normal'
  }
]

describe('RegisterWorkersView 平台人员字段映射', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    http.get.mockResolvedValue(platformRows)
  })

  it('将 2.5 原始字段渲染为姓名、证书编号、手机号和绑定状态', async () => {
    const wrapper = mount(RegisterWorkersView, { global: { stubs: { PageMeta: true } } })
    await flushPromises()

    expect(wrapper.findAll('tbody tr')).toHaveLength(2)
    expect(wrapper.text()).toContain('张伟')
    expect(wrapper.text()).toContain('李强')
    expect(wrapper.text()).toContain('CQ3601030001')
    expect(wrapper.text()).toContain('CQ3601030003')
    expect(wrapper.text()).toContain('13800000001')
    expect(wrapper.text()).toContain('13800000002')
    expect(wrapper.findAll('tbody .tag-ok')).toHaveLength(2)
    expect(wrapper.findAll('tbody .tag-ok')[0].text()).toBe('已绑定')
  })
})
