<template>
  <div>
    <div class="narrow-warn">
      <svg class="ic" aria-hidden="true"><use href="#i-alert" /></svg>
      <span>建议桌面访问 · 当前窗口 ≤1024px，管理端优先 1440×900 体验</span>
    </div>
    <div :class="['shell', { collapsed: ui.collapsed }]">
      <aside :class="['side', { 'mobile-open': ui.mobileOpen }]">
        <div class="side-brand">
          <div class="brand-mark">博</div>
          <div class="brand-txt"><b>博威无纸化</b><span>ADMIN CONSOLE</span></div>
        </div>
        <div v-for="g in menus" :key="g.title" class="nav-group">
          <div class="ng-label">{{ g.title }}</div>
          <router-link v-for="it in g.items" :key="it.path" :to="it.path"
            :class="['nav-item', { on: isActive(it), 'is-ro': isReadonly(it) }]" :title="it.title">
            <svg class="ic" aria-hidden="true"><use :href="'#i-' + (it.icon || 'list')" /></svg>
            <span class="nav-txt">{{ it.title }}</span>
            <span v-if="badge(it)" :class="['nav-badge', { q: badge(it).q }]">{{ badge(it).n }}</span>
            <span v-else-if="isReadonly(it)" class="nav-badge q" style="background:#243138;color:#8EA0A7">罗</span>
          </router-link>
        </div>
        <div class="side-foot">
          <svg class="ic ic-sm" aria-hidden="true"><use href="#i-info" /></svg>
          <span class="grow">博威无纸化 · 管理端</span>
          <router-link v-if="isDev" to="/dev/spec">规范</router-link>
        </div>
      </aside>
      <div class="main">
        <header class="top">
          <button class="icon-btn" aria-label="折叠侧边栏" @click="toggleSide">
            <svg class="ic" aria-hidden="true"><use href="#i-panel" /></svg>
          </button>
          <div class="top-title"><b>{{ curName }}</b><span>#{{ route.path }}</span></div>
          <div class="top-grow" />
          <div class="role-switch">
            <button aria-haspopup="true" @click.stop="ui.roleMenuOpen = !ui.roleMenuOpen">
              <span class="role-dot" /><span class="rs-txt">{{ roleLabel(perspective.activeRole) }}</span>
              <svg class="ic ic-sm" aria-hidden="true"><use href="#i-chevron-down" /></svg>
            </button>
            <div :class="['role-menu', { open: ui.roleMenuOpen }]">
              <div v-for="p in personas" :key="p.role" :class="['role-opt', { on: p.role === perspective.activeRole }]"
                @click="switchRole(p.role)">
                <span class="role-dot" :style="{ background: p.role === perspective.activeRole ? 'var(--amber)' : '#3E5158' }" />
                <div class="ro-main"><b>{{ roleLabel(p.role) }}</b><span>{{ p.name }} · {{ p.phone }} · {{ p.scope }}</span></div>
                <svg v-if="p.role === perspective.activeRole" class="ic ic-sm" aria-hidden="true"><use href="#i-check" /></svg>
              </div>
              <div class="rule-note mt8" style="margin:6px">
                <svg class="ic ic-sm" aria-hidden="true"><use href="#i-info" /></svg>
                <span>切换视角即重算导航 / 数据范围 / 脱敏演示。真实权限以服务端下发为准，登录页不选角色。</span>
              </div>
            </div>
          </div>
          <ScopePill :scope="perspective.scope" :label="scopeLabel(perspective.scope)" />
          <button :class="['sim', { on: ui.simError }]" @click="ui.toggleSim()"><span class="dot" />模拟异常</button>
          <div class="top-sep" />
          <div class="top-user">
            <div class="avatar">{{ (auth.userName || '管').slice(0, 1) }}</div>
            <div class="tu-txt"><b>{{ auth.userName }}</b><span>{{ roleLabel(auth.role) }}</span></div>
          </div>
          <button class="icon-btn" aria-label="退出登录" @click="auth.logout()">
            <svg class="ic" aria-hidden="true"><use href="#i-logout" /></svg>
          </button>
        </header>
        <nav class="crumbs">
          <span class="cb-link" @click="$router.push('/dashboard')">管理端</span>
          <svg class="ic" aria-hidden="true"><use href="#i-chevron-right" /></svg>
          <span>{{ route.meta.group || '概览' }}</span>
          <svg class="ic" aria-hidden="true"><use href="#i-chevron-right" /></svg>
          <b>{{ curName }}</b>
          <template v-if="route.params.id">
            <svg class="ic" aria-hidden="true"><use href="#i-chevron-right" /></svg>
            <span class="mono">{{ route.params.id }}</span>
          </template>
        </nav>
        <div class="view">
          <ErrorState v-if="ui.simError && !ui.retryOnce" sim :reason="'GET /admin' + route.path + ' (mock) · 503 Service Unavailable'"
            @retry="ui.retry()" @sim-off="ui.simOff()" />
          <LoadingSkeleton v-else-if="switching" :rows="8" />
          <router-view v-else />
        </div>
      </div>
    </div>
    <Toast />
  </div>
