<template>
  <div class="page">
    <div class="page-head"><h1>上报日志</h1></div>
    <DataTable :columns="cols" :rows="rows" :loading="loading" :search-keys="['elevatorCode']" />
  </div>
</template>
<script setup>
// M5 上报日志：只读列表
import { ref, onMounted } from 'vue'
import http from '../api/client'
import DataTable from '../ui/DataTable.vue'
const rows = ref([])
const loading = ref(false)
const cols = [
  { key: 'elevatorCode', label: '设备码', sortable: true },
  { key: 'workTypeCode', label: '类别' },
  { key: 'reportStatus', label: '上报状态' },
  { key: 'checkoutTime', label: '签退时间' }
]
onMounted(async () => { loading.value = true; try { rows.value = await http.get('/admin/records') } finally { loading.value = false } })
</script>
<style scoped>
.page-head { margin-bottom: 16px; }
.page-head h1 { font-size: 20px; }
</style>