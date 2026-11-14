import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mount } from '@vue/test-utils'
import { useUiStore } from './stores/ui'
import Toast from './Toast.vue'

describe('ui store + Toast', () => {
  beforeEach(() => setActivePinia(createPinia()))
  it('push adds a toast and dismiss removes it', () => {
    const ui = useUiStore()
    const id = ui.push('success', '已保存')
    expect(ui.toasts.length).toBe(1)
    ui.dismiss(id)
    expect(ui.toasts.length).toBe(0)
  })
  it('Toast renders queued messages with type class', () => {
    const ui = useUiStore()
    ui.push('error', '失败')
    const w = mount(Toast)
    expect(w.text()).toContain('失败')
    expect(w.find('.toast--error').exists()).toBe(true)
  })
  it('collapsed toggles', () => {
    const ui = useUiStore()
    const before = ui.collapsed
    ui.toggleCollapsed()
    expect(ui.collapsed).toBe(!before)
  })
})