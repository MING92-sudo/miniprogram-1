import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mount } from '@vue/test-utils'
import { useUiStore } from './stores/ui'
import Toast from './Toast.vue'

describe('ui store + Toast（原型 toast 对齐）', () => {
  beforeEach(() => setActivePinia(createPinia()))
  it('push adds a toast and dismiss removes it', () => {
    const ui = useUiStore()
    const id = ui.push('success', '已保存')
    expect(ui.toasts.length).toBe(1)
    ui.dismiss(id)
    expect(ui.toasts.length).toBe(0)
  })
  it('Toast renders queued messages with prototype tone class and code', () => {
    const ui = useUiStore()
    ui.push('error', '失败', 'code=500')
    const w = mount(Toast)
    expect(w.text()).toContain('失败')
    expect(w.find('.toast.err').exists()).toBe(true)
    expect(w.find('.t-code').text()).toBe('code=500')
  })
  it('collapsed toggles', () => {
    const ui = useUiStore()
    const before = ui.collapsed
    ui.toggleCollapsed()
    expect(ui.collapsed).toBe(!before)
  })
  it('sim toggle drives failure state and retry resets once', () => {
    const ui = useUiStore()
    ui.toggleSim()
    expect(ui.simError).toBe(true)
    ui.retry()
    expect(ui.retryOnce).toBe(true)
    ui.simOff()
    expect(ui.simError).toBe(false)
    expect(ui.retryOnce).toBe(false)
  })
})