<template>
  <div class="meta-strip">
    <span v-for="(it, i) in items" :key="i" :class="['ms', it.cls || '']">
      <svg v-if="it.icon" class="ic ic-sm" aria-hidden="true"><use :href="'#i-' + it.icon" /></svg>
      {{ it.k }} <b>{{ it.v }}</b>
    </span>
  </div>
</template>
<script setup>
// 页元信息条（原型 pageMeta/metaStrip 对齐）：角色 / 路由 / 范围 / 权限三态 / 只读模式 + 扩展项
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { usePerspectiveStore } from './stores/perspective'
import { stateForRoles } from './permission-v2'
import { roleLabel, scopeLabel } from './labels'
const props = defineProps({
  perm: { type: String, default: '' },
  extra: { type: Array, default: () => [] }
})
const perspective = usePerspectiveStore()
const route = useRoute()
const items = computed(() => {
  const roles = perspective.roleSet
  const roleText = roles.map((role) => roleLabel(role)).join(' / ')
  const out = [
    { k: '角色', v: roleText, icon: 'user' },
    { k: '路由', v: '#' + route.path, icon: 'link' },
    { k: '范围', v: perspective.scope + ' · ' + scopeLabel(perspective.scope), icon: 'database' }
  ]
  if (props.perm) {
    const permState = stateForRoles(roles, props.perm)
    out.push({ k: '权限', v: props.perm + ' = ' + permState, icon: 'key', cls: 'code' })
    if (permState === 'C') out.push({ k: '模式', v: '只读', icon: 'eye', cls: 'count' })
  }
  return out.concat(props.extra || [])
})
</script>