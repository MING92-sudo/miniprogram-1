<template>
  <div>
    <ViewHead title="维保计划" sub="到期基线只读投影 · 半月保 = 15 天间隔 · 手动指派 / 延期审批（自动派单仅定时任务驱动）">
      <template #meta><PageMeta perm="plan:view" :extra="[{ k: '计划数', v: rows.length, icon: 'calendar', cls: 'count' }]" /></template>
    </ViewHead>

    <div class="mt16">
      <DataTable :columns="cols" :rows="rows" :loading="loading" selectable
        :search-keys="['elevatorCode', 'elevatorName', 'useUnitName', 'orderNo']" search-placeholder="搜索设备码 / 梯号 / 使用单位 / 工单号"
        :filters="filters" empty-hint="当前数据范围内没有维保计划。"
        :bulk-actions="[{ code: 'plan:generate', label: '批量调整日期', icon: 'calendar' }]" @bulk="onBulk">
        <template #toolbar>
          <PermButton code="plan:generate" label="延期审批" variant="ghost" icon="clock" @click="loadDelays(true)" />
        </template>
        <template #cell-planNo="{ row }"><b class="mono">{{ row.orderNo || row.id }}</b></template>
        <template #cell-elevatorCode="{ row }">
          <span class="mono f12">{{ row.elevatorCode }}</span>
          <div class="cell-2">{{ row.elevatorName }}</div>
        </template>
        <template #cell-workType="{ value }"><span class="tag tag-ghost">{{ value }}</span></template>
        <template #cell-planDate="{ value }"><span class="mono f12">{{ value }}</span></template>
        <template #cell-principal="{ row }">
          {{ row.principal ? row.principal.name : '待指派' }}
          <div class="cell-2">{{ row.assistant ? '配合 ' + row.assistant.name : '无配合人' }}</div>
        </template>
        <template #cell-status="{ value }"><StatusTag :status="value" :label="planStatusLabel(value)" /></template>
        <template #row-actions="{ row }">
          <PermButton v-if="row.status === 'UNASSIGNED' || row.status === 'ASSIGNED'" code="plan:generate"
            :label="row.status === 'UNASSIGNED' ? '指派' : '改派'" variant="ghost" size="xs" icon="user-check" @click="openAssign(row)" />
          <PermButton v-if="row.status !== 'CANCELLED' && row.status !== 'DISPATCHED'" code="plan:generate"
            label="申请延期" variant="ghost" size="xs" icon="clock" @click="openDelay(row)" />
        </template>
      </DataTable>
    </div>
    <RuleNote class="mt12">自动派单仅由定时任务驱动（每日 09:00 主扫描 + 每 13 分钟兜底，AGENTS §3.6）；本页为只读投影 + 手动指派/延期（D-A1，不做按合同周期批量生成）。平台为运维只读（D3）；指派校验主/配合互斥、证件有效与 platform_id 同步（1004/1007）。</RuleNote>

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

    <!-- 指派 / 改派 -->
    <Modal :open="assignModal" :title="assignForm.id ? '指派 / 改派' : '批量调整日期'" :sub="assignSub" icon="user-check"
      note="plan:generate · 手动派单" confirm-label="确认指派" @close="assignModal = false" @confirm="confirmAssign">
      <div class="form-grid one">
        <template v-if="!bulkMode">
          <FormField label="主维保" required :error="assignErr.principal">
            <select v-model="assignForm.principalId" class="ctl">
              <option value="">请选择主维保</option>
              <option v-for="e in employees" :key="e.id" :value="e.id">{{ e.name }}{{ e.groupName ? ' · ' + e.groupName : '' }}</option>
            </select>
          </FormField>
          <FormField label="配合人员" hint="新工单强制双人（P-4）">
            <select v-model="assignForm.assistantId" class="ctl">
              <option value="">请选择配合人员</option>
              <option v-for="e in employees" :key="e.id" :value="e.id">{{ e.name }}{{ e.groupName ? ' · ' + e.groupName : '' }}</option>
            </select>
          </FormField>
        </template>
        <FormField label="计划日期" required :error="assignErr.date" :hint="'≥ ' + today">
          <input v-model="assignForm.planDate" type="date" class="ctl mono" />
        </FormField>
      </div>
      <RuleNote class="mt12">主/配合须为不同账号且均非维保经理；证件过期或 platform_id 未同步将被拦截（1004/1007）。</RuleNote>
    </Modal>

    <!-- 申请延期 -->
    <Modal :open="delayModal" title="申请延期" :sub="delayTarget ? delayTarget.elevatorName : ''" icon="clock"
      note="plan:generate · 延期申请" confirm-label="提交申请" @close="delayModal = false" @confirm="confirmDelay">
      <div class="form-grid one">
        <FormField label="延期原因" required :error="delayErr.reason">
          <textarea v-model="delayForm.reason" class="ctl" placeholder="必填，说明延期原因" />
        </FormField>
        <FormField label="延期天数" required :error="delayErr.days">
          <input v-model="delayForm.delayDays" type="number" min="1" max="90" class="ctl mono" />
        </FormField>
        <FormField label="期望日期" required :error="delayErr.date">
          <input v-model="delayForm.expectedDate" type="date" class="ctl mono" />
        </FormField>
      </div>
    </Modal>

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
// 维保计划（原型 Views.plans 布局对齐）：到期基线只读投影 + 手动指派/改派/延期申请/延期审批。
// 合规偏离 D-A1：不做「按合同周期批量生成 N 条」——自动派单仅定时任务驱动（AGENTS §3.6）。
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
const today = new Date().toISOString().slice(0, 10)

