<template>
  <div class="page">
    <div class="page-head"><h1>平台登记人员</h1></div>
    <DataTable :columns="cols" :rows="rows" :loading="loading" :search-keys="['name', 'certificateNo']" />
    <p class="hint">监管平台人员登记（接口 2.3/2.4）。</p>
  </div>
</template>
<script setup>
// M5 平台登记人员：列表（platform:worker_view）
import { ref, onMounted } from 'vue'
import http from '../api/client'
import DataTable from '../ui/DataTable.vue'
const rows = ref([])
const loading = ref(false)
const cols = [
  { key: 'name', label: '姓名', sortable: true },
  { key: 'certificateNo', label: '证书编号' },
  { key: 'phone', label: '手机号' },
  { key: 'bindStatus', label: '绑定状态' }
]
onMounted(async () => { loading.value = true; try { rows.value = await http.get('/platform/workers') } finally { loading.value = false } })
</script>
<style scoped>
.page-head { margin-bottom: 16px; }
.page-head h1 { font-size: 20px; }
.hint { color: var(--dim); font-size: 12px; margin-top: 10px; }
</style>