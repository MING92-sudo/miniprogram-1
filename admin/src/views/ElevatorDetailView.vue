<template>
  <div>
    <ViewHead title="电梯档案" :sub="(row.projectName || '—') + ' · ' + (row.elevatorCode || '')">
      <template #meta><PageMeta perm="elevator:view" :extra="[{ k: '内部编号', v: row.insideNumber || '—', icon: 'elevator' }]" /></template>
      <template #actions>
        <PermButton v-if="canGeoEdit" code="elevator:edit" label="修改定位" variant="primary" icon="map-pin" @click="geoOpen = true" />
        <PermButton code="elevator:view" label="返回台账" variant="ghost" icon="chevron-left" @click="$router.push('/elevators')" />
      </template>
    </ViewHead>
    <div class="tab-bar mt16">
      <button type="button" :class="['tab-i', { on: tab === 'info' }]" @click="go('info')">基本信息</button>
      <button type="button" :class="['tab-i', { on: tab === 'params' }]" @click="go('params')">电梯参数</button>
      <button type="button" :class="['tab-i', { on: tab === 'plans' }]" @click="go('plans')">维保计划</button>
      <button type="button" :class="['tab-i', { on: tab === 'orders' }]" @click="go('orders')">维保工单</button>
      <button type="button" :class="['tab-i', { on: tab === 'faults' }]" @click="go('faults')">维修工单</button>
    </div>

    <!-- 基本信息 -->
    <div v-if="tab === 'info'" class="card card-pad0 mt16">
      <div class="card-head"><h3><svg class="ic" aria-hidden="true"><use href="#i-elevator" /></svg>基本信息</h3></div>
      <div class="card-body">
        <div class="kv-grid">
          <div class="kv"><span class="k">注册代码</span><span class="v mono">{{ row.registrationCode || '—' }}</span></div>
          <div class="kv"><span class="k">设备种类</span><span class="v">{{ row.category || '—' }}</span></div>
          <div class="kv"><span class="k">电梯编码</span><span class="v mono">{{ row.elevatorCode || '—' }}</span></div>
          <div class="kv"><span class="k">登记证编码</span><span class="v mono">{{ row.certNo || '—' }}</span></div>
          <div class="kv"><span class="k">使用单位</span><span class="v">{{ row.useUnitName || '—' }}</span></div>
          <div class="kv"><span class="k">所在区域</span><span class="v">{{ row.regionName || '重庆市（全市）' }}</span></div>
          <div class="kv"><span class="k">单位内编号</span><span class="v mono">{{ row.insideNumber || '—' }}</span></div>
          <div class="kv"><span class="k">下次年检日期</span><span class="v mono">{{ row.nextCheckDate || '—' }}</span></div>
          <div class="kv"><span class="k">应急救援电话</span><span class="v mono">{{ row.emergencyPhone || '—' }}</span></div>
          <div class="kv"><span class="k">详细地址</span><span class="v">{{ row.location || '—' }}</span></div>
          <div class="kv"><span class="k">经度（GCJ-02）</span><span class="v mono">{{ row.lng || '—' }}</span></div>
          <div class="kv"><span class="k">纬度（GCJ-02）</span><span class="v mono">{{ row.lat || '—' }}</span></div>
          <div class="kv"><span class="k">定位状态</span><span class="v">{{ row.geoStatus === 'OK' ? '已定位' : '待补 / 待重新采集' }}</span></div>
        </div>
      </div>
    </div>

    <!-- 注意 profile.local 无 factoryNumber（仅在 profile.platform），读 local 会恒空 -->
    <div v-if="tab === 'params'" class="card card-pad0 mt16">
      <div class="card-head"><h3><svg class="ic" aria-hidden="true"><use href="#i-sliders" /></svg>电梯参数</h3></div>
      <div class="card-body">
        <div class="kv-grid">
          <div class="kv"><span class="k">出厂编号</span><span class="v mono">{{ row.factoryNumber || '—' }}</span></div>
          <div class="kv"><span class="k">制造单位</span><span class="v">{{ row.manufacturer || '—' }}</span></div>
          <div class="kv"><span class="k">额定速度</span><span class="v">{{ ratedSpeedText }}</span></div>
          <div class="kv"><span class="k">载重</span><span class="v">{{ ratedLoadText }}</span></div>
          <div class="kv"><span class="k">层站数</span><span class="v mono">{{ row.stationsDoors || '—' }}</span></div>
        </div>
      </div>
    </div>

    <!-- 数据 /maintenance-calendar 前向投影（只读，不触发派单），按 elevatorId 前端过滤； -->
    <div v-if="tab === 'plans'" class="mt16">
      <div class="cal-legend card-body">
        <span v-for="t in TYPE_META" :key="t.code" class="cal-lg-i">
          <i class="cal-dot" :style="{ background: t.color }"></i>{{ t.label }}
        </span>
        <span class="cal-lg-sep"></span>
        <span class="f12 dim">✓ 已完成 · 派 已派单 · 超期标红框</span>
      </div>
      <div v-if="plansLoading" class="dim f12 mt16">日历加载中…</div>
      <div v-else-if="!entryCount" class="dim f12 mt16">该电梯未来 12 个月暂无维保计划（建档即排期，若为空请检查排期基线）。</div>
      <div v-else class="cal-year">
        <div v-for="m in months" :key="m.key" class="cal-month">
          <div class="cal-mh">{{ m.label }}</div>
          <div class="cal-grid cal-week"><span v-for="w in WEEK" :key="w">{{ w }}</span></div>
          <div class="cal-grid">
            <span v-for="n in m.offset" :key="'b' + n" class="cal-cell cal-blank"></span>
            <span v-for="d in m.days" :key="d.date" class="cal-cell"
              :class="{ 'cal-has': d.entry, 'cal-overdue': d.entry && d.entry.status === 'OVERDUE' }"
              :title="d.entry ? d.date + ' ' + d.entry.workType + '（' + d.entry.statusText + '）' : d.date">
              <em>{{ d.day }}</em>
              <b v-if="d.entry" class="cal-badge" :style="{ background: typeColor(d.entry.workTypeCode) }">
                {{ d.entry.status === 'DONE' ? '✓' : d.entry.status === 'DISPATCHED' ? '派' : typeShort(d.entry.workTypeCode) }}
              </b>
            </span>
          </div>
        </div>
      </div>
    </div>

    <div v-if="tab === 'orders'" class="mt16">
      <AlertStrip v-if="recordsTruncated" tone="as-sig" icon="alert" title="记录过多"
        :text="'仅显示最近 ' + records.length + ' 条维保记录，完整历史请前往维保记录页查询。'" />
      <DataTable :columns="recordCols" :rows="records" :loading="ordersLoading"
        :search-keys="['workerName', 'assistantName', 'workType']" search-placeholder="搜索维保人员 / 类别"
        empty-hint="该电梯暂无维保记录。">
        <template #toolbar>
          <PermButton v-if="records.length" code="record:export" label="批量下载PDF" variant="ghost" icon="download" @click="batchRecords" />
        </template>
        <template #cell-checkoutTime="{ value }"><span class="mono f12">{{ value || '—' }}</span></template>
        <template #cell-confirmStatus="{ value }"><StatusTag :status="value" :label="confirmStatusLabel(value)" /></template>
        <template #cell-reportStatus="{ value }"><StatusTag :status="value" :label="reportStatusLabel(value)" /></template>
        <template #cell-duration="{ value }"><span class="mono f12">{{ value || '—' }}</span></template>
        <template #row-actions="{ row }">
          <PermButton code="record:export" label="预览" variant="ghost" size="xs" icon="eye" @click="previewRecord(row)" />
          <PermButton code="record:export" label="下载PDF" variant="ghost" size="xs" icon="download" @click="downloadRecord(row)" />
        </template>
      </DataTable>
    </div>

    <!-- 维修工单：该梯全部急修/维修单（与维保记录同逻辑：预览/下载/批量下载 PDF） -->
    <div v-if="tab === 'faults'" class="mt16">
      <DataTable :columns="faultCols" :rows="faults" :loading="faultsLoading"
        :search-keys="['faultNo', 'faultType', 'desc']" search-placeholder="搜索单号 / 类型 / 描述"
        empty-hint="该电梯暂无维修工单。">
        <template #toolbar>
          <PermButton v-if="faults.length" code="record:export" label="批量下载PDF" variant="ghost" icon="download" @click="batchFaults" />
        </template>
        <template #cell-faultNo="{ value }"><span class="mono">{{ value || '—' }}</span></template>
        <template #cell-createdAt="{ value }"><span class="mono f12">{{ value || '—' }}</span></template>
        <template #cell-status="{ value }"><StatusTag :status="value" :label="faultStatusLabel(value)" /></template>
        <template #cell-desc="{ value }"><span class="f12">{{ value || '—' }}</span></template>
        <template #row-actions="{ row }">
          <PermButton code="record:export" label="预览" variant="ghost" size="xs" icon="eye" @click="previewFault(row)" />
          <PermButton code="record:export" label="下载PDF" variant="ghost" size="xs" icon="download" @click="downloadFault(row)" />
        </template>
      </DataTable>
    </div>
    <ElevatorGeoModal :open="geoOpen" :row="row" @close="geoOpen = false" @saved="geoSaved" />
  </div>
