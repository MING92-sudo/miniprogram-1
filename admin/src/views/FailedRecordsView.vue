<template>
  <div class="page">
    <div class="page-head"><h1>上报异常清单</h1></div>
    <DataTable :columns="cols" :rows="rows" :loading="loading" :search-keys="['elevatorCode']" />
    <p class="hint">逐条人工重报，禁止批量。</p>
  </div>
</template>
<script setup>
// M5 上报异常清单：逐条人工重报，禁批量
import { ref, onMounted } from 'vue'
import http from '../api/client'
import DataTable from '../ui/DataTable.vue'
const rows = ref([])
const loading = ref(false)
const cols = [
  { key: 'elevatorCode', label: '设备码', sortable: true },
  { key: 'workTypeCode', label: '类别' },
  { key: 'reportStatus', label: '上报状态' },
  { key: 'originalRecordId', label: '平台记录ID' }
]
onMounted(async () => {
  loading.value = true
  try { const all = await http.get('/admin/records'); rows.value = all.filter(r => r.reportStatus === 'FAILED' || r.reportStatus === 'PENDING') }
  finally { loading.value = false }
})
</script>
<style scoped>
.page-head { margin-bottom: 16px; }
.page-head h1 { font-size: 20px; }
.hint { color: var(--dim); font-size: 12px; margin-top: 10px; }
</style>