<template>
  <div>
    <ViewHead title="工单详情" :sub="order ? (order.faultNo || id) : String(id)">
      <template #meta>
        <PageMeta perm="order:view_pool" :extra="order ? [{ k: '状态', v: faultStatusLabel(order.status), icon: 'activity' }] : []" />
      </template>
    </ViewHead>
    <ErrorState v-if="loadError" :reason="loadError" @retry="load" />
    <LoadingSkeleton v-else-if="!order" :rows="8" />
    <div v-else class="mt16">
      <!-- 工单头 -->
      <div class="card">
        <div class="card-body">
          <div class="row between wrap gap12">
            <div class="row gap8 wrap">
              <span :class="['tag', isRescue ? 'tag-signal' : 'tag-ghost']">{{ isRescue ? '急修' : '报修' }}</span>
              <span :class="['tag', levelLabel === 'P1' ? 'tag-signal' : 'tag-amber']">{{ levelLabel }}{{ levelLabel === 'P1' ? ' 困人' : '' }}</span>
              <StatusTag :status="order.status" :label="faultStatusLabel(order.status)" />
              <span v-if="order.escalatedAt" class="tag tag-signal">已升级</span>
            </div>
            <div class="mono f12 dim">{{ order.faultNo || order.id }} · {{ order.elevatorCode }} · {{ order.useUnitName || order.elevatorName || '—' }}</div>
          </div>
          <div class="prog-row mt12">
            <span class="pr-l">SLA</span>
            <div :class="['progress', slaTone, 'grow']"><i :style="{ width: slaPct + '%' }" /></div>
            <span class="pr-v">{{ elapsed }}′ / {{ order.slaMinutes || '—' }}′</span>
            <span :class="['tag', slaTagTone]">{{ slaTagText }}</span>
          </div>
          <div class="kv-grid mt12">
            <div class="kv"><span class="k">报修人</span><span class="v">{{ order.createdByName || '—' }}</span></div>
            <div class="kv"><span class="k">联系电话</span><span class="v mono">{{ order.reporterPhone || '—' }}</span></div>
            <div class="kv"><span class="k">责任人</span><span class="v">{{ order.dispatchedWorkerName || '待派' }}</span></div>
            <div class="kv"><span class="k">来源</span><span class="v">{{ order.proxySource ? channelLabel(order.proxySource) + '代报修' : '小程序' }}</span></div>
            <div class="kv"><span class="k">创建时间</span><span class="v mono">{{ fmtTime(order.createdAt) }}</span></div>
            <div v-if="order.recordingNo" class="kv"><span class="k">录音编号</span><span class="v mono">{{ order.recordingNo }}</span></div>
          </div>
        </div>
      </div>

      <!-- 7 态时间轴 -->
      <div class="card mt16">
        <div class="card-head"><h3><svg class="ic" aria-hidden="true"><use href="#i-activity" /></svg>7 态时间轴</h3></div>
        <div class="card-body">
          <Steps :states="ORDER_STATES" :current="faultStatusLabel(order.status)" :bad-state="order.status === 'CANCELLED' ? '已取消' : ''" />
          <Timeline class="mt16" :items="timeline" />
        </div>
      </div>

      <!-- 故障处置记录 -->
      <div class="card mt16">
        <div class="card-head"><h3><svg class="ic" aria-hidden="true"><use href="#i-wrench" /></svg>故障处置记录</h3></div>
        <div class="card-body">
          <RuleNote icon="alert">{{ order.descr || order.desc || order.siteDesc || '—' }}</RuleNote>
          <template v-if="parts.length">
            <div class="sec-t mt16">更换配件</div>
            <div class="spec-list"><div v-for="(p, i) in parts" :key="i">{{ p.name }} ×{{ p.qty || 1 }}</div></div>
          </template>
          <template v-if="order.handleDesc">
            <div class="sec-t mt16">处理结果</div>
            <div class="spec-list"><div>{{ order.handleDesc }}</div></div>
          </template>
          <div class="sec-t mt16">现场照片（水印缩略图）</div>
          <div class="thumbs">
            <span v-for="n in photoCount" :key="n" :class="['thumb', { sig: isRescue }]" aria-hidden="true" />
            <span v-if="!photoCount" class="dim f12">暂无照片</span>
          </div>
          <div v-if="order.gps" class="f11 dim mt8 mono">GPS {{ order.gps }}</div>
        </div>
      </div>

      <!-- 使用单位确认区 -->
      <div v-if="order.status === 'PENDING_CONFIRM'" class="card mt16">
        <div class="card-head"><h3><svg class="ic" aria-hidden="true"><use href="#i-user-check" /></svg>使用单位确认区</h3></div>
        <div class="card-body">
          <RuleNote>使用单位确认后由维保主管关闭工单（order:close）。使用单位签字为小程序侧动作（order:confirm）；管理端关闭须凭已生成的签字凭证。</RuleNote>
        </div>
      </div>

      <!-- 底部操作 -->
      <div class="card mt16">
        <div class="card-body row gap8 wrap">
          <PermButton v-if="order.status === 'OPEN' || order.status === 'ASSIGNED'" code="order:dispatch"
            :label="order.status === 'OPEN' ? '派单' : '改派'" variant="primary" icon="user-check" @click="dispatchOpen = true" />
          <PermButton code="order:escalate" label="升级" variant="ghost" icon="alert" @click="escalate" />
          <PermButton v-if="order.status === 'PENDING_CONFIRM'" code="order:close" label="关闭工单" variant="ok" icon="check" @click="closeOpen = true" />
          <PermButton v-if="order.status !== 'CLOSED' && order.status !== 'CANCELLED'" code="order:cancel" label="取消" variant="danger" icon="ban" @click="cancelOpen = true" />
          <PermButton code="order:create_proxy" label="代报修" variant="ghost" icon="plus" @click="proxyOpen = true" />
          <span class="grow" />
          <button class="btn btn-ghost" type="button" @click="$router.push('/dispatch')">
            <svg class="ic" aria-hidden="true"><use href="#i-chevron-left" /></svg>返回调度
          </button>
        </div>
      </div>
    </div>

    <!-- 派单 / 改派 -->
    <Drawer :open="dispatchOpen" title="派单 / 改派" sub="必选维保人员 + 原因 → 状态已接单" note="order:dispatch" @close="dispatchOpen = false">
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
        <button class="btn btn-primary" type="button" @click="submitDispatch">
          <svg class="ic" aria-hidden="true"><use href="#i-check" /></svg>确认派单
        </button>
      </template>
    </Drawer>

    <!-- 关闭工单（真实契约：处理结果 + 使用单位签字凭证必填） -->
    <Modal :open="closeOpen" title="关闭工单" :sub="order ? (order.faultNo || id) : ''" icon="check" note="order:close"
      confirm-label="确认关闭" @close="closeOpen = false" @confirm="submitClose">
      <div class="form-grid one">
        <FormField label="处理结果" required :error="closeErr.result">
          <textarea v-model="closeForm.result" class="ctl" placeholder="必填，说明处置结果" />
        </FormField>
        <FormField label="使用单位签字凭证" required hint="签名文件 ID" :error="closeErr.signature">
          <input v-model="closeForm.signature" class="ctl mono" placeholder="小程序侧签署生成的凭证 ID" />
        </FormField>
      </div>
      <RuleNote class="mt12">签字为使用单位小程序侧动作；此处仅校验凭证已存在，不代签。</RuleNote>
    </Modal>

    <!-- 取消工单 -->
    <Modal :open="cancelOpen" title="取消工单" :sub="order ? (order.faultNo || id) : ''" icon="ban" danger note="order:cancel"
      confirm-label="确认取消" @close="cancelOpen = false" @confirm="submitCancel">
      <div class="form-grid one">
        <FormField label="取消原因" required :error="cancelErr">
          <textarea v-model="cancelReason" class="ctl" placeholder="必填" />
        </FormField>
      </div>
    </Modal>

    <!-- 代报修登记（真实契约：来源 + 录音编号） -->
    <Modal :open="proxyOpen" title="代报修录入" sub="维保主管代使用单位报修 → 登记来源与录音编号" icon="phone" note="order:create_proxy"
      confirm-label="提交报修" @close="proxyOpen = false" @confirm="submitProxy">
      <div class="form-grid">
        <FormField label="来源" required>
          <select v-model="proxyForm.proxySource" class="ctl">
            <option value="PHONE">电话</option><option value="WECHAT">微信</option><option value="ONSITE">现场</option>
          </select>
        </FormField>
        <FormField label="录音编号" required hint="^REC-\d{4,6}$" :error="proxyErr">
          <input v-model="proxyForm.recordingNo" class="ctl mono" placeholder="REC-20261" />
        </FormField>
      </div>
    </Modal>
  </div>