</template>
<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import http from '../api/client'
import { listAllRecords, exportPdf, exportFaultPdf } from '../api/report'
import DataTable from '../ui/DataTable.vue'
import AlertStrip from '../ui/AlertStrip.vue'
import ViewHead from '../ui/ViewHead.vue'
import PageMeta from '../ui/PageMeta.vue'
import PermButton from '../ui/PermButton.vue'
import StatusTag from '../ui/StatusTag.vue'
import ElevatorGeoModal from '../ui/ElevatorGeoModal.vue'
import { faultStatusLabel, reportStatusLabel } from '../ui/labels'
const CONFIRM_LABEL = { PENDING: '待确认', CONFIRMED: '已确认' }
function confirmStatusLabel(v) { return CONFIRM_LABEL[String(v || '').toUpperCase()] || String(v || '') }
import { useToast } from '../ui/stores/ui'
import { useAuthStore } from '../stores/auth'
import { canEditElevator } from '../ui/permission-v2'
const auth = useAuthStore()
const canGeoEdit = computed(() => canEditElevator(auth.role))
const toast = useToast()
const route = useRoute()
const id = route.params.id
const row = ref({})
const geoOpen = ref(false)
function geoSaved(updated) {
  row.value = { ...row.value, ...updated }
}
// 载重/额定速度：台账行投影为数值 + 独立单位列，展示时合并（缺省单位 kg / m/s）
const ratedLoadText = computed(() => row.value.ratedLoad ? row.value.ratedLoad + ' ' + (row.value.ratedLoadUnit || 'kg') : '—')
const ratedSpeedText = computed(() => row.value.ratedSpeed ? row.value.ratedSpeed + ' ' + (row.value.ratedSpeedUnit || 'm/s') : '—')
const tab = ref(['info', 'params', 'plans', 'orders', 'faults'].includes(route.query.tab) ? route.query.tab : 'info')

