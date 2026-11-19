// 壳层 UI 状态：侧栏折叠（localStorage）+ Toast 队列 + 视图切换骨架
import { defineStore } from 'pinia'
let seq = 0
export const useUiStore = defineStore('ui', {
  state: () => ({
    collapsed: localStorage.getItem('ui_collapsed') === '1',
    toasts: [],
    simError: false,
    retryOnce: false,
    mobileOpen: false
  }),
  actions: {
    toggleCollapsed() {
      this.collapsed = !this.collapsed
      localStorage.setItem('ui_collapsed', this.collapsed ? '1' : '0')
    },
    push(type, msg, code) {
      const id = ++seq
      this.toasts.push({ id, type, msg, code: code || '' })
      setTimeout(() => this.dismiss(id), 3000)
      return id
    },
    dismiss(id) { this.toasts = this.toasts.filter(t => t.id !== id) },
    toggleSim() {
      this.simError = !this.simError
      this.retryOnce = false
      this.push(this.simError ? 'warn' : 'success', this.simError ? '模拟异常已开启 · 下一视图出失败态' : '模拟异常已关闭')
    },
    simOff() { this.simError = false; this.retryOnce = false },
    retry() { this.retryOnce = true }
  }
})
export function useToast() {
  const ui = useUiStore()
  return {
    success: (m, code) => ui.push('success', m, code),
    warn: (m, code) => ui.push('warn', m, code),
    error: (m, code) => ui.push('error', m, code)
  }
}