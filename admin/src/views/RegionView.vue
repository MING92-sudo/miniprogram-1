<template>
  <div class="page">
    <div class="page-head"><h1>区域管理</h1></div>
    <DataTable :columns="cols" :rows="rows" :loading="loading" :search-keys="['name']" />
  </div>
</template>
<script setup>
// M5 区域管理：列表（region:view）
import { ref, onMounted } from 'vue'
import http from '../api/client'
import DataTable from '../ui/DataTable.vue'
const rows = ref([])
const loading = ref(false)
const cols = [
  { key: 'name', label: '区域名称', sortable: true },
  { key: 'code', label: '编码' },
  { key: 'remark', label: '备注' }
]
onMounted(async () => { loading.value = true; try { rows.value = await http.get('/regions') } finally { loading.value = false } })
</script>
<style scoped>
.page-head { margin-bottom: 16px; }
.page-head h1 { font-size: 20px; }
</style>