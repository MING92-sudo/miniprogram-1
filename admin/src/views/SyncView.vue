<template>
  <div>
    <ViewHead title="监管平台同步" sub="记录单归档后自动上报「重庆市智慧特种设备安全管理系统」· 接口规范 V1.5">
      <template #meta><PageMeta perm="report:view" :extra="[{ k: '同步记录', v: rows.length, icon: 'cloud-upload', cls: 'count' }]" /></template>
    </ViewHead>
    <div class="notice-bar mt16">
      <svg class="ic" aria-hidden="true"><use href="#i-info" /></svg>
      <span><b>演示数据（原型基准集）。</b>上报由服务端按接口规范 2.6 完成，本页为状态明细与运维台；真实接口后续接入。</span>
    </div>
    <div class="mt12">
      <DataTable :columns="cols" :rows="rows" :loading="false" :search-keys="['recordNo']"
        search-placeholder="搜索记录单号" empty-hint="当前范围内暂无同步记录。"
        :filters="[{ key: 'status', label: '状态', options: M.SYNC_STATES }]" clickable @row-click="openDetail">
        <template #cell-recordNo="{ value }"><span class="mono">{{ value }}</span></template>
        <template #cell-elev="{ row }">
          <span class="mono">{{ meta(row).elevatorCode || '—' }}</span>
          <div class="cell-2">{{ meta(row).elev ? meta(row).elev.code + ' · ' + meta(row).elev.name : '—' }}</div>
        </template>
        <template #cell-project="{ row }">{{ meta(row).projectName }}</template>
        <template #cell-api="{ value }"><span class="mono f12">{{ value }}</span></template>
        <template #cell-syncAt="{ value }"><span class="mono f12">{{ value || '—' }}</span></template>
        <template #cell-status="{ value }"><span :class="['tag', 'tag-' + syncTone(value)]">{{ value }}</span></template>
        <template #cell-code="{ value }">
          <span v-if="value == null" class="dim">—</span>
          <span v-else :class="['mono', value === 200 ? 'c-ok' : 'c-sig']">{{ value }}</span>
        </template>
        <template #cell-retry="{ value }"><span class="num">{{ value }}</span></template>
        <template #cell-message="{ row }">
          <span v-if="row.message" :class="['f12', row.code === 200 ? '' : 'c-sig']">{{ row.message }}</span>
          <span v-else class="dim">—</span>
        </template>
        <template #row-actions="{ row }">
          <PermButton v-if="row.status === '失败' || row.status === '已重试'" code="report:retry" label="重报" variant="ghost" size="xs" icon="refresh"
            c-tip="仅逐条人工重报（AGENTS §2.4 禁批量）" @click="retry(row)" />
          <button class="btn btn-ghost btn-xs" type="button" @click="openDetail(row)">
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
    <div class="grid2 mt16">
      <div class="card">
        <div class="card-head"><h3><svg class="ic" aria-hidden="true"><use href="#i-calendar" /></svg>维保类别码表（3.1）</h3></div>
        <div class="card-body"><div class="chips"><span v-for="w in M.WORK_TYPE_MAP" :key="w.code" class="chip"><b class="mono">{{ w.code }}</b> {{ w.name }}</span></div></div>
      </div>
      <div class="card">
        <div class="card-head"><h3><svg class="ic" aria-hidden="true"><use href="#i-alert" /></svg>严重事故隐患码表（3.2）</h3></div>
        <div class="card-body">
          <div v-for="p in M.PROBLEM_CODE_MAP" :key="p.code" class="kv"><span class="k mono">{{ p.code }}</span><span class="v">{{ p.name }}</span></div>
        </div>
      </div>
    </div>
    <RuleNote class="mt16" icon="lock">上报由服务端按接口规范 2.6 完成；重报仅逐条人工（禁批量自动重试），写审计。脱敏在服务端，前端不接收明文。</RuleNote>
    <Drawer :open="!!detail" :title="detail ? '同步详情 ' + detail.id : ''" :sub="detail ? detail.recordNo + ' · ' + detail.api : ''"
      :meta="{ perm: 'report:view' }" note="report:retry 仅逐条" @close="detail = null">
      <template v-if="detail">
        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-cloud-upload" /></svg>上报概览</div>
          <div class="kv-grid">
            <div class="kv"><span class="k">同步记录</span><span class="v mono">{{ detail.id }}</span></div>
            <div class="kv"><span class="k">记录单号</span><span class="v mono">{{ detail.recordNo }}</span></div>
            <div class="kv"><span class="k">电梯编码</span><span class="v mono">{{ meta(detail).elevatorCode || '—' }}</span></div>
            <div class="kv"><span class="k">设备</span><span class="v">{{ meta(detail).elev ? meta(detail).elev.code + ' · ' + meta(detail).elev.name : '—' }}</span></div>
            <div class="kv"><span class="k">项目</span><span class="v">{{ meta(detail).projectName }}</span></div>
            <div class="kv"><span class="k">接口</span><span class="v mono">{{ detail.api }}</span></div>
            <div class="kv"><span class="k">状态</span><span class="v">{{ detail.status }}</span></div>
            <div class="kv"><span class="k">返回 code</span><span class="v mono">{{ detail.code ?? '—' }}</span></div>
            <div class="kv"><span class="k">重试次数</span><span class="v">{{ detail.retry }}</span></div>
            <div class="kv"><span class="k">上报时间</span><span class="v mono">{{ detail.syncAt || '—' }}</span></div>
          </div>
        </div>
        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-file-text" /></svg>请求体（示例 · 接口 2.6）</div>
          <pre class="json">{{ buildRequest(detail) }}</pre>
        </div>
        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-history" /></svg>响应体</div>
          <pre class="json">{{ buildResponse(detail) }}</pre>
        </div>
      </template>
      <template #footer>
        <PermButton v-if="detail && (detail.status === '失败' || detail.status === '已重试')" code="report:retry" label="立即重报" variant="primary" icon="refresh" @click="retry(detail)" />
        <button class="btn btn-ghost" type="button" @click="detail = null">关闭</button>
      </template>
    </Drawer>
  </div>
