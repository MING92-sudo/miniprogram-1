<template>
  <div class="page">
    <div class="page-head"><h1>维保计划（到期基线只读投影）</h1></div>
    <DataTable :columns="cols" :rows="rows" :loading="loading" :search-keys="['elevatorCode', 'elevatorName']" />
    <p class="hint">自动派单仅定时任务驱动；本页只读投影 + 手动派单/延期入口。</p>
  </div>
</template>
<script setup>
// M4 维保计划：只读投影（plan:view），手动派单 plan:generate
import { ref, onMounted } from 'vue'
import http from '../api/client'
import DataTable from '../ui/DataTable.vue'
const rows = ref([])
const loading = ref(false)
const cols = [
  { key: 'elevatorCode', label: '设备码', sortable: true },
  { key: 'elevatorName', label: '梯号' },
  { key: 'nextDate', label: '下次维保', sortable: true },
  { key: 'workTypeCode', label: '类别' }
]
onMounted(async () => { loading.value = true; try { rows.value = (await http.get('/maintenance-calendar')).entries || [] } finally { loading.value = false } })
</script>
<style scoped>
.page-head { margin-bottom: 16px; }
.page-head h1 { font-size: 20px; }
.hint { color: var(--dim); font-size: 12px; margin-top: 10px; }
</style>