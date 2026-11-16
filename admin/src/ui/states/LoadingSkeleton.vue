<template>
  <div class="skel-wrap" aria-busy="true" aria-label="加载中">
    <div class="skel hl" style="width:240px" />
    <div class="skel" style="width:60%" />
    <div class="skel-bars mt8">
      <div class="skel lg grow" />
      <div class="skel lg" style="width:120px" />
    </div>
    <div v-for="n in rows" :key="n" class="skel row" />
  </div>
</template>
<script setup>
// 加载骨架（原型 skeleton 对齐）：表头 + 灰块行，hazard 微光动画
defineProps({ rows: { type: Number, default: 8 } })
</script>