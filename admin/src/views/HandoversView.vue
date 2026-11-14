<template>
  <div class="page">
    <div class="page-head"><h1>移交管理</h1></div>
    <DataTable :columns="cols" :rows="rows" :loading="loading" :search-keys="['handoverNo', 'elevatorCode']" />
  </div>
</template>
<script setup>
// M4 移交管理：10 态列表（handover:view），详情时间轴见抽屉（复用既有 /handovers）
import { ref, onMounted } from 'vue'
import http from '../api/client'
import DataTable from '../ui/DataTable.vue'
const rows = ref([])
const loading = ref(false)
const cols = [
  { key: 'handoverNo', label: '移交单号', sortable: true },
  { key: 'elevatorCode', label: '设备码' },
  { key: 'status', label: '状态' },
  { key: 'installOrg', label: '安装单位' },
  { key: 'createdAt', label: '创建' }
]
onMounted(async () => { loading.value = true; try { rows.value = await http.get('/handovers') } finally { loading.value = false } })
</script>
<style scoped>
.page-head { margin-bottom: 16px; }
.page-head h1 { font-size: 20px; }
</style>