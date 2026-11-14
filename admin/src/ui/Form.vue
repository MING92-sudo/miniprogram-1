<template>
  <form class="ui-form" @submit.prevent="onSubmit" novalidate>
    <slot :errors="errors" :values="values" />
  </form>
</template>
<script setup>
// M2 表单：失焦 + 提交双校验（spec §3.4）；暴露 validate()
import { watch } from 'vue'
import { useForm } from './composables/useForm'
const props = defineProps({ rules: { type: Object, required: true }, modelValue: { type: Object, default: () => ({}) } })
const emit = defineEmits(['update:modelValue', 'submit'])
const { values, errors, validateField, validateAll } = useForm(props.rules)
watch(() => props.modelValue, (v) => Object.assign(values, v), { immediate: true, deep: true })
watch(values, (v) => emit('update:modelValue', { ...v }), { deep: true })
function onSubmit() { if (validateAll()) emit('submit', { ...values }) }
defineExpose({ validate: validateAll, validateField, errors, values })
</script>
<style scoped>
.ui-form { display: block; }
</style>