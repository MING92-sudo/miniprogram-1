<template>
  <div>
    <ViewHead title="上报异常清单" sub="FAILED / PENDING / 待核对记录 · 重报与核对均逐条人工触发">
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
          <template v-if="isStuck(row.reportStatus)">
            <PermButton code="report:retry" label="标记已上报" variant="primary" size="xs" icon="check" @click="openReconcile(row, 'REPORTED')" />
            <PermButton code="report:retry" label="标记未上报" variant="ghost" size="xs" icon="x" @click="openReconcile(row, 'FAILED')" />
          </template>
        </template>
      </DataTable>
    </div>
    <RuleNote class="mt12" icon="lock">仅逐条人工重报/核对，禁止批量自动重试（AGENTS §2.4）；重报使用签退冻结快照，不重新组装。上报失败只标记状态，不阻断本地归档。停留态先核对平台控制台再标记，标记只改本地状态、绝不调用平台。</RuleNote>
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
            <div class="kv"><span class="k">重试次数</span><span class="v num">{{ detail.retryCount || 0 }}</span></div>
          </div>
        </div>
        <div v-if="isStuck(detail.reportStatus)" class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-cloud-upload" /></svg>上报应答</div>
          <div v-if="logsLoading" class="dim">正在加载上报应答…</div>
          <div v-else-if="!logs.length" class="dim">暂无上报应答日志。</div>
          <template v-else>
            <div v-for="g in logs" :key="g.id" class="sec">
              <div class="fl">
                <span class="tag tag-ghost">{{ actionLabel(g.action) }}</span>
                <span :class="['mono', String(g.platformCode) === '200' ? 'c-ok' : 'c-sig']">{{ g.platformCode ?? '—' }}</span>
                <span class="mono f12 dim">{{ g.createdAt || '—' }}</span>
              </div>
              <div class="f12">{{ g.platformMessage || '—' }}</div>
              <div v-if="g.requestDigest" class="mono f11 dim">快照 digest：{{ g.requestDigest }}</div>
            </div>
          </template>
        </div>
        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-info" /></svg>处理口径</div>
          <RuleNote icon="lock">人工重报经服务端逐条转发 2.6，使用签退冻结快照；无自动重试、无批量入口。停留态核对先在平台控制台确认结果，标记只改本地状态、绝不调用平台。</RuleNote>
        </div>
      </template>
      <template #footer>
        <PermButton v-if="detail && detail.reportStatus === 'FAILED'" code="report:retry" label="人工重报" variant="primary" icon="refresh" @click="doReupload(detail)" />
        <template v-if="detail && isStuck(detail.reportStatus)">
          <PermButton code="report:retry" label="标记已上报" variant="primary" icon="check" @click="openReconcile(detail, 'REPORTED')" />
          <PermButton code="report:retry" label="标记未上报" variant="ghost" icon="x" @click="openReconcile(detail, 'FAILED')" />
        </template>
        <PermButton v-if="detail" code="record:export" label="导出PDF" variant="ghost" icon="download" @click="doExport(detail)" />
      </template>
    </Drawer>
    <Modal :open="!!reconcileTarget" :title="reconcileTarget && reconcileTarget.outcome === 'REPORTED' ? '标记已上报' : '标记未上报'"
      :sub="reconcileTarget ? (reconcileTarget.row.originalRecordId || reconcileTarget.row.id) : ''" icon="check"
      note="report:retry · 只改本地状态，不调用平台" :confirm-label="reconciling ? '提交中…' : '确认标记'"
      @close="reconcileTarget = null" @confirm="doReconcile">
      <FormField label="核对说明" required :error="reconcileErr">
        <textarea v-model="reconcileNote" class="ctl" placeholder="必填，说明平台控制台核对结论" />
      </FormField>
    </Modal>
  </div>
</template>
<script setup>
// 上报异常清单：FAILED/PENDING 重报 + 停留态（UNKNOWN/REPORTING/REUPLOADING/LEGACY_UPLOADING）逐条人工核对，唯一恢复入口
import { ref, onMounted } from 'vue'
import DataTable from '../ui/DataTable.vue'
import PermButton from '../ui/PermButton.vue'
import ViewHead from '../ui/ViewHead.vue'
import PageMeta from '../ui/PageMeta.vue'
import RuleNote from '../ui/RuleNote.vue'
import StatusTag from '../ui/StatusTag.vue'
import Drawer from '../ui/Drawer.vue'
import Modal from '../ui/Modal.vue'
import FormField from '../ui/FormField.vue'
import { useToast } from '../ui/stores/ui'
import { workTypeLabel } from '../ui/labels'
import { listAllRecords, reupload, reconcile, exportPdf } from '../api/report'
import { uploadLogs } from '../api/platform'
const STUCK_STATUSES = ['UNKNOWN', 'REPORTING', 'REUPLOADING', 'LEGACY_UPLOADING']
const FILTER_STATUSES = ['FAILED', 'PENDING', ...STUCK_STATUSES]
const toast = useToast()
const rows = ref([])
const loading = ref(false)
const detail = ref(null)
const logs = ref([])
const logsLoading = ref(false)
const reconcileTarget = ref(null)
const reconcileNote = ref('')
const reconcileErr = ref('')
const reconciling = ref(false)
const cols = [
  { key: 'elevatorCode', label: '设备码', sortable: true },
  { key: 'workTypeCode', label: '类别' },
  { key: 'reportStatus', label: '上报状态', sortable: true },
  { key: 'originalRecordId', label: '平台记录ID' }
]
function isStuck(s) { return STUCK_STATUSES.includes(String(s || '').toUpperCase()) }
function actionLabel(a) { return { UPLOAD: '上报', REUPLOAD: '重报', LEGACY: '存量', RECONCILE: '核对' }[String(a || '').toUpperCase()] || a || '—' }
async function load() {
  loading.value = true
  try {
    const all = (await listAllRecords({ reportStatus: FILTER_STATUSES.join(',') })).list || []
    rows.value = all.filter(r => FILTER_STATUSES.includes(r.reportStatus))
  }
  catch (e) { toast.error(e.message || '加载失败') } finally { loading.value = false }
}
function openDetail(r) {
  detail.value = r
  logs.value = []
  if (!isStuck(r.reportStatus) || !r.originalRecordId) return
  logsLoading.value = true
  uploadLogs({ originalRecordId: r.originalRecordId, size: 5 })
    .then(res => { logs.value = (res && res.list) || [] })
    .catch(e => { toast.error(e.message || '上报应答加载失败') })
    .finally(() => { logsLoading.value = false })
}
async function doReupload(r) {
  try { await reupload(r.id); toast.success('已触发人工重报', r.originalRecordId || r.id); detail.value = null; await load() }
  catch (e) { toast.error(e.message || '重报失败') }
}
function openReconcile(r, outcome) {
  reconcileTarget.value = { row: r, outcome }
  reconcileNote.value = ''
  reconcileErr.value = ''
}
async function doReconcile() {
  const t = reconcileTarget.value
  if (!t || reconciling.value) return
  if (!reconcileNote.value.trim()) { reconcileErr.value = '请填写核对说明'; return }
  reconciling.value = true
  try {
    await reconcile(t.row.id, t.outcome, reconcileNote.value.trim())
    toast.success(t.outcome === 'REPORTED' ? '已标记为已上报' : '已标记为未上报', t.row.originalRecordId || t.row.id)
    reconcileTarget.value = null
    detail.value = null
    await load()
  } catch (e) { toast.error(e.message || '核对失败') } finally { reconciling.value = false }
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
