<template>
  <div class="page">
    <div class="page-head"><h1>上报异常清单</h1></div>
    <DataTable :columns="cols" :rows="rows" :loading="loading" :search-keys="['elevatorCode', 'originalRecordId']" />
    <h2 class="label">逐条处理（人工重报 / 导出记录）</h2>
    <p v-if="!rows.length" class="hint">暂无上报异常记录。</p>
    <ul class="rec-list">
      <li v-for="r in rows" :key="r.id">
        <span>{{ r.elevatorCode }} · {{ workTypeLabel(r.workTypeCode) }} · {{ statusLabel(r.reportStatus) }}</span>
        <span class="acts">
          <PermButton code="record:export" label="导出PDF" @click="doExport(r)" @blocked="onBlocked" />
          <PermButton v-if="r.reportStatus === 'FAILED'" code="report:retry" label="人工重报" variant="primary" @click="doReupload(r)" @blocked="onBlocked" />
        </span>
      </li>
    </ul>
    <p class="hint">仅逐条人工重报，禁止批量自动重试；重报使用签退冻结快照。</p>
  </div>
</template>
<script setup>
// 上报异常清单：FAILED/PENDING 记录 + 逐条人工重报（report:retry）+ 记录 PDF 导出
import { ref, onMounted } from 'vue'
import DataTable from '../ui/DataTable.vue'
import PermButton from '../ui/PermButton.vue'
import { useToast } from '../ui/stores/ui'
import { workTypeLabel, statusLabel } from '../ui/labels'
import { listRecords, reupload, exportPdf } from '../api/report'
const toast = useToast()
const rows = ref([])
const loading = ref(false)
const cols = [
  { key: 'elevatorCode', label: '设备码', sortable: true },
  { key: 'workTypeCode', label: '类别' },
  { key: 'reportStatus', label: '上报状态' },
  { key: 'originalRecordId', label: '平台记录ID' }
]
async function load() {
  loading.value = true
  try { const all = (await listRecords()).list || []; rows.value = all.filter(r => r.reportStatus === 'FAILED' || r.reportStatus === 'PENDING') }
  catch (e) { toast.error(e.message || '加载失败') } finally { loading.value = false }
}
function onBlocked(code) { toast.warn('需审批或前置未满足：' + code) }
async function doReupload(r) {
  try { await reupload(r.id); toast.success('已触发人工重报'); await load() }
  catch (e) { toast.error(e.message || '重报失败') }
}
async function doExport(r) {
  try {
    const blob = await exportPdf(r.id)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url; a.download = `维保记录-${r.originalRecordId || r.id}.pdf`
    document.body.appendChild(a); a.click(); a.remove(); URL.revokeObjectURL(url)
  } catch (e) { toast.error(e.message || '导出失败') }
}
onMounted(load)
</script>
<style scoped>
.page-head { margin-bottom: 16px; }
.page-head h1 { font-size: 20px; }
h2.label { margin: 18px 0 8px; }
.rec-list { list-style: none; padding: 0; }
.rec-list li { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding: 8px 12px; background: var(--panel); border: 1px solid var(--line); border-radius: 6px; margin-bottom: 6px; font-size: 13px; }
.acts { display: flex; gap: 8px; }
.hint { color: var(--dim); font-size: 12px; margin-top: 10px; }
</style>