<template>
  <div>
    <el-card shadow="never">
      <div class="head">
        <el-radio-group v-model="days" size="small" @change="load">
          <el-radio-button :value="7">近 7 天</el-radio-button>
          <el-radio-button :value="30">近 30 天</el-radio-button>
          <el-radio-button :value="90">近 90 天</el-radio-button>
        </el-radio-group>
        <el-button type="primary" size="small" @click="exportExcel">导出 Excel</el-button>
      </div>
      <el-row :gutter="12" class="mt12">
        <el-col :span="6"><el-card shadow="never" class="stat"><div class="num">{{ st.total }}</div><div class="lab">工单总数</div></el-card></el-col>
        <el-col :span="6"><el-card shadow="never" class="stat"><div class="num">{{ st.done }}</div><div class="lab">已完成</div></el-card></el-col>
        <el-col :span="6"><el-card shadow="never" class="stat"><div class="num">{{ st.completionRate }}%</div><div class="lab">完成率</div></el-card></el-col>
        <el-col :span="6"><el-card shadow="never" class="stat"><div class="num">{{ s.recordCount }}</div><div class="lab">维保记录（范围内）</div></el-card></el-col>
      </el-row>
    </el-card>

    <el-row :gutter="12" class="mt12">
      <el-col :span="12">
        <el-card shadow="never">
          <template #header>隐患分布（S0—S7，按记录 problemCodes 聚合）</template>
          <div ref="hazardEl" class="chart" />
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never">
          <template #header>人员 / 电梯维保量 Top10</template>
          <el-table :data="s.byWorker || []" size="small" stripe>
            <el-table-column type="index" label="#" width="50" />
            <el-table-column prop="name" label="人员" />
            <el-table-column prop="count" label="记录数" width="100" />
          </el-table>
          <el-table :data="s.byElevator || []" size="small" stripe class="mt12">
            <el-table-column type="index" label="#" width="50" />
            <el-table-column prop="name" label="电梯编号" />
            <el-table-column prop="count" label="记录数" width="100" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import * as echarts from 'echarts'
import ExcelJS from 'exceljs'
import { stats as statsApi } from '../api/admin'
import { PROBLEM_CODES } from '../constants'
import { showErr } from '../utils/ui'

const days = ref(30)
const s = ref({})
const hazardEl = ref(null)
let chart = null

const st = computed(() => (s.value && s.value.orders) || {})

async function load() {
  try {
    s.value = await statsApi(days.value)
    await nextTick()
    renderChart()
  } catch (e) {
    showErr(e)
  }
}

function renderChart() {
  if (!hazardEl.value || !s.value) return
  const hazard = s.value.hazardDist || {}
  const codes = Object.keys(hazard)
  if (!chart) {
    chart = echarts.init(hazardEl.value)
  }
  chart.setOption({
    grid: { left: 40, right: 16, top: 24, bottom: 28 },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: codes },
    yAxis: { type: 'value', minInterval: 1 },
    series: [{
      type: 'bar',
      data: codes.map((c) => ({
        value: hazard[c],
        itemStyle: { color: c === 'S0' ? '#00b578' : '#f53f3f' }
      }))
    }]
  })
}

function onResize() {
  if (chart) chart.resize()
}

async function exportExcel() {
  const wb = new ExcelJS.Workbook()
  const ws1 = wb.addWorksheet('隐患分布')
  ws1.addRow(['隐患码', '说明', '数量'])
  const hazard = (s.value && s.value.hazardDist) || {}
  Object.keys(hazard).forEach((code) => {
    ws1.addRow([code, PROBLEM_CODES[code] || '', hazard[code]])
  })
  const ws2 = wb.addWorksheet('人员维保量')
  ws2.addRow(['人员', '记录数'])
  ;(s.value.byWorker || []).forEach((r) => ws2.addRow([r.name, r.count]))
  const ws3 = wb.addWorksheet('电梯维保量')
  ws3.addRow(['电梯编号', '记录数'])
  ;(s.value.byElevator || []).forEach((r) => ws3.addRow([r.name, r.count]))
  const buf = await wb.xlsx.writeBuffer()
  const blob = new Blob([buf], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `维保统计-近${days.value}天.xlsx`
  a.click()
  URL.revokeObjectURL(a.href)
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
.head { display: flex; justify-content: space-between; align-items: center; }
.stat { text-align: center; }
.stat .num { font-size: 26px; font-weight: 700; }
.stat .lab { color: #86909c; font-size: 12px; margin-top: 4px; }
.mt12 { margin-top: 12px; }
.chart { height: 320px; }
</style>