const PLAN_LABEL = { UNASSIGNED: '待指派', ASSIGNED: '已指派', POSTPONE_PENDING: '延期待审', DISPATCHED: '已派单', CANCELLED: '已取消' }
function planStatusLabel(s) { return PLAN_LABEL[s] || statusLabel(s) }
const DELAY_LABEL = { PENDING: '待审批', APPROVED: '已通过', REJECTED: '已驳回' }
function delayStatusLabel(s) { return DELAY_LABEL[s] || statusLabel(s) }

const rows = ref([])
const loading = ref(false)
const employees = ref([])
const cols = [
  { key: 'planNo', label: '计划 / 工单号' },
  { key: 'elevatorCode', label: '设备码', sortable: true },
  { key: 'useUnitName', label: '使用单位', sortable: true },
  { key: 'workType', label: '类型' },
  { key: 'planDate', label: '计划日期', sortable: true },
  { key: 'principal', label: '主检 / 配合' },
  { key: 'status', label: '状态', sortable: true }
]
const filters = [
  { key: 'status', label: '状态', options: Object.keys(PLAN_LABEL).map((c) => ({ value: c, label: PLAN_LABEL[c] })) },
  { key: 'workTypeCode', label: '类别', options: ['FM', 'HM', 'TM', 'SM', 'OY'].map((c) => ({ value: c, label: { FM: '按需', HM: '半月', TM: '季度', SM: '半年', OY: '年度' }[c] })) }
]

async function load() {
  loading.value = true
  try { rows.value = (await http.get('/admin/plans', { params: { size: 200 } })).list || [] }
  catch (e) { toast.error(e.message || '加载计划失败') } finally { loading.value = false }
}
async function loadEmployees() {
  try { employees.value = (await http.get('/employees')) || [] } catch { employees.value = [] }
}
onMounted(() => { load(); loadEmployees() })

