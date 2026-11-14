<template>
  <div class="forbidden">
    <p class="fb-code num">403</p>
    <p class="fb-title">无权限访问</p>
    <p class="fb-line">所需权限码：<code>{{ code }}</code></p>
    <p class="fb-line">允许角色：<span v-for="r in allowedRoles" :key="r" class="fb-role">{{ r }}</span></p>
    <p class="fb-line">当前角色：<span class="fb-role">{{ currentRole }}</span></p>
  </div>
</template>
<script setup>
// M2 403 态：所需码 + 允许角色 + 当前角色（spec §3.10）
defineProps({ code: { type: String, default: '' }, allowedRoles: { type: Array, default: () => [] }, currentRole: { type: String, default: '' } })
</script>
<style scoped>
.forbidden { text-align: center; padding: 48px 16px; }
.fb-code { font-size: 40px; color: var(--signal); font-weight: 700; }
.fb-title { font-size: 16px; margin: 8px 0 20px; }
.fb-line { font-size: 12px; color: var(--muted); margin: 6px 0; }
.fb-line code { color: var(--amber); font-family: var(--font-mono); }
.fb-role { display: inline-block; margin: 0 4px; padding: 1px 6px; border: 1px solid var(--line-2); border-radius: 4px; font-family: var(--font-mono); font-size: 11px; color: var(--text); }
</style>