<template>
  <div class="board">
    <header class="board-head">
      <div class="board-title">
        <svg class="ic" aria-hidden="true"><use href="#i-monitor" /></svg>
        <div><b>调度大屏</b><span class="board-sub">博威无纸化 · 实时调度 · {{ scopeLabel }}（演示数据）</span></div>
      </div>
      <div class="board-clock">{{ clock }}</div>
      <div class="board-head-right">
        <ScopePill :scope="perspective.scope" :label="scopeLabel" />
        <button class="btn btn-ghost" type="button" @click="$router.push('/dashboard')">
          <svg class="ic" aria-hidden="true"><use href="#i-logout" /></svg>退出大屏
        </button>
      </div>
    </header>
    <div class="board-kpi">
      <div class="bkpi">
        <div class="bkpi-ico"><svg class="ic" aria-hidden="true"><use href="#i-inbox" /></svg></div>
        <div class="bkpi-n num">{{ pending.length }}</div><div class="bkpi-k">待接单</div>
      </div>
      <div class="bkpi">
        <div class="bkpi-ico"><svg class="ic" aria-hidden="true"><use href="#i-wrench" /></svg></div>
        <div class="bkpi-n num">{{ handling.length }}</div><div class="bkpi-k">处置中</div>
      </div>
      <div class="bkpi b-sig">
        <div class="bkpi-ico"><svg class="ic" aria-hidden="true"><use href="#i-bolt" /></svg></div>
        <div class="bkpi-n num">{{ overtime.length }}</div><div class="bkpi-k">SLA 超时</div>
      </div>
      <div class="bkpi b-ok">
        <div class="bkpi-ico"><svg class="ic" aria-hidden="true"><use href="#i-check" /></svg></div>
        <div class="bkpi-n num">{{ closed.length }}</div><div class="bkpi-k">今日闭环</div>
      </div>
    </div>
    <div class="board-main">
      <section class="board-panel board-pool">
        <div class="bp-head">
          <svg class="ic" aria-hidden="true"><use href="#i-inbox" /></svg><b>待接单池</b><span class="bp-tag">{{ pending.length }}</span>
        </div>
        <div class="bp-body">
          <div v-for="o in pending" :key="o.no" :class="['b-order', { over: o.overtime }]">
            <span :class="['tag', o.level === 'P1' ? 'tag-signal' : 'tag-ghost']">{{ o.level }}</span>
            <div class="grow">
              <div class="f12 w6">{{ o.no }} · {{ o.type }}</div>
              <div class="f11 dim">{{ projName(o.projectId) }} · {{ elevName(o.deviceCode) }}</div>
            </div>
            <span v-if="o.remaining <= 0" class="num c-sig">超时 {{ Math.abs(o.remaining) }}′</span>
            <span v-else :class="['num', o.remaining <= 30 ? 'c-amber' : '']">{{ o.remaining }}′</span>
          </div>
          <div v-if="!pending.length" class="dim f12">当前无待接单</div>
        </div>
      </section>
      <section class="board-panel board-map">
        <div class="bp-head"><svg class="ic" aria-hidden="true"><use href="#i-map-pin" /></svg><b>项目调度态势</b></div>
        <div class="board-grid">
          <div v-for="p in projects" :key="p.id" :class="['bg-cell', { over: projOver(p.id) }]">
            <div class="bgc-name">{{ p.name }}</div>
            <div class="bgc-count num">{{ p.elevatorCount }} 台</div>
            <div class="bgc-meta">
              <span class="dot amber" />待接单 {{ projCount(p.id, '待接单') }} ·
              <span class="dot ok" />处置中 {{ projHandling(p.id) }}
              <template v-if="projOver(p.id)"> · <span class="dot sig" />超时 {{ projOver(p.id) }}</template>
            </div>
          </div>
        </div>
      </section>
    </div>
    <section class="board-panel board-tech">
      <div class="bp-head">
        <svg class="ic" aria-hidden="true"><use href="#i-users" /></svg><b>班组在线</b><span class="bp-tag">{{ techs.length }}</span>
      </div>
      <div class="bp-body board-techs">
        <div v-for="a in techs" :key="a.id" class="bt-item">
          <div class="avatar">{{ a.name.slice(0, 1) }}</div>
          <div class="grow">
            <div class="f12 w6">{{ a.name }}</div>
            <div class="f11 dim">{{ a.empNo }} · {{ orgName(a.orgId) }}</div>
          </div>
          <span :class="['tag', a.status === '正常' ? 'tag-ok' : 'tag-dim']">{{ a.status === '正常' ? '在岗' : '离线' }}</span>
          <span class="num f11">{{ myOrders(a.id) }} 单</span>
        </div>
      </div>
    </section>
  </div>
</template>
<script setup>
// 二期：大屏调度（原型 Views.board 对齐，独立全屏不渲染侧栏/顶栏；演示数据＝原型基准集）
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { M } from '../ui/prototype-mock'
import { usePerspectiveStore } from '../ui/stores/perspective'
import ScopePill from '../ui/ScopePill.vue'
const perspective = usePerspectiveStore()
const scopeLabel = computed(() => (M.switchByRole(perspective.activeRole) || {}).scopeLabel || '全量')
const orders = computed(() => {
  const pids = M.scopeProjects(perspective.activeRole)
  return M.ORDERS.filter(o => pids.includes(o.projectId))
})
const pending = computed(() => orders.value.filter(o => o.status === '待接单'))
const handling = computed(() => orders.value.filter(o => o.status === '已接单' || o.status === '处置中'))
const overtime = computed(() => orders.value.filter(o => o.overtime && o.status !== '已闭环' && o.status !== '已取消'))
const closed = computed(() => orders.value.filter(o => o.status === '已闭环'))
const projects = computed(() => M.PROJECTS.filter(p => M.scopeProjects(perspective.activeRole).includes(p.id)))
const techs = computed(() => M.ACCOUNTS.filter(a => a.roles.includes('MAINT_TECH')))
function projName(pid) { const p = M.projectById(pid); return p ? p.name : pid }
function orgName(oid) { const o = M.orgById(oid); return o ? o.name : oid }
function elevName(code) { const e = M.elevatorByCode(code); return e ? e.name : code }
function projCount(pid, status) { return orders.value.filter(o => o.projectId === pid && o.status === status).length }
function projHandling(pid) { return orders.value.filter(o => o.projectId === pid && (o.status === '已接单' || o.status === '处置中')).length }
function projOver(pid) { return orders.value.filter(o => o.projectId === pid && o.overtime && o.status !== '已闭环' && o.status !== '已取消').length }
function myOrders(aid) { return orders.value.filter(o => o.owner === aid && o.status !== '已闭环' && o.status !== '已取消').length }
// 时钟（原型 liveNow：基准时刻 + 运行秒数）
const clock = ref('')
let timer = null
let bootMs = null
function tick() {
  const bt = new Date(M.meta.NOW)
  const now = new Date()
  if (bootMs == null) bootMs = now.getTime()
  bt.setSeconds(bt.getSeconds() + Math.floor((now.getTime() - bootMs) / 1000))
  const p = n => (n < 10 ? '0' : '') + n
  clock.value = M.meta.TODAY + ' ' + p(bt.getHours()) + ':' + p(bt.getMinutes()) + ':' + p(bt.getSeconds())
}
onMounted(() => { tick(); timer = setInterval(tick, 1000) })
onUnmounted(() => clearInterval(timer))
</script>