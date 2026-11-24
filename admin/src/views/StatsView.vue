<template>
  <div>
    <ViewHead title="统计报表" :sub="'当前口径：' + scopeText">
      <template #actions>
        <PermButton code="stats:export" label="导出报表" variant="ghost" icon="download" @click="exportReport" />
        <PermButton code="data:export_sensitive" label="敏感数据导出" variant="solid-sig" icon="lock" @click="sensitiveOpen = true" />
      </template>
      <template #meta><PageMeta perm="stats:view" :extra="[{ k: '口径', v: scopeText, icon: 'database' }]" /></template>
    </ViewHead>
    <div class="mt16">
      <div class="kpi-grid">
        <div v-for="m in metrics" :key="m.label" :class="['kpi', m.cls || '']">
          <div class="kpi-k"><svg class="ic" aria-hidden="true"><use :href="'#i-' + m.icon" /></svg>{{ m.label }}</div>
          <div class="kpi-n">{{ m.value }}<small v-if="m.unit && m.value !== '—'">{{ m.unit }}</small></div>
          <div class="kpi-x">{{ m.sub }}</div>
        </div>
      </div>
    </div>

    <!-- 分项目台量（分项之和 = 总数） -->
    <div class="card card-pad0 mt16">
      <div class="card-head">
        <h3><svg class="ic" aria-hidden="true"><use href="#i-chart" /></svg>分项目台量（分项之和 = 总数）</h3>
        <span class="ch-tag tag tag-dim">{{ totalElevators }} 台</span>
      </div>
      <div class="tbl-wrap">
        <table class="tbl">
          <thead><tr><th>项目</th><th class="num">台量</th><th class="num">记录单</th><th class="num">工单</th></tr></thead>
          <tbody>
            <tr v-for="p in byProject" :key="p.name">
              <td>{{ p.name }}</td>
              <td class="num">{{ p.elevators }}</td>
              <td class="num">{{ p.records }}</td>
              <td class="num">{{ p.orders }}</td>
            </tr>
            <tr v-if="!byProject.length"><td colspan="4" class="dim">暂无项目数据</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 敏感导出审批 -->
    <div v-if="approvals.length" class="card mt16">
      <div class="card-head"><h3><svg class="ic" aria-hidden="true"><use href="#i-lock" /></svg>敏感导出审批</h3></div>
      <div class="card-body">
        <div class="chk-list">
          <div v-for="a in approvals" :key="a.id" :class="['chk-row', { pass: a.status === 'APPROVED' }]">
            <svg class="ic" aria-hidden="true"><use :href="a.status === 'APPROVED' ? '#i-check' : '#i-clock'" /></svg>
            <span class="cr-t">{{ a.id }} · {{ a.purpose }}</span>
            <span class="cr-d">
              {{ a.status === 'APPROVED' ? '已完成' : '审批中' }}
              <PermButton v-if="a.status === 'PENDING'" code="data:export_sensitive" label="平台确认" variant="ghost" size="xs" @click="openApprove(a)" />
            </span>
          </div>
        </div>
      </div>
    </div>
    <RuleNote class="mt12" icon="lock">聚合口径随角色数据范围变化。普通导出按导出人权限脱敏并写审计；敏感导出需用途 ≥10 字 + 勾选 + 二次确认 → 审批中 → 平台再次确认才产出已完成。</RuleNote>

    <!-- 敏感数据导出申请 -->
    <Modal :open="sensitiveOpen" title="敏感数据导出申请" sub="需审批 + 二次确认 + 用途留痕" icon="lock" danger
      note="data:export_sensitive" require-confirm confirm-text="我已知悉本次导出将全程审计留痕" confirm-label="提交申请"
      @close="sensitiveOpen = false" @confirm="submitSensitive">
      <div class="form-grid one">
        <FormField label="导出用途" required hint="≥10 字" :error="sensitiveErr">
          <textarea v-model="purpose" class="ctl" placeholder="必填，≥10 字，说明用途与范围" />
        </FormField>
      </div>
      <RuleNote class="mt12" icon="alert">提交后状态「审批中」，出现在工作台待办与审计日志；审批通过后平台再次确认才产出「已完成」。</RuleNote>
    </Modal>

    <!-- 平台确认（第二段） -->
    <Modal :open="approveOpen" title="平台确认敏感导出（第二段）" :sub="approving ? approving.id : ''" icon="check" danger
      note="data:export_sensitive" require-confirm confirm-text="我已知悉，确认产出" confirm-label="确认产出"
      @close="approveOpen = false" @confirm="submitApprove">
      <RuleNote icon="alert">用途：{{ approving ? approving.purpose : '' }}。确认后产出「已完成」并写审计。</RuleNote>
    </Modal>
  </div>
