<template>
  <div class="view" style="max-width:760px;margin:0 auto;padding-top:60px">
    <ForbiddenState :code="code" :allowed-roles="allowedRoles" :current-role="auth.role" :name="name" />
  </div>
</template>
<script setup>
// 403 页（原型 forbidState 对齐）：所需码 + 允许角色 + 当前角色 + 返回工作台
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import ForbiddenState from '../ui/states/ForbiddenState.vue'
import { useAuthStore } from '../stores/auth'
const route = useRoute()
const auth = useAuthStore()
const code = computed(() => route.query.code || '')
const name = computed(() => route.query.name || '')
const allowedRoles = computed(() => (route.query.roles ? String(route.query.roles).split(',') : []))
</script>