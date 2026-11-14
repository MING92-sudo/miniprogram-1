<template>
  <div class="error-state" role="alert">
    <p class="err-title">加载失败</p>
    <p class="err-reason">{{ reason || '未知错误' }}</p>
    <button class="err-retry" @click="$emit('retry')">重试</button>
  </div>
</template>
<script setup>
// M2 失败态：可读原因 + 重试 + 退路，不清空已填数据（spec §3.10）
defineProps({ reason: { type: String, default: '' } })
defineEmits(['retry'])
</script>
<style scoped>
.error-state { text-align: center; padding: 32px 16px; }
.err-title { color: var(--signal); font-size: 14px; font-weight: 600; }
.err-reason { color: var(--muted); font-size: 12px; margin: 8px 0 16px; }
.err-retry { background: var(--panel-2); border: 1px solid var(--line-2); color: var(--text); padding: 6px 16px; border-radius: 6px; cursor: pointer; }
</style>