</template>
<script setup>
// 二期：监管平台同步明细（原型 Views.sync 对齐；演示数据＝原型基准集，真实接口后续接入）。
// 合规偏离：原型「批量重报」不实现——AGENTS §2.4 禁止管理端批量重试入口，仅逐条人工重报。
import { ref, computed, reactive } from 'vue'
import { M } from '../ui/prototype-mock'
import { usePerspectiveStore } from '../ui/stores/perspective'
import { useToast } from '../ui/stores/ui'
import DataTable from '../ui/DataTable.vue'
import Drawer from '../ui/Drawer.vue'
import ViewHead from '../ui/ViewHead.vue'
import PageMeta from '../ui/PageMeta.vue'
import RuleNote from '../ui/RuleNote.vue'
import PermButton from '../ui/PermButton.vue'
const perspective = usePerspectiveStore()
const toast = useToast()
const detail = ref(null)
// 演示数据本地响应式副本（重报演示改写状态；真实接口接入后换 API 数据源）
const all = reactive(M.SYNC_RECORDS.map(s => ({ ...s })))
const rows = computed(() => {
  const pids = M.scopeProjects(perspective.activeRole)
  return all.filter(s => { const m = meta(s); return m.pid && pids.includes(m.pid) })
})
const cols = [
  { key: 'recordNo', label: '记录单号', sortable: true },
  { key: 'elev', label: '电梯编码 / 设备' },
  { key: 'project', label: '项目' },
  { key: 'api', label: '接口' },
  { key: 'syncAt', label: '上报时间', sortable: true },
  { key: 'status', label: '状态', sortable: true },
  { key: 'code', label: '返回', cls: 'num' },
  { key: 'retry', label: '重试', cls: 'num', sortable: true },
  { key: 'message', label: '返回信息' }
]
const fieldMap = [
  ['elevatorCode', '电梯编码', '电梯台账「监管编码」'],
  ['deviceCode', '设备代码', '电梯注册代码 regCode'],
  ['unitPrincipal / Phone', '使用单位负责人', '组织负责人 leader / phone'],
  ['elevatorAdminister / Phone', '使用单位安全管理员', '组织 administer / administerPhone'],
  ['emergencyPhone', '紧急联系电话', '组织 emergencyPhone'],
  ['insideNumber', '单位内编号', '电梯梯号 name'],
  ['workMenegerName / Phone', '维保经理', 'MAINT_MGR 账号'],
  ['workMan1Id / workMan2Id', '维保人员 1 / 2', '主检 / 配合人'],
  ['startTime / endTime', '维保起止时间', '记录单作业起止'],
  ['workType', '维保类别代码', '半月保→HM · 季度保→TM'],
  ['recorder / recorderPhone', '记录填写人', '记录单主检 / 手机'],
  ['originalRecordId', '原始记录 ID', '本地记录单号 WB-…'],
  ['problemCode', '严重事故隐患码', '未发现→S0（有严重隐患按 3.2 码表 S1–S7）'],
  ['nextMaintenanceDate', '下次维保日期', '电梯 nextMaintDue']
]
function meta(s) {
  const rec = M.RECORDS.find(x => x.no === s.recordNo) || null
  const deviceCode = rec ? rec.deviceCode : ''
  const elev = deviceCode ? M.elevatorByCode(deviceCode) : null
  const pid = rec ? rec.projectId : (elev ? elev.projectId : null)
  return { rec, elev, pid, elevatorCode: M.regulatorCode(deviceCode), projectName: pid ? (M.projectById(pid) || {}).name : '—' }
}
function syncTone(s) { return { 待上报: 'dim', 上报中: 'amber', 成功: 'ok', 失败: 'signal', 已重试: 'ok' }[s] || 'ghost' }
function projectCycle(pid) {
  const p = M.projectById(pid)
  if (!p) return '半月保'
  const c = M.CONTRACTS.find(x => x.id === p.contractId)
  return c ? c.cycle : '半月保'
}
function buildRequest(s) {
  const m = meta(s), rec = m.rec, elev = m.elev
  const project = m.pid ? M.projectById(m.pid) : null
  const org = project ? M.orgById(project.clientId) : null
  const primary = rec ? M.accountById(rec.primaryId) : null
  const partner = rec ? M.accountById(rec.partnerId) : null
  const mgr = M.ACCOUNTS.find(a => a.roles.includes('MAINT_MGR'))
  return JSON.stringify({
    elevatorCode: m.elevatorCode || '',
    unitPrincipal: org ? org.leader : '',
    unitPrincipalPhone: org ? org.phone : '',
    elevatorAdminister: org ? (org.administer || '') : '',
    elevatorAdministerPhone: org ? (org.administerPhone || '') : '',
    emergencyPhone: org ? (org.emergencyPhone || '') : '',
    insideNumber: elev ? elev.name : '',
    workMenegerName: mgr ? mgr.name : '',
    workMenegerPhone: mgr ? mgr.phone : '',
    workMan1Id: primary ? primary.empNo : '',
    workMan2Id: partner ? partner.empNo : '',
    startTime: rec ? rec.workDate + ' 09:00:00' : '',
    endTime: rec ? rec.workDate + ' 10:00:00' : '',
    workType: m.pid ? M.workTypeCode(projectCycle(m.pid)) : 'HM',
    recorder: primary ? primary.name : '',
    recorderPhone: primary ? primary.phone : '',
    originalRecordId: s.recordNo,
    problemCode: 'S0',
    deviceCode: elev ? elev.regCode : '',
    nextMaintenanceDate: elev ? elev.nextMaintDue : ''
  }, null, 2)
}
function buildResponse(s) {
  if (s.code == null) return JSON.stringify({ code: null, message: '待上报 / 上报中，尚无响应' }, null, 2)
  return JSON.stringify({ code: s.code, message: s.message }, null, 2)
}
function openDetail(r) { detail.value = r }
function retry(s) {
  // 演示：逐条人工重报（真实系统经服务端 report:retry 逐条转发，禁批量）
  s.retry += 1; s.status = '已重试'; s.code = 200; s.message = '操作成功'
  s.syncAt = M.meta.NOW.replace('T', ' ')
  toast.success('已重报 · ' + s.recordNo, 'maintenanceRecord → 200（演示）')
  detail.value = null
}
</script>