<template>
  <div class="card card-pad0">
    <div class="tbl-toolbar">
      <div v-if="searchKeys.length" class="search-box">
        <span v-html="searchIcon" />
        <input v-model="t.query.value" type="text" :placeholder="searchPlaceholder || '搜索…'" aria-label="搜索" @input="t.pagination.setPage(1)" />
      </div>
      <div v-for="f in filters" :key="f.key" class="fl">
        <label>{{ f.label }}</label>
        <select class="ctl" style="height:34px;width:auto;min-width:120px" :value="t.filters.value[f.key] || ''"
          :aria-label="f.label" @change="t.setFilter(f.key, $event.target.value)">
          <option value="">全部</option>
          <option v-for="o in f.options" :key="optValue(o)" :value="optValue(o)">{{ optLabel(o) }}</option>
        </select>
      </div>
      <div v-if="$slots.toolbar" class="grow" />
      <slot name="toolbar" />
    </div>
    <LoadingSkeleton v-if="loading" :rows="8" />
    <EmptyState v-else-if="!t.total.value" :chips="activeChips" :hint="emptyHint" clearable @clear="t.clearFilters" />
    <template v-else>
      <div v-if="selectable && t.selected.value.size" class="bulkbar">
        <span v-html="checkIcon" />已选 <b>{{ t.selected.value.size }}</b> 项
        <PermButton v-for="a in bulkActions" :key="a.code + a.label" :code="a.code" :label="a.label"
          :variant="a.variant || 'ghost'" size="xs" :icon="a.icon" :c-tip="a.cTip"
          @click="$emit('bulk', { action: a.code, ids: [...t.selected.value] })" />
        <button class="btn btn-ghost btn-xs" type="button" @click="t.selected.value = new Set()">清空选择</button>
      </div>
      <div class="tbl-wrap">
        <table class="tbl">
          <thead>
            <tr>
              <th v-if="selectable" class="col-chk">
                <input type="checkbox" :class="['chk', { indet: someSelected && !t.allSelected.value }]"
                  :checked="t.allSelected.value" aria-label="全选" @change="t.toggleAll" />
              </th>
              <th v-if="index" class="col-idx">#</th>
              <th v-for="col in columns" :key="col.key" :class="[col.cls || '', { sortable: col.sortable, sorted: t.sortKey.value === col.key && t.sortDir.value !== 'none' }]"
                @click="col.sortable && t.toggleSort(col.key)">
                <span class="th-in">{{ col.label }}
                  <svg v-if="col.sortable" class="ic sort-ic" :style="t.sortKey.value === col.key && t.sortDir.value === 'desc' ? 'transform:rotate(180deg)' : ''" aria-hidden="true">
                    <use :href="t.sortKey.value === col.key && t.sortDir.value !== 'none' ? '#i-chevron-down' : '#i-sort'" />
                  </svg>
                </span>
              </th>
              <th v-if="$slots['row-actions']" class="acts">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, i) in t.visibleRows.value" :key="row.id"
              :class="[{ sel: t.selected.value.has(row.id), clickable: clickable }, rowClass ? rowClass(row) : '']"
              @click="onRowClick(row, $event)">
              <td v-if="selectable" class="col-chk">
                <input type="checkbox" class="chk" :checked="t.selected.value.has(row.id)" :aria-label="'选择 ' + row.id"
                  @click.stop @change="t.toggleSelect(row.id)" />
              </td>
              <td v-if="index" class="col-idx">{{ t.pagination.offset.value + i + 1 }}</td>
              <td v-for="col in columns" :key="col.key" :class="col.cls || ''">
                <slot :name="'cell-' + col.key" :row="row" :value="row[col.key]">{{ formatCell(col, row[col.key]) }}</slot>
              </td>
              <td v-if="$slots['row-actions']" class="acts" @click.stop>
                <slot name="row-actions" :row="row" />
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="tbl-foot">
        <span class="tbl-count">共 <b>{{ t.total.value }}</b> 条 · 第 <b>{{ t.pagination.page.value }}</b>/<b>{{ t.pagination.pageCount.value }}</b> 页</span>
        <div class="fl">
          <label>每页</label>
          <select class="ctl" style="height:28px;width:auto" :value="t.pagination.size.value" aria-label="每页条数"
            @change="t.pagination.setSize(+$event.target.value)">
            <option :value="8">8</option><option :value="16">16</option><option :value="32">32</option>
          </select>
        </div>
        <div class="pager">
          <button class="pg" :disabled="t.pagination.page.value <= 1" aria-label="上一页" @click="t.pagination.setPage(t.pagination.page.value - 1)">
            <svg class="ic ic-sm" aria-hidden="true"><use href="#i-chevron-left" /></svg>
          </button>
          <template v-for="(p, pi) in pageList" :key="pi">
            <span v-if="p === '…'" class="dim">…</span>
            <button v-else :class="['pg', { on: p === t.pagination.page.value }]" @click="t.pagination.setPage(p)">{{ p }}</button>
          </template>
          <button class="pg" :disabled="t.pagination.page.value >= t.pagination.pageCount.value" aria-label="下一页" @click="t.pagination.setPage(t.pagination.page.value + 1)">
            <svg class="ic ic-sm" aria-hidden="true"><use href="#i-chevron-right" /></svg>
          </button>
        </div>
      </div>
    </template>
  </div>
