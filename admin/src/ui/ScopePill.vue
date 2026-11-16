<template>
  <span :class="['scope-pill', { all: scope === 'ALL' }]">
    <svg class="ic ic-sm" aria-hidden="true"><use href="#i-database" /></svg>
    {{ scope }}<template v-if="label"> · {{ label }}</template>
  </span>
</template>
<script setup>
// 数据范围胶囊（原型 scopePill 对齐）：ALL 琥珀高亮
defineProps({ scope: { type: String, default: '' }, label: { type: String, default: '' } })
</script>