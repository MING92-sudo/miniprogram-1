<template>
  <div class="steps">
    <template v-for="(s, i) in states" :key="s">
      <div :class="['st', cls(i, s)]">
        <span class="dot">
          <svg v-if="i < idx" class="ic ic-sm" aria-hidden="true"><use href="#i-check" /></svg>
          <template v-else>{{ i + 1 }}</template>
        </span>
        <span class="st-k">{{ s }}</span>
      </div>
      <div v-if="i < states.length - 1" :class="['st-line', { done: i < idx }]" />
    </template>
  </div>
</template>
<script setup>
// 状态机 stepper（原型 stepsHtml 对齐）：done/active/bad + 连接线
import { computed } from 'vue'
const props = defineProps({
  states: { type: Array, required: true },
  current: { type: String, default: '' },
  badState: { type: String, default: '' }
})
const idx = computed(() => props.states.indexOf(props.current))
function cls(i, s) {
  if (props.badState && s === props.badState) return 'bad'
  if (i < idx.value) return 'done'
  if (i === idx.value) return 'active'
  return ''
}
</script>