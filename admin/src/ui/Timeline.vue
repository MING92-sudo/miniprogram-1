<template>
  <ol class="timeline">
    <li v-for="(it, i) in items" :key="i" class="tl-item">
      <span class="tl-dot" />
      <div class="tl-body">
        <div class="tl-head"><strong>{{ it.actor }}</strong><span class="tl-role">{{ it.role }}</span><span class="tl-time num">{{ it.time }}</span></div>
        <div class="tl-text">{{ it.text }}</div>
      </div>
    </li>
  </ol>
</template>
<script setup>
// M2 时间轴：操作人 + 角色 + 时间（spec §3.7）
defineProps({ items: { type: Array, default: () => [] } })
</script>
<style scoped>
.timeline { list-style: none; margin: 0; padding: 0; }
.tl-item { position: relative; padding: 0 0 14px 20px; border-left: 1px solid var(--line); }
.tl-dot { position: absolute; left: -4px; top: 4px; width: 7px; height: 7px; border-radius: 50%; background: var(--amber); }
.tl-head { display: flex; gap: 8px; align-items: baseline; font-size: 12px; }
.tl-role { color: var(--dim); font-family: var(--font-mono); font-size: 11px; }
.tl-time { color: var(--muted); margin-left: auto; }
.tl-text { color: var(--text); font-size: 13px; margin-top: 2px; }
</style>