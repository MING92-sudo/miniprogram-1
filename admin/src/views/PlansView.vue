<template>
  <div>
    <ViewHead title="维保计划" sub="到期投影 · 与自动派单同一基线（最近完成 + 间隔天数，类型累加式升级）· 自动派单仅定时任务驱动">
      <template #meta><PageMeta perm="plan:view" :extra="[{ k: '电梯数', v: rows.length, icon: 'calendar', cls: 'count' }]" /></template>
    </ViewHead>

    <div class="mt16">
      <DataTable :columns="cols" :rows="viewRows" :loading="loading"
        :search-keys="['elevatorCode', 'elevatorName', 'projectName', 'regionName']" search-placeholder="搜索电梯编码 / 名称 / 项目 / 区域"
        :filters="filters" empty-hint="当前数据范围内没有电梯。"
        :row-class="rowClass">
        <template #toolbar>
          <PermButton code="plan:generate" label="延期审批" variant="ghost" icon="clock" @click="loadDelays(true)" />
        </template>
        <template #cell-dueDate="{ row }"><b class="mono">{{ row.dueDate || '—' }}</b></template>
        <template #cell-elevatorCode="{ row }">
          <span class="mono f12">{{ row.elevatorCode }}</span>
          <div class="cell-2">{{ row.elevatorName }}</div>
        </template>
        <template #cell-workType="{ row }"><span class="tag tag-ghost">{{ typeLabel(row.workTypeCode) }}</span></template>
        <template #cell-lastDoneAt="{ value }"><span class="mono f12">{{ value || '—' }}</span></template>
        <template #cell-regionName="{ row }">
          {{ row.regionName || '不限区域' }}
          <span v-if="row.regionStaffed === false" class="tag tag-amber f11" style="margin-left:6px">区域无人</span>
        </template>
        <template #cell-status="{ row }">
          <StatusTag :status="row.status" :label="statusTextOf(row)" />
        </template>
      </DataTable>
    </div>
    <RuleNote class="mt12">自动派单仅由定时任务驱动（每日 09:00 主扫描 + 每 13 分钟兜底，AGENTS §3.6）：到期前一日 09:00 发布到区域抢单池（不带人），同区域持平台ID的维保员先签到者认领；「区域无人」= 该区域暂无持平台ID的在职维保员（请在账号管理维护所属区域），「无基线」电梯需手动首保。</RuleNote>

    <!-- 延期审批 -->
    <div v-if="delaysOpen" class="card card-pad0 mt16">
      <div class="card-head">
        <h3><svg class="ic" aria-hidden="true"><use href="#i-clock" /></svg>延期审批</h3>
        <span class="ch-tag tag tag-dim">{{ delays.length }} 条</span>
        <button class="btn btn-ghost btn-xs" type="button" style="margin-left:8px" @click="delaysOpen = false">收起</button>
      </div>
      <div v-if="delays.length" class="tbl-wrap">
        <table class="tbl">
          <thead><tr><th>梯号</th><th>现计划日期</th><th>原因</th><th class="num">天数</th><th>期望日期</th><th>状态</th><th>申请时间</th><th class="acts">操作</th></tr></thead>
          <tbody>
            <tr v-for="d in delays" :key="d.id">
              <td>{{ d.elevatorName }}</td>
              <td class="mono f12">{{ d.currentPlanDate }}</td>
              <td class="f12">{{ d.reason }}</td>
              <td class="num">{{ d.delayDays }}</td>
              <td class="mono f12">{{ d.expectedDate }}</td>
              <td><StatusTag :status="d.status" :label="delayStatusLabel(d.status)" /></td>
              <td class="mono f12">{{ d.createdAt }}</td>
              <td class="acts">
                <template v-if="d.status === 'PENDING'">
                  <PermButton code="plan:generate" label="通过" variant="ghost" size="xs" icon="check" @click="openDecide(d, true)" />
                  <PermButton code="plan:generate" label="驳回" variant="danger" size="xs" icon="x" @click="openDecide(d, false)" />
                </template>
                <span v-else class="dim f11">{{ d.approveComment || '—' }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="card-body dim">暂无延期申请。</div>
    </div>

    <!-- 延期审批决定 -->
    <Modal :open="decideModal" :title="decideForm.approved ? '延期审批 · 通过' : '延期审批 · 驳回'" :sub="decideTarget ? decideTarget.elevatorName : ''"
      :icon="decideForm.approved ? 'check' : 'x'" :danger="!decideForm.approved" note="plan:generate · 延期审批"
      :confirm-label="decideForm.approved ? '确认通过' : '确认驳回'" @close="decideModal = false" @confirm="confirmDecide">
      <FormField label="审批意见" :required="!decideForm.approved" :error="decideErr">
        <textarea v-model="decideForm.comment" class="ctl" :placeholder="decideForm.approved ? '选填' : '驳回必填意见'" />
      </FormField>
    </Modal>
  </div>
</template>
<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import http from '../api/client'
import DataTable from '../ui/DataTable.vue'
import Modal from '../ui/Modal.vue'
import FormField from '../ui/FormField.vue'
import ViewHead from '../ui/ViewHead.vue'
import PageMeta from '../ui/PageMeta.vue'
import RuleNote from '../ui/RuleNote.vue'
import StatusTag from '../ui/StatusTag.vue'
import PermButton from '../ui/PermButton.vue'
import { useToast } from '../ui/stores/ui'
import { statusLabel } from '../ui/labels'

const toast = useToast()

const TYPE_LABEL = { HM: '半月维保', TM: '季度维保', SM: '半年维保', OY: '年度维保', FM: '按需维保' }
function typeLabel(c) { return TYPE_LABEL[c] || c || '—' }

const rows = ref([])
const loading = ref(false)
const cols = [
  { key: 'dueDate', label: '到期日', sortable: true },
  { key: 'elevatorCode', label: '电梯', sortable: true },
  { key: 'projectName', label: '项目名称', sortable: true },
  { key: 'workType', label: '应做类别' },
  { key: 'lastDoneAt', label: '上次维保', sortable: true },
  { key: 'regionName', label: '区域' },
  { key: 'status', label: '状态', sortable: true }
]
const STATUS_TEXT = {
  NO_BASELINE: '无基线（需手动首保）',
  OVERDUE: '超期', TODAY: '今日到期', SOON: '临期', NORMAL: '正常'
}
function statusTextOf(r) {
  if (r.status === 'OVERDUE') return '超期 ' + (-r.daysToDue) + ' 天'
  if (r.status === 'SOON') return r.daysToDue + ' 天后到期'
  return STATUS_TEXT[r.status] || r.statusText || r.status
}
const STATUS_ORDER = ['NO_BASELINE', 'OVERDUE', 'TODAY', 'SOON', 'NORMAL']
const filters = [
  { key: 'status', label: '状态', options: STATUS_ORDER.map(s => ({ value: s, label: STATUS_TEXT[s] })) },
  { key: 'workTypeCode', label: '类别', options: ['HM', 'TM', 'SM', 'OY'].map(c => ({ value: c, label: TYPE_LABEL[c] })) }
]
// 区域名解析（regionId → GET /regions 主数据；解析失败回空串显示「不限区域」，不阻断列表）
const regions = ref([])
function regionNameOf(id) {
  if (!id) return ''
  const r = regions.value.find(x => x.id === id)
  return r ? r.name : ''
}
const viewRows = computed(() => rows.value.map(r => ({ ...r, regionName: regionNameOf(r.regionId) })))
function rowClass(r) { return r.status === 'OVERDUE' ? 'row-bad' : r.status === 'TODAY' ? 'row-warn' : '' }

async function load() {
  loading.value = true
  try {
    const d = await http.get('/admin/plans/projection')
    rows.value = Array.isArray(d) ? d : d.list || []
  } catch (e) { toast.error(e.message || '加载到期投影失败') } finally { loading.value = false }
}
async function loadRegions() {
  try {
    const r = await http.get('/regions')
    regions.value = Array.isArray(r) ? r : r.list || []
  } catch { regions.value = [] }
}
onMounted(() => { load(); loadRegions() })

// ── 延期审批（沿用 maintain_plan 延期流端点）──
const DELAY_LABEL = { PENDING: '待审批', APPROVED: '已通过', REJECTED: '已驳回' }
function delayStatusLabel(s) { return DELAY_LABEL[s] || statusLabel(s) }
const delaysOpen = ref(false)
const delays = ref([])
async function loadDelays(open) {
  try { delays.value = (await http.get('/admin/plans/delays')).list || [] }
  catch (e) { toast.error(e.message || '加载延期申请失败'); return }
  if (open) delaysOpen.value = true
}
const decideModal = ref(false)
const decideTarget = ref(null)
const decideForm = reactive({ approved: true, comment: '' })
const decideErr = ref('')
function openDecide(d, approved) {
  decideTarget.value = d
  decideForm.approved = approved
  decideForm.comment = ''
  decideErr.value = ''
  decideModal.value = true
}
async function confirmDecide() {
  if (!decideForm.approved && !decideForm.comment.trim()) { decideErr.value = '驳回须填写意见'; return }
  decideErr.value = ''
  try {
    await http.put(`/admin/plans/delays/${decideTarget.value.id}/decide`, { approved: decideForm.approved, comment: decideForm.comment })
    decideModal.value = false
    toast.success(decideForm.approved ? '延期已通过' : '延期已驳回', decideTarget.value.elevatorName)
    await loadDelays(false)
  } catch (e) { toast.error(e.message || '审批失败') }
}
</script>
