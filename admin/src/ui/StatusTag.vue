<template>
  <span :class="['tag', 'tag-' + tone]">{{ label || statusLabel(status) }}</span>
</template>
<script setup>
// 状态标签：原型 .tag 语义色（ok/amber/signal/dim/ghost）；默认中文标签
import { computed } from 'vue'
import { tagClass } from './status-colors'
import { statusLabel } from './labels'
const props = defineProps({ status: { type: String, required: true }, label: { type: String, default: '' } })
const tone = computed(() => tagClass(props.status))
</script>