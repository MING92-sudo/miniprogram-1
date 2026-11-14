<template>
  <div v-if="open" class="modal-scrim" @click.self="$emit('close')">
    <div :class="['modal', { 'modal--danger': danger }]" role="dialog" aria-modal="true" :aria-label="title">
      <div class="modal-head"><span>{{ title }}</span><button class="modal-x" aria-label="关闭" @click="$emit('close')">×</button></div>
      <div class="modal-body"><slot /></div>
      <label v-if="requireConfirm" class="modal-confirm-row">
        <input type="checkbox" class="modal-check" v-model="checked" /> 我已知悉此操作不可逆
      </label>
      <div class="modal-foot">
        <button class="modal-cancel" @click="$emit('close')">取消</button>
        <button class="modal-confirm" :disabled="requireConfirm && !checked" @click="onConfirm">{{ confirmLabel }}</button>
      </div>
    </div>
  </div>
</template>
<script setup>
// M2 模态：危险变体 + 勾选二次确认（spec §3.3）
import { ref, watch } from 'vue'
const props = defineProps({
  open: Boolean, title: { type: String, default: '' }, danger: Boolean,
  requireConfirm: Boolean, confirmLabel: { type: String, default: '确认' }
})
const emit = defineEmits(['close', 'confirm'])
const checked = ref(false)
watch(() => props.open, (o) => { if (o) checked.value = false })
function onConfirm() { if (props.requireConfirm && !checked.value) return; emit('confirm') }
</script>
<style scoped>
.modal-scrim { position: fixed; inset: 0; background: var(--scrim); display: flex; align-items: center; justify-content: center; z-index: 900; }
.modal { width: 440px; max-width: 92vw; background: var(--panel); border: 1px solid var(--line-2); border-radius: 10px; }
.modal--danger { border-color: var(--signal); }
.modal-head { display: flex; justify-content: space-between; padding: 14px 16px; border-bottom: 1px solid var(--line); font-size: 14px; font-weight: 600; }
.modal-x { background: none; border: none; color: var(--muted); font-size: 18px; cursor: pointer; }
.modal-body { padding: 16px; font-size: 13px; }
.modal-confirm-row { display: flex; gap: 8px; align-items: center; padding: 0 16px 12px; font-size: 12px; color: var(--muted); }
.modal-foot { display: flex; justify-content: flex-end; gap: 8px; padding: 12px 16px; border-top: 1px solid var(--line); }
.modal-cancel { background: var(--panel-2); border: 1px solid var(--line-2); color: var(--text); padding: 6px 14px; border-radius: 6px; cursor: pointer; }
.modal-confirm { background: var(--amber); border: none; color: var(--accent-on-amber); padding: 6px 14px; border-radius: 6px; cursor: pointer; font-weight: 600; }
.modal--danger .modal-confirm { background: var(--signal); color: var(--accent-on-signal); }
.modal-confirm:disabled { opacity: .45; cursor: not-allowed; }
</style>