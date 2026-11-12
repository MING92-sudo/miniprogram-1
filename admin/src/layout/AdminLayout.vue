<template>
  <el-container class="admin-shell">
    <el-aside width="220px" class="admin-aside">
      <div class="brand">
        <div class="brand-title">电梯维保管理端</div>
        <div class="brand-sub">智慧特种设备维保系统</div>
      </div>
      <el-menu :default-active="active" :default-openeds="openMenus" router background-color="#001529"
               text-color="#a6adb4" active-text-color="#ffffff" class="admin-menu">
        <template v-for="item in menus" :key="item.index">
          <el-menu-item v-if="!item.children" :index="item.index">
            <el-icon><component :is="item.icon" /></el-icon><span>{{ item.title }}</span>
          </el-menu-item>
          <el-sub-menu v-else :index="item.index">
            <template #title><el-icon><component :is="item.icon" /></el-icon><span>{{ item.title }}</span></template>
            <el-menu-item v-for="child in item.children" :key="child.index" :index="child.index">
              <span>{{ child.title }}</span>
            </el-menu-item>
          </el-sub-menu>
        </template>
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
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
Odometer, Tickets, Refresh, Notebook, List
} from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'
import * as authApi from '../api/auth'
import { filterMenus, attachIcons } from '../utils/menu'
import { ROLE_TEXT } from '../constants'

const route = useRoute()

/**
 * 菜单清单与所需能力码（docs/14 §6 管理端权限矩阵；与 router meta.perm 同源同码）。
 * 菜单按角色过滤只是体验优化，后端 PermissionMatrix 才是权威（docs/14 §12.2）。
 * 移交单/外接建档/维保合同/区域主数据 等菜单待对应视图落地后按矩阵追加：
 * handover:read、filing:read、contract:read、region:write。
 */
const MENUS = [
  { index: '/dashboard', title: '监控看板', icon: Odometer, perm: 'console:read' },
  { index: '/schedule', title: '计划调度', icon: Tickets, perm: 'plan:write' },
  { index: '/ledger', title: '电梯台账', icon: Notebook, perm: 'ledger:read' },
  { index: '/templates', title: '模板管理', icon: List, perm: 'template:write' },
  {
    index: 'monitor',
    title: '工单监控',
    icon: Refresh,
    children: [
      { index: '/orders', title: '工单列表', perm: 'order:read' },
      { index: '/faults', title: '急修单', perm: 'fault:read' },
      { index: '/reports/failed', title: '上报异常清单', perm: 'order:read' }
    ]
  },
  {
    index: 'archive',
    title: '档案管理',
    icon: Tickets,
    children: [
      { index: '/archive/company', title: '维保单位', perm: 'employee:read' },
      { index: '/archive/use-units', title: '使用单位', perm: 'useunit:read' },
      { index: '/archive/employees', title: '人员', perm: 'employee:read' },
      { index: '/archive/elevators', title: '电梯档案', perm: 'ledger:read' }
    ]
  },
  {
    index: 'register',
    title: '平台登记',
    icon: Notebook,
    children: [
      { index: '/register/workers', title: '人员管理', perm: 'employee:read' }
    ]
  },
  {
    index: 'logs',
    title: '上报日志',
    icon: Notebook,
    children: [
      { index: '/reports/logs', title: '上报日志', perm: 'record:read' }
    ]
  }
]

const auth = useAuthStore()

/** 图标表：接口只下发 index/title/perm，图标组件留在前端按 index 取（未知 index 用 Notebook 兜底） */
const MENU_ICONS = {
  '/dashboard': Odometer,
  '/schedule': Tickets,
  '/ledger': Notebook,
  '/templates': List,
  monitor: Refresh,
  archive: Tickets,
  register: Notebook,
  logs: Notebook
}

/** 服务端下发结果（docs/14 §9.3）；为空表示未拿到/不可登录，退回本地表 */
const remoteMenus = ref([])

onMounted(async () => {
  try {
    const res = await authApi.getMenus()
    remoteMenus.value = (res && res.menus) || []
  } catch (e) {
    // 接口不可用（旧后端/离线）不阻塞管理端：退回本地表 + 能力过滤
    remoteMenus.value = []
  }
})

/** 菜单来源：服务端下发优先，失败退回本地 MENUS；两条路径都过能力过滤 */
const menus = computed(() => {
  const source = remoteMenus.value.length ? remoteMenus.value : MENUS
  return attachIcons(filterMenus(source, auth.can), MENU_ICONS, Notebook)
})

/** 当前路由所在模块默认展开，其余折叠 */
const openMenus = computed(() => {
  const p = route.path
  if (p.startsWith('/orders') || p.startsWith('/platform') || p.startsWith('/reports/failed')) return ['monitor']
  if (p.startsWith('/archive')) return ['archive']
  if (p.startsWith('/register')) return ['register']
  if (p.startsWith('/reports/logs')) return ['logs']
  return ['monitor']
})

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
