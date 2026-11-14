<template>
  <span class="status-tag" :style="{ color: c.fg, borderColor: c.fg }">{{ label || statusLabel(status) }}</span>
</template>
<script setup>
// M2 状态标签：状态机色映射（spec §3.6）；默认中文标签
import { computed } from 'vue'
import { statusColor } from './status-colors'
import { statusLabel } from './labels'
const props = defineProps({ status: { type: String, required: true }, label: { type: String, default: '' } })
const c = computed(() => statusColor(props.status))
</script>
<style scoped>
.status-tag { display: inline-block; padding: 2px 8px; border: 1px solid; border-radius: 999px;
  font-size: 11px; font-family: var(--font-mono); letter-spacing: .04em; background: var(--panel); }
</style>