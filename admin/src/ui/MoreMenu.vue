<template>
  <span class="more-menu" ref="root">
    <button class="btn btn-ghost btn-xs" type="button" @click.stop="open = !open">
      {{ label }} <svg class="ic ic-sm" aria-hidden="true"><use href="#i-chevron-down" /></svg>
    </button>
    <span v-if="open" class="more-panel" @click="open = false"><slot /></span>
  </span>
</template>
<script setup>
// 行内「更多 ▾」下拉（参照 sptecloud 员工管理行操作）：点击外部自动收起
import { ref, onMounted, onBeforeUnmount } from 'vue'
defineProps({ label: { type: String, default: '更多' } })
const open = ref(false)
const root = ref(null)
function onDoc(e) { if (root.value && !root.value.contains(e.target)) open.value = false }
onMounted(() => document.addEventListener('click', onDoc))
onBeforeUnmount(() => document.removeEventListener('click', onDoc))
</script>
<style scoped>
.more-menu { position: relative; display: inline-block }
.more-panel {
  position: absolute; right: 0; top: calc(100% + 4px); z-index: 60;
  display: flex; flex-direction: column; gap: 2px; min-width: 128px; padding: 4px;
  background: var(--bg-raise, #171d26); border: 1px solid var(--line-2, #2a3340);
  border-radius: var(--r-card, 10px); box-shadow: 0 10px 28px rgba(0, 0, 0, .45);
}
.more-panel :deep(.btn) { justify-content: flex-start; width: 100% }
</style>
