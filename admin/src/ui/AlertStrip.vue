<template>
  <div :class="['alert-strip', tone]">
    <svg class="ic" aria-hidden="true"><use :href="'#i-' + icon" /></svg>
    <b>{{ title }}</b>
    <span>{{ text }}</span>
    <span v-if="to" class="as-link" @click="$router.push(to)">查看
      <svg class="ic ic-sm" aria-hidden="true"><use href="#i-arrow-right" /></svg>
    </span>
  </div>
</template>
<script setup>
// 预警条（原型 alertStrip 对齐）：as-sig / as-ok / as-info + 跳转链接
defineProps({
  tone: { type: String, default: '' },
  icon: { type: String, default: 'alert' },
  title: { type: String, default: '' },
  text: { type: String, default: '' },
  to: { type: String, default: '' }
})
</script>