// ── 维保计划：全年日历 ──
const WEEK = ['一', '二', '三', '四', '五', '六', '日']
const TYPE_META = [
  { code: 'HM', label: '半月', short: '半月', color: '#2f6fed' },
  { code: 'TM', label: '季度', short: '季度', color: '#18a058' },
  { code: 'SM', label: '半年', short: '半年', color: '#e8930c' },
  { code: 'OY', label: '年度', short: '年度', color: '#d03050' }
]
function typeColor(c) { return (TYPE_META.find(t => t.code === c) || {}).color || '#888' }
function typeShort(c) { return (TYPE_META.find(t => t.code === c) || {}).short || c }
function ymd(d) { return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0') }

const planEntries = ref([]) // 本梯 12 个月投影条目（已按 elevatorId 过滤）
const entryByDate = computed(() => {
  const m = {}
  planEntries.value.forEach(e => { m[e.date] = e })
  return m
})
const entryCount = computed(() => planEntries.value.length)
// 本月起 12 个月的月历网格（周一开头）
const months = computed(() => {
  const now = new Date()
  const out = []
  for (let i = 0; i < 12; i++) {
    const first = new Date(now.getFullYear(), now.getMonth() + i, 1)
    const y = first.getFullYear(), mo = first.getMonth()
    const daysInMonth = new Date(y, mo + 1, 0).getDate()
    const days = []
    for (let d = 1; d <= daysInMonth; d++) {
      const date = ymd(new Date(y, mo, d))
      days.push({ date, day: d, entry: entryByDate.value[date] || null })
    }
    out.push({
      key: y + '-' + mo,
      label: y + ' 年 ' + (mo + 1) + ' 月',
      offset: (first.getDay() + 6) % 7, // 周日=0 → 周一开头偏移
      days
    })
  }
  return out
})

const recordCols = [
  { key: 'checkoutTime', label: '作业时间', sortable: true },
  { key: 'workType', label: '维保类别' },
  { key: 'workerName', label: '主维保' },
  { key: 'assistantName', label: '配合人员' },
  { key: 'duration', label: '作业时长' },
  { key: 'confirmStatus', label: '客户签字' },
  { key: 'reportStatus', label: '上报状态' }
]
const faultCols = [
  { key: 'faultNo', label: '单号', sortable: true },
  { key: 'createdAt', label: '报修时间', sortable: true },
  { key: 'faultType', label: '故障类型' },
  { key: 'status', label: '状态' },
  { key: 'desc', label: '故障描述' }
]

const records = ref([]), faults = ref([])
const recordsTruncated = ref(false)
const plansLoading = ref(false), ordersLoading = ref(false), faultsLoading = ref(false)
const loaded = { plans: false, orders: false, faults: false }

function listOf(d) { return Array.isArray(d) ? d : (d.entries || d.list || []) }

async function loadBase() {
  try {
    const e = await http.get('/admin/elevators')
    const list = listOf(e)
    row.value = list.find(r => r.id === id) || {}
    if (!row.value.id) toast.warn('未找到该电梯档案')
  } catch (e) { toast.error(e.message || '加载失败') }
}
async function loadPlans() {
  plansLoading.value = true
  try {
    const now = new Date()
    const from = ymd(new Date(now.getFullYear(), now.getMonth(), 1))
    const to = ymd(new Date(now.getFullYear(), now.getMonth() + 12, 0))
    const d = await http.get('/maintenance-calendar?from=' + from + '&to=' + to)
    planEntries.value = listOf(d).filter(e => e.elevatorId === id)
  } catch (e) { toast.error(e.message || '计划加载失败') } finally { plansLoading.value = false }
}
async function loadOrders() {
  ordersLoading.value = true
  try {
    // 该梯全部维保记录：按后端 total 逐页拉满（listAllRecords 带页数硬上限，截断时给出提示）
    const params = { size: 200 }
    if (row.value.elevatorCode) params.elevatorCode = row.value.elevatorCode
    const data = await listAllRecords(params)
    records.value = data.list || []
    recordsTruncated.value = Boolean(data.truncated)
  } catch (e) { toast.error(e.message || '维保记录加载失败') } finally { ordersLoading.value = false }
}

// ── PDF 预览 / 下载 / 批量下载（维保记录与急修单同逻辑）──
function saveBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}
function openBlob(blob) {
  // 预览 = 新页打开 PDF 原件（含照片/签名/版面，与下载件一致）；延迟回收避免新页未加载完
  const url = URL.createObjectURL(blob)
  window.open(url, '_blank')
  setTimeout(() => URL.revokeObjectURL(url), 60000)
}
function recordName(r) { return '维保记录-' + (r.elevatorCode || '') + '-' + String(r.checkoutTime || r.id).slice(0, 10) + '.pdf' }
function faultName(f) { return '急修单-' + (f.faultNo || f.id) + '.pdf' }
async function downloadRecord(r) {
  try { saveBlob(await exportPdf(r.id), recordName(r)) } catch (e) { toast.error(e.message || '导出失败') }
}
async function previewRecord(r) {
  try { openBlob(await exportPdf(r.id)) } catch (e) { toast.error(e.message || '预览失败') }
}
async function downloadFault(f) {
  try { saveBlob(await exportFaultPdf(f.id), faultName(f)) } catch (e) { toast.error(e.message || '导出失败') }
}
async function previewFault(f) {
  try { openBlob(await exportFaultPdf(f.id)) } catch (e) { toast.error(e.message || '预览失败') }
}
// 批量：逐条串行 + 间隔，避免浏览器拦截多文件同时下载
async function batchOf(list, fetcher, nameOf, tag) {
  let ok = 0
  for (const it of list) {
    try {
      saveBlob(await fetcher(it.id), nameOf(it))
      ok++
      await new Promise(r => setTimeout(r, 350))
    } catch (e) { toast.error((nameOf(it)) + ' 导出失败：' + (e.message || '')) }
  }
  toast.success(tag + '批量下载完成：' + ok + '/' + list.length)
}
function batchRecords() { batchOf(records.value, exportPdf, recordName, '维保记录 ') }
function batchFaults() { batchOf(faults.value, exportFaultPdf, faultName, '急修单 ') }
async function loadFaults() {
  faultsLoading.value = true
  try {
    const list = listOf(await http.get('/faults?size=200'))
    faults.value = list.filter(f => f.elevatorCode && f.elevatorCode === row.value.elevatorCode)
  } catch (e) { toast.error(e.message || '维修工单加载失败') } finally { faultsLoading.value = false }
}
function go(t) {
  tab.value = t
  if (t === 'plans' && !loaded.plans) { loaded.plans = true; loadPlans() }
  if (t === 'orders' && !loaded.orders) { loaded.orders = true; loadOrders() }
  if (t === 'faults' && !loaded.faults) { loaded.faults = true; loadFaults() }
}
onMounted(async () => { await loadBase(); if (tab.value !== 'info' && tab.value !== 'params') go(tab.value) })
</script>
<style scoped>
.cal-legend { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; padding: 10px 14px; background: var(--bg-card, #fff); border: 1px solid var(--line-2, #e5e7eb); border-radius: var(--r-card, 8px); }
.cal-lg-i { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; }
.cal-dot { width: 10px; height: 10px; border-radius: 3px; display: inline-block; }
.cal-lg-sep { flex: 1; }
.cal-year { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-top: 12px; }
@media (max-width: 1280px) { .cal-year { grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 960px) { .cal-year { grid-template-columns: repeat(2, 1fr); } }
.cal-month { background: var(--bg-card, #fff); border: 1px solid var(--line-2, #e5e7eb); border-radius: var(--r-card, 8px); padding: 10px; }
.cal-mh { font-weight: 600; font-size: 13px; margin-bottom: 8px; }
.cal-grid { display: grid; grid-template-columns: repeat(7, 1fr); gap: 2px; }
.cal-week span { text-align: center; font-size: 11px; color: var(--text-dim, #98a2b3); padding: 2px 0; }
.cal-cell { display: flex; flex-direction: column; align-items: center; justify-content: center; aspect-ratio: 1; font-size: 11px; border-radius: 4px; border: 1px solid transparent; }
.cal-cell em { font-style: normal; line-height: 1; }
.cal-has { background: #f2f6ff; border-color: #dbe6ff; }
.cal-overdue { border-color: #d03050; }
.cal-badge { font-size: 10px; font-weight: 600; color: #fff; border-radius: 3px; padding: 0 3px; line-height: 1.5; margin-top: 1px; }
</style>
