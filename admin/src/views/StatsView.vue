<template>
  <div class="page">
    <div class="page-head"><h1>统计报表</h1></div>
    <div class="kpi-grid">
      <div class="kpi-card"><p class="kpi-value num">{{ stats.elevators ?? 0 }}</p><p class="label">在管电梯</p></div>
      <div class="kpi-card"><p class="kpi-value num">{{ stats.workOrders ?? 0 }}</p><p class="label">工单</p></div>
      <div class="kpi-card"><p class="kpi-value num">{{ stats.records ?? 0 }}</p><p class="label">维保记录</p></div>
    </div>
    <div class="bars">
      <div v-for="b in bars" :key="b.label" class="bar-row">
        <span class="bar-label">{{ b.label }}</span>
        <div class="bar-track"><div class="bar-fill" :style="{ width: b.pct + '%', background: b.color }" /></div>
        <span class="num">{{ b.value }}</span>
      </div>
    </div>
    <p class="hint">普通导出脱敏；敏感导出需审批（data:export_sensitive）。</p>
  </div>
</template>
<script setup>
// M5 统计报表：随范围聚合（stats:view），六色可分离条形
import { ref, onMounted, computed } from 'vue'
import http from '../api/client'
const stats = ref({})
const bars = computed(() => {
  const max = Math.max(1, stats.value.elevators || 0, stats.value.workOrders || 0, stats.value.records || 0)
  return [
    { label: '电梯', value: stats.value.elevators || 0, pct: ((stats.value.elevators || 0) / max) * 100, color: 'var(--amber)' },
    { label: '工单', value: stats.value.workOrders || 0, pct: ((stats.value.workOrders || 0) / max) * 100, color: 'var(--ok)' },
    { label: '记录', value: stats.value.records || 0, pct: ((stats.value.records || 0) / max) * 100, color: 'var(--signal)' }
  ]
})
onMounted(async () => { stats.value = await http.get('/stats') })
</script>
<style scoped>
.page-head { margin-bottom: 16px; }
.page-head h1 { font-size: 20px; }
.kpi-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-bottom: 20px; }
.kpi-card { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 16px; }
.kpi-value { font-size: 28px; font-weight: 700; color: var(--amber); }
.bars { display: flex; flex-direction: column; gap: 10px; }
.bar-row { display: flex; align-items: center; gap: 10px; font-size: 12px; }
.bar-label { width: 48px; color: var(--muted); }
.bar-track { flex: 1; height: 10px; background: var(--panel-2); border-radius: 5px; }
.bar-fill { height: 100%; border-radius: 5px; }
.hint { color: var(--dim); font-size: 12px; margin-top: 14px; }
</style>