<template>
  <div class="ui-scope login-page">
    <div class="login-card">
      <div class="login-brand"><span class="brand-mark">梯维通</span><span class="label">LIFT·OPS 管理端</span></div>
      <Form :rules="rules" v-model="model" @submit="onSubmit" v-slot="{ errors }">
        <FormField label="账号" required :error="errors.phone">
          <input name="phone" v-model="model.phone" autocomplete="username" />
        </FormField>
        <FormField label="密码" required :error="errors.password">
          <input name="password" type="password" v-model="model.password" autocomplete="current-password" />
        </FormField>
        <button class="login-btn" :disabled="loading" type="submit">{{ loading ? '登录中…' : '登录' }}</button>
        <p v-if="serverError" class="login-error">{{ serverError }}</p>
      </Form>
    </div>
  </div>
</template>
<script setup>
// M2 登录页：真实后端鉴权 + 双校验（spec §5.1）；校验由 Form 承载（scoped slot errors）
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import Form from '../ui/Form.vue'
import FormField from '../ui/FormField.vue'
import { useAuthStore } from '../stores/auth'
const router = useRouter()
const auth = useAuthStore()
const model = reactive({ phone: '', password: '' })
const loading = ref(false)
const serverError = ref('')
const rules = {
  phone: [(v) => (v ? '' : '请输入账号')],
  password: [(v) => ((v && v.length >= 6) ? '' : '密码至少 6 位')]
}
async function onSubmit() {
  loading.value = true; serverError.value = ''
  try {
    await auth.login(model.phone, model.password)
    router.push('/dashboard')
  } catch (e) {
    serverError.value = (e && e.message) || '登录失败，请重试'
  } finally { loading.value = false }
}
</script>
<style scoped>
.login-page { display: flex; align-items: center; justify-content: center; min-height: 100vh; }
.login-card { width: 360px; padding: 32px; background: var(--panel); border: 1px solid var(--line-2); border-radius: 12px; }
.login-brand { display: flex; flex-direction: column; gap: 4px; margin-bottom: 24px; }
.brand-mark { font-size: 22px; font-weight: 700; color: var(--amber); }
.login-card input { width: 100%; padding: 9px 12px; background: var(--panel-2); border: 1px solid var(--line-2); border-radius: 6px; color: var(--text); font-size: 13px; }
.login-btn { width: 100%; margin-top: 8px; padding: 10px; background: var(--amber); border: none; border-radius: 6px; color: var(--accent-on-amber); font-weight: 600; cursor: pointer; }
.login-btn:disabled { opacity: .6; cursor: not-allowed; }
.login-error { color: var(--signal); font-size: 12px; margin-top: 12px; }
</style>