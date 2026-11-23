<template>
  <div v-if="open" class="scrim fade" @click.self="$emit('close')">
    <div :class="['modal', { danger }]" role="dialog" aria-modal="true" :aria-label="title">
      <div class="modal-head">
        <span class="mh-ico"><svg class="ic" aria-hidden="true"><use :href="'#i-' + (icon || 'info')" /></svg></span>
        <div class="grow">
          <h3>{{ title }}</h3>
          <div v-if="sub" class="mh-sub">{{ sub }}</div>
        </div>
        <button class="icon-btn mh-x" aria-label="关闭" @click="$emit('close')">
          <svg class="ic" aria-hidden="true"><use href="#i-x" /></svg>
        </button>
      </div>
      <div class="modal-body">
        <slot />
        <label v-if="requireConfirm" class="perm-item mt12" style="cursor:pointer">
          <input v-model="checked" type="checkbox" class="chk" />
          <span>{{ confirmText || '我已知悉此操作不可逆' }}</span>
        </label>
      </div>
      <div class="modal-foot">
        <span v-if="note" class="mf-note">{{ note }}</span>
        <button class="btn btn-ghost" type="button" @click="$emit('close')">取消</button>
        <button :class="['btn', danger ? 'btn-solid-sig' : 'btn-primary']" type="button"
          :disabled="loading || (requireConfirm && !checked)" @click="onConfirm">{{ confirmLabel }}</button>
      </div>
    </div>
  </div>
</template>
<script setup>
// 模态（原型 openModal/modalHead 对齐）：危险变体描边 signal + 勾选二次确认才可提交
import { ref, watch } from 'vue'
const props = defineProps({
  open: Boolean, title: { type: String, default: '' }, sub: { type: String, default: '' },
  icon: { type: String, default: '' }, danger: Boolean, note: { type: String, default: '' },
  requireConfirm: Boolean, confirmText: { type: String, default: '' },
  confirmLabel: { type: String, default: '确认' }, loading: Boolean
})
const emit = defineEmits(['close', 'confirm'])
const checked = ref(false)
watch(() => props.open, (o) => { if (o) checked.value = false })
function onConfirm() {
  if (props.loading) return
  if (props.requireConfirm && !checked.value) return
  emit('confirm')
}
</script>