<template>
  <div>
    <ViewHead v-if="!embedded" title="检查项模板" sub="维保检查项模板（区别于系统配置签字模板）· 品种 × 附件 A/B/C/D 路由">
      <template #meta><PageMeta perm="template:view" :extra="[{ k: '模板数', v: rows.length, icon: 'clipboard-list', cls: 'count' }]" /></template>
    </ViewHead>
    <div class="mt16">
      <DataTable :columns="cols" :rows="rows" :loading="loading" :search-keys="['name', 'categoryScope', 'itemCode']"
        search-placeholder="搜索检查项 / 品种 / 项目编号" empty-hint="暂无检查项模板。">
        <template #cell-enabled="{ value }">
          <span :class="['tag', value ? 'tag-ok' : 'tag-dim']">{{ value ? '启用' : '停用' }}</span>
        </template>
      </DataTable>
    </div>
    <RuleNote class="mt12" icon="info">品种四类口径（TSG T5002 第八条）：曳引与强制驱动 / 液压 / 杂物 / 自动扶梯与人行道 → 附件 A/B/C/D；启停按 template:toggle（质量负责人可操作）。</RuleNote>
  </div>
</template>
<script setup>
// embedded：作为 Tab 子页嵌入时隐藏自身页头（合并视图用）
defineProps({ embedded: Boolean })
// 检查项模板：列表（template:view）
import { ref, onMounted } from 'vue'
import http from '../api/client'
import DataTable from '../ui/DataTable.vue'
import ViewHead from '../ui/ViewHead.vue'
import PageMeta from '../ui/PageMeta.vue'
import RuleNote from '../ui/RuleNote.vue'
const rows = ref([])
const loading = ref(false)
const cols = [
  { key: 'name', label: '检查项', sortable: true },
  { key: 'categoryScope', label: '品种' },
  { key: 'appendix', label: '附件' },
  { key: 'itemCode', label: '项目编号', sortable: true },
  { key: 'enabled', label: '状态' }
]
onMounted(async () => { loading.value = true; try { rows.value = (await http.get('/admin/templates')).list || [] } finally { loading.value = false } })
</script>