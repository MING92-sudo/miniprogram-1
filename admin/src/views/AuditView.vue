<template>
  <div class="page">
    <div class="page-head"><h1>审计日志（只读）</h1></div>
    <DataTable :columns="cols" :rows="rows" :loading="loading" :search-keys="['actorId', 'action']" />
    <p class="hint">审计只读不可删改；旧操作日志保留满足留存。</p>
  </div>
</template>
<script setup>
// M5 审计日志：只读（audit:view），无删除/编辑入口
import { ref, onMounted } from 'vue'
import http from '../api/client'
import DataTable from '../ui/DataTable.vue'
import { roleLabel, permLabel } from '../ui/labels'
const rows = ref([])
const loading = ref(false)
const cols = [
  { key: 'createdAt', label: '时间', sortable: true },
  { key: 'actorId', label: '操作人' },
  { key: 'actorRole', label: '角色', format: roleLabel },
  { key: 'action', label: '动作', format: permLabel },
  { key: 'target', label: '对象' },
  { key: 'result', label: '结果' }
]
onMounted(async () => { loading.value = true; try { rows.value = await http.get('/audit?limit=200') } finally { loading.value = false } })
</script>
<style scoped>
.page-head { margin-bottom: 16px; }
.page-head h1 { font-size: 20px; }
.hint { color: var(--dim); font-size: 12px; margin-top: 10px; }
</style>