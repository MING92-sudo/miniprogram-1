<template>
  <div>
    <ViewHead title="上报异常清单" sub="FAILED / PENDING 记录 · 仅逐条人工重报 · 重报使用签退冻结快照">
      <template #meta><PageMeta perm="report:view" :extra="[{ k: '异常记录', v: rows.length, icon: 'alert', cls: 'count' }]" /></template>
    </ViewHead>
    <div class="mt16">
      <DataTable :columns="cols" :rows="rows" :loading="loading" :search-keys="['elevatorCode', 'originalRecordId']"
        search-placeholder="搜索设备码 / 平台记录ID" empty-hint="暂无上报异常记录。" clickable @row-click="openDetail">
        <template #cell-reportStatus="{ value }"><StatusTag :status="value" /></template>
        <template #cell-workTypeCode="{ value }"><span class="tag tag-ghost">{{ workTypeLabel(value) }}</span></template>
        <template #cell-originalRecordId="{ value }"><span class="mono f12">{{ value || '—' }}</span></template>
        <template #row-actions="{ row }">
          <PermButton code="record:export" label="导出PDF" variant="ghost" size="xs" icon="download" @click="doExport(row)" />
          <PermButton v-if="row.reportStatus === 'FAILED'" code="report:retry" label="人工重报" variant="primary" size="xs" icon="refresh" @click="doReupload(row)" />
        </template>
      </DataTable>
    </div>
    <RuleNote class="mt12" icon="lock">仅逐条人工重报，禁止批量自动重试（AGENTS §2.4）；重报使用签退冻结快照，不重新组装。上报失败只标记状态，不阻断本地归档。</RuleNote>
    <Drawer :open="!!detail" :title="detail ? '异常记录 · ' + (detail.originalRecordId || detail.id) : ''"
      :sub="detail ? detail.elevatorCode + ' · ' + workTypeLabel(detail.workTypeCode) : ''" :meta="{ perm: 'report:view' }" note="report:retry 仅逐条" @close="detail = null">
      <template v-if="detail">
        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-alert" /></svg>记录信息</div>
          <div class="kv-grid">
            <div class="kv"><span class="k">设备码</span><span class="v mono">{{ detail.elevatorCode }}</span></div>
            <div class="kv"><span class="k">维保类别</span><span class="v">{{ workTypeLabel(detail.workTypeCode) }}</span></div>
            <div class="kv"><span class="k">上报状态</span><span class="v"><StatusTag :status="detail.reportStatus" /></span></div>
            <div class="kv"><span class="k">平台记录ID</span><span class="v mono">{{ detail.originalRecordId || '—' }}</span></div>
          </div>
        </div>
        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-info" /></svg>处理口径</div>
          <RuleNote icon="lock">人工重报经服务端逐条转发 2.6，使用签退冻结快照；无自动重试、无批量入口。</RuleNote>
        </div>
      </template>
      <template #footer>
        <PermButton v-if="detail && detail.reportStatus === 'FAILED'" code="report:retry" label="人工重报" variant="primary" icon="refresh" @click="doReupload(detail)" />
        <PermButton v-if="detail" code="record:export" label="导出PDF" variant="ghost" icon="download" @click="doExport(detail)" />
      </template>
    </Drawer>
  </div>
</template>
<script setup>
// 上报异常清单：FAILED/PENDING 记录 + 逐条人工重报（report:retry）+ 记录 PDF 导出（原型设计语言对齐）
import { ref, onMounted } from 'vue'
import DataTable from '../ui/DataTable.vue'
import PermButton from '../ui/PermButton.vue'
import ViewHead from '../ui/ViewHead.vue'
import PageMeta from '../ui/PageMeta.vue'
import RuleNote from '../ui/RuleNote.vue'
import StatusTag from '../ui/StatusTag.vue'
import Drawer from '../ui/Drawer.vue'
import { useToast } from '../ui/stores/ui'
import { workTypeLabel } from '../ui/labels'
import { listRecords, reupload, exportPdf } from '../api/report'
const toast = useToast()
const rows = ref([])
const loading = ref(false)
const detail = ref(null)
const cols = [
  { key: 'elevatorCode', label: '设备码', sortable: true },
  { key: 'workTypeCode', label: '类别' },
  { key: 'reportStatus', label: '上报状态', sortable: true },
  { key: 'originalRecordId', label: '平台记录ID' }
]
async function load() {
  loading.value = true
  try { const all = (await listRecords()).list || []; rows.value = all.filter(r => r.reportStatus === 'FAILED' || r.reportStatus === 'PENDING') }
  catch (e) { toast.error(e.message || '加载失败') } finally { loading.value = false }
}
function openDetail(r) { detail.value = r }
async function doReupload(r) {
  try { await reupload(r.id); toast.success('已触发人工重报', r.originalRecordId || r.id); detail.value = null; await load() }
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