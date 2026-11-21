<template>
  <div>
    <ViewHead v-if="!embedded" title="区域管理" sub="区域主数据 · 仅平台管理员可写（region:write）">
      <template #meta><PageMeta perm="region:view" :extra="[{ k: '区域数', v: rows.length, icon: 'map-pin', cls: 'count' }]" /></template>
    </ViewHead>
    <div class="mt16">
      <DataTable :columns="cols" :rows="rows" :loading="loading" :search-keys="['name', 'code']"
        search-placeholder="搜索区域名称 / 编码" empty-hint="暂无区域数据。">
        <template #cell-code="{ value }"><span class="mono f12">{{ value || '—' }}</span></template>
      </DataTable>
    </div>
    <RuleNote class="mt12" icon="lock">区域主数据为合规保留模块；写操作仅平台（region:write），数据范围谓词引用区域锚点。</RuleNote>
    <RuleNote class="mt8" icon="map-pin">重庆市为总区域：电梯「所在区域」留空 = 全市可抢单/可见；选区县 = 仅该区域维保员可接（2025-11 区划调整：撤江北区/渝北区，设两江新区，全市 37 区县）。</RuleNote>
  </div>
</template>
<script setup>
// embedded：作为 Tab 子页嵌入时隐藏自身页头（合并视图用）
defineProps({ embedded: Boolean })
// 区域管理：列表（region:view）
import { ref, onMounted } from 'vue'
import http from '../api/client'
import DataTable from '../ui/DataTable.vue'
import ViewHead from '../ui/ViewHead.vue'
import PageMeta from '../ui/PageMeta.vue'
import RuleNote from '../ui/RuleNote.vue'
const rows = ref([])
const loading = ref(false)
const cols = [
  { key: 'name', label: '区域名称', sortable: true },
  { key: 'code', label: '编码' },
  { key: 'remark', label: '备注' }
]
onMounted(async () => { loading.value = true; try { rows.value = await http.get('/regions') } finally { loading.value = false } })
</script>