<template>
  <div class="toast-root" role="status" aria-live="polite">
    <div v-for="t in ui.toasts" :key="t.id" :class="['toast', tone(t.type)]">
      <span class="t-ico">
        <svg class="ic ic-sm" aria-hidden="true"><use :href="'#i-' + (t.type === 'warn' ? 'alert' : t.type === 'error' ? 'x' : 'check')" /></svg>
      </span>
      <div class="t-body">
        <b>{{ t.msg }}</b>
        <div v-if="t.code" class="t-code">{{ t.code }}</div>
      </div>
    </div>
  </div>
</template>
<script setup>
// 全局 Toast（原型 toast 对齐）：右下角三型 3 秒自动消失 + 可选 mono 副文案
import { useUiStore } from './stores/ui'
const ui = useUiStore()
function tone(type) { return type === 'success' ? 'ok' : type === 'error' ? 'err' : 'warn' }
</script>