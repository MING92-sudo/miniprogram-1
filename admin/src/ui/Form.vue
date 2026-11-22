<template>
  <form class="ui-form" @submit.prevent="onSubmit" novalidate>
    <slot :errors="errors" :values="values" />
  </form>
</template>
<script setup>
import { watch } from 'vue'
import { useForm } from './composables/useForm'
const props = defineProps({ rules: { type: Object, required: true }, modelValue: { type: Object, default: () => ({}) } })
const emit = defineEmits(['update:modelValue', 'submit'])
const { values, errors, validateField, validateAll } = useForm(props.rules)
watch(() => props.modelValue, (v) => Object.assign(values, v), { immediate: true, deep: true })
watch(values, (v) => emit('update:modelValue', { ...v }), { deep: true })
function onSubmit() {
  Object.assign(values, props.modelValue)
  if (validateAll()) emit('submit', { ...values })
}
// 暴露的 validate 同样先同步（CRUD 视图直接调 formRef.validate()，不走 onSubmit）
function validate() {
  Object.assign(values, props.modelValue)
  return validateAll()
}
defineExpose({ validate, validateField, errors, values })
</script>
<style scoped>
.ui-form { display: block; }
</style>