<template>
  <div>
    <ViewHead title="工单调度" sub="急修 / 报修 · SLA 倒计时 · 派单 / 升级 / 取消">
      <template #meta>
        <PageMeta perm="order:view_pool" :extra="[{ k: '工单', v: rows.length, icon: 'bolt', cls: 'count' }, { k: '超时', v: overtimeCount, icon: 'alert', cls: 'count' }]" />
      </template>
    </ViewHead>
    <div class="seg mt16">
      <button type="button" :class="['seg-i', { on: tab === 'faults' }]" @click="switchTab('faults')">急修 / 报修</button>
      <button type="button" :class="['seg-i', { on: tab === 'pool' }]" @click="switchTab('pool')">维保工单池</button>
    </div>
    <div v-if="tab === 'faults'" class="mt16">
      <DataTable :columns="cols" :rows="rows" :loading="loading" clickable index
        search-placeholder="搜索工单号 / 设备码 / 报修人" :search-keys="['faultNo', 'elevatorCode', 'createdByName']"
        :filters="filters" :bulk-actions="[{ code: 'order:dispatch', label: '批量派单', icon: 'user-check', cTip: '仅同状态（待接单）可用' }]"
        empty-hint="当前数据范围内没有工单。" :row-class="r => (r.overtime && r.status !== 'CLOSED' && r.status !== 'CANCELLED') ? 'dim-row' : ''"
        @row-click="r => $router.push('/order/' + r.id)" @bulk="onBulk">
        <template #toolbar>
          <PermButton code="order:create_proxy" label="代报修" variant="primary" icon="plus" @click="openProxy" />
          <PermButton code="order:export" label="导出工单" variant="ghost" icon="download" @click="exportOrders" />
        </template>
        <template #cell-faultNo="{ row }">
          <b class="mono">{{ row.faultNo || row.id }}</b>
          <span v-if="row.isRescue" class="tag tag-signal">困人</span>
        </template>
        <template #cell-type="{ row }">
          <span :class="['tag', row.typeLabel === '急修' ? 'tag-signal' : 'tag-ghost']">{{ row.typeLabel }}</span>
          <span class="mono f11">{{ row.levelLabel }}</span>
        </template>
        <template #cell-elevatorCode="{ row }"><span class="mono f12">{{ row.elevatorCode }}</span></template>
        <template #cell-project="{ row }">{{ row.useUnitName || row.elevatorName || '—' }}</template>
        <template #cell-reporter="{ row }">
          {{ row.createdByName || '—' }}
          <div class="cell-2">{{ row.reporterPhone || '—' }}</div>
        </template>
        <template #cell-status="{ row }"><StatusTag :status="row.status" :label="faultStatusLabel(row.status)" /></template>
        <template #cell-sla="{ row }">
          <span class="mono f11 dim">{{ row.slaMinutes || '—' }}′</span>
          <div class="cell-2"><span :class="['tag', slaTone(row)]">{{ slaText(row) }}</span></div>
        </template>
        <template #cell-owner="{ row }">
          <span v-if="row.dispatchedWorkerName">{{ row.dispatchedWorkerName }}</span>
          <span v-else class="dim">待派</span>
        </template>
        <template #cell-source="{ row }"><span class="f12 dim">{{ row.sourceLabel }}</span></template>
        <template #row-actions="{ row }">
          <PermButton v-if="row.status === 'OPEN' || row.status === 'ASSIGNED'" code="order:dispatch"
            :label="row.status === 'OPEN' ? '派单' : '改派'" variant="ghost" size="xs" icon="user-check" @click="openDispatch([row.id])" />
          <button class="btn btn-ghost btn-xs" type="button" @click="$router.push('/order/' + row.id)">
            <svg class="ic" aria-hidden="true"><use href="#i-eye" /></svg><span>详情</span>
          </button>
        </template>
      </DataTable>
    </div>

    <div v-else class="mt16">
      <DataTable :columns="poolCols" :rows="poolRows" :loading="poolLoading"
        :search-keys="['orderNo', 'elevatorCode', 'elevatorName', 'projectName']" search-placeholder="搜索工单号 / 设备码 / 项目"
        :filters="poolFilters" empty-hint="当前数据范围内没有维保工单。">
        <template #cell-orderNo="{ row }"><b class="mono">{{ row.orderNo || row.id }}</b></template>
        <template #cell-elevator="{ row }">
          <span class="mono f12">{{ row.elevatorCode || '—' }}</span>
          <div class="cell-2">{{ row.elevatorName || row.projectName || '' }}</div>
        </template>
        <template #cell-workType="{ row }"><span class="tag tag-ghost">{{ row.workType || row.workTypeCode || '—' }}</span></template>
        <template #cell-planTime="{ value }"><span class="mono f12">{{ value ? String(value).slice(0, 16) : '—' }}</span></template>
        <template #cell-claim="{ row }">
          <span v-if="isUnclaimed(row)" class="tag tag-amber">待接单 · {{ row.regionName || '不限区域' }}</span>
          <template v-else>
            {{ row.workerName || '—' }}
            <div v-if="row.assistantName" class="cell-2">配合 {{ row.assistantName }}</div>
          </template>
        </template>
        <template #cell-status="{ row }"><StatusTag :status="row.status" :label="poolStatusLabel(row.status)" /></template>
        <template #row-actions="{ row }">
          <PermButton v-if="row.status !== 'DONE' && row.status !== 'CANCELLED'" code="order:dispatch"
            label="退回池" variant="ghost" size="xs" icon="refresh" c-tip="清空认领回区域池，审计先行" @click="askRelease(row)" />
        </template>
      </DataTable>
    </div>
    <RuleNote v-if="tab === 'faults'" class="mt12" icon="clock">剩余 &lt;0 → 超时（红）；≤30% → 临期（黄）；P1 困人默认 {{ slaP1 }} 分钟（读系统配置）。平台为运维只读（D3）；使用单位仅见本单位工单且接单前可取消。</RuleNote>
    <RuleNote v-else class="mt12" icon="refresh">区域抢单池：派单只落区域快照不带人，同区域持平台ID的维保员先签到者认领。「退回池」审计先行——已签到工单的签到留痕转入审计快照后清空，工单回待接单重新入池；已签退（DONE）工单不可退回。</RuleNote>

    <!-- 派单 / 改派（原型 openDispatch Drawer） -->
    <Drawer :open="dispatchOpen" :title="dispatchIds.length > 1 ? '批量派单（' + dispatchIds.length + ' 单）' : '派单 / 改派'"
      sub="必选维保人员 + 原因 → 状态已接单" note="order:dispatch" @close="dispatchOpen = false">
      <RuleNote class="mb12">确认后工单状态 → 已接单，责任人更新，时间轴追加，写审计。</RuleNote>
      <div class="form-grid one">
        <FormField label="维保人员" required :error="dispatchErr.worker">
          <select v-model="dispatchForm.workerId" class="ctl">
            <option value="">请选择维保人员</option>
            <option v-for="w in workers" :key="w.id" :value="w.id">{{ w.name }} · {{ w.jobNo || w.id }}</option>
          </select>
        </FormField>
        <FormField label="派单 / 改派原因" required :error="dispatchErr.reason">
          <textarea v-model="dispatchForm.reason" class="ctl" placeholder="必填" />
        </FormField>
      </div>
      <template #footer>
        <button class="btn btn-ghost" type="button" @click="dispatchOpen = false">取消</button>
        <button class="btn btn-primary" type="button" :disabled="dispatching" @click="submitDispatch">
          <svg class="ic" aria-hidden="true"><use href="#i-check" /></svg>确认派单
        </button>
      </template>
    </Drawer>

    <!-- 代报修录入（原型 openProxyForm Modal；真实链路 POST /faults/proxy 原子建单） -->
    <Modal :open="proxyOpen" title="代报修录入" sub="维保主管代使用单位报修 → 新工单进入报修池" icon="phone" note="order:create_proxy"
      :loading="proxySubmitting" confirm-label="提交报修" @close="proxyOpen = false" @confirm="submitProxy">
      <div class="form-grid">
        <FormField label="报修人姓名" required :error="proxyErr.reporter">
          <input v-model="proxyForm.reporter" class="ctl" placeholder="必填" />
        </FormField>
        <FormField label="联系电话" required hint="^1[3-9]\d{9}$" :error="proxyErr.phone">
          <input v-model="proxyForm.phone" class="ctl mono" placeholder="11 位手机号" />
        </FormField>
        <FormField label="设备码" required :error="proxyErr.device">
          <select v-model="proxyForm.device" class="ctl mono">
            <option value="">请选择设备</option>
            <option v-for="e in elevators" :key="e.elevatorCode" :value="e.elevatorCode">{{ e.elevatorCode }} · {{ e.elevatorName || e.projectName || '' }}</option>
          </select>
        </FormField>
        <FormField label="来源" required>
          <select v-model="proxyForm.source" class="ctl">
            <option value="PHONE">电话</option><option value="WECHAT">微信</option><option value="ONSITE">现场</option>
          </select>
        </FormField>
        <FormField label="录音编号" required hint="^REC-\d{4,6}$" :error="proxyErr.recNo">
          <input v-model="proxyForm.recNo" class="ctl mono" placeholder="REC-20261" />
        </FormField>
      </div>
      <div class="form-grid one mt12">
        <FormField label="故障描述" required :error="proxyErr.fault">
          <textarea v-model="proxyForm.fault" class="ctl" placeholder="必填，≥5 字" />
        </FormField>
      </div>
    </Modal>

    <Modal :open="!!releaseTarget" title="退回池" :sub="releaseTarget ? (releaseTarget.orderNo || releaseTarget.id) : ''"
      icon="refresh" danger note="order:dispatch · 审计先行" require-confirm
      :confirm-text="releaseConfirmText" confirm-label="确认退回池"
      @close="releaseTarget = null" @confirm="submitRelease">
      <RuleNote icon="alert">{{ releaseNote }}</RuleNote>
    </Modal>
  </div>
