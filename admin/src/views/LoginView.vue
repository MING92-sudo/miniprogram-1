<template>
  <div class="login-wrap">
    <el-card class="login-card">
      <div class="login-head">
        <h2>电梯维保管理端</h2>
        <p>智慧特种设备维保系统 · 管理端（班组长 / 维保部管理员）</p>
      </div>
      <el-form label-position="top" @keyup.enter="submit">
        <el-form-item label="账号（手机号）">
          <el-input v-model="phone" placeholder="请输入账号" maxlength="20" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="password" type="password" show-password placeholder="请输入密码" />
        </el-form-item>
        <el-button type="primary" class="login-btn" :loading="loading" @click="submit">登 录</el-button>
        <div v-if="error" class="login-error">{{ error }}</div>
      </el-form>
      <div class="login-foot">账号由维保单位分配；维保人员请使用小程序</div>
    </el-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const phone = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')

async function submit() {
  if (!phone.value || !password.value) {
    error.value = '请输入账号与密码'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const data = await auth.login(phone.value.trim(), password.value)
    ElMessage.success('登录成功')
    router.push(String(route.query.redirect || '/dashboard'))
    return data
  } catch (e) {
    error.value = e.message || '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-wrap {
  height: 100%; display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #0052d9 0%, #0a2a5c 100%);
}
.login-card { width: 380px; padding: 8px 12px; }
.login-head h2 { margin: 0 0 6px; color: #1d2129; }
.login-head p { margin: 0 0 12px; color: #86909c; font-size: 13px; }
.login-btn { width: 100%; margin-top: 4px; }
.login-error { color: #f53f3f; font-size: 13px; margin-top: 10px; }
.login-foot { color: #86909c; font-size: 12px; margin-top: 14px; text-align: center; }
</style>
