<template>
  <div :class="['field', { bad: !!error }]">
    <label>
      <span v-if="required" class="req">*</span>{{ label }}
      <span v-if="hint" class="hint">{{ hint }}</span>
    </label>
    <slot />
    <span class="err">
      <svg class="ic ic-sm" aria-hidden="true"><use href="#i-alert" /></svg>
      <span class="err-t">{{ error }}</span>
    </span>
  </div>
</template>
<script setup>
// 表单字段（原型 fieldHtml 对齐）：必填星号 + hint + 下方错误文案（.field.bad 驱动）
defineProps({
  label: String, required: Boolean, hint: { type: String, default: '' },
  error: { type: String, default: '' }
})
</script>