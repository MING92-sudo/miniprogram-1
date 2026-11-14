// 权限门控 composable：state 解析 + 两档门控（spec §4.3）
import { computed } from 'vue'
import { state, canonical } from '../permission-v2'
export function usePermission(roleRef) {
  const role = computed(() => canonical(roleRef.value))
  function stateOf(code) { return state(role.value, code) }
  function gate(code) {
    const s = stateOf(code)
    if (s === 'Y') return 'allow'
    if (s === 'C') return 'conditional'
    return 'deny'
  }
  return { stateOf, gate }
}