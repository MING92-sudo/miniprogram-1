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
        <el-sub-menu index="platform">
          <template #title><el-icon><Refresh /></el-icon><span>平台对接</span></template>
          <el-menu-item index="/platform/sync"><span>平台同步</span></el-menu-item>
          <el-menu-item index="/reports/failed"><span>上报异常清单</span></el-menu-item>
          <el-menu-item index="/reports/logs"><span>上报日志</span></el-menu-item>
          <el-menu-item index="/archive/company"><span>维保单位</span></el-menu-item>
          <el-menu-item index="/archive/use-units"><span>使用单位</span></el-menu-item>
          <el-menu-item index="/archive/employees"><span>人员</span></el-menu-item>
          <el-menu-item index="/archive/elevators"><span>电梯档案</span></el-menu-item>
        </el-sub-menu>
        <el-sub-menu index="system">
          <template #title><el-icon><Tickets /></el-icon><span>系统功能</span></template>
          <el-menu-item index="/orders"><span>工单监控</span></el-menu-item>
          <el-menu-item index="/schedule"><span>计划调度</span></el-menu-item>
          <el-menu-item index="/templates"><span>检查项模板</span></el-menu-item>
          <el-menu-item index="/ledger/inspects"><span>自行检查</span></el-menu-item>
          <el-menu-item index="/ledger/drills"><span>应急演练</span></el-menu-item>
          <el-menu-item index="/ledger/rescues"><span>困人救援</span></el-menu-item>
          <el-menu-item index="/ledger/faults"><span>故障记录</span></el-menu-item>
          <el-menu-item index="/approvals"><span>定位异常申述审核</span></el-menu-item>
          <el-menu-item index="/stats"><span>统计报表</span></el-menu-item>
          <el-menu-item index="/alerts"><span>预警规则</span></el-menu-item>
          <el-menu-item index="/notify"><span>发送记录</span></el-menu-item>
        </el-sub-menu>
        <el-sub-menu v-if="auth.isSysAdmin" index="sysadmin">
          <template #title><el-icon><Notebook /></el-icon><span>系统管理</span></template>
          <el-menu-item index="/users"><span>用户权限</span></el-menu-item>
          <el-menu-item index="/op-logs"><span>审计日志</span></el-menu-item>
        </el-sub-menu>      </el-menu>
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
  Odometer, Tickets, Refresh, Notebook
} from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'
import { ROLE_TEXT } from '../constants'

const route = useRoute()
/** 当前路由所在模块默认展开，其余折叠 */
const openMenus = computed(() => {
  const p = route.path
  if (p.startsWith('/platform') || p.startsWith('/reports') || p.startsWith('/archive')) return ['platform']
  if (p.startsWith('/users') || p.startsWith('/op-logs')) return ['sysadmin']
  return ['system']
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