</template>
<script setup>
import { ref, computed, onMounted } from 'vue'
import http from '../api/client'
import { workOrders, releaseToPool } from '../api/orders'
import DataTable from '../ui/DataTable.vue'
import ViewHead from '../ui/ViewHead.vue'
import PageMeta from '../ui/PageMeta.vue'
import RuleNote from '../ui/RuleNote.vue'
import StatusTag from '../ui/StatusTag.vue'
import PermButton from '../ui/PermButton.vue'
import Drawer from '../ui/Drawer.vue'
import Modal from '../ui/Modal.vue'
import FormField from '../ui/FormField.vue'
import { useToast } from '../ui/stores/ui'
import { exportCsv } from '../ui/export-csv'
const toast = useToast()
const rows = ref([])
const loading = ref(false)
const workers = ref([])
const elevators = ref([])
const slaP1 = ref(30)
const FAULT_STATUS = { OPEN: '待接单', ASSIGNED: '已接单', PROCESSING: '处置中', PENDING_CONFIRM: '待使用单位确认', REFUSED: '待整改', CLOSED: '已闭环', CANCELLED: '已取消' }
function faultStatusLabel(s) { return FAULT_STATUS[s] || s }
const filters = [
  { key: 'status', label: '状态', options: Object.keys(FAULT_STATUS).map(k => ({ value: k, label: FAULT_STATUS[k] })) },
  { key: 'typeLabel', label: '类型', options: ['急修', '报修'] },
  { key: 'levelLabel', label: '等级', options: ['P1', 'P2', 'P3'] }
]
const cols = [
  { key: 'faultNo', label: '工单号', sortable: true, value: (r) => r.faultNo || r.id },
  { key: 'type', label: '类型 / 等级', value: (r) => [r.typeLabel, r.levelLabel].filter(Boolean).join(' ') },
  { key: 'elevatorCode', label: '设备码', sortable: true },
  { key: 'project', label: '项目', value: (r) => r.useUnitName || r.elevatorName || '—' },
  { key: 'reporter', label: '报修人 / 电话', value: (r) => (r.createdByName || '—') + ' / ' + (r.reporterPhone || '—') },
  { key: 'status', label: '状态', sortable: true, value: (r) => faultStatusLabel(r.status) },
  { key: 'sla', label: 'SLA / 剩余', sortable: true, value: (r) => (r.slaMinutes ? r.slaMinutes + '′' : '—') + ' / ' + slaText(r) },
  { key: 'owner', label: '责任人', value: (r) => r.dispatchedWorkerName || '待派' },
  { key: 'source', label: '来源', value: (r) => r.sourceLabel || '—' }
]
function exportOrders() {
  const n = exportCsv('工单台账', cols, rows.value)
  toast.success('已导出 ' + n + ' 条工单', 'CSV · 按导出人权限脱敏')
}
function minutesBetween(a, b) { return Math.round((new Date(b) - new Date(a)) / 60000) }function decorate(f) {
  const isRescue = Boolean((f.trappedCount > 0) || String(f.faultType || '').includes('困人'))
  const now = new Date()
  let remaining = null
  if (f.deadline) remaining = minutesBetween(now, f.deadline)
  else if (f.slaMinutes != null && f.createdAt) remaining = f.slaMinutes - minutesBetween(f.createdAt, now)
  const overtime = remaining != null && remaining < 0 && f.status !== 'CLOSED' && f.status !== 'CANCELLED'
  return {
    ...f,
    isRescue,
    typeLabel: isRescue ? '急修' : '报修',
    levelLabel: isRescue ? 'P1' : 'P2',
    remaining,
    overtime,
    sourceLabel: f.proxySource ? '电话代报修' : (String(f.createdBy || '').startsWith('unit:') ? '使用单位' : '小程序')
  }
}
const overtimeCount = computed(() => rows.value.filter(r => r.overtime).length)
function slaTone(r) {
  if (r.status === 'CLOSED' || r.status === 'CANCELLED') return 'tag-dim'
  if (r.remaining == null) return 'tag-dim'
  if (r.overtime) return 'tag-signal'
  if (r.slaMinutes && r.remaining <= r.slaMinutes * 0.3) return 'tag-amber'
  return 'tag-ok'
}
function slaText(r) {
  if (r.status === 'CLOSED' || r.status === 'CANCELLED') return '已结算'
  if (r.remaining == null) return '—'
  if (r.overtime) return '超时 ' + Math.abs(r.remaining) + '′'
  if (r.slaMinutes && r.remaining <= r.slaMinutes * 0.3) return '临期 ' + r.remaining + '′'
  return '剩 ' + r.remaining + '′'
}
async function load() {
  loading.value = true
  try {
    const data = await http.get('/faults')
    rows.value = (Array.isArray(data) ? data : (data && data.list) || []).map(decorate)
  } catch (e) { toast.error(e.message || '工单加载失败') } finally { loading.value = false }
}
onMounted(async () => {
  load()
  try { workers.value = await http.get('/employees') } catch { workers.value = [] }
  try { const d = await http.get('/elevators'); elevators.value = d.list || d || [] } catch { elevators.value = [] }
  try { const cfg = await http.get('/settings'); if (cfg && cfg.sla_minutes) slaP1.value = cfg.sla_minutes } catch { /* 配置不可读时用默认 30 */ }
})

