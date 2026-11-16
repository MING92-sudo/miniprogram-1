<template>
  <div class="rule-note">
    <svg class="ic" aria-hidden="true"><use :href="'#i-' + icon" /></svg>
    <span><slot /></span>
  </div>
</template>
<script setup>
// 规则提示条（原型 rule-note）：左侧琥珀竖线 + 图标 + 说明文案
defineProps({ icon: { type: String, default: 'info' } })
</script>