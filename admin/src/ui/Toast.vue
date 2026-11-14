<template>
  <div class="toast-wrap" role="status" aria-live="polite">
    <div v-for="t in ui.toasts" :key="t.id" :class="['toast', 'toast--' + t.type]">
      <span>{{ t.msg }}</span>
      <button class="toast-x" aria-label="关闭" @click="ui.dismiss(t.id)">×</button>
    </div>
  </div>
</template>
<script setup>
// M2 全局 Toast：三型 3 秒自动消失（spec §3.5）
import { useUiStore } from './stores/ui'
const ui = useUiStore()
</script>
<style scoped>
.toast-wrap { position: fixed; top: 16px; right: 16px; z-index: 1000; display: flex; flex-direction: column; gap: 8px; }
.toast { display: flex; align-items: center; gap: 10px; padding: 10px 14px; border-radius: 8px;
  background: var(--panel-2); border: 1px solid var(--line-2); color: var(--text); font-size: 13px;
  box-shadow: 0 6px 24px rgba(0,0,0,.4); }
.toast--success { border-color: var(--ok); }
.toast--warn { border-color: var(--amber); }
.toast--error { border-color: var(--signal); }
.toast-x { background: none; border: none; color: var(--muted); cursor: pointer; font-size: 16px; }
</style>