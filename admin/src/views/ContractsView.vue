<template>
  <div>
    <ViewHead title="合同管理" sub="金额分级脱敏对照页 · 到期前 90 天提醒">
      <template #meta><PageMeta perm="contract:view" :extra="[{ k: '合同数', v: rows.length, icon: 'file-sign', cls: 'count' }]" /></template>
    </ViewHead>
    <div class="mt16">
      <DataTable :columns="cols" :rows="rows" :loading="loading"
        :search-keys="['contractNo', 'useUnitName']" search-placeholder="搜索合同号 / 项目"
        :filters="[{ key: 'status', label: '状态', options: statusOptions }]"
        empty-hint="当前数据范围内没有合同。">
        <template #toolbar>
          <PermButton code="contract:edit" label="续签 / 变更" variant="primary" icon="edit" c-tip="平台 / 经理可用；主管只读" @click="openRenew(null)" />
          <PermButton code="stats:export" label="导出" variant="ghost" icon="download" c-tip="按导出人数据范围导出 CSV" @click="exportContracts" />
        </template>
        <template #cell-contractNo="{ value }"><span class="mono">{{ value }}</span></template>
        <template #cell-proj="{ row }">{{ row.useUnitName || '—' }}</template>
        <template #cell-cycle="{ row }"><span class="tag tag-ghost">{{ row.cycle || '—' }}</span></template>
        <template #cell-units="{ row }"><span class="num">{{ row.units != null ? row.units : '—' }}</span></template>
        <template #cell-end="{ value }"><span class="mono f12">{{ value }}</span></template>
        <template #cell-days="{ row }">
          <span :class="['num', row.daysLeft <= 90 && row.daysLeft > 0 ? 'c-amber' : row.daysLeft <= 0 ? 'c-sig' : '']">{{ row.daysLeft }}</span>
        </template>
        <template #cell-amount="{ row }">
          <span v-if="row.amountMasked" class="kv v masked">{{ row.amountText }}</span>
          <span v-else class="num">{{ fmtAmount(row.amount) }}</span>
        </template>
        <template #cell-status="{ value }"><StatusTag :status="value" /></template>
        <template #row-actions="{ row }">
          <PermButton code="contract:edit" label="续签" variant="ghost" size="xs" icon="edit" c-tip="平台 / 经理可用" @click="openRenew(row)" />
        </template>
      </DataTable>
    </div>

    <!-- 金额脱敏对照卡（原型 §6 分级脱敏） -->
    <div class="card mt16">
      <div class="card-head">
        <h3><svg class="ic" aria-hidden="true"><use href="#i-eye" /></svg>金额脱敏对照（同一合同 {{ sample.contractNo || '—' }}）</h3>
        <span class="ch-tag tag tag-amber">§6 分级脱敏</span>
      </div>
      <div class="card-body">
        <div class="grid2">
          <div class="plate">
            <div class="hero-label">维保主管视角（MAINT_SUP）</div>
            <div class="hero-value" style="font-size:18px"><span class="kv v masked">¥ *** 万</span></div>
            <div class="fhelp mt6">仅显示合同期与台量：{{ sample.startDate || '—' }} ~ {{ sample.endDate || '—' }}</div>
          </div>
          <div class="plate">
            <div class="hero-label">维保经理 / 使用单位 / 平台视角</div>
            <div class="hero-value num" style="font-size:18px">{{ sample.amount != null ? fmtAmount(sample.amount) : '完整金额可见' }}</div>
            <div class="fhelp mt6">完整金额可见（roles §5.3 / 决策 3）</div>
          </div>
        </div>
        <RuleNote icon="lock" class="mt12">列表金额随当前角色实时出值；脱敏在服务端完成，前端不接收明文。当前角色：{{ roleLabel(perspective.activeRole) }}。</RuleNote>
      </div>
    </div>

    <!-- 续签 / 变更 -->
    <Modal :open="showRenew" title="合同续签 / 变更" :sub="renewTarget ? (renewTarget.contractNo + ' · ' + (renewTarget.useUnitName || '')) : '请选择合同续签'" icon="edit" note="contract:edit"
      confirm-label="保存" @close="showRenew = false" @confirm="submitRenew">
      <Form ref="renewForm" :rules="renewRules" v-model="renewModel" v-slot="{ errors }">
        <div class="form-grid">
          <FormField label="新到期日" required :error="errors.end"><input v-model="renewModel.end" type="date" class="ctl mono" /></FormField>
          <FormField label="金额（元）" required :error="errors.amount"><input v-model="renewModel.amount" type="number" class="ctl mono" /></FormField>
        </div>
      </Form>
      <RuleNote class="mt12">留痕；到期前 90 天提醒（roles §4.2）。</RuleNote>
    </Modal>
  </div>
