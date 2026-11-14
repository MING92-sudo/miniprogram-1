<template>
  <div class="skeleton" aria-busy="true" aria-label="加载中">
    <div v-for="n in rows" :key="n" class="sk-row" />
  </div>
</template>
<script setup>
// M2 加载骨架：220ms 后显示由调用方控制（spec §3.10）
defineProps({ rows: { type: Number, default: 5 } })
</script>
<style scoped>
.skeleton { display: flex; flex-direction: column; gap: 10px; padding: 12px; }
.sk-row { height: 40px; border-radius: 6px; background: linear-gradient(90deg, var(--panel) 25%, var(--panel-2) 50%, var(--panel) 75%); background-size: 200% 100%; animation: sk 1.2s infinite; }
@keyframes sk { to { background-position: -200% 0; } }
</style>