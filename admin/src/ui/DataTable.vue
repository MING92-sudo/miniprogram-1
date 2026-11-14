<template>
  <div class="dt">
    <div class="dt-toolbar">
      <input v-if="searchKeys.length" class="dt-search" v-model="t.query.value" placeholder="搜索…" aria-label="搜索" />
      <select v-for="f in filters" :key="f.key" class="dt-filter" :value="t.filters.value[f.key] || ''"
        @change="t.setFilter(f.key, $event.target.value)" :aria-label="f.label">
        <option value="">{{ f.label }}：全部</option>
        <option v-for="o in f.options" :key="optValue(o)" :value="optValue(o)">{{ optLabel(o) }}</option>
      </select>
    </div>
    <LoadingSkeleton v-if="loading" :rows="6" />
    <EmptyState v-else-if="!t.total.value" :chips="activeChips" clearable @clear="t.clearFilters" />
    <template v-else>
      <div v-if="selectable && t.selected.value.size" class="dt-bulk">
        <span>已选 {{ t.selected.value.size }}</span>
        <PermButton v-for="a in bulkActions" :key="a.code" :code="a.code" :label="a.label" variant="danger" @click="$emit('bulk', { action: a.code, ids: [...t.selected.value] })" />
      </div>
      <table class="dt-table">
        <thead>
          <tr>
            <th v-if="selectable" class="dt-check-col"><input type="checkbox" class="dt-check-all" :checked="t.allSelected.value" @change="t.toggleAll" aria-label="全选" /></th>
            <th v-for="col in columns" :key="col.key" @click="col.sortable && t.toggleSort(col.key)" :class="{ sortable: col.sortable }">
              {{ col.label }}<span v-if="col.sortable" class="dt-arrow">{{ arrow(col.key) }}</span>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in t.visibleRows.value" :key="row.id" :class="{ 'row-clickable': clickable }" @click="clickable && $emit('row-click', row)">
            <td v-if="selectable"><input type="checkbox" :checked="t.selected.value.has(row.id)" @change="t.toggleSelect(row.id)" :aria-label="'选择 ' + row.id" /></td>
            <td v-for="col in columns" :key="col.key">{{ formatCell(col, row[col.key]) }}</td>
          </tr>
        </tbody>
      </table>
      <div class="dt-pager">
        <button :disabled="t.pagination.page.value <= 1" @click="t.pagination.setPage(t.pagination.page.value - 1)">上一页</button>
        <span class="num">{{ t.pagination.page.value }} / {{ t.pagination.pageCount.value }}</span>
        <button :disabled="t.pagination.page.value >= t.pagination.pageCount.value" @click="t.pagination.setPage(t.pagination.page.value + 1)">下一页</button>
        <select :value="t.pagination.size.value" @change="t.pagination.setSize(+$event.target.value)" aria-label="每页条数">
          <option :value="8">8</option><option :value="16">16</option><option :value="32">32</option>
        </select>
      </div>
    </template>
  </div>
</template>
<script setup>
// M2 数据表：排序三态/搜索/筛选/分页/批量/空态/骨架（spec §3.1）
import { computed } from 'vue'
import { useTable } from './composables/useTable'
import { formatCell, statusLabel, roleLabel } from './labels'
import LoadingSkeleton from './states/LoadingSkeleton.vue'
import EmptyState from './states/EmptyState.vue'
import PermButton from './PermButton.vue'
const props = defineProps({
  columns: { type: Array, required: true }, rows: { type: Array, default: () => [] },
  loading: Boolean, selectable: Boolean, searchKeys: { type: Array, default: () => [] },
  filters: { type: Array, default: () => [] }, bulkActions: { type: Array, default: () => [] },
  clickable: Boolean
})
defineEmits(['sort', 'search', 'filter', 'page', 'select', 'bulk', 'row-click'])
const rowsRef = computed(() => props.rows)
const t = useTable(rowsRef, { searchKeys: props.searchKeys })
function arrow(key) {
  if (t.sortKey.value !== key) return '↕'
  return t.sortDir.value === 'asc' ? '↑' : t.sortDir.value === 'desc' ? '↓' : '↕'
}
const activeChips = computed(() => Object.entries(t.filters.value).filter(([, v]) => v).map(([k, v]) => {
  const def = props.filters.find((f) => f.key === k)
  const label = (def && def.label) || k
  const opt = def && def.options && def.options.find((o) => optValue(o) === v)
  const display = opt ? optLabel(opt) : (k === 'status' ? statusLabel(v) : (k === 'role' || k === 'roles') ? roleLabel(v) : v)
  return `${label}: ${display}`
}))
function optValue(o) { return o && typeof o === 'object' ? o.value : o }
function optLabel(o) { return o && typeof o === 'object' ? o.label : o }
</script>
<style scoped>
.dt-toolbar { display: flex; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.dt-search, .dt-filter { background: var(--panel-2); border: 1px solid var(--line-2); color: var(--text); padding: 6px 10px; border-radius: 6px; font-size: 12px; }
.dt-bulk { display: flex; align-items: center; gap: 10px; padding: 8px 12px; background: var(--panel-2); border: 1px solid var(--line-2); border-radius: 6px; margin-bottom: 8px; font-size: 12px; }
.dt-table { width: 100%; border-collapse: collapse; }
.dt-table th { text-align: left; padding: 10px 12px; background: var(--panel-2); color: var(--muted); font-size: 11px; font-family: var(--font-mono); letter-spacing: .04em; text-transform: uppercase; border-bottom: 1px solid var(--line-2); }
.dt-table th.sortable { cursor: pointer; }
.dt-table td { padding: 0 12px; height: 40px; border-bottom: 1px solid var(--line); font-size: 13px; }
.dt-table tr.row-clickable { cursor: pointer; }
.dt-table tr.row-clickable:hover td { background: var(--panel-2); }
.dt-arrow { margin-left: 4px; color: var(--amber); }
.dt-pager { display: flex; align-items: center; gap: 10px; margin-top: 12px; font-size: 12px; }
.dt-pager button { background: var(--panel-2); border: 1px solid var(--line-2); color: var(--text); padding: 4px 10px; border-radius: 6px; cursor: pointer; }
.dt-pager button:disabled { opacity: .4; cursor: not-allowed; }
.dt-pager select { background: var(--panel-2); border: 1px solid var(--line-2); color: var(--text); border-radius: 6px; padding: 4px; }
</style>