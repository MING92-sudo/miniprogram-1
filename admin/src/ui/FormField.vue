<template>
  <div class="field">
    <label class="field-label">{{ label }}<span v-if="required" class="req">*</span></label>
    <slot />
    <p v-if="error" class="field-error">{{ error }}</p>
  </div>
</template>
<script setup>
// M2 表单字段：标签 + 必填星号 + 下方错误文案（spec §3.4）
defineProps({ label: String, required: Boolean, error: { type: String, default: '' } })
</script>
<style scoped>
.field { margin-bottom: 14px; }
.field-label { display: block; font-size: 12px; color: var(--muted); margin-bottom: 5px; }
.req { color: var(--signal); margin-left: 3px; }
.field-error { color: var(--signal); font-size: 12px; margin: 5px 0 0; }
</style>