</template>
<script setup>
// 合同管理（原型 Views.contracts 对齐）：金额分级脱敏（服务端裁剪）+ 剩余天数 + 对照卡 + 续签 Modal
import { ref, computed, onMounted } from 'vue'
import DataTable from '../ui/DataTable.vue'
import Modal from '../ui/Modal.vue'
import Form from '../ui/Form.vue'
import FormField from '../ui/FormField.vue'
import PermButton from '../ui/PermButton.vue'
import ViewHead from '../ui/ViewHead.vue'
import PageMeta from '../ui/PageMeta.vue'
import StatusTag from '../ui/StatusTag.vue'
import RuleNote from '../ui/RuleNote.vue'
import { useToast } from '../ui/stores/ui'
import { exportCsv } from '../ui/export-csv'
import { usePerspectiveStore } from '../ui/stores/perspective'
import { statusLabel, roleLabel } from '../ui/labels'
import { listContractsLedger, listUseUnits } from '../api/ledger'
const toast = useToast()
const perspective = usePerspectiveStore()
const rows = ref([])
const loading = ref(false)
const unitMap = ref({})
const cols = [
  { key: 'contractNo', label: '合同号', sortable: true },
  { key: 'proj', label: '项目' },
  { key: 'cycle', label: '周期' },
  { key: 'units', label: '台量', cls: 'num', sortable: true },
  { key: 'end', label: '到期日', sortable: true },
  { key: 'days', label: '剩余天数', cls: 'num', sortable: true },
  { key: 'amount', label: '金额', cls: 'num' },
  { key: 'status', label: '状态', sortable: true }
]
const statusOptions = computed(() => [...new Set(rows.value.map(r => r.status).filter(Boolean))].map(s => ({ value: s, label: statusLabel(s) })))
const sample = computed(() => rows.value[0] || {})
function fmtAmount(n) { return n == null ? '—' : '¥ ' + Number(n).toLocaleString('en-US') + '.00' }
function daysLeft(end) {
  if (!end) return 0
  const today = new Date(); today.setHours(0, 0, 0, 0)
  return Math.round((new Date(String(end).replace(' ', 'T')) - today) / 86400000)
}
async function load() {
  loading.value = true
  try {
    const [data, units] = await Promise.all([listContractsLedger(), listUseUnits().catch(() => [])])
    ;(Array.isArray(units) ? units : units.list || []).forEach(u => { unitMap.value[u.id] = u.unitName || u.name })
    const list = Array.isArray(data) ? data : data.list || []
    rows.value = list.map(c => ({
      ...c,
      useUnitName: unitMap.value[c.useUnitId] || c.useUnitId || '',
      end: c.endDate,
      days: daysLeft(c.endDate),
      daysLeft: daysLeft(c.endDate),
      amountDisplay: c.amountMasked ? c.amountText : String(c.amount)
    }))
  } catch (e) { toast.error(e.message || '加载失败') } finally { loading.value = false }
}
onMounted(load)

// 续签 / 变更：后端仅提供 GET /contracts/{id}，续签写接口尚未接入——表单一律明确拒绝，不伪造成功
const showRenew = ref(false)
const renewTarget = ref(null)
const renewForm = ref(null)
const renewModel = ref({ end: '', amount: '' })
const renewRules = { end: [(v) => (v ? '' : '新到期日必填')], amount: [(v) => (v !== '' && v != null ? '' : '金额必填')] }
function openRenew(row) {
  if (!row) { toast.warn('请先从列表选择一份合同'); return }
  renewTarget.value = row
  renewModel.value = { end: row.endDate || '', amount: row.amount != null ? row.amount : '' }
  showRenew.value = true
}
function submitRenew() {
  if (!renewForm.value.validate()) return
  toast.error('合同续签接口尚未接入', '后端暂无 POST/PUT /contracts 写接口，请联系平台侧处理')
  showRenew.value = false
}
function exportContracts() {
  const n = exportCsv('合同台账', cols, rows.value)
  toast.success('已导出 ' + n + ' 条合同台账', 'CSV · 按导出人权限脱敏')
}
</script>