<template>
  <div class="page">
    <div class="page-head"><h1>记录单复核</h1></div>
    <DataTable :columns="cols" :rows="rows" :loading="loading" :search-keys="['elevatorCode']" />
    <ul class="rec-list">
      <li v-for="r in rows" :key="r.id">
        <span>{{ r.elevatorCode }} · v{{ r.version || 1 }} · {{ r.confirmStatus }}</span>
        <PermButton v-if="r.confirmStatus === 'CONFIRMED'" code="record:amend" label="发起修订" @click="openAmend(r)" />
      </li>
    </ul>
    <Modal :open="showAmend" title="发起修订（生成 v2 + 新哈希，不重报）" @close="showAmend = false" @confirm="submitAmend">
      <FormField label="修订原因" required><input v-model="amendReason" /></FormField>
    </Modal>
  </div>
</template>
<script setup>
// M4 记录单复核：双签核验/退回/抽查标/归档后修订 v2（record:amend，不触发上报）
import { ref, onMounted } from 'vue'
import http from '../api/client'
import DataTable from '../ui/DataTable.vue'
import Modal from '../ui/Modal.vue'
import FormField from '../ui/FormField.vue'
import PermButton from '../ui/PermButton.vue'
import { useToast } from '../ui/stores/ui'
const toast = useToast()
const rows = ref([])
const loading = ref(false)
const showAmend = ref(false)
const amendReason = ref('')
const amendId = ref('')
const cols = [
  { key: 'elevatorCode', label: '设备码', sortable: true },
  { key: 'workTypeCode', label: '类别' },
  { key: 'workerName', label: '主维保' },
  { key: 'confirmStatus', label: '确认' },
  { key: 'reportStatus', label: '上报' }
]
async function load() { loading.value = true; try { rows.value = await http.get('/admin/records') } finally { loading.value = false } }
function openAmend(r) { amendId.value = r.id; amendReason.value = ''; showAmend.value = true }
async function submitAmend() {
  if (!amendReason.value) { toast.error('修订原因必填'); return }
  try { await http.post(`/records/${amendId.value}/amend`, { reason: amendReason.value }); toast.success('已生成 v2'); showAmend.value = false; load() }
  catch (e) { toast.error(e.message || '修订失败') }
}
onMounted(load)
</script>
<style scoped>
.page-head { margin-bottom: 16px; }
.page-head h1 { font-size: 20px; }
.rec-list { list-style: none; padding: 0; margin-top: 12px; }
.rec-list li { display: flex; justify-content: space-between; align-items: center; padding: 8px 12px; background: var(--panel); border: 1px solid var(--line); border-radius: 6px; margin-bottom: 6px; font-size: 13px; }
.page input { width: 100%; padding: 8px; background: var(--panel-2); border: 1px solid var(--line-2); border-radius: 6px; color: var(--text); }
</style>