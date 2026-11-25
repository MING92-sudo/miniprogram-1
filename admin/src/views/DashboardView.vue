<template>
  <div>
    <ViewHead title="概览工作台" :sub="subline">
      <template #actions>
        <button v-if="isDev" class="btn btn-ghost" type="button" @click="$router.push('/dev/components')">
          <svg class="ic" aria-hidden="true"><use href="#i-palette" /></svg>组件廊
        </button>
      </template>
      <template #meta><PageMeta :extra="loadState" /></template>
    </ViewHead>

    <ErrorState v-if="error" :reason="error" @retry="load" />

    <template v-else>
      <div class="mt16">
        <div class="kpi-grid">
          <div v-for="it in kpis" :key="it.key" :class="['kpi', it.cls, { clickable: !!it.view }]"
            @click="it.view && $router.push(it.view)">
            <div class="kpi-k"><svg class="ic" aria-hidden="true"><use :href="'#i-' + it.icon" /></svg>{{ it.label }}</div>
            <div class="kpi-n">{{ loading ? '—' : it.value }}<small v-if="it.unit">{{ it.unit }}</small></div>
            <div class="kpi-x">{{ it.sub }}</div>
          </div>
        </div>
      </div>

      <div class="col gap8 mt16">
        <AlertStrip v-if="d.orders.overdue" tone="as-sig" icon="bolt" title="SLA 超时"
          :text="d.orders.overdue + ' 个工单已超出计划时限，需升级或改派'" to="/dispatch" />
        <AlertStrip v-if="d.reports.failed" tone="as-sig" icon="alert" title="上报异常"
          :text="d.reports.failed + ' 条记录上报监管平台失败，可逐条重报'" to="/reports/failed" />
        <AlertStrip v-if="d.reports.unconfirmed" icon="signature" title="待客户签字"
          :text="d.reports.unconfirmed + ' 条记录等待使用单位签署'" to="/records" />
        <AlertStrip v-if="d.platform.elevatorGeoMissing" icon="map-pin" title="位置待补"
          :text="d.platform.elevatorGeoMissing + ' 台电梯缺少经纬度，影响调度与围栏'" to="/elevators" />
        <AlertStrip v-if="d.platform.employeePendingSync" icon="user-check" title="人员待同步"
          :text="pendingSyncText" to="/register/workers" />
        <AlertStrip v-if="!hasAlert && isEmptyDb" tone="as-info" icon="info" title="尚未录入业务数据"
          text="系统已就绪。请依次在「组织架构 → 使用单位档案 → 电梯台账」建立基础数据，再为客户开通账号" />
        <AlertStrip v-else-if="!hasAlert" tone="as-ok" icon="check" title="运行正常"
          text="当前无超时工单、上报失败或待签记录" />
      </div>

      <div class="grid2 mt16">
        <div class="card">
          <div class="card-head">
            <h3><svg class="ic" aria-hidden="true"><use href="#i-inbox" /></svg>工单待办</h3>
            <span class="ch-tag tag tag-dim">{{ d.orders.dueToday }} 今日</span>
          </div>
          <div class="card-body">
            <div class="kv-grid">
              <div class="kv"><span class="k">今日应做</span><span class="v num">{{ d.orders.dueToday }}</span></div>
              <div class="kv"><span class="k">3 日内</span><span class="v num">{{ d.orders.dueSoon }}</span></div>
              <div class="kv"><span class="k">超时未做</span><span class="v num c-sig">{{ d.orders.overdue }}</span></div>
              <div class="kv"><span class="k">处置中</span><span class="v num">{{ d.orders.inProgress }}</span></div>
            </div>
          </div>
        </div>
        <div class="card">
          <div class="card-head">
            <h3><svg class="ic" aria-hidden="true"><use href="#i-cloud-upload" /></svg>上报与签署</h3>
            <span class="ch-tag tag" :class="d.reports.failed ? 'tag-signal' : 'tag-dim'">{{ d.reports.failed }} 失败</span>
          </div>
          <div class="card-body">
            <div class="kv-grid">
              <div class="kv"><span class="k">上报异常</span><span class="v num">{{ d.reports.failed }}</span></div>
              <div class="kv"><span class="k">已上报平台</span><span class="v num">{{ d.reports.reported }}</span></div>
              <div class="kv"><span class="k">待客户签</span><span class="v num">{{ d.reports.unconfirmed }}</span></div>
              <div class="kv"><span class="k">未闭环故障</span><span class="v num">{{ d.reports.openFaults }}</span></div>
            </div>
          </div>
        </div>
      </div>

      <div class="card mt16">
        <div class="card-head">
          <h3><svg class="ic" aria-hidden="true"><use href="#i-chart" /></svg>近 7 日完工趋势</h3>
          <span class="ch-tag tag tag-dim">累计 {{ trendTotal }} 单</span>
        </div>
        <div class="card-body">
          <div class="dist-row" v-for="p in d.trend" :key="p.date">
            <span class="dim f11" style="width:52px">{{ p.date }}</span>
            <div class="dist-bar"><i :style="{ width: trendPct(p.completed) + '%' }" /></div>
            <span class="num f11" style="width:28px;text-align:right">{{ p.completed }}</span>
          </div>
          <div v-if="!d.trend.length" class="dim f12">暂无完工数据。</div>
        </div>
      </div>

      <div class="card mt16">
        <div class="card-head">
          <h3><svg class="ic" aria-hidden="true"><use href="#i-database" /></svg>平台同步状态</h3>
        </div>
        <div class="card-body">
          <div class="kv-grid">
            <div class="kv"><span class="k">在管电梯</span><span class="v num">{{ d.orders.elevatorTotal }}</span></div>
            <div class="kv"><span class="k">员工总数</span><span class="v num">{{ d.platform.employeeTotal }}</span></div>
            <div class="kv"><span class="k">待同步人员</span><span class="v num">{{ d.platform.employeePendingSync }}</span></div>
            <div class="kv"><span class="k">位置待补</span><span class="v num">{{ d.platform.elevatorGeoMissing }}</span></div>
            <div class="kv"><span class="k">监管接口</span><span class="v">{{ d.platform.platformConfigured ? '已配置' : '未配置' }}</span></div>
            <div class="kv"><span class="k">最近同步</span><span class="v mono">{{ d.platform.lastSyncAt || '—' }}</span></div>
          </div>
        </div>
      </div>

      <RuleNote class="mt16">数据来源 <span class="mono">GET /admin/dashboard</span>，与电梯台账、工单调度、记录单同源；刷新页面即重新拉取。</RuleNote>
    </template>
  </div>
