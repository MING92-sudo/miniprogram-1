<template>
  <div class="page">
    <div class="page-head"><h1>合同管理</h1></div>
    <div class="mask-card">
      <p class="label">金额脱敏对照</p>
      <div class="mask-row"><span>维保主管视角</span><strong>¥ ***万（仅期与台量）</strong></div>
      <div class="mask-row"><span>经理/使用单位/平台视角</span><strong>完整金额</strong></div>
    </div>
    <DataTable :columns="cols" :rows="rows" :loading="loading" :search-keys="['contractNo']" />
  </div>
</template>
<script setup>
// M3 合同管理：金额分级脱敏（服务端裁剪），页内对照卡
import { ref, onMounted, computed } from 'vue'
import DataTable from '../ui/DataTable.vue'
import { listContractsLedger } from '../api/ledger'
const rows = ref([])
const loading = ref(false)
const cols = computed(() => [
  { key: 'contractNo', label: '合同号', sortable: true },
  { key: 'useUnitId', label: '使用单位' },
  { key: 'startDate', label: '起' },
  { key: 'endDate', label: '止', sortable: true },
  { key: 'amountDisplay', label: '金额' },
  { key: 'status', label: '状态' }
])
async function load() {
  loading.value = true
  try {
    const data = await listContractsLedger()
    rows.value = data.map(c => ({ ...c, amountDisplay: c.amountMasked ? c.amountText : String(c.amount) }))
  } finally { loading.value = false }
}
onMounted(load)
</script>
<style scoped>
.page-head { margin-bottom: 16px; }
.page-head h1 { font-size: 20px; }
.mask-card { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 14px; margin-bottom: 16px; }
.mask-row { display: flex; justify-content: space-between; font-size: 13px; padding: 4px 0; color: var(--muted); }
.mask-row strong { color: var(--amber); }
</style>