</template>
<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import http from '../api/client'
import ViewHead from '../ui/ViewHead.vue'
import PageMeta from '../ui/PageMeta.vue'
import RuleNote from '../ui/RuleNote.vue'
import StatusTag from '../ui/StatusTag.vue'
import PermButton from '../ui/PermButton.vue'
import Steps from '../ui/Steps.vue'
import Timeline from '../ui/Timeline.vue'
import Drawer from '../ui/Drawer.vue'
import Modal from '../ui/Modal.vue'
import FormField from '../ui/FormField.vue'
import ErrorState from '../ui/states/ErrorState.vue'
import LoadingSkeleton from '../ui/states/LoadingSkeleton.vue'
import { useToast } from '../ui/stores/ui'
import { channelLabel } from '../ui/labels'
const toast = useToast()
const route = useRoute()
const id = computed(() => route.params.id)
const order = ref(null)
const loadError = ref('')
const workers = ref([])
const ORDER_STATES = ['待接单', '已接单', '处置中', '待使用单位确认', '待整改', '已闭环', '已取消']
const FAULT_STATUS = { OPEN: '待接单', ASSIGNED: '已接单', PROCESSING: '处置中', PENDING_CONFIRM: '待使用单位确认', REFUSED: '待整改', CLOSED: '已闭环', CANCELLED: '已取消' }
function faultStatusLabel(s) { return FAULT_STATUS[s] || s }
function fmtTime(t) { return String(t || '').replace('T', ' ').slice(0, 16) }
const isRescue = computed(() => Boolean(order.value && (order.value.rescue || order.value.trapped || String(order.value.faultType || '').includes('困人') || order.value.priority === 'P1')))
const levelLabel = computed(() => (order.value && order.value.priority) || (isRescue.value ? 'P1' : 'P2'))
function minutesBetween(a, b) { return Math.round((new Date(b) - new Date(a)) / 60000) }
const elapsed = computed(() => {
  const o = order.value
  if (!o || !o.createdAt) return 0
  const end = (o.status === 'CLOSED' || o.status === 'CANCELLED') && o.finishedAt ? o.finishedAt : new Date()
  return Math.max(0, minutesBetween(o.createdAt, end))
})
const remaining = computed(() => {
  const o = order.value
  if (!o) return null
  if (o.deadline) return minutesBetween(new Date(), o.deadline)
  if (o.slaMinutes != null) return o.slaMinutes - elapsed.value
  return null
})
const slaPct = computed(() => {
  const o = order.value
  if (!o || !o.slaMinutes) return 0
  return Math.min(100, Math.round(elapsed.value / o.slaMinutes * 100))
})
const slaTone = computed(() => (remaining.value != null && remaining.value < 0 ? 'sig' : slaPct.value >= 70 ? '' : 'ok'))
const slaTagTone = computed(() => {
  const o = order.value
  if (!o || o.status === 'CLOSED' || o.status === 'CANCELLED') return 'tag-dim'
  if (remaining.value == null) return 'tag-dim'
  if (remaining.value < 0) return 'tag-signal'
  if (o.slaMinutes && remaining.value <= o.slaMinutes * 0.3) return 'tag-amber'
  return 'tag-ok'
})
const slaTagText = computed(() => {
  const o = order.value
  if (!o || o.status === 'CLOSED' || o.status === 'CANCELLED') return '已结算'
  if (remaining.value == null) return '—'
  if (remaining.value < 0) return '超时 ' + Math.abs(remaining.value) + '′'
  if (o.slaMinutes && remaining.value <= o.slaMinutes * 0.3) return '临期 ' + remaining.value + '′'
  return '剩 ' + remaining.value + '′'
})
const parts = computed(() => {
  const p = order.value && order.value.parts
  return Array.isArray(p) ? p : []
})
const photoCount = computed(() => {
  const p = order.value && order.value.photos
  if (Array.isArray(p)) return p.length
  if (typeof p === 'string' && p) { try { const a = JSON.parse(p); return Array.isArray(a) ? a.length : 1 } catch { return 1 } }
  return 0
})
const timeline = computed(() => {
  const o = order.value
  if (!o) return []
  const items = [{ cls: 'done', title: '报修创建', time: fmtTime(o.createdAt), actor: o.createdByName || '系统', text: o.proxySource ? channelLabel(o.proxySource) + '代报修' : '小程序' }]
  if (o.dispatchedAt) items.push({ cls: 'done', title: '派单 → ' + (o.dispatchedWorkerName || ''), time: fmtTime(o.dispatchedAt), actor: '调度', role: 'MAINT_SUP' })
  if (o.arrivedAt) items.push({ cls: 'done', title: '到场打卡', time: fmtTime(o.arrivedAt), actor: o.dispatchedWorkerName || '', role: 'MAINT_TECH' })
  if (o.finishedAt) items.push({ cls: 'done', title: '处置完成', time: fmtTime(o.finishedAt), actor: o.dispatchedWorkerName || '', role: 'MAINT_TECH' })
  if (o.confirmedAt) items.push({ cls: 'done', title: '使用单位确认', time: fmtTime(o.confirmedAt), actor: '使用单位', role: 'CLIENT_SIGNER' })
  if (o.cancelReason) items.push({ cls: 'bad', title: '取消', time: '', actor: '', text: o.cancelReason })
  items.push({ cls: 'active', title: '当前 · ' + faultStatusLabel(o.status), time: '', text: o.refuseReason || '' })
  return items
})
let loadSeq = 0
async function load() {
  const targetId = id.value
  const seq = ++loadSeq
  loadError.value = ''
  try {
    const data = await http.get(`/faults/${targetId}`)
    if (seq !== loadSeq || id.value !== targetId) return
    order.value = data
  } catch (e) {
    if (seq !== loadSeq || id.value !== targetId) return
    order.value = null
    loadError.value = e.message || '工单不存在或超出数据范围'
  }
}
onMounted(async () => {
  load()
  try { workers.value = await http.get('/employees') } catch { workers.value = [] }
})
function ensureCurrentOrder(targetId) {
  if (!order.value || String(order.value.id) !== String(targetId)) {
    toast.error('工单已切换，请重新操作')
    return false
  }
  return true
}

