<template>
  <div class="page">
    <div class="page-head"><h1>工单调度（急修/报修统一池）</h1></div>
    <DataTable :columns="cols" :rows="rows" :loading="loading" :search-keys="['faultNo', 'elevatorCode']" />
    <p class="hint">超时倒计时以创建时冻结的时限计算；派单/改派/升级/取消按权限。</p>
  </div>
</template>
<script setup>
// M4 工单调度：急修/报修统一池 + SLA 倒计时（order:view_pool / order:dispatch）
import { ref, onMounted } from 'vue'
import http from '../api/client'
import DataTable from '../ui/DataTable.vue'
const rows = ref([])
const loading = ref(false)
const cols = [
  { key: 'faultNo', label: '单号', sortable: true },
  { key: 'elevatorCode', label: '设备码' },
  { key: 'faultType', label: '类型' },
  { key: 'status', label: '状态' },
  { key: 'slaMinutes', label: '时限(分)' },
  { key: 'createdAt', label: '创建' }
]
onMounted(async () => { loading.value = true; try { rows.value = (await http.get('/faults')).list || [] } finally { loading.value = false } })
</script>
<style scoped>
.page-head { margin-bottom: 16px; }
.page-head h1 { font-size: 20px; }
.hint { color: var(--dim); font-size: 12px; margin-top: 10px; }
</style>