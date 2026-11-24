<template>
  <div class="board">
    <header class="board-head">
      <div class="board-title">
        <svg class="ic" aria-hidden="true"><use href="#i-monitor" /></svg>
        <div><b>调度大屏</b><span class="board-sub">博威无纸化 · 实时调度 · {{ scopeText }}</span></div>
      </div>
      <div class="board-clock">{{ clock }}</div>
      <div class="board-head-right">
        <ScopePill :scope="perspective.scope" :label="scopeLabel(perspective.scope)" />
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
        <div class="bkpi-n num">{{ closedToday.length }}</div><div class="bkpi-k">今日闭环</div>
      </div>
    </div>
    <div class="board-main">
      <section class="board-panel board-pool">
        <div class="bp-head">
          <svg class="ic" aria-hidden="true"><use href="#i-inbox" /></svg><b>待接单池</b><span class="bp-tag">{{ pending.length }}</span>
        </div>
        <div class="bp-body">
          <div v-for="o in pending" :key="o.id" :class="['b-order', { over: isOverdue(o) }]">
            <span :class="['tag', 'tag-ghost']">{{ o.workTypeCode || '—' }}</span>
            <div class="grow">
              <div class="f12 w6">{{ o.orderNo }} · {{ o.workType || '维保' }}</div>
              <div class="f11 dim">{{ elevLabel(o.elevatorId) }} · 计划 {{ shortTime(o.planTime) }}</div>
            </div>
            <span v-if="isOverdue(o)" class="num c-sig">超时</span>
            <span v-else class="num c-amber">{{ shortTime(o.planTime) }}</span>
          </div>
          <div v-if="!pending.length" class="dim f12">当前无待接单</div>
        </div>
      </section>
      <section class="board-panel board-map">
        <div class="bp-head"><svg class="ic" aria-hidden="true"><use href="#i-map-pin" /></svg><b>项目调度态势</b><span class="bp-tag">{{ projects.length }}</span></div>
        <div class="board-grid">
          <div v-for="p in projects" :key="p.id" :class="['bg-cell', { over: projOver(p.id) }]">
            <div class="bgc-name">{{ p.name }}</div>
            <div class="bgc-count num">{{ p.count }} 台</div>
            <div class="bgc-meta">
              <span class="dot amber" />待接单 {{ projCount(p.id) }} ·
              <span class="dot ok" />处置中 {{ projHandling(p.id) }}
              <template v-if="projOver(p.id)"> · <span class="dot sig" />超时 {{ projOver(p.id) }}</template>
            </div>
          </div>
          <div v-if="!projects.length" class="dim f12">暂无项目</div>
        </div>
      </section>
    </div>
    <section class="board-panel board-tech">
      <div class="bp-head">
        <svg class="ic" aria-hidden="true"><use href="#i-users" /></svg><b>维保人员</b><span class="bp-tag">{{ workers.length }}</span>
      </div>
      <div class="bp-body board-techs">
        <div v-for="a in workers" :key="a.id" class="bt-item">
          <div class="avatar">{{ (a.name || '?').slice(0, 1) }}</div>
          <div class="grow">
            <div class="f12 w6">{{ a.name || '—' }}</div>
            <div class="f11 dim">{{ a.empNo || a.id }}</div>
          </div>
          <span class="num f11">{{ myOrders(a.name) }} 单</span>
        </div>
        <div v-if="!workers.length" class="dim f12">暂无维保人员</div>
      </div>
    </section>
    <ErrorState v-if="error" :reason="error" @retry="load" />
  </div>
</template>
<script setup>
// 调度大屏：工单/项目/人员全部取自真实接口（GET /work-orders、/projects、/elevators、/employees）
import { ref, computed, onMounted, onUnmounted } from 'vue'
import http from '../api/client'
import { usePerspectiveStore } from '../ui/stores/perspective'
import { scopeLabel } from '../ui/labels'
import { todayGmt8 } from '../ui/datetime'
import ScopePill from '../ui/ScopePill.vue'
import ErrorState from '../ui/states/ErrorState.vue'
const perspective = usePerspectiveStore()
const scopeText = computed(() => scopeLabel(perspective.scope) + ' · ' + perspective.scope)
const orders = ref([])
const projects = ref([])
const elevators = ref([])
const employees = ref([])
const error = ref('')

const asList = (d) => (Array.isArray(d) ? d : (d && d.list) || [])
async function safe(url, pick) {
  try { return pick(await http.get(url)) } catch { return [] }
}

async function load() {
  error.value = ''
  const [orderRes, projRes, elevRes, empRes] = await Promise.all([
    safe('/work-orders', (d) => (Array.isArray(d) ? d : (d && d.list) || [])),
    safe('/projects', asList),
    safe('/elevators', asList),
    safe('/employees', asList)
  ])
  orders.value = orderRes
  projects.value = projRes.map((p) => ({
    id: p.id,
    name: p.name || p.projectNo || p.id,
    count: elevRes.filter((e) => e.projectId === p.id || e.projectName === p.name).length
  }))
  elevators.value = elevRes
  employees.value = empRes
}
onMounted(load)

// 今日闭环按 GMT+8 本地日统计；checkoutTime 已是 GMT+8 文本
const todayStr = () => new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit'
}).format(new Date())
const pending = computed(() => orders.value.filter((o) => o.status === 'PENDING'))
const handling = computed(() => orders.value.filter((o) => o.status === 'PROCESSING'))
const active = computed(() => orders.value.filter((o) => o.status !== 'DONE' && o.status !== 'CANCELLED'))
const overtime = computed(() => active.value.filter(isOverdue))
const closedToday = computed(() => orders.value.filter((o) => o.status === 'DONE'
  && String(o.checkoutTime || '').slice(0, 10) === todayStr()))

// 超期判定与后端 dashboard / 小程序同为天级：计划日早于 GMT+8 今天才算超期
function isOverdue(o) {
  if (!o.planTime) return false
  if (o.status === 'DONE' || o.status === 'CANCELLED') return false
  return String(o.planTime).slice(0, 10) < todayGmt8()
}
function shortTime(t) { return t ? String(t).replace('T', ' ').slice(5, 16) : '—' }
function elevLabel(id) {
  const e = elevators.value.find((x) => x.id === id)
  return e ? (e.elevatorCode || e.elevatorName || id) : (id || '—')
}
function projOf(o) {
  const e = elevators.value.find((x) => x.id === o.elevatorId)
  return e ? (e.projectId || e.projectName) : null
}
function projCount(pid) { return orders.value.filter((o) => o.status === 'PENDING' && projOf(o) === pid).length }
function projHandling(pid) { return orders.value.filter((o) => o.status === 'PROCESSING' && projOf(o) === pid).length }
function projOver(pid) { return overtime.value.filter((o) => projOf(o) === pid).length }
function myOrders(name) { return active.value.filter((o) => o.workerName === name).length }

const workers = computed(() => {
  const all = employees.value
  const tech = all.filter((e) => String(e.role || '').includes('MAINT_TECH')
    || (Array.isArray(e.roles) && e.roles.includes('MAINT_TECH')))
  return (tech.length ? tech : []).slice(0, 12)
})

// 时钟：本地实时
const clock = ref('')
let timer = null
function tick() {
  const d = new Date()
  const p = (n) => (n < 10 ? '0' : '') + n
  clock.value = d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' '
    + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds())
}
onMounted(() => { tick(); timer = setInterval(tick, 1000) })
onUnmounted(() => clearInterval(timer))
</script>