</template>
<script setup>
// 应用壳（原型 shellHtml 逐字对齐）：侧栏分组/只读徽标/待办角标/折叠 + 顶栏视角切换/范围胶囊/模拟异常/头像 + 面包屑
import { computed, ref, watch, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUiStore } from './stores/ui'
import { usePerspectiveStore } from './stores/perspective'
import { useAuthStore } from '../stores/auth'
import { visibleMenus } from './menu-config'
import { state, canonical } from './permission-v2'
import { roleLabel, scopeLabel } from './labels'
import { provider } from './providers/provider'
import { DEMO_PERSONAS } from './demo-personas'
import ScopePill from './ScopePill.vue'
import Toast from './Toast.vue'
import ErrorState from './states/ErrorState.vue'
import LoadingSkeleton from './states/LoadingSkeleton.vue'
const ui = useUiStore()
const perspective = usePerspectiveStore()
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const isDev = import.meta.env.DEV
const IMPLEMENTED = ['/dashboard', '/org', '/users', '/roles', '/grants', '/projects', '/contracts', '/elevators',
  '/handovers', '/plans', '/records', '/dispatch', '/order', '/stats', '/audit', '/settings',
  '/reports/failed', '/reports/logs', '/filings', '/regions', '/templates', '/register/workers',
  '/sync', '/msg-templates', '/reviews', '/board']
const menus = computed(() => visibleMenus(perspective.activeRole, IMPLEMENTED))
const personas = DEMO_PERSONAS.filter(p => perspective.roleSet.includes(p.role))
const curName = computed(() => route.meta.title || '概览工作台')
const kpis = computed(() => provider.getKPIs(perspective.activeRole))
function kpi(key) { return (kpis.value.find(k => k.key === key) || {}).value || 0 }
function badge(it) {
  if (it.path === '/handovers' && kpi('handoverReview')) return { n: kpi('handoverReview'), q: false }
  if (it.path === '/records' && kpi('recordReview')) return { n: kpi('recordReview'), q: true }
  if (it.path === '/dispatch' && kpi('slaOverdue')) return { n: kpi('slaOverdue'), q: false }
  return null
}
function isReadonly(it) { return it.perm ? state(perspective.activeRole, it.perm) === 'C' : false }
function isActive(it) {
  return route.path === it.path || (it.path === '/dispatch' && route.path.startsWith('/order'))
}
function switchRole(role) {
  perspective.setRole(role)
  ui.roleMenuOpen = false
  ui.push('success', '已切换视角 · ' + roleLabel(role), 'dataScope=' + perspective.scope)
  if (route.meta.perm && state(role, route.meta.perm) === 'DENY') router.push('/dashboard')
}
function toggleSide() {
  if (window.innerWidth <= 1024) ui.mobileOpen = !ui.mobileOpen
  else ui.toggleCollapsed()
}
// 视图切换骨架 220ms（原型 paintView）
const switching = ref(false)
let switchTimer = null
watch(() => route.path, () => {
  switching.value = true
  ui.mobileOpen = false
  clearTimeout(switchTimer)
  switchTimer = setTimeout(() => { switching.value = false }, 220)
})
// 真实登录角色 → 初始视角（演示切换不影响服务端权限）
watch(() => auth.role, (r) => {
  const c = canonical(r)
  if (c && perspective.roleSet.includes(c)) perspective.setRole(c)
}, { immediate: true })
function onDocClick(e) {
  if (ui.roleMenuOpen && !e.target.closest('.role-switch')) ui.roleMenuOpen = false
}
function onKeydown(e) { if (e.key === 'Escape') ui.roleMenuOpen = false }
onMounted(() => { document.addEventListener('click', onDocClick); document.addEventListener('keydown', onKeydown) })
onUnmounted(() => { document.removeEventListener('click', onDocClick); document.removeEventListener('keydown', onKeydown); clearTimeout(switchTimer) })
</script>