// ── 派单 / 批量派单 ──
const dispatchOpen = ref(false)
const dispatchIds = ref([])
const dispatching = ref(false)
const dispatchForm = ref({ workerId: '', reason: '' })
const dispatchErr = ref({ worker: '', reason: '' })
function openDispatch(ids) {
  dispatchIds.value = ids
  dispatchForm.value = { workerId: '', reason: '' }
  dispatchErr.value = { worker: '', reason: '' }
  dispatchOpen.value = true
}
function onBulk({ action, ids }) {
  if (action !== 'order:dispatch') return
  const sel = rows.value.filter(r => ids.includes(r.id))
  if (!sel.every(r => r.status === 'OPEN')) { toast.warn('批量派单仅支持同状态（待接单）'); return }
  openDispatch(ids)
}
async function submitDispatch() {
  const err = { worker: '', reason: '' }
  if (!dispatchForm.value.workerId) err.worker = '请选择维保人员'
  if (!dispatchForm.value.reason.trim()) err.reason = '原因必填'
  dispatchErr.value = err
  if (err.worker || err.reason) return
  dispatching.value = true
  try {
    for (const id of dispatchIds.value) {
      await http.post(`/admin/faults/${id}/dispatch`, { workerId: dispatchForm.value.workerId, reason: dispatchForm.value.reason })
    }
    const w = workers.value.find(x => x.id === dispatchForm.value.workerId)
    toast.success('已派单 ' + dispatchIds.value.length + ' 单 · 状态已接单', (w && w.name) || dispatchForm.value.workerId)
    dispatchOpen.value = false
    load()
  } catch (e) { toast.error(e.message || '派单失败') } finally { dispatching.value = false }
}

