<template>
  <div>
    <el-row :gutter="12">
      <el-col :span="6" v-for="card in orderCards" :key="card.label">
        <el-card shadow="never" class="stat-card" :class="{ warn: card.warn }">
          <div class="stat-num">{{ card.value }}</div>
          <div class="stat-label">{{ card.label }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="12" class="mt12">
      <el-col :span="8" v-for="card in platformCards" :key="card.label">
        <el-card shadow="never" class="stat-card">
          <div class="stat-num sm">{{ card.value }}</div>
          <div class="stat-label">{{ card.label }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="12" class="mt12">
      <el-col :span="16">
        <el-card shadow="never">
          <template #header>近 7 日完成工单</template>
          <div ref="trendEl" class="chart" />
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never">
          <template #header>上报状态</template>
          <div class="kv" v-for="row in reportRows" :key="row.label">
            <span>{{ row.label }}</span>
            <b :class="{ danger: row.danger }">{{ row.value }}</b>
          </div>
          <el-button type="primary" plain size="small" class="mt12" @click="$router.push('/reports/failed')">
            处理上报异常
          </el-button>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
// echarts 按需引入（docs/09 V3.6 分包）：看板只用折线图 + 网格/提示，
// 全量 'echarts' 会打进 1 MB 以上的 vendor 块；按需后仅含 LineChart/Grid/Tooltip/Canvas 渲染器。
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import * as adminApi from '../api/admin'
import { showErr } from '../utils/ui'

echarts.use([LineChart, GridComponent, TooltipComponent, CanvasRenderer])

const dash = ref(null)
const trendEl = ref(null)
let chart = null

const orderCards = computed(() => {
  const o = (dash.value && dash.value.orders) || {}
  return [
    { label: '今日到期', value: o.dueToday ?? '-' },
    { label: '3 日内到期', value: o.dueSoon ?? '-' },
    { label: '超期未完成', value: o.overdue ?? '-', warn: (o.overdue || 0) > 0 },
    { label: '进行中', value: o.inProgress ?? '-' }
  ]
})

const platformCards = computed(() => {
  const p = (dash.value && dash.value.platform) || {}
  return [
    { label: '待同步人员', value: p.employeePendingSync ?? '-' },
    { label: '位置待补电梯', value: p.elevatorGeoMissing ?? '-' },
    { label: '最近平台同步', value: p.lastSyncAt || '从未同步' }
  ]
})

const reportRows = computed(() => {
  const r = (dash.value && dash.value.reports) || {}
  return [
    { label: '上报失败（需人工重报）', value: r.failed ?? 0, danger: (r.failed || 0) > 0 },
    { label: '待上报', value: r.submitted ?? 0 },
    { label: '已上报', value: r.reported ?? 0 },
    { label: '待使用单位确认', value: r.unconfirmed ?? 0 },
    { label: '未闭环故障', value: r.openFaults ?? 0 }
  ]
})

async function load() {
  try {
    dash.value = await adminApi.dashboard()
    renderChart()
  } catch (e) {
    showErr(e)
  }
}

function renderChart() {
  if (!trendEl.value || !dash.value) return
  const trend = dash.value.trend || []
  if (!chart) {
    chart = echarts.init(trendEl.value)
  }
  chart.setOption({
    grid: { left: 40, right: 16, top: 24, bottom: 28 },
    xAxis: { type: 'category', data: trend.map((t) => t.date) },
    yAxis: { type: 'value', minInterval: 1 },
    series: [{ type: 'line', smooth: true, data: trend.map((t) => t.completed), areaStyle: {} }],
    tooltip: { trigger: 'axis' }
  })
}

function onResize() {
  if (chart) chart.resize()
}

onMounted(() => {
  load()
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  if (chart) chart.dispose()
})
</script>

<style scoped>
.stat-card { text-align: center; }
.stat-num { font-size: 30px; font-weight: 700; color: #1d2129; }
.stat-num.sm { font-size: 20px; }
.stat-label { color: #86909c; font-size: 13px; margin-top: 4px; }
.warn .stat-num { color: #f53f3f; }
.mt12 { margin-top: 12px; }
.chart { height: 300px; }
.kv { display: flex; justify-content: space-between; padding: 6px 0; border-bottom: 1px dashed #f0f1f2; }
.kv b.danger { color: #f53f3f; }
</style>
