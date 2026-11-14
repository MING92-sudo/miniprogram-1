<template>
  <div class="page">
    <div class="page-head">
      <h1>电梯台账</h1>
      <PermButton code="elevator:transfer" label="移装/停用/报废" @click="showChange = true" />
    </div>
    <DataTable :columns="cols" :rows="rows" :loading="loading" :search-keys="['elevatorCode', 'elevatorName']" />
    <h2 class="label">变更单（双人确认）</h2>
    <ul class="chg-list">
      <li v-for="c in changes" :key="c.id">
        <span>{{ c.elevatorId }} · {{ c.changeType }} · {{ c.status }}</span>
        <PermButton v-if="c.status === 'PENDING'" code="elevator:transfer" label="确认" variant="danger" @click="openConfirm(c.id)" />
      </li>
    </ul>

    <Modal :open="showChange" title="发起电梯变更" danger @close="showChange = false" @confirm="submitChange">
      <Form ref="chgForm" :rules="chgRules" v-model="chgModel" v-slot="{ errors }">
        <FormField label="电梯 ID" required :error="errors.elevatorId"><input v-model="chgModel.elevatorId" /></FormField>
        <FormField label="类型" required :error="errors.changeType">
          <select v-model="chgModel.changeType"><option>TRANSFER</option><option>DISABLE</option><option>SCRAP</option></select>
        </FormField>
        <FormField label="原因" required :error="errors.reason"><input v-model="chgModel.reason" /></FormField>
      </Form>
    </Modal>
    <Modal :open="showConfirm" title="确认变更（须不同于发起人）" danger require-confirm @close="showConfirm = false" @confirm="submitConfirm">
      <FormField label="确认人 ID"><input v-model="confirmerId" /></FormField>
    </Modal>
  </div>
</template>
<script setup>
// M3 电梯台账：列表 + 移装/停用/报废双人确认（elevator:transfer）
import { ref, onMounted } from 'vue'
import http from '../api/client'
import DataTable from '../ui/DataTable.vue'
import Modal from '../ui/Modal.vue'
import Form from '../ui/Form.vue'
import FormField from '../ui/FormField.vue'
import PermButton from '../ui/PermButton.vue'
import { useToast } from '../ui/stores/ui'
import { listChanges, initiateChange, confirmChange } from '../api/ledger'
const toast = useToast()
const rows = ref([])
const changes = ref([])
const loading = ref(false)
const showChange = ref(false)
const showConfirm = ref(false)
const confirmerId = ref('')
const confirmId = ref('')
const chgForm = ref(null)
const chgModel = ref({ elevatorId: '', changeType: 'DISABLE', reason: '' })
const chgRules = {
  elevatorId: [(v) => (v ? '' : '电梯必填')],
  changeType: [(v) => (v ? '' : '类型必填')],
  reason: [(v) => (v ? '' : '原因必填')]
}
const cols = [
  { key: 'elevatorCode', label: '设备码', sortable: true }, { key: 'elevatorName', label: '梯号' },
  { key: 'projectName', label: '项目' }, { key: 'status', label: '状态' }
]
async function load() {
  loading.value = true
  try { rows.value = await http.get('/elevators'); changes.value = await listChanges() }
  finally { loading.value = false }
}
async function submitChange() {
  if (!chgForm.value.validate()) return
  try { await initiateChange(chgModel.value); toast.success('变更已发起，待双人确认'); showChange.value = false; load() }
  catch (e) { toast.error(e.message || '发起失败') }
}
function openConfirm(id) { confirmId.value = id; confirmerId.value = ''; showConfirm.value = true }
async function submitConfirm() {
  try { await confirmChange(confirmId.value, { confirmerId: confirmerId.value }); toast.success('已确认'); showConfirm.value = false; load() }
  catch (e) { toast.error(e.message || '确认失败') }
}
onMounted(load)
</script>
<style scoped>
.page-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-head h1 { font-size: 20px; }
h2.label { margin: 18px 0 8px; }
.chg-list { list-style: none; padding: 0; }
.chg-list li { display: flex; justify-content: space-between; align-items: center; padding: 8px 12px; background: var(--panel); border: 1px solid var(--line); border-radius: 6px; margin-bottom: 6px; font-size: 13px; }
.page input, .page select { width: 100%; padding: 8px; background: var(--panel-2); border: 1px solid var(--line-2); border-radius: 6px; color: var(--text); }
</style>