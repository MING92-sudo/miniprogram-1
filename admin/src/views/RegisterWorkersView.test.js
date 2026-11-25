import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'

vi.mock('../api/client', () => {
  let seq = 0
  return {
    default: {
      get: vi.fn(),
      post: vi.fn()
    },
    onUnauthorized: vi.fn(),
    idempotencyKey: vi.fn(() => 'idem-test-' + (++seq))
  }
})

import http from '../api/client'
import { useAuthStore } from '../stores/auth'
import RegisterWorkersView from './RegisterWorkersView.vue'
import Modal from '../ui/Modal.vue'

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

describe('RegisterWorkersView 平台建档幂等（Task 20）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    useAuthStore().role = 'PLATFORM_ADMIN' // platform:worker_register Y
    vi.clearAllMocks()
    http.get.mockResolvedValue(platformRows)
    http.post.mockReset()
    http.post.mockResolvedValue({ account: '13800000001' })
  })

  async function mountModal() {
    const wrapper = mount(RegisterWorkersView, { global: { stubs: { PageMeta: true } } })
    await flushPromises()
    const open = wrapper.findAll('button').find(b => b.text().includes('维保人员建档'))
    expect(open, '应有「维保人员建档」入口').toBeTruthy()
    await open.trigger('click')
    await flushPromises()
    const modal = wrapper.findAllComponents(Modal).find(m => m.props('title') === '维保人员建档')
    expect(modal, '应弹出建档弹窗').toBeTruthy()
    return { wrapper, modal }
  }

  async function fillForm(modal) {
    await modal.find('input[placeholder="如：张伟"]').setValue('张伟')
    await modal.find('input[placeholder="证书编号（平台内唯一匹配键）"]').setValue('CQ3601030001')
    const dates = modal.findAll('input[type="date"]')
    await dates[0].setValue('2026-01-01')
    await dates[1].setValue('2026-12-31')
    await modal.find('input[placeholder="11 位手机号，用作登录账号"]').setValue('13800000001')
    const file = modal.find('input[type="file"]')
    Object.defineProperty(file.element, 'files', {
      value: [new File(['pdf'], 'cert.pdf', { type: 'application/pdf' })],
      configurable: true
    })
    await file.trigger('change')
  }

  function postedKey(call) {
    return call[2] && call[2].headers && call[2].headers['X-Idempotency-Key']
  }

  it('双击确认只发起一次平台建档请求', async () => {
    const pending = {}
    http.post.mockImplementation(() => new Promise((resolve) => { pending.resolve = resolve }))
    const { modal } = await mountModal()
    await fillForm(modal)

    modal.vm.$emit('confirm')
    modal.vm.$emit('confirm')
    await flushPromises()

    expect(http.post).toHaveBeenCalledTimes(1)
    expect(http.post.mock.calls[0][0]).toBe('/platform/register/worker')
    expect(modal.props('loading')).toBe(true)
    const confirmBtn = modal.findAll('button').find(b => b.text().includes('提交中'))
    expect(confirmBtn, '提交中确认按钮应显示 loading 文案').toBeTruthy()
    expect(confirmBtn.attributes('disabled')).toBeDefined()
    pending.resolve({ account: '13800000001' })
    await flushPromises()
    expect(http.post).toHaveBeenCalledTimes(1)
  })

  it('失败重试复用同一个 X-Idempotency-Key', async () => {
    http.post.mockRejectedValueOnce(new Error('平台暂不可达'))
    http.post.mockResolvedValueOnce({ account: '13800000001' })
    const { modal } = await mountModal()
    await fillForm(modal)

    modal.vm.$emit('confirm')
    await flushPromises()
    modal.vm.$emit('confirm')
    await flushPromises()

    expect(http.post).toHaveBeenCalledTimes(2)
    expect(postedKey(http.post.mock.calls[0])).toBeTruthy()
    expect(postedKey(http.post.mock.calls[1])).toBe(postedKey(http.post.mock.calls[0]))
  })

  it('成功后重新打开弹窗使用新的 X-Idempotency-Key', async () => {
    const { wrapper, modal } = await mountModal()
    await fillForm(modal)
    modal.vm.$emit('confirm')
    await flushPromises()

    const reopen = wrapper.findAll('button').find(b => b.text().includes('维保人员建档'))
    await reopen.trigger('click')
    await flushPromises()
    const secondModal = wrapper.findAllComponents(Modal).find(m => m.props('title') === '维保人员建档')
    await fillForm(secondModal)
    secondModal.vm.$emit('confirm')
    await flushPromises()

    expect(http.post).toHaveBeenCalledTimes(2)
    expect(postedKey(http.post.mock.calls[1])).toBeTruthy()
    expect(postedKey(http.post.mock.calls[1])).not.toBe(postedKey(http.post.mock.calls[0]))
  })

  it('平台登记成功但本地未建号（手机号互斥）时显示警示而非已开通账号', async () => {
    http.post.mockResolvedValue({ employeeCreated: false, account: '13800000001', employeeCreateReason: '手机号互斥（1002）：该手机号已是 维保经理' })
    const { wrapper, modal } = await mountModal()
    await fillForm(modal)
    modal.vm.$emit('confirm')
    await flushPromises()

    expect(http.post).toHaveBeenCalledTimes(1)
    const text = wrapper.text()
    expect(text).toContain('本地账号未开通')
    expect(text).toContain('维保经理')
    expect(text).toContain('账号管理 → 新增用户')
    expect(text).not.toContain('已开通维保账号')
    expect(wrapper.findAll('.alert-strip.as-sig').some(w => w.text().includes('本地账号未开通'))).toBe(true)
  })

  it('旧幂等重放响应无 employeeCreated 字段时仍按成功显示已开通账号', async () => {
    http.post.mockResolvedValue({ account: '13800000001' })
    const { wrapper, modal } = await mountModal()
    await fillForm(modal)
    modal.vm.$emit('confirm')
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('建档成功')
    expect(text).toContain('已开通维保账号')
    expect(text).not.toContain('本地账号未开通')
  })

  it('建立状态选「终止」提交成功后显示已终止绑定，不显示已开通账号', async () => {
    http.post.mockResolvedValue({ ok: true, employeeId: '', account: '13800000001', platformBackfill: { status: 'SKIPPED' } })
    const { wrapper, modal } = await mountModal()
    await fillForm(modal)
    expect(modal.findAll('select')).toHaveLength(1)
    await modal.find('select').setValue('1')
    modal.vm.$emit('confirm')
    await flushPromises()

    expect(http.post).toHaveBeenCalledTimes(1)
    expect(http.post.mock.calls[0][1].get('changState')).toBe('1')
    const text = wrapper.text()
    expect(text).toContain('已终止绑定')
    expect(text).not.toContain('已开通维保账号')
    expect(text).not.toContain('初始密码')
    expect(text).not.toContain('建档成功')
    expect(text).not.toContain('本地账号未开通')
  })
})
