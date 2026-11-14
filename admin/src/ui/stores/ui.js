// M2 壳层 UI 状态：侧栏折叠（localStorage）+ Toast 队列
import { defineStore } from 'pinia'
let seq = 0
export const useUiStore = defineStore('ui', {
  state: () => ({
    collapsed: localStorage.getItem('ui_collapsed') === '1',
    toasts: []
  }),
  actions: {
    toggleCollapsed() {
      this.collapsed = !this.collapsed
      localStorage.setItem('ui_collapsed', this.collapsed ? '1' : '0')
    },
    push(type, msg) {
      const id = ++seq
      this.toasts.push({ id, type, msg })
      setTimeout(() => this.dismiss(id), 3000)
      return id
    },
    dismiss(id) { this.toasts = this.toasts.filter(t => t.id !== id) }
  }
})
export function useToast() {
  const ui = useUiStore()
  return {
    success: (m) => ui.push('success', m),
    warn: (m) => ui.push('warn', m),
    error: (m) => ui.push('error', m)
  }
}