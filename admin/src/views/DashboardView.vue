<template>
  <div class="workbench">
    <div class="wb-head">
      <h1 class="wb-title">工作台</h1>
      <div class="persp-switch">
        <button v-for="r in perspective.roleSet" :key="r" :class="['persp-btn', { active: r === perspective.activeRole }]" @click="perspective.setRole(r)">{{ r }}</button>
      </div>
    </div>
    <div v-if="overdue" class="alert-bar">SLA 超时 {{ overdue }} 单，请优先处理</div>
    <div class="kpi-grid">
      <div v-for="k in kpis" :key="k.key" class="kpi-card">
        <p class="kpi-value num">{{ k.value }}</p>
        <p class="kpi-label label">{{ k.label }}</p>
      </div>
    </div>
    <section class="wb-todos">
      <h2 class="label">待办</h2>
      <ul v-if="todos.length">
        <li v-for="t in todos" :key="t.id">{{ t.text }}</li>
      </ul>
      <p v-else class="wb-empty">暂无待办</p>
    </section>
  </div>
</template>
<script setup>
// M2 工作台：KPI 7 项 + 预警 + 待办 + 视角切换（spec §5.2）；M2 数据源 mock 基准集
import { computed } from 'vue'
import { provider } from '../ui/providers/provider'
import { usePerspectiveStore } from '../ui/stores/perspective'
const perspective = usePerspectiveStore()
const kpis = computed(() => provider.getKPIs(perspective.activeRole))
const todos = computed(() => provider.getTodos(perspective.activeRole))
const overdue = computed(() => (kpis.value.find(k => k.key === 'slaOverdue') || {}).value || 0)
</script>
<style scoped>
.wb-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.wb-title { font-size: 20px; font-weight: 600; }
.persp-switch { display: flex; gap: 6px; flex-wrap: wrap; }
.persp-btn { background: var(--panel-2); border: 1px solid var(--line-2); color: var(--muted); padding: 4px 10px; border-radius: 6px; cursor: pointer; font-size: 11px; font-family: var(--font-mono); }
.persp-btn.active { background: var(--amber); color: var(--accent-on-amber); border-color: var(--amber); }
.alert-bar { background: var(--signal); color: var(--accent-on-signal); padding: 8px 14px; border-radius: 6px; font-size: 13px; margin-bottom: 16px; }
.kpi-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); gap: 12px; }
.kpi-card { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 16px; }
.kpi-value { font-size: 28px; font-weight: 700; color: var(--amber); }
.kpi-label { margin-top: 6px; }
.wb-todos { margin-top: 24px; }
.wb-todos ul { list-style: none; padding: 0; margin: 8px 0; }
.wb-todos li { padding: 10px 12px; background: var(--panel); border: 1px solid var(--line); border-radius: 6px; margin-bottom: 6px; font-size: 13px; }
.wb-empty { color: var(--dim); font-size: 13px; }
</style>