</template>
<script setup>
// 概览工作台：KPI / 预警 / 趋势 / 同步状态全部取自 GET /admin/dashboard（空库时全为 0）
import { ref, computed, onMounted } from 'vue'
import { dashboard } from '../api/admin'
import { usePerspectiveStore } from '../ui/stores/perspective'
import ViewHead from '../ui/ViewHead.vue'
import PageMeta from '../ui/PageMeta.vue'
import AlertStrip from '../ui/AlertStrip.vue'
import RuleNote from '../ui/RuleNote.vue'
import ErrorState from '../ui/states/ErrorState.vue'
const isDev = import.meta.env.DEV
const perspective = usePerspectiveStore()
const loading = ref(false)
const error = ref('')
const empty = () => ({
  orders: { dueToday: 0, dueSoon: 0, overdue: 0, inProgress: 0, elevatorTotal: 0 },
  reports: { failed: 0, reported: 0, submitted: 0, unconfirmed: 0, openFaults: 0 },
  platform: { employeeTotal: 0, employeePendingSync: 0, employeePendingSyncNames: [], elevatorGeoMissing: 0, lastSyncAt: '', platformConfigured: false },
  trend: [],
  warnCount: 0
})
const d = ref(empty())

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await dashboard()
    d.value = {
      orders: { ...empty().orders, ...(data.orders || {}) },
      reports: { ...empty().reports, ...(data.reports || {}) },
      platform: { ...empty().platform, ...(data.platform || {}) },
      trend: data.trend || [],
      warnCount: data.warnCount || 0
    }
  } catch (e) {
    error.value = (e && e.message) || '看板数据加载失败'
    d.value = empty()
  } finally {
    loading.value = false
  }
}
onMounted(load)

const subline = computed(() => `在管电梯 ${d.value.orders.elevatorTotal} 台 · 今日应做 ${d.value.orders.dueToday} 单 · ${perspective.scope}`)
const loadState = computed(() => (loading.value ? [{ k: '状态', v: '加载中', cls: 'count' }] : []))
const kpis = computed(() => {
  const o = d.value.orders
  const r = d.value.reports
  return [
    { key: 'elevators', label: '在管电梯', value: o.elevatorTotal, icon: 'elevator', unit: '台', sub: '台账总数', view: '/elevators' },
    { key: 'dueToday', label: '今日应做', value: o.dueToday, icon: 'calendar', unit: '单', sub: '按计划工时', view: '/plans' },
    { key: 'overdue', label: 'SLA 超时', value: o.overdue, icon: 'bolt', unit: '单', sub: '需升级 / 改派', cls: o.overdue ? 'k-sig' : 'k-ok', view: '/dispatch' },
    { key: 'inProgress', label: '处置中', value: o.inProgress, icon: 'wrench', unit: '单', sub: '工单执行中', view: '/dispatch' },
    { key: 'failed', label: '上报异常', value: r.failed, icon: 'alert', unit: '条', sub: '需逐条重报', cls: r.failed ? 'k-sig' : 'k-ok', view: '/reports/failed' },
    { key: 'unconfirmed', label: '待客户签', value: r.unconfirmed, icon: 'signature', unit: '条', sub: '等待单位签署', view: '/records' },
    { key: 'openFaults', label: '未闭环故障', value: r.openFaults, icon: 'alert', unit: '个', sub: '处理中', cls: r.openFaults ? '' : 'k-ok', view: '/dispatch' }
  ]
})
const hasAlert = computed(() => d.value.orders.overdue || d.value.reports.failed || d.value.reports.unconfirmed
  || d.value.platform.elevatorGeoMissing || d.value.platform.employeePendingSync)
const pendingSyncText = computed(() => {
  const names = (d.value.platform.employeePendingSyncNames || []).filter(Boolean)
  let text = d.value.platform.employeePendingSync + ' 名维保人员未回填监管平台人员ID，影响维保记录上报'
  if (!names.length) return text
  const shown = names.slice(0, 5).join('、')
  return text + '（' + shown + (names.length > 5 ? ' 等' : '') + '）'
})
/** 空库判定：无电梯、无记录、无工单 —— 只看业务数据，不计管理员自身账号（用于建库引导而非「运行正常」） */
const isEmptyDb = computed(() => !d.value.orders.elevatorTotal && !d.value.reports.reported
  && !d.value.reports.submitted && !d.value.orders.dueToday && !d.value.reports.failed)
const trendTotal = computed(() => d.value.trend.reduce((s, p) => s + (p.completed || 0), 0))
const trendMax = computed(() => Math.max(1, ...d.value.trend.map((p) => p.completed || 0)))
function trendPct(n) { return Math.round((n || 0) / trendMax.value * 100) }
</script>
