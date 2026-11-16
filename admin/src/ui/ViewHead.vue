<template>
  <div class="view-head">
    <div class="view-title">
      <h1>{{ title }}</h1>
      <span v-if="sub" class="vt-sub">{{ sub }}</span>
      <div v-if="$slots.actions" class="vt-actions"><slot name="actions" /></div>
    </div>
    <slot name="meta" />
  </div>
</template>
<script setup>
// 视图头（原型 viewHead 对齐）：H1 + 副标题 + 右侧操作区 + 元信息条插槽
defineProps({ title: { type: String, required: true }, sub: { type: String, default: '' } })
</script>