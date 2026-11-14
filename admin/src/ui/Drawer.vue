<template>
  <div v-if="open" class="drawer-scrim" @click.self="$emit('close')">
    <aside class="drawer" role="dialog" aria-modal="true" :aria-label="title" tabindex="-1" @keydown.esc="$emit('close')">
      <div class="drawer-head"><span>{{ title }}</span><button class="drawer-x" aria-label="关闭" @click="$emit('close')">×</button></div>
      <div class="drawer-meta">
        <span v-if="meta.role" class="label">角色 {{ meta.role }}</span>
        <span v-if="meta.perm" class="label">权限 {{ meta.perm }}</span>
        <span v-if="meta.scope" class="label">范围 {{ meta.scope }}</span>
      </div>
      <div class="drawer-body"><slot /></div>
      <div class="drawer-foot"><slot name="footer" /></div>
    </aside>
  </div>
</template>
<script setup>
// M2 抽屉：右 560px + 元信息条 + Esc 关闭（spec §3.2）
import { watch, nextTick } from 'vue'
const props = defineProps({ open: Boolean, title: { type: String, default: '' }, meta: { type: Object, default: () => ({}) } })
defineEmits(['close'])
watch(() => props.open, async (o) => { if (o) { await nextTick(); document.querySelector('.drawer')?.focus() } })
</script>
<style scoped>
.drawer-scrim { position: fixed; inset: 0; background: var(--scrim); z-index: 800; display: flex; justify-content: flex-end; }
.drawer { width: 560px; max-width: 94vw; height: 100%; background: var(--panel); border-left: 1px solid var(--line-2); display: flex; flex-direction: column; }
.drawer-head { display: flex; justify-content: space-between; padding: 14px 16px; border-bottom: 1px solid var(--line); font-size: 14px; font-weight: 600; }
.drawer-x { background: none; border: none; color: var(--muted); font-size: 18px; cursor: pointer; }
.drawer-meta { display: flex; gap: 12px; padding: 10px 16px; border-bottom: 1px solid var(--line); flex-wrap: wrap; }
.drawer-body { flex: 1; overflow: auto; padding: 16px; }
.drawer-foot { padding: 12px 16px; border-top: 1px solid var(--line); display: flex; gap: 8px; justify-content: flex-end; }
</style>