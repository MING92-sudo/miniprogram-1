<template>
  <div>
    <ViewHead title="审计日志" sub="登录 / 授权 / 签字 / 退回 / 导出 / 作废 · 只读不可删">
      <template #meta>
        <PageMeta perm="audit:view" :extra="[{ k: '日志数', v: rows.length, icon: 'history', cls: 'count' }]" />
      </template>
    </ViewHead>
    <div class="notice-bar mt16">
      <svg class="ic" aria-hidden="true"><use href="#i-lock" /></svg>
      <span><b>审计日志只读，不可删除或修改。</b>所有写操作即时追加日志行并可在本页看到。</span>
    </div>
    <div class="mt12">
      <DataTable :columns="cols" :rows="rows" :loading="loading"
        search-placeholder="搜索操作人 / 对象 / 哈希" :search-keys="['actorId', 'target', 'action', 'hashRef']"
        :filters="filters" empty-hint="没有符合条件的审计记录。">
        <template #toolbar>
          <PermButton code="stats:export" label="导出日志" variant="ghost" icon="download" @click="exportAudit" />
        </template>
        <template #cell-createdAt="{ row }"><span class="mono f12">{{ fmtTime(row.createdAt) }}</span></template>
        <template #cell-actorId="{ row }">
          <b>{{ row.actorId || '—' }}</b>
          <div class="cell-2 dim f11">{{ roleLabel(row.actorRole) }}</div>
        </template>
        <template #cell-action="{ row }"><span class="tag tag-ghost">{{ permLabel(row.action) }}</span></template>
        <template #cell-target="{ row }"><span class="mono f12">{{ row.target || '—' }}</span></template>
        <template #cell-scope="{ row }"><span class="scope-pill">{{ row.projectId || row.orgId || '—' }}</span></template>
        <template #cell-ip="{ row }"><span class="mono f12">{{ row.ip || '—' }}</span></template>
        <template #cell-result="{ row }">
          <span :class="['tag', resultTone(row.result)]">{{ resultLabel(row.result) }}</span>
        </template>
        <template #cell-hashRef="{ row }"><span class="mono f12 c-amber">{{ String(row.hashRef || '').slice(0, 8) || '—' }}</span></template>
      </DataTable>
    </div>
  </div>
</template>
<script setup>
// 审计日志（原型 Views.audit 对齐）：只读，无删除/编辑入口；
import { ref, computed, onMounted } from 'vue'
import http from '../api/client'
import DataTable from '../ui/DataTable.vue'
import ViewHead from '../ui/ViewHead.vue'
import PageMeta from '../ui/PageMeta.vue'
import PermButton from '../ui/PermButton.vue'
import { useToast } from '../ui/stores/ui'
import { exportCsv } from '../ui/export-csv'
import { roleLabel, permLabel } from '../ui/labels'
const toast = useToast()
const rows = ref([])
const loading = ref(false)
const cols = [
  { key: 'createdAt', label: '时间', sortable: true },
  { key: 'actorId', label: '操作人 / 角色', sortable: true },
  { key: 'action', label: '动作', sortable: true },
  { key: 'target', label: '对象' },
  { key: 'scope', label: '范围快照' },
  { key: 'ip', label: 'IP' },
  { key: 'result', label: '结果', sortable: true },
  { key: 'hashRef', label: '哈希前 8 位' }
]
// 动作筛选按真实日志动态去重（后端 action 存能力码）；结果固定三态
const filters = computed(() => [
  { key: 'action', label: '动作', options: [...new Set(rows.value.map(r => r.action).filter(Boolean))].map(a => ({ value: a, label: permLabel(a) })) },
  { key: 'result', label: '结果', options: [{ value: 'SUCCESS', label: '成功' }, { value: 'FAILED', label: '失败' }, { value: 'BLOCKED', label: '被拦截' }] }
])
function fmtTime(t) { return String(t || '').replace('T', ' ').slice(0, 16) }
function exportAudit() {
  const n = exportCsv('审计日志', cols, rows.value)
  toast.success('已导出 ' + n + ' 条审计日志', 'CSV · 按导出人权限脱敏')
}
function resultLabel(r) { return r === 'SUCCESS' ? '成功' : r === 'BLOCKED' ? '被拦截' : r === 'FAILED' ? '失败' : (r || '—') }
function resultTone(r) { return r === 'SUCCESS' ? 'tag-ok' : r === 'BLOCKED' ? 'tag-signal' : 'tag-amber' }
onMounted(async () => {
  loading.value = true
  try {
    const d = await http.get('/audit?limit=200')
    rows.value = Array.isArray(d) ? d : (d && d.list) || []
  } catch (e) { toast.error(e.message || '审计日志加载失败') } finally { loading.value = false }
})
</script>