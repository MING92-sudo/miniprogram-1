<template>
  <div class="page">
    <div class="page-head"><h1>外接建档</h1></div>
    <DataTable :columns="cols" :rows="rows" :loading="loading" :search-keys="['elevatorCode', 'useUnitName']" />
  </div>
</template>
<script setup>
// M5 外接建档：只读列表（filing:view）
import { ref, onMounted } from 'vue'
import http from '../api/client'
import DataTable from '../ui/DataTable.vue'
const rows = ref([])
const loading = ref(false)
const cols = [
  { key: 'elevatorCode', label: '设备码', sortable: true },
  { key: 'useUnitName', label: '使用单位' },
  { key: 'status', label: '状态' },
  { key: 'createdAt', label: '创建' }
]
onMounted(async () => { loading.value = true; try { rows.value = (await http.get('/filings')).list || [] } finally { loading.value = false } })
</script>
<style scoped>
.page-head { margin-bottom: 16px; }
.page-head h1 { font-size: 20px; }
</style>