<template>
  <button
    :class="['perm-btn', variant, { 'is-deny': gate === 'deny', 'is-cond': gate === 'conditional' }]"
    :disabled="gate === 'deny'"
    :aria-disabled="gate === 'conditional' ? 'true' : null"
    :title="gate === 'deny' ? '所需权限：' + code : (gate === 'conditional' ? '需审批/前置未满足：' + code : null)"
    @click.capture="onClick"
  >{{ label }}</button>
</template>
<script setup>
// M2 权限按钮：两档门控（spec §4.3）——Y 可点 / DENY native disabled+title / C aria-disabled+捕获拦截
import { computed } from 'vue'
import { state } from './permission-v2'
const props = defineProps({
  code: { type: String, required: true },
  role: { type: String, default: '' },
  label: { type: String, default: '操作' },
  variant: { type: String, default: 'primary' }
})
const emit = defineEmits(['click', 'blocked'])
const gate = computed(() => {
  const s = state(props.role, props.code)
  return s === 'Y' ? 'allow' : s === 'C' ? 'conditional' : 'deny'
})
function onClick(e) {
  if (gate.value === 'deny') { e.preventDefault(); e.stopPropagation(); return }
  if (gate.value === 'conditional') { e.preventDefault(); e.stopPropagation(); emit('blocked', props.code); return }
  emit('click', e)
}
</script>
<style scoped>
.perm-btn { padding: 6px 14px; border-radius: 6px; border: 1px solid var(--line-2); cursor: pointer;
  font-size: 13px; background: var(--panel-2); color: var(--text); }
.perm-btn.primary { background: var(--amber); color: var(--accent-on-amber); border-color: var(--amber); font-weight: 600; }
.perm-btn.danger { background: transparent; color: var(--signal); border-color: var(--signal); }
.perm-btn.is-deny { opacity: .45; cursor: not-allowed; }
.perm-btn.is-cond { opacity: .7; cursor: help; }
</style>