</template>
<script setup>
// 后端尚未聚合的指标显示「—」并注明待聚合接口，不伪造数值。
import { ref, computed, onMounted } from 'vue'
import http from '../api/client'
import ViewHead from '../ui/ViewHead.vue'
import PageMeta from '../ui/PageMeta.vue'
import RuleNote from '../ui/RuleNote.vue'
import PermButton from '../ui/PermButton.vue'
import Modal from '../ui/Modal.vue'
import FormField from '../ui/FormField.vue'
import { useToast } from '../ui/stores/ui'
import { exportCsv } from '../ui/export-csv'
import { usePerspectiveStore } from '../ui/stores/perspective'
import { scopeLabel } from '../ui/labels'
import { minutesBetween, daysBetween, todayGmt8 } from '../ui/datetime'
const toast = useToast()
const perspective = usePerspectiveStore()
const scopeText = computed(() => scopeLabel(perspective.scope) + ' · ' + perspective.scope)
const stats = ref({})
const faults = ref([])
const records = ref([])
const recordTotal = ref(0)
const contracts = ref([])
const elevators = ref([])
const approvals = ref([])
function faultOvertime(f) {
  if (f.status === 'CLOSED' || f.status === 'CANCELLED') return false
  if (f.deadline) return minutesBetween(new Date(), f.deadline) < 0
  if (f.slaMinutes != null && f.createdAt) return minutesBetween(f.createdAt, new Date()) > f.slaMinutes
  return false
}
const totalElevators = computed(() => stats.value.elevators ?? elevators.value.length)
const metrics = computed(() => {
  const overtime = faults.value.filter(faultOvertime).length
  const slaRate = faults.value.length ? Math.round(faults.value.filter(f => !faultOvertime(f)).length / faults.value.length * 100) : null
  const recordsPartial = recordTotal.value > records.value.length
  const hasAbnormal = !recordsPartial && records.value.some(r => r.checkAbnormal != null)
  const passRate = hasAbnormal && records.value.length ? Math.round(records.value.filter(r => Number(r.checkAbnormal) === 0).length / records.value.length * 100) : null
  const hasDual = !recordsPartial && records.value.some(r => (r.workerSigned != null || r.primarySignAt != null))
  const dualRate = hasDual && records.value.length
    ? Math.round(records.value.filter(r => (r.workerSigned && r.assistantSigned) || (r.primarySignAt && r.partnerSignAt)).length / records.value.length * 100)
    : null
  const pendingReview = !recordsPartial && records.value.some(r => r.lifecycleStatus != null) ? records.value.filter(r => r.lifecycleStatus === 'PENDING_REVIEW').length : null
  const expiring = contracts.value.filter(c => {
    const days = c.daysLeft != null ? c.daysLeft : (c.endDate ? daysBetween(todayGmt8(), c.endDate) : null)
    return days != null && days > 0 && days <= 90
  }).length
  return [
    { icon: 'elevator', label: '在管台量', value: totalElevators.value, unit: '台', sub: '范围 ' + perspective.scope },
    { icon: 'check', label: '一次合格率', value: passRate == null ? '—' : passRate, unit: '%', sub: passRate == null ? '待聚合接口' : '异常 0 的记录占比', cls: passRate == null ? '' : 'k-ok' },
    { icon: 'bolt', label: '超时候车工单', value: overtime, unit: '单', sub: '需升级 / 改派', cls: overtime ? 'k-sig' : 'k-ok' },
    { icon: 'clock', label: 'SLA 达成率', value: slaRate == null ? '—' : slaRate, unit: '%', sub: slaRate == null ? '待聚合接口' : '未超时工单占比' },
    { icon: 'signature', label: '双签完整率', value: dualRate == null ? '—' : dualRate, unit: '%', sub: dualRate == null ? '待聚合接口' : '主检人 + 配合人均签' },
    { icon: 'clipboard', label: '待复核积压', value: pendingReview == null ? '—' : pendingReview, unit: '单', sub: pendingReview == null ? '待聚合接口' : '维保主管复核' },
    { icon: 'file-sign', label: '合同临期数', value: contracts.value.length ? expiring : '—', unit: '份', sub: contracts.value.length ? '≤90 天' : '待聚合接口' }
  ]
})
const byProject = computed(() => {
  const groups = {}
  elevators.value.forEach(e => {
    const name = e.projectName || e.useUnitName || '未分组'
    if (!groups[name]) groups[name] = { name, elevators: 0, records: 0, orders: 0 }
    groups[name].elevators++
  })
  const elevProject = {}
  elevators.value.forEach(e => { elevProject[e.elevatorCode] = e.projectName || e.useUnitName || '未分组' })
  records.value.forEach(r => {
    const name = r.projectName || elevProject[r.elevatorCode]
    if (name && groups[name]) groups[name].records++
  })
  faults.value.forEach(f => {
    const name = f.projectName || elevProject[f.elevatorCode]
    if (name && groups[name]) groups[name].orders++
  })
  return Object.values(groups)
})
async function load() {
  const asList = d => (Array.isArray(d) ? d : (d && d.list) || [])
  const safe = async (url, pick) => { try { const d = await http.get(url); return pick ? pick(d) : d } catch { return pick ? [] : {} } }
  stats.value = await safe('/stats')
  faults.value = await safe('/faults', asList)
  const recordPage = await safe('/admin/records')
  records.value = asList(recordPage)
  recordTotal.value = Number(recordPage.total) || records.value.length
  contracts.value = await safe('/contracts', asList)
  elevators.value = await safe('/elevators', asList)
  approvals.value = await safe('/export-approvals', asList)
}
onMounted(load)

