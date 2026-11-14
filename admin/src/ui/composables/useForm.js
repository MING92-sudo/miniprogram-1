// 表单 headless 校验：失焦 + 提交双校验（spec §3.4）
import { reactive, computed } from 'vue'
export function useForm(rules) {
  const values = reactive({})
  const errors = reactive({})
  const touched = reactive({})
  function validateField(name) {
    const fns = rules[name] || []
    let msg = ''
    for (const fn of fns) { msg = fn(values[name]); if (msg) break }
    errors[name] = msg
    return msg
  }
  function validateAll() {
    let ok = true
    Object.keys(rules).forEach(name => { touched[name] = true; if (validateField(name)) ok = false })
    return ok
  }
  function reset() {
    Object.keys(values).forEach(k => delete values[k])
    Object.keys(errors).forEach(k => delete errors[k])
    Object.keys(touched).forEach(k => delete touched[k])
  }
  const isValid = computed(() => Object.values(errors).every(e => !e))
  return { values, errors, touched, validateField, validateAll, reset, isValid }
}