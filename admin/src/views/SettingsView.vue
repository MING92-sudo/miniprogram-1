<template>
  <div class="page">
    <div class="page-head"><h1>系统配置（仅平台）</h1></div>
    <div class="cfg-grid">
      <div v-for="f in fields" :key="f.key" class="cfg-card">
        <p class="label">{{ f.label }}</p>
        <input v-model="model[f.key]" type="number" />
        <PermButton code="config:manage" label="保存" @click="save(f.key)" />
      </div>
    </div>
    <h2 class="label">敏感导出审批</h2>
    <div class="exp-row">
      <input v-model="purpose" placeholder="导出用途（≥10字）" />
      <PermButton code="data:export_sensitive" label="申请导出" @click="requestExport" />
    </div>
    <ul class="exp-list">
      <li v-for="a in approvals" :key="a.id">
        <span>{{ a.purpose }} · {{ a.status }}</span>
        <PermButton v-if="a.status === 'PENDING'" code="data:export_sensitive" label="批准" @click="approve(a.id)" />
      </li>
    </ul>
  </div>
</template>
<script setup>
// M5 系统配置：保存即时生效+写审计（config:manage）；敏感导出审批（data:export_sensitive）
import { ref, onMounted, reactive } from 'vue'
import http from '../api/client'
import PermButton from '../ui/PermButton.vue'
import { useToast } from '../ui/stores/ui'
const toast = useToast()
const model = reactive({ sla_minutes: '30', maint_cycle_days: '15', spotcheck_ratio: '10' })
const purpose = ref('')
const approvals = ref([])
const fields = [
  { key: 'sla_minutes', label: 'SLA 困人时限(分,1—240)' },
  { key: 'maint_cycle_days', label: '保养周期(天,1—90)' },
  { key: 'spotcheck_ratio', label: '抽查比例(%,1—100)' }
]
async function load() {
  const cfg = await http.get('/settings')
  Object.keys(model).forEach(k => { if (cfg[k] != null) model[k] = cfg[k] })
  approvals.value = await http.get('/export-approvals')
}
async function save(key) {
  try { await http.put(`/settings/${key}`, { value: model[key] }); toast.success('已保存'); }
  catch (e) { toast.error(e.message || '保存失败') }
}
async function requestExport() {
  try { await http.post('/export-approvals', { purpose: purpose.value }); toast.success('已提交审批'); purpose.value = ''; load() }
  catch (e) { toast.error(e.message || '提交失败') }
}
async function approve(id) {
  try { await http.post(`/export-approvals/${id}/approve`, {}); toast.success('已批准'); load() }
  catch (e) { toast.error(e.message || '批准失败') }
}
onMounted(load)
</script>
<style scoped>
.page-head { margin-bottom: 16px; }
.page-head h1 { font-size: 20px; }
.cfg-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.cfg-card { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 14px; display: flex; flex-direction: column; gap: 8px; }
.cfg-card input, .exp-row input { padding: 8px; background: var(--panel-2); border: 1px solid var(--line-2); border-radius: 6px; color: var(--text); }
h2.label { margin: 20px 0 8px; }
.exp-row { display: flex; gap: 8px; }
.exp-row input { flex: 1; }
.exp-list { list-style: none; padding: 0; margin-top: 10px; }
.exp-list li { display: flex; justify-content: space-between; align-items: center; padding: 8px 12px; background: var(--panel); border: 1px solid var(--line); border-radius: 6px; margin-bottom: 6px; font-size: 13px; }
</style>