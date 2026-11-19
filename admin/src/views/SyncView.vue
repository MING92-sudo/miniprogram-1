<template>
  <div>
    <ViewHead title="监管平台同步" sub="记录单归档后上报「重庆市智慧特种设备安全管理系统」· 接口规范 V1.5">
      <template #meta><PageMeta perm="report:view" :extra="[{ k: '记录单', v: total, icon: 'cloud-upload', cls: 'count' }]" /></template>
    </ViewHead>
    <div class="mt12">
      <div class="kpi-grid">
        <div class="kpi" v-for="s in summary" :key="s.key">
          <div class="kpi-k"><svg class="ic" aria-hidden="true"><use :href="'#i-' + s.icon" /></svg>{{ s.label }}</div>
          <div class="kpi-n">{{ s.value }}</div>
          <div class="kpi-x">{{ s.sub }}</div>
        </div>
      </div>
    </div>
    <div class="mt16">
      <DataTable :columns="cols" :rows="rows" :loading="loading" :search-keys="['recordNo', 'elevatorCode', 'workerName']"
        search-placeholder="搜索记录单号 / 电梯编码 / 维保人" empty-hint="暂无记录单。记录单归档后自动上报，此处显示上报状态。"
        :filters="[{ key: 'uploadStatus', label: '上报状态', options: statusOptions }]" clickable @row-click="openDetail">
        <template #cell-recordNo="{ value }"><span class="mono">{{ value }}</span></template>
        <template #cell-elev="{ row }">
          <span class="mono">{{ row.elevatorCode || '—' }}</span>
          <div class="cell-2">{{ row.elevatorName || '—' }}</div>
        </template>
        <template #cell-syncAt="{ value }"><span class="mono f12">{{ value || '—' }}</span></template>
        <template #cell-uploadStatus="{ value }"><span :class="['tag', 'tag-' + statusTone(value)]">{{ value }}</span></template>
        <template #cell-retry="{ value }"><span class="num">{{ value }}</span></template>
        <template #row-actions="{ row }">
          <button class="btn btn-ghost btn-xs" type="button" @click.stop="openDetail(row)">
            <svg class="ic" aria-hidden="true"><use href="#i-eye" /></svg><span>详情</span>
          </button>
        </template>
      </DataTable>
    </div>
    <div class="card mt16">
      <div class="card-head">
        <h3><svg class="ic" aria-hidden="true"><use href="#i-exchange" /></svg>字段映射（本地 → 接口 2.6 maintenanceRecord）</h3>
        <span class="ch-tag tag tag-amber">接口规范 V1.5</span>
      </div>
      <div class="card-body">
        <div class="kv-grid">
          <div v-for="r in fieldMap" :key="r[0]" class="kv">
            <span class="k mono">{{ r[0] }}</span>
            <span class="v">{{ r[1] }}<span class="dim f11"> · {{ r[2] }}</span></span>
          </div>
        </div>
      </div>
    </div>
    <RuleNote class="mt16" icon="lock">上报由服务端按接口规范 2.6 完成，管理端不代发；重报仅逐条人工（禁批量自动重试）。脱敏在服务端，前端不接收明文。</RuleNote>
    <Drawer :open="!!detail" :title="detail ? '上报详情 ' + (detail.originalRecordId || detail.id) : ''"
      :sub="detail ? (detail.elevatorCode || '') + ' · ' + (detail.workType || '') : ''"
      :meta="{ perm: 'report:view' }" note="只读" @close="detail = null">
      <template v-if="detail">
        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-cloud-upload" /></svg>上报概览</div>
          <div class="kv-grid">
            <div class="kv"><span class="k">记录单号</span><span class="v mono">{{ detail.originalRecordId || detail.id }}</span></div>
            <div class="kv"><span class="k">电梯编码</span><span class="v mono">{{ detail.elevatorCode || '—' }}</span></div>
            <div class="kv"><span class="k">设备</span><span class="v">{{ detail.elevatorName || '—' }}</span></div>
            <div class="kv"><span class="k">维保类别</span><span class="v">{{ detail.workType || '—' }}</span></div>
            <div class="kv"><span class="k">主检</span><span class="v">{{ detail.workerName || '—' }}</span></div>
            <div class="kv"><span class="k">配合人</span><span class="v">{{ detail.assistantName || '—' }}</span></div>
            <div class="kv"><span class="k">上报状态</span><span class="v">{{ detail.uploadStatus }}</span></div>
            <div class="kv"><span class="k">后端状态码</span><span class="v mono">{{ detail.reportStatus || '—' }}</span></div>
            <div class="kv"><span class="k">重试次数</span><span class="v">{{ detail.retryCount }}</span></div>
            <div class="kv"><span class="k">客户签署</span><span class="v">{{ detail.confirmStatus === 'CONFIRMED' ? '已签署' : '待签署' }}</span></div>
            <div class="kv"><span class="k">作业完成</span><span class="v mono">{{ detail.checkoutTime || '—' }}</span></div>
            <div class="kv"><span class="k">记录创建</span><span class="v mono">{{ detail.createdAt || '—' }}</span></div>
          </div>
        </div>
      </template>
      <template #footer><button class="btn btn-ghost" type="button" @click="detail = null">关闭</button></template>
    </Drawer>
  </div>
