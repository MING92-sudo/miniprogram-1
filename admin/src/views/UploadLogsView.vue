<template>
  <div class="page">
    <div class="page-head"><h1>上报日志</h1></div>
    <DataTable :columns="cols" :rows="rows" :loading="loading" :search-keys="['originalRecordId', 'platformMessage']" />
    <p class="hint">平台上报转发日志（reg_upload_log），报文摘要脱敏只读；逐条人工重报见「上报异常清单」。</p>
  </div>
</template>
<script setup>
// 上报日志：GET /reg/upload-logs（reg_upload_log 分页，digest 脱敏只读，docs/09 §4.1）
import { ref, onMounted } from 'vue'
import DataTable from '../ui/DataTable.vue'
import { uploadLogs } from '../api/platform'
const rows = ref([])
const loading = ref(false)
const cols = [
  { key: 'createdAt', label: '时间', sortable: true },
  { key: 'originalRecordId', label: '平台记录ID' },
  { key: 'action', label: '接口' },
  { key: 'status', label: '状态' },
  { key: 'platformCode', label: '平台码' },
  { key: 'platformMessage', label: '平台返回' }
]
onMounted(async () => {
  loading.value = true
  try { const res = await uploadLogs({ size: 200 }); rows.value = res.list || [] }
  finally { loading.value = false }
})
</script>
<style scoped>
.page-head { margin-bottom: 16px; }
.page-head h1 { font-size: 20px; }
.hint { color: var(--dim); font-size: 12px; margin-top: 10px; }
</style>