// 指派 / 改派 / 批量调整日期
const assignModal = ref(false)
const bulkMode = ref(false)
const bulkRows = ref([])
const assignForm = reactive({ id: '', principalId: '', assistantId: '', planDate: '' })
const assignErr = reactive({ principal: '', date: '' })
const assignSub = computed(() => {
  if (bulkMode.value) return bulkRows.value.length + ' 条计划 · 仅调整日期（保留原主检/配合）'
  const r = rows.value.find((x) => x.id === assignForm.id)
  return r ? r.elevatorName + ' · ' + r.elevatorCode : ''
})
function openAssign(row) {
  bulkMode.value = false
  assignForm.id = row.id
  assignForm.principalId = row.principal ? row.principal.id : ''
  assignForm.assistantId = row.assistant ? row.assistant.id : ''
  assignForm.planDate = row.planDate || today
  assignErr.principal = ''; assignErr.date = ''
  assignModal.value = true
}
function onBulk({ ids }) {
  const selected = rows.value.filter((r) => ids.includes(r.id))
  if (!selected.length) { toast.warn('请先选择计划'); return }
  if (selected.some((r) => !r.principal || !r.principal.id)) { toast.warn('批量调整仅支持已指派计划（保留原主检/配合）'); return }
  if (selected.some((r) => r.status !== 'ASSIGNED' && r.status !== 'UNASSIGNED')) { toast.warn('仅待指派/已指派计划可调整日期'); return }
  bulkMode.value = true
  bulkRows.value = selected
  assignForm.id = ''; assignForm.planDate = today
  assignErr.principal = ''; assignErr.date = ''
  assignModal.value = true
}
async function confirmAssign() {
  assignErr.date = assignForm.planDate && assignForm.planDate >= today ? '' : '计划日期不能早于今天（' + today + '）'
  if (!bulkMode.value) assignErr.principal = assignForm.principalId ? '' : '请选择主维保'
  if (assignErr.date || assignErr.principal) return
  try {
    if (bulkMode.value) {
      for (const r of bulkRows.value) {
        await http.put(`/admin/plans/${r.id}/assign`, { principalId: r.principal.id, assistantId: r.assistant ? r.assistant.id : '', planDate: assignForm.planDate })
      }
      toast.success('已调整 ' + bulkRows.value.length + ' 条计划',assignForm.planDate)
    } else {
      await http.put(`/admin/plans/${assignForm.id}/assign`, { principalId: assignForm.principalId, assistantId: assignForm.assistantId, planDate: assignForm.planDate })
      toast.success('指派已保存',assignForm.planDate)
    }
    assignModal.value = false
    await load()
  } catch (e) { toast.error(e.message || '指派失败') }
}

// 延期申请
const delayModal = ref(false)
const delayTarget = ref(null)
const delayForm = reactive({ reason: '', delayDays: 1, expectedDate: '' })
const delayErr = reactive({ reason: '', days: '', date: '' })
function openDelay(row) {
  delayTarget.value = row
  delayForm.reason = ''; delayForm.delayDays = 1; delayForm.expectedDate = row.planDate || today
  delayErr.reason = ''; delayErr.days = ''; delayErr.date = ''
  delayModal.value = true
}
async function confirmDelay() {
  delayErr.reason = delayForm.reason.trim() ? '' : '延期原因必填'
  const days = parseInt(delayForm.delayDays, 10)
  delayErr.days = days >= 1 && days <= 90 ? '' : '延期天数须为 1–90'
  delayErr.date = delayForm.expectedDate ? '' : '期望日期必填'
  if (delayErr.reason || delayErr.days || delayErr.date) return
  try {
    await http.post(`/admin/plans/${delayTarget.value.id}/delay`, { reason: delayForm.reason, delayDays: days, expectedDate: delayForm.expectedDate })
    delayModal.value = false
    toast.success('延期申请已提交 · 待审批',delayTarget.value.elevatorName)
    await load()
  } catch (e) { toast.error(e.message || '延期申请失败') }
}

// 延期审批
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
    toast.success(decideForm.approved ? '延期已通过' : '延期已驳回',decideTarget.value.elevatorName)
    await Promise.all([loadDelays(false), load()])
  } catch (e) { toast.error(e.message || '审批失败') }
}
</script>