// ── 派单 / 改派 ──
const dispatchOpen = ref(false)
const dispatchForm = ref({ workerId: '', reason: '' })
const dispatchErr = ref({ worker: '', reason: '' })
async function submitDispatch() {
  const targetId = id.value
  const err = { worker: '', reason: '' }
  if (!dispatchForm.value.workerId) err.worker = '请选择维保人员'
  if (!dispatchForm.value.reason.trim()) err.reason = '原因必填'
  dispatchErr.value = err
  if (err.worker || err.reason) return
  if (!ensureCurrentOrder(targetId)) return
  try {
    await http.post(`/admin/faults/${targetId}/dispatch`, { workerId: dispatchForm.value.workerId, reason: dispatchForm.value.reason })
    toast.success('已派单 · 状态已接单')
    dispatchOpen.value = false
    load()
  } catch (e) { toast.error(e.message || '派单失败') }
}
// ── 升级：后端无升级端点，明确拒绝，不伪造状态 ──
function escalate() { toast.error('超时升级接口尚未接入', '后端暂无 /work-orders/{id}/escalate，请联系平台侧处理') }
// ── 关闭 ──
const closeOpen = ref(false)
const closeForm = ref({ result: '', signature: '' })
const closeErr = ref({ result: '', signature: '' })
async function submitClose() {
  const targetId = id.value
  const err = { result: '', signature: '' }
  if (!closeForm.value.result.trim()) err.result = '处理结果必填'
  if (!closeForm.value.signature.trim()) err.signature = '请先由使用单位安全管理员签字确认'
  closeErr.value = err
  if (err.result || err.signature) return
  if (!ensureCurrentOrder(targetId)) return
  try {
    await http.post(`/faults/${targetId}/close`, { result: closeForm.value.result, signature: closeForm.value.signature })
    toast.success('工单已闭环')
    closeOpen.value = false
    load()
  } catch (e) { toast.error(e.message || '关闭失败') }
}
// ── 取消 ──
const cancelOpen = ref(false)
const cancelReason = ref('')
const cancelErr = ref('')
async function submitCancel() {
  const targetId = id.value
  if (!cancelReason.value.trim()) { cancelErr.value = '取消原因必填'; return }
  cancelErr.value = ''
  if (!ensureCurrentOrder(targetId)) return
  try {
    await http.post(`/faults/${targetId}/cancel`, { reason: cancelReason.value })
    toast.success('工单已取消')
    cancelOpen.value = false
    load()
  } catch (e) { toast.error(e.message || '取消失败') }
}
// ── 代报修登记 ──
const proxyOpen = ref(false)
const proxyForm = ref({ proxySource: 'PHONE', recordingNo: '' })
const proxyErr = ref('')
async function submitProxy() {
  const targetId = id.value
  if (!/^REC-\d{4,6}$/.test(proxyForm.value.recordingNo)) { proxyErr.value = '录音编号格式 REC-####'; return }
  proxyErr.value = ''
  if (!ensureCurrentOrder(targetId)) return
  try {
    await http.post(`/faults/${targetId}/proxy`, proxyForm.value)
    toast.success('代报修已登记')
    proxyOpen.value = false
    load()
  } catch (e) { toast.error(e.message || '登记失败') }
}

function resetActions() {
  dispatchOpen.value = false
  closeOpen.value = false
  cancelOpen.value = false
  proxyOpen.value = false
  dispatchErr.value = { worker: '', reason: '' }
  closeErr.value = { result: '', signature: '' }
  cancelErr.value = ''
  proxyErr.value = ''
}
watch(id, () => {
  order.value = null
  loadError.value = ''
  resetActions()
  load()
})
</script>