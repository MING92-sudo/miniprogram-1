<template>
  <div class="login-wrap">
    <div class="login-left">
      <div class="login-brand">
        <div class="login-mark">博</div>
        <div>
          <b style="font-size:18px;letter-spacing:.06em">博威无纸化</b>
          <div class="label-mono mt2">ELEVATOR MAINTENANCE PLATFORM</div>
        </div>
      </div>
      <h1 class="login-h1">电梯维保<span>管理端</span><br>权限驱动的无纸化运营</h1>
      <p class="login-sub">一个手机号 = 一个账号，登录页不选角色；后台下发 roles + permissions + dataScope，前端据此渲染。前端只隐藏、后端必校验。</p>
      <div class="login-chains">
        <div class="chain"><span class="ch-k">安装移交</span><span class="ch-v">项目负责人发起<i>→</i>资料员上传<i>→</i>安装主管审核<i>→</i>维保主管接收<i>→</i>维保员验收<i>→</i>双方签字</span></div>
        <div class="chain"><span class="ch-k">维保双签</span><span class="ch-v">主检先签<i>→</i>配合人后签（不同账号）<i>→</i>使用单位签署<i>→</i>自动归档</span></div>
        <div class="chain"><span class="ch-k">数据范围</span><span class="ch-v">SELF<i>·</i>TEAM<i>·</i>PROJECT<i>·</i>ORG<i>·</i>ORG_FLAT<i>·</i>ALL</span></div>
      </div>
    </div>
    <div class="login-right">
      <div class="login-card">
        <h2>后台登录</h2>
        <div class="lc-sub">账号 + 密码 · 角色与数据范围由服务端下发</div>
        <Form :rules="rules" :modelValue="model" @submit="onSubmit" v-slot="{ errors }">
          <div class="form-grid one">
            <FormField label="后台账号" required hint="手机号" :error="errors.phone">
              <input name="phone" v-model="model.phone" class="ctl mono" autocomplete="username" placeholder="11 位手机号" />
            </FormField>
            <FormField label="密码" required hint="≥6" :error="errors.password">
              <input name="password" type="password" v-model="model.password" class="ctl" autocomplete="current-password" placeholder="至少 6 位" />
            </FormField>
          </div>
          <div class="form-foot mt16">
            <button class="btn btn-primary btn-lg btn-block" :disabled="loading" type="submit">
              <svg class="ic" aria-hidden="true"><use href="#i-logout" /></svg>{{ loading ? '登录中…' : '登 录' }}
            </button>
          </div>
          <p v-if="serverError" class="f12 c-sig mt8">{{ serverError }}</p>
        </Form>
        <div class="login-foot">账号由系统管理员在「账号管理」中开通。<br>角色与数据范围只从服务端读取，绝不接受客户端传入；脱敏在服务端完成。</div>
      </div>
    </div>
  </div>
</template>
<script setup>
// 登录页：左右分栏 + 业务链；鉴权走真实后端，页面不提供任何预置账号
import { reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import Form from '../ui/Form.vue'
import FormField from '../ui/FormField.vue'
import { useAuthStore } from '../stores/auth'
const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const model = reactive({ phone: '', password: '' })
const loading = ref(false)
const serverError = ref('')
const rules = {
  phone: [(v) => (v ? '' : '请输入后台账号')],
  password: [(v) => ((v && v.length >= 6) ? '' : '密码至少 6 位')]
}
async function onSubmit() {
  loading.value = true; serverError.value = ''
  try {
    await auth.login(model.phone, model.password)
    router.push(route.query.redirect || '/dashboard')
  } catch (e) {
    serverError.value = (e && e.message) || '登录失败，请重试'
  } finally { loading.value = false }
}
</script>