</template>
<script setup>
// 监管平台同步：数据取自 GET /admin/records（含 reportStatus / uploadStatus / retryCount），不本地伪造上报结果
import { ref, computed, onMounted } from 'vue'
import { records } from '../api/admin'
import DataTable from '../ui/DataTable.vue'
import Drawer from '../ui/Drawer.vue'
import ViewHead from '../ui/ViewHead.vue'
import PageMeta from '../ui/PageMeta.vue'
import RuleNote from '../ui/RuleNote.vue'
const loading = ref(false)
const rows = ref([])
const total = ref(0)
const detail = ref(null)
const cols = [
  { key: 'recordNo', label: '记录单号', sortable: true },
  { key: 'elev', label: '电梯编码 / 设备' },
  { key: 'workType', label: '维保类别' },
  { key: 'workerName', label: '主检' },
  { key: 'syncAt', label: '完成时间', sortable: true },
  { key: 'uploadStatus', label: '上报状态', sortable: true },
  { key: 'retry', label: '重试', cls: 'num', sortable: true }
]
const fieldMap = [
  ['elevatorCode', '电梯编码', '电梯台账「监管编码」'],
  ['deviceCode', '设备代码', '电梯注册代码 regCode'],
  ['unitPrincipal / Phone', '使用单位负责人', '使用单位档案'],
  ['elevatorAdminister / Phone', '使用单位安全管理员', '使用单位档案'],
  ['emergencyPhone', '紧急联系电话', '使用单位档案'],
  ['insideNumber', '单位内编号', '电梯梯号'],
  ['workMenegerName / Phone', '维保经理', 'MAINT_MGR 账号'],
  ['workMan1Id / workMan2Id', '维保人员 1 / 2', '主检 / 配合人'],
  ['startTime / endTime', '维保起止时间', '记录单作业起止'],
  ['workType', '维保类别代码', '半月保→HM · 季度保→TM'],
  ['recorder / recorderPhone', '记录填写人', '记录单主检'],
  ['originalRecordId', '原始记录 ID', '本地记录单号'],
  ['problemCode', '严重事故隐患码', '未发现→S0'],
  ['nextMaintenanceDate', '下次维保日期', '电梯 nextMaintDue']
]

function toRow(r) {
  return {
    id: r.id,
    recordNo: r.originalRecordId || r.id,
    originalRecordId: r.originalRecordId || '',
    elevatorCode: r.elevatorCode || '',
    elevatorName: r.elevatorName || '',
    workType: r.workType || '',
    workerName: r.workerName || '',
    assistantName: r.assistantName || '',
    checkoutTime: r.checkoutTime || '',
    syncAt: r.checkoutTime || r.createdAt || '',
    uploadStatus: r.uploadStatus || r.reportStatus || '待上报',
    reportStatus: r.reportStatus || '',
    retryCount: r.retryCount || 0,
    retry: r.retryCount || 0,
    confirmStatus: r.confirmStatus || '',
    createdAt: r.createdAt || ''
  }
}

async function load() {
  loading.value = true
  try {
    const data = await records({ page: 1, size: 200 })
    const list = (data && data.list) || []
    rows.value = list.map(toRow)
    total.value = (data && data.total) || rows.value.length
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}
onMounted(load)

const statusOptions = ['待上报', '上报中', '成功', '失败']
const summary = computed(() => {
  const count = (s) => rows.value.filter((r) => r.uploadStatus === s).length
  return [
    { key: 'ok', label: '上报成功', value: count('成功'), sub: '已同步监管平台', icon: 'check' },
    { key: 'fail', label: '上报失败', value: count('失败'), sub: '需排查后重试', icon: 'alert' },
    { key: 'pending', label: '待上报 / 上报中', value: count('待上报') + count('上报中'), sub: '等待归档或重试', icon: 'clock' },
    { key: 'total', label: '记录总数', value: total.value, sub: '当前页统计口径', icon: 'file-text' }
  ]
})
function statusTone(s) { return { 待上报: 'dim', 上报中: 'amber', 成功: 'ok', 失败: 'signal' }[s] || 'ghost' }
function openDetail(r) { detail.value = r }
</script>
