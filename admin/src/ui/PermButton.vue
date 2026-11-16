<template>
  <span v-if="gate === 'conditional'" class="perm-wrap" :data-tip="tip">
    <button :class="btnCls" disabled aria-disabled="true" type="button">
      <span v-if="icon" v-html="iconSvg" /><span>{{ label }}</span>
    </button>
  </span>
  <button v-else-if="gate === 'allow'" :class="btnCls" type="button" @click="$emit('click', $event)">
    <span v-if="icon" v-html="iconSvg" /><span>{{ label }}</span>
  </button>
  <!-- DENY → 不渲染（原型 actBtn：'-' 隐藏；前端隐藏不作为安全边界） -->
</template>
<script setup>
// 权限按钮（原型 actBtn 三态）：Y 可用 / C 置灰+tooltip / DENY 隐藏；后端强校验兜底
import { computed } from 'vue'
import { state } from './permission-v2'
import { icon as svgIcon } from './icons'
import { useAuthStore } from '../stores/auth'
const props = defineProps({
  code: { type: String, required: true },
  role: { type: String, default: '' },
  label: { type: String, default: '操作' },
  variant: { type: String, default: 'primary' },
  size: { type: String, default: '' },
  icon: { type: String, default: '' },
  cTip: { type: String, default: '' }
})
defineEmits(['click', 'blocked'])
const auth = useAuthStore()
const gate = computed(() => {
  const s = state(props.role || auth.role, props.code)
  return s === 'Y' ? 'allow' : s === 'C' ? 'conditional' : 'deny'
})
const tip = computed(() => props.cTip || ('需权限 ' + props.code + '：当前角色为只读 / 需审批 / 仅限非管理角色'))
const VARIANT = { primary: 'btn-primary', ghost: 'btn-ghost', danger: 'btn-danger', ok: 'btn-ok', 'solid-sig': 'btn-solid-sig' }
const btnCls = computed(() => ['btn', VARIANT[props.variant] || 'btn-ghost', props.size ? 'btn-' + props.size : ''])
const iconSvg = computed(() => (props.icon ? svgIcon(props.icon) : ''))
</script>