// ── 代报修（一次请求原子完成建单 + 来源/录音登记） ──
const proxyOpen = ref(false)
const proxySubmitting = ref(false)
const proxyForm = ref({ reporter: '', phone: '', device: '', source: 'PHONE', recNo: '', fault: '' })
const proxyErr = ref({ reporter: '', phone: '', device: '', recNo: '', fault: '' })
function openProxy() {
  proxyForm.value = { reporter: '', phone: '', device: '', source: 'PHONE', recNo: '', fault: '' }
  proxyErr.value = { reporter: '', phone: '', device: '', recNo: '', fault: '' }
  proxySubmitting.value = false
  proxyOpen.value = true
}
async function submitProxy() {
  if (proxySubmitting.value) return
  const f = proxyForm.value
  const err = { reporter: '', phone: '', device: '', recNo: '', fault: '' }
  if (!f.reporter.trim()) err.reporter = '报修人姓名必填'
  if (!/^1[3-9]\d{9}$/.test(f.phone)) err.phone = '手机号格式不正确'
  if (!f.device) err.device = '请选择设备'
  if (!/^REC-\d{4,6}$/.test(f.recNo)) err.recNo = '录音编号格式 REC-####'
  if (f.fault.trim().length < 5) err.fault = '故障描述至少 5 字'
  proxyErr.value = err
  if (Object.values(err).some(Boolean)) { toast.warn('请修正表单错误后再提交'); return }
  proxySubmitting.value = true
  try {
    const created = await http.post('/faults/proxy', {
      elevatorCode: f.device, desc: f.fault, faultType: '一般故障',
      reporter: f.reporter, reporterPhone: f.phone,
      proxySource: f.source, recordingNo: f.recNo
    })
    toast.success('代报修已提交 · 进入报修池', (created && created.faultNo) || '')
    proxyOpen.value = false
    load()
  } catch (e) { toast.error(e.message || '代报修提交失败') } finally { proxySubmitting.value = false }
}

