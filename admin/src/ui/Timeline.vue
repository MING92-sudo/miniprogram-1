<template>
  <div class="timeline">
    <div v-for="(it, i) in items" :key="i" :class="['tl-item', it.cls || 'done']">
      <div class="tl-head">
        <b>{{ it.title || it.actor }}</b>
        <span v-if="it.time" class="tl-time">{{ it.time }}</span>
      </div>
      <div v-if="body(it)" class="tl-body">{{ body(it) }}</div>
    </div>
  </div>
</template>
<script setup>
// 时间轴（原型 tlItem 对齐）：done/active/bad 三态圆点 + 操作人/角色/时间
import { roleLabel } from './labels'
defineProps({ items: { type: Array, default: () => [] } })
function body(it) {
  if (it.title) return [it.actor, it.role ? roleLabel(it.role) : '', it.text || it.note].filter(Boolean).join(' · ')
  return [it.role ? roleLabel(it.role) : '', it.text].filter(Boolean).join(' · ')
}
</script>