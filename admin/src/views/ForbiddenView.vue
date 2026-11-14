<template>
  <div class="ui-scope fb-page">
    <ForbiddenState :code="code" :allowed-roles="allowedRoles" :current-role="auth.role" />
    <router-link to="/dashboard" class="fb-back">返回工作台</router-link>
  </div>
</template>
<script setup>
// M2 403 页：所需码 + 允许角色 + 当前角色（spec §5.3）
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import ForbiddenState from '../ui/states/ForbiddenState.vue'
import { useAuthStore } from '../stores/auth'
const route = useRoute()
const auth = useAuthStore()
const code = computed(() => route.query.code || '')
const allowedRoles = computed(() => (route.query.roles ? String(route.query.roles).split(',') : []))
</script>
<style scoped>
.fb-page { min-height: 100vh; display: flex; flex-direction: column; align-items: center; justify-content: center; }
.fb-back { color: var(--amber); font-size: 13px; margin-top: 16px; }
</style>