</template>
<script setup>
// 数据表（原型 tableCard/paintTableBody 逐字对齐）：排序三态/搜索/筛选/分页 8-16-32/批量条/空态 chips/骨架
import { computed } from 'vue'
import { useTable } from './composables/useTable'
import { formatCell, statusLabel, roleLabel } from './labels'
import { icon } from './icons'
import LoadingSkeleton from './states/LoadingSkeleton.vue'
import EmptyState from './states/EmptyState.vue'
import PermButton from './PermButton.vue'
const props = defineProps({
  columns: { type: Array, required: true }, rows: { type: Array, default: () => [] },
  loading: Boolean, selectable: Boolean, searchKeys: { type: Array, default: () => [] },
  searchPlaceholder: { type: String, default: '' }, emptyHint: { type: String, default: '' },
  filters: { type: Array, default: () => [] }, bulkActions: { type: Array, default: () => [] },
  clickable: Boolean, index: Boolean, rowClass: { type: Function, default: null }
})
const emit = defineEmits(['sort', 'search', 'filter', 'page', 'select', 'bulk', 'row-click'])
const rowsRef = computed(() => props.rows)
const t = useTable(rowsRef, { searchKeys: props.searchKeys })
const searchIcon = icon('search')
const checkIcon = icon('check', 'ic-sm')
const someSelected = computed(() => t.visibleRows.value.some(r => t.selected.value.has(r.id)))
// 原型分页省略逻辑：>7 页时仅显示当前±2 + 首尾页
const pageList = computed(() => {
  const pages = t.pagination.pageCount.value, cur = t.pagination.page.value
  const out = []
  for (let p = 1; p <= pages; p++) {
    if (pages > 7 && Math.abs(p - cur) > 2 && p !== 1 && p !== pages) {
      if (Math.abs(p - cur) === 3) out.push('…')
      continue
    }
    out.push(p)
  }
  return out
})
function onRowClick(row, e) {
  if (!props.clickable) return
  if (e.target.closest('input') || e.target.closest('button')) return
  emit('row-click', row)
}
const activeChips = computed(() => {
  const chips = []
  if (t.query.value) chips.push('关键词：' + t.query.value)
  Object.entries(t.filters.value).filter(([, v]) => v).forEach(([k, v]) => {
    const def = props.filters.find((f) => f.key === k)
    const label = (def && def.label) || k
    const opt = def && def.options && def.options.find((o) => optValue(o) === v)
    const display = opt ? optLabel(opt) : (k === 'status' ? statusLabel(v) : (k === 'role' || k === 'roles') ? roleLabel(v) : v)
    chips.push(label + '：' + display)
  })
  return chips
})
function optValue(o) { return o && typeof o === 'object' ? o.value : o }
function optLabel(o) { return o && typeof o === 'object' ? o.label : o }
</script>