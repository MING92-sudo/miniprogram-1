<template>
  <div class="ui-scope shell" :class="{ collapsed: ui.collapsed }">
    <div v-if="narrow" class="narrow-bar">建议桌面访问（≥1024px）以获得完整体验</div>
    <aside class="sidebar">
      <div class="brand"><span class="brand-mark">博威无纸化</span><span class="brand-sub label">电梯维保管理端</span></div>
      <nav class="nav">
        <div v-for="g in menus" :key="g.title" class="nav-group">
          <p class="nav-group-title label">{{ g.title }}</p>
          <router-link v-for="it in g.items" :key="it.path" :to="it.path" class="nav-item" active-class="active">
            <span class="nav-icon" v-html="icon(it.icon || 'default', 16)" /><span class="nav-text">{{ it.title }}</span>
          </router-link>
        </div>
      </nav>
    </aside>
    <div class="main">
      <header class="topbar">
        <button class="collapse-btn" aria-label="折叠侧栏" @click="ui.toggleCollapsed">☰</button>
        <nav class="crumb"><span>{{ crumb.group }}</span><span class="crumb-sep">/</span><span>{{ crumb.view }}</span></nav>
        <span class="scope-indicator label">范围 {{ scopeLabel(perspective.scope) }}</span>
        <span class="user-name">{{ auth.userName }}</span>
        <button class="logout" @click="auth.logout()">退出</button>
      </header>
      <main class="content"><router-view /></main>
    </div>
    <Toast />
  </div>
</template>
<script setup>
// M2 应用壳：侧栏 8 组折叠 + 顶栏范围/用户/退出 + 面包屑 + <1024 提示（spec §4.1）
import { computed, ref, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { useUiStore } from './stores/ui'
import { usePerspectiveStore } from './stores/perspective'
import { useAuthStore } from '../stores/auth'
import { visibleMenus } from './menu-config'
import { scopeLabel } from './labels'
import { icon } from './icons'
import Toast from './Toast.vue'
const ui = useUiStore()
const perspective = usePerspectiveStore()
const auth = useAuthStore()
const route = useRoute()
const IMPLEMENTED = ['/dashboard', '/org', '/users', '/roles', '/grants', '/projects', '/contracts', '/elevators',
  '/handovers', '/plans', '/records', '/dispatch', '/order', '/stats', '/audit', '/settings',
  '/reports/failed', '/reports/logs', '/filings', '/regions', '/templates', '/register/workers']
const menus = computed(() => visibleMenus(perspective.activeRole, IMPLEMENTED))
const crumb = computed(() => ({ group: route.meta.group || '概览', view: route.meta.title || '' }))
const narrow = ref(false)
function onResize() { narrow.value = window.innerWidth < 1024 }
onMounted(() => { onResize(); window.addEventListener('resize', onResize) })
onUnmounted(() => window.removeEventListener('resize', onResize))
</script>
<style scoped>
.shell { display: flex; height: 100vh; }
.narrow-bar { position: fixed; top: 0; left: 0; right: 0; z-index: 50; background: var(--amber); color: var(--accent-on-amber); text-align: center; font-size: 12px; padding: 4px; }
.sidebar { width: 232px; background: var(--ink-2); border-right: 1px solid var(--line); overflow-y: auto; transition: width .15s; flex-shrink: 0; }
.shell.collapsed .sidebar { width: 64px; }
.shell.collapsed .nav-text, .shell.collapsed .nav-group-title, .shell.collapsed .brand-sub { display: none; }
.brand { padding: 18px 16px; display: flex; flex-direction: column; gap: 2px; }
.brand-mark { font-size: 17px; font-weight: 700; color: var(--amber); }
.nav { padding: 8px; }
.nav-group { margin-bottom: 14px; }
.nav-group-title { padding: 4px 8px; }
.nav-item { display: flex; align-items: center; gap: 10px; padding: 8px 10px; border-radius: 6px; color: var(--muted); text-decoration: none; font-size: 13px; }
.nav-item:hover { background: var(--panel); color: var(--text); }
.nav-item.active { background: var(--panel-2); color: var(--amber); }
.nav-icon { display: inline-flex; }
.main { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.topbar { display: flex; align-items: center; gap: 14px; height: 56px; padding: 0 16px; background: var(--panel); border-bottom: 1px solid var(--line); }
.collapse-btn { background: none; border: 1px solid var(--line-2); color: var(--text); border-radius: 6px; cursor: pointer; padding: 4px 8px; }
.crumb { font-size: 12px; color: var(--muted); }
.crumb-sep { margin: 0 6px; color: var(--dim); }
.scope-indicator { margin-left: auto; color: var(--amber); }
.user-name { font-size: 13px; }
.logout { background: none; border: 1px solid var(--signal); color: var(--signal); border-radius: 6px; padding: 4px 10px; cursor: pointer; font-size: 12px; }
.content { flex: 1; overflow: auto; padding: 16px; }
</style>