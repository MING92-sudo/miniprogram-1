<template>
  <div class="empty">
    <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--dim)" stroke-width="1.4"><path d="M4 7h16v13H4zM4 7l2-3h12l2 3M9 11h6"/></svg>
    <p class="empty-title">暂无数据</p>
    <div v-if="chips.length" class="empty-chips">
      <span v-for="c in chips" :key="c" class="chip">{{ c }}</span>
    </div>
    <button v-if="clearable" class="empty-clear" @click="$emit('clear')">清空筛选</button>
  </div>
</template>
<script setup>
// M2 空态：筛选 chips + 清空（spec §3.10）
defineProps({ chips: { type: Array, default: () => [] }, clearable: { type: Boolean, default: false } })
defineEmits(['clear'])
</script>
<style scoped>
.empty { text-align: center; padding: 40px 16px; color: var(--muted); }
.empty-title { font-size: 13px; margin: 12px 0; }
.empty-chips { display: flex; gap: 6px; justify-content: center; flex-wrap: wrap; margin-bottom: 12px; }
.chip { font-size: 11px; padding: 2px 8px; border: 1px solid var(--line-2); border-radius: 999px; color: var(--dim); }
.empty-clear { background: none; border: 1px solid var(--line-2); color: var(--amber); padding: 5px 12px; border-radius: 6px; cursor: pointer; }
</style>