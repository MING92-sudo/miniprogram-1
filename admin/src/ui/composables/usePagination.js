// 分页 composable：页码/页大小/偏移（headless，可单测）
import { ref, computed } from 'vue'
export function usePagination(totalRef, initialSize = 8) {
  const page = ref(1)
  const size = ref(initialSize)
  const pageCount = computed(() => Math.max(1, Math.ceil((totalRef.value || 0) / size.value)))
  const offset = computed(() => (page.value - 1) * size.value)
  function setPage(n) { page.value = Math.min(Math.max(1, n), pageCount.value) }
  function setSize(n) { size.value = n; page.value = 1 }
  function slice(rows) { return rows.slice(offset.value, offset.value + size.value) }
  return { page, size, pageCount, offset, setPage, setSize, slice }
}