// 普通报表导出：KPI 指标 + 分项目台量（敏感字段导出仍走审批链 data:export_sensitive）
function exportReport() {
  const metricCols = [
    { key: 'label', label: '指标' },
    { key: 'value', label: '数值' },
    { key: 'unit', label: '单位' },
    { key: 'sub', label: '口径' }
  ]
  const n = exportCsv('统计报表-指标', metricCols, metrics.value)
  const p = exportCsv('统计报表-分项目台量',
    [{ key: 'name', label: '项目' }, { key: 'elevators', label: '台量' }, { key: 'records', label: '记录单' }, { key: 'orders', label: '工单' }],
    byProject.value)
  toast.success('已导出 ' + n + ' 项指标 · ' + p + ' 个项目', 'CSV · 按导出人权限脱敏')
}

// ── 敏感导出申请 ──
const sensitiveOpen = ref(false)
const purpose = ref('')
const sensitiveErr = ref('')
async function submitSensitive() {
  if (purpose.value.trim().length < 10) { sensitiveErr.value = '用途至少 10 字'; return }
  sensitiveErr.value = ''
  try {
    await http.post('/export-approvals', { purpose: purpose.value, scope: perspective.scope })
    toast.warn('申请已提交 · 审批中')
    sensitiveOpen.value = false
    purpose.value = ''
    load()
  } catch (e) { toast.error(e.message || '提交失败') }
}
// ── 平台确认 ──
const approveOpen = ref(false)
const approving = ref(null)
function openApprove(a) { approving.value = a; approveOpen.value = true }
async function submitApprove() {
  try {
    await http.post(`/export-approvals/${approving.value.id}/approve`, {})
    toast.success('敏感导出已完成', approving.value.id)
    approveOpen.value = false
    load()
  } catch (e) { toast.error(e.message || '确认失败') }
}
</script>