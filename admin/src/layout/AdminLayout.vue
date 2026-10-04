<template>
  <el-container class="admin-shell">
    <el-aside width="220px" class="admin-aside">
      <div class="brand">
        <div class="brand-title">电梯维保管理端</div>
        <div class="brand-sub">智慧特种设备维保系统</div>
      </div>
      <el-menu :default-active="active" :default-openeds="openMenus" router background-color="#001529"
               text-color="#a6adb4" active-text-color="#ffffff" class="admin-menu">
        <el-menu-item index="/dashboard">
          <el-icon><Odometer /></el-icon><span>监控看板</span>
        </el-menu-item>
        <el-menu-item index="/schedule">
          <el-icon><Tickets /></el-icon><span>计划调度</span>
        </el-menu-item>
        <el-menu-item index="/ledger">
          <el-icon><Notebook /></el-icon><span>电梯台账</span>
        </el-menu-item>
        <el-menu-item index="/templates">
          <el-icon><List /></el-icon><span>模板管理</span>
        </el-menu-item>
        <el-sub-menu index="monitor">
          <template #title><el-icon><Refresh /></el-icon><span>工单监控</span></template>
          <el-menu-item index="/orders"><span>工单列表</span></el-menu-item>
          <el-menu-item index="/faults"><span>急修单</span></el-menu-item>
          <el-menu-item index="/reports/failed"><span>上报异常清单</span></el-menu-item>
        </el-sub-menu>
        <el-sub-menu index="archive">
          <template #title><el-icon><Tickets /></el-icon><span>档案管理</span></template>
          <el-menu-item index="/archive/company"><span>维保单位</span></el-menu-item>
          <el-menu-item index="/archive/use-units"><span>使用单位</span></el-menu-item>
          <el-menu-item index="/archive/employees"><span>人员</span></el-menu-item>
          <el-menu-item index="/archive/elevators"><span>电梯档案</span></el-menu-item>
        </el-sub-menu>
        <el-sub-menu index="register">
          <template #title><el-icon><Notebook /></el-icon><span>平台登记</span></template>
          <el-menu-item index="/register/workers"><span>人员管理</span></el-menu-item>
        </el-sub-menu>
        <el-sub-menu index="logs">
          <template #title><el-icon><Notebook /></el-icon><span>上报日志</span></template>
          <el-menu-item index="/reports/logs"><span>上报日志</span></el-menu-item>
        </el-sub-menu>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="admin-header">
        <div class="page-title">{{ title }}</div>
        <div class="user-box">
          <el-tag :type="auth.canWrite ? 'primary' : 'info'" size="small" class="role-tag">
            {{ roleText }}
          </el-tag>
          <span class="user-name">{{ auth.userName }}</span>
          <el-button link type="danger" @click="auth.logout()">退出登录</el-button>
        </div>
      </el-header>
      <el-main class="admin-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import {
Odometer, Tickets, Refresh, Notebook, List
} from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'
import { ROLE_TEXT } from '../constants'

const route = useRoute()
/** 当前路由所在模块默认展开，其余折叠 */
const openMenus = computed(() => {
  const p = route.path
  if (p.startsWith('/orders') || p.startsWith('/platform') || p.startsWith('/reports/failed')) return ['monitor']
  if (p.startsWith('/archive')) return ['archive']
  if (p.startsWith('/register')) return ['register']
  if (p.startsWith('/reports/logs')) return ['logs']
  return ['monitor']
})
const auth = useAuthStore()

const active = computed(() => '/' + route.path.split('/').filter(Boolean).slice(0, 2).join('/'))
const title = computed(() => (route.meta && route.meta.title) || '电梯维保管理端')
const roleText = computed(() => ROLE_TEXT[auth.role] || auth.role)
</script>

<style scoped>
.admin-shell { height: 100%; }
.admin-aside { background: #001529; }
.brand { padding: 20px 16px 14px; color: #fff; }
.brand-title { font-size: 17px; font-weight: 600; }
.brand-sub { font-size: 12px; color: #86909c; margin-top: 4px; }
.admin-menu { border-right: none; }
.group { color: #86909c; font-size: 12px; }
.admin-header {
  display: flex; align-items: center; justify-content: space-between;
  background: #fff; border-bottom: 1px solid #e5e6eb; height: 56px;
}
.page-title { font-size: 16px; font-weight: 600; color: #1d2129; }
.user-box { display: flex; align-items: center; gap: 10px; }
.user-name { color: #1d2129; }
.admin-main { padding: 16px; overflow: auto; }
</style>

<style>
/* 全局布局微调（管理端所有页面生效） */
.admin-main { background: #f5f6f7; }
.admin-main .el-card { border-radius: 10px; border: none; box-shadow: 0 1px 4px rgba(29,33,41,.06); }
.admin-main .el-card__header { background: #fafbfc; }
.el-table th.el-table__cell { background: #f7f8fa; color: #1d2129; font-weight: 600; }
.el-table .cell .el-button.is-link { margin-left: 0; margin-right: 10px; }
.el-table .cell .el-button.is-link:last-child { margin-right: 0; }
</style>
