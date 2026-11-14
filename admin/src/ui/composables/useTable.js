// 表格 headless 逻辑：排序三态/搜索/筛选/多选 + 分页
import { ref, computed } from 'vue'
import { usePagination } from './usePagination'
export function useTable(rowsRef, { searchKeys = [] } = {}) {
  const query = ref('')
  const sortKey = ref('')
  const sortDir = ref('none')
  const filters = ref({})
  const selected = ref(new Set())
  const filtered = computed(() => {
    let out = rowsRef.value.slice()
    const q = query.value.trim().toLowerCase()
    if (q) out = out.filter(r => searchKeys.some(k => String(r[k] ?? '').toLowerCase().includes(q)))
    Object.entries(filters.value).forEach(([k, v]) => {
      if (v !== '' && v != null) out = out.filter(r => String(r[k]) === String(v))
    })
    if (sortDir.value !== 'none' && sortKey.value) {
      const dir = sortDir.value === 'asc' ? 1 : -1
      out.sort((a, b) => (a[sortKey.value] > b[sortKey.value] ? dir : a[sortKey.value] < b[sortKey.value] ? -dir : 0))
    }
    return out
  })
  const total = computed(() => filtered.value.length)
  const pagination = usePagination(total, 8)
  const visibleRows = computed(() => pagination.slice(filtered.value))
  function toggleSort(key) {
    if (sortKey.value !== key) { sortKey.value = key; sortDir.value = 'asc'; return }
    sortDir.value = sortDir.value === 'asc' ? 'desc' : sortDir.value === 'desc' ? 'none' : 'asc'
    if (sortDir.value === 'none') sortKey.value = ''
  }
  function setFilter(key, val) { filters.value = { ...filters.value, [key]: val }; pagination.setPage(1) }
  function clearFilters() { filters.value = {}; query.value = ''; pagination.setPage(1) }
  function toggleSelect(id) {
    const next = new Set(selected.value)
    next.has(id) ? next.delete(id) : next.add(id)
    selected.value = next
  }
  const allSelected = computed(() => visibleRows.value.length > 0 && visibleRows.value.every(r => selected.value.has(r.id)))
  function toggleAll() {
    const next = new Set(selected.value)
    if (allSelected.value) visibleRows.value.forEach(r => next.delete(r.id))
    else visibleRows.value.forEach(r => next.add(r.id))
    selected.value = next
  }
  return { query, sortKey, sortDir, filters, selected, visibleRows, total, toggleSort, setFilter, clearFilters, toggleSelect, toggleAll, allSelected, pagination }
}