const tab = ref('faults')
const poolRows = ref([])
const poolLoading = ref(false)
const poolLoaded = ref(false)
const POOL_STATUS = { PENDING: '待接单', PROCESSING: '进行中', DONE: '已完成', CANCELLED: '已取消' }
function poolStatusLabel(s) { return POOL_STATUS[s] || s || '—' }
const poolCols = [
  { key: 'orderNo', label: '工单号', sortable: true },
  { key: 'elevator', label: '电梯' },
  { key: 'workType', label: '类别' },
  { key: 'planTime', label: '计划时间', sortable: true },
  { key: 'claim', label: '认领 / 区域' },
  { key: 'status', label: '状态', sortable: true }
]
const poolFilters = [
  { key: 'status', label: '状态', options: Object.keys(POOL_STATUS).map(k => ({ value: k, label: POOL_STATUS[k] })) }
]
function isUnclaimed(row) { return row.status === 'PENDING' && !row.workerName && !row.workerPlatformId }
function switchTab(t) {
  tab.value = t
  if (t === 'pool' && !poolLoaded.value) loadPool()
}
async function loadPool() {
  poolLoading.value = true
  try {
    const d = await workOrders({ size: 200 })
    poolRows.value = Array.isArray(d) ? d : (d && d.list) || []
    poolLoaded.value = true
  } catch (e) { toast.error(e.message || '维保工单池加载失败') } finally { poolLoading.value = false }
}

// 退回池：未签到直接清认领回池；已签到（PROCESSING/有签到留痕）二次确认须明示留痕转审计
const releaseTarget = ref(null)
function askRelease(row) { releaseTarget.value = row }
const releaseSignedIn = computed(() => {
  const t = releaseTarget.value
  return !!t && (t.status === 'PROCESSING' || t.principalCheckedIn || t.assistantCheckedIn)
})
const releaseNote = computed(() =>
  '退回后清空认领人与配合人，工单回「待接单」重新进入区域池；操作人与退回前快照写入审计日志。'
  + (releaseSignedIn.value ? '该工单已有签到记录：已签到留痕将转入审计快照后再清空。' : ''))
const releaseConfirmText = computed(() =>
  releaseSignedIn.value ? '我已知悉已签到留痕将转入审计，确认退回池' : '我已知悉，确认退回池')
async function submitRelease() {
  const t = releaseTarget.value
  try {
    const r = await releaseToPool(t.id)
    toast.success(r && r.released === false ? '工单本就在池中（未认领），无需退回' : '已退回池 · 工单重新进入区域待接单', t.orderNo || t.id)
    releaseTarget.value = null
    loadPool()
  } catch (e) { toast.error(e.message || '退回池失败') }
}
</script>
<style scoped>
.seg { display: inline-flex; gap: 4px; padding: 3px; border: 1px solid var(--line-2, #2a3340); border-radius: var(--r-card, 10px) }
.seg-i { padding: 5px 14px; border: 0; border-radius: 7px; background: transparent; color: var(--ink-2, #9aa7b8); cursor: pointer; font-size: 13px }
.seg-i.on { background: var(--brand, #e8a020); color: #141414; font-weight: 600 }
</style>