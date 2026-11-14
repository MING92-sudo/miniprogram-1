<template>
  <div class="page">
    <div class="page-head"><h1>检查项模板</h1></div>
    <DataTable :columns="cols" :rows="rows" :loading="loading" :search-keys="['name', 'category']" />
    <p class="hint">维保检查项模板（区别于系统配置签字模板）；可启用/停用。</p>
  </div>
</template>
<script setup>
// M5 检查项模板：列表（template:view）
import { ref, onMounted } from 'vue'
import http from '../api/client'
import DataTable from '../ui/DataTable.vue'
const rows = ref([])
const loading = ref(false)
const cols = [
  { key: 'name', label: '模板名', sortable: true },
  { key: 'category', label: '品种' },
  { key: 'itemCount', label: '项数' },
  { key: 'enabled', label: '启用' }
]
onMounted(async () => { loading.value = true; try { rows.value = (await http.get('/admin/templates')).list || [] } finally { loading.value = false } })
</script>
<style scoped>
.page-head { margin-bottom: 16px; }
.page-head h1 { font-size: 20px; }
.hint { color: var(--dim); font-size: 12px; margin-top: 10px; }
</style>