<template>
  <div>
    <ViewHead title="外接建档" sub="合同前置 · 缺件待补办 · 接管查验责任归属必填 · archive_source=2 + 首保 +3 日">
      <template #meta><PageMeta perm="filing:view" :extra="[{ k: '建档单', v: rows.length, icon: 'folder', cls: 'count' }]" /></template>
    </ViewHead>
    <div class="mt16">
      <DataTable :columns="cols" :rows="rows" :loading="loading" :search-keys="['elevatorCode', 'useUnitName']"
        search-placeholder="搜索设备码 / 使用单位" empty-hint="暂无外接建档单。"
        :filters="[{ key: 'status', label: '状态', options: ['INIT', 'DOC_COLLECTING', 'TAKEOVER_CHECKING', 'PENDING_CONFIRM', 'ARCHIVED'] }]">
        <template #cell-elevatorCode="{ value }"><span class="mono">{{ value }}</span></template>
        <template #cell-status="{ value }"><StatusTag :status="value" /></template>
        <template #cell-createdAt="{ value }"><span class="mono f12">{{ value }}</span></template>
      </DataTable>
    </div>
    <RuleNote class="mt12" icon="info">外接建档状态机：草稿 → 资料收集 → 接管查验 → 待确认 → 已归档；归档 archive_source=2，首保 +3 日（区别于移交 +15 日）。</RuleNote>
  </div>
</template>
<script setup>
// 外接建档：只读列表（filing:view）
import { ref, onMounted } from 'vue'
import http from '../api/client'
import DataTable from '../ui/DataTable.vue'
import ViewHead from '../ui/ViewHead.vue'
import PageMeta from '../ui/PageMeta.vue'
import RuleNote from '../ui/RuleNote.vue'
import StatusTag from '../ui/StatusTag.vue'
const rows = ref([])
const loading = ref(false)
const cols = [
  { key: 'elevatorCode', label: '设备码', sortable: true },
  { key: 'useUnitName', label: '使用单位' },
  { key: 'status', label: '状态', sortable: true },
  { key: 'createdAt', label: '创建', sortable: true }
]
onMounted(async () => { loading.value = true; try { rows.value = (await http.get('/filings')).list || [] } finally { loading.value = false } })
</script>