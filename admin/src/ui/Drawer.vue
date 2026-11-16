<template>
  <template v-if="open">
    <div class="drawer-scrim open" @click="$emit('close')" />
    <aside class="drawer" role="dialog" aria-modal="true" :aria-label="title" tabindex="-1" @keydown.esc="$emit('close')">
      <div class="drawer-head">
        <div class="dh-top">
          <div class="grow">
            <h2>{{ title }}</h2>
            <div v-if="sub" class="dh-sub">{{ sub }}</div>
          </div>
          <button class="icon-btn dh-x" aria-label="关闭" @click="$emit('close')">
            <svg class="ic" aria-hidden="true"><use href="#i-x" /></svg>
          </button>
        </div>
        <div v-if="hasMeta" class="drawer-meta">
          <slot name="meta">
            <span v-if="meta.role" class="scope-pill">{{ meta.role }}</span>
            <span v-if="meta.perm" class="scope-pill">{{ meta.perm }}</span>
            <span v-if="meta.scope" :class="['scope-pill', { all: meta.scope === 'ALL' }]">{{ meta.scope }}</span>
          </slot>
        </div>
      </div>
      <div class="drawer-body"><slot /></div>
      <div class="drawer-foot">
        <span v-if="note" class="ff-note">{{ note }}</span>
        <slot name="footer" />
      </div>
    </aside>
  </template>
</template>
<script setup>
// 抽屉（原型 openDrawer/drawerHead 对齐）：右 620px + 元信息条 + Esc 关闭
import { computed, watch, nextTick, useSlots } from 'vue'
const props = defineProps({
  open: Boolean, title: { type: String, default: '' }, sub: { type: String, default: '' },
  meta: { type: Object, default: () => ({}) }, note: { type: String, default: '' }
})
defineEmits(['close'])
const slots = useSlots()
const hasMeta = computed(() => Boolean(props.meta.role || props.meta.perm || props.meta.scope) || Boolean(slots.meta))
watch(() => props.open, async (o) => { if (o) { await nextTick(); document.querySelector('.drawer')?.focus() } })
</script>