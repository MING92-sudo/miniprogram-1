<template>
  <div>
    <ViewHead title="记录单复核" sub="双签核验 · 退回 · 送使用单位签署 · 归档后修订（v2 + 版本留痕，不重报）">
      <template #meta><PageMeta perm="record:view" :extra="[{ k: '记录单', v: rows.length, icon: 'clipboard', cls: 'count' }]" /></template>
    </ViewHead>

    <div class="mt16">
      <DataTable :columns="cols" :rows="rows" :loading="loading" clickable
        :search-keys="['elevatorCode', 'elevatorName', 'workerName', 'originalRecordId']" search-placeholder="搜索记录单号 / 设备码 / 主检"
        :filters="filters" empty-hint="当前数据范围内没有记录单。" @row-click="openDetail">
        <template #toolbar>
          <PermButton code="record:export" label="导出记录单" variant="ghost" icon="download" @click="exportCurrent" />
        </template>
        <template #cell-recordNo="{ row }"><b class="mono">{{ row.originalRecordId || row.id }}</b></template>
        <template #cell-elevatorCode="{ row }">
          <span class="mono f12">{{ row.elevatorCode }}</span>
          <div class="cell-2">{{ row.elevatorName }}</div>
        </template>
        <template #cell-workType="{ value }"><span class="tag tag-ghost">{{ value }}</span></template>
        <template #cell-signers="{ row }">
          {{ row.workerName || '—' }} / {{ row.assistantName || '—' }}
        </template>
        <template #cell-problemCodes="{ value }">
          <span v-if="!abnormalCodes(value).length" class="tag tag-ok">S0 无异常</span>
          <span v-else class="tag tag-signal">{{ abnormalCodes(value).join(' ') }}</span>
        </template>
        <template #cell-confirmStatus="{ row }">
          <StatusTag :status="row.confirmStatus" />
          <div v-if="row.lifecycleStatus === 'REFUSED_RETURNED'" class="cell-2">
            <span class="tag tag-signal">拒签{{ row.refuseCode ? ' ' + row.refuseCode : '' }}</span>
          </div>
          <div class="cell-2"><span :class="['tag', row.reportStatus === 'FAILED' ? 'tag-signal' : row.reportStatus === 'REPORTED' ? 'tag-ok' : 'tag-dim']">{{ reportLabel(row.reportStatus) }}</span></div>
        </template>
        <template #cell-createdAt="{ value }"><span class="mono f12">{{ value }}</span></template>
        <template #row-actions="{ row }">
          <button class="btn btn-ghost btn-xs" type="button" @click.stop="openDetail(row)">
            <svg class="ic" aria-hidden="true"><use href="#i-eye" /></svg><span>详情</span>
          </button>
          <PermButton v-if="row.confirmStatus === 'CONFIRMED' && row.lifecycleStatus === 'ARCHIVED'" code="record:amend" label="发起修订" variant="ghost" size="xs" icon="edit" @click="openAmend(row)" />
        </template>
      </DataTable>
    </div>
    <RuleNote class="mt12">双签三条件：① 主检≠配合人（不同账号）② 主检先签、配合人后签 ③ 双签完成才可送使用单位——②③ 由服务端在送签时强校验（含存量单人豁免标记）。归档后修订生成 v2 与版本留痕，v1 与签名永久保留，<b>不触发任何上报</b>（P-5）。</RuleNote>

    <!-- 详情抽屉 -->
    <Drawer :open="detailOpen" :title="detail.originalRecordId || detail.id || '记录单'" :sub="(detail.elevatorCode || '') + ' · ' + (detail.elevatorName || '')"
      :meta="{ perm: 'record:view' }" note="按权限码渲染 · 后端强校验" @close="detailOpen = false">
      <template #meta>
        <StatusTag :status="detail.confirmStatus" />
        <span class="scope-pill">{{ reportLabel(detail.reportStatus) }}</span>
      </template>

      <div v-if="detail.id">
        <div v-if="detail.lifecycleStatus === 'REFUSED_RETURNED'" class="sec refuse-sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-x" /></svg>使用单位拒签（V56 分类码）</div>
          <div class="kv-grid">
            <div class="kv"><span class="k">拒签分类</span><span class="v"><b class="mono">{{ detail.refuseCode || '—' }}</b> · {{ refuseCodeLabel(detail.refuseCode) || '未分类' }}</span></div>
            <div class="kv"><span class="k">拒签原因</span><span class="v">{{ detail.refuseReason || '—' }}</span></div>
          </div>
          <RuleNote class="mt8">拒签记录已退回维保侧整改，不产生归档哈希，不触发监管平台上报；整改完成后重新走送签流程。</RuleNote>
        </div>

        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-shield" /></svg>双签核验</div>
          <div class="chk-list">
            <div :class="['chk-row', dualOk ? 'pass' : 'fail']">
              <svg class="ic" aria-hidden="true"><use :href="dualOk ? '#i-check' : '#i-x'" /></svg>
              <span class="cr-t">① 主检与配合人为两个不同账号（{{ detail.workerName || '—' }} / {{ detail.assistantName || '—' }}）</span>
              <span class="cr-d">{{ dualOk ? '通过' : '待补全' }}</span>
            </div>
            <div class="chk-row">
              <svg class="ic" aria-hidden="true"><use href="#i-clock" /></svg>
              <span class="cr-t">② 主检先签、配合人后签（时序有序）</span>
              <span class="cr-d">服务端门控</span>
            </div>
            <div class="chk-row">
              <svg class="ic" aria-hidden="true"><use href="#i-signature" /></svg>
              <span class="cr-t">③ 双签完成才可送使用单位</span>
              <span class="cr-d">服务端门控</span>
            </div>
          </div>
          <RuleNote class="mt8">送签时服务端校验双签完整性（含单人豁免标记），未完成返回 422 拦截；签字时间戳与设备指纹以服务端留痕为准。</RuleNote>
        </div>

        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-clipboard" /></svg>单据信息</div>
          <div class="kv-grid">
            <div class="kv"><span class="k">设备码</span><span class="v mono">{{ detail.elevatorCode }}</span></div>
            <div class="kv"><span class="k">梯号</span><span class="v">{{ detail.elevatorName }}</span></div>
            <div class="kv"><span class="k">维保类别</span><span class="v">{{ detail.workType }}</span></div>
            <div class="kv"><span class="k">主检</span><span class="v">{{ detail.workerName || '—' }}</span></div>
            <div class="kv"><span class="k">配合人</span><span class="v">{{ detail.assistantName || '—' }}</span></div>
            <div class="kv"><span class="k">签到时间</span><span class="v mono">{{ detail.checkinTime || '—' }}</span></div>
            <div class="kv"><span class="k">签退时间</span><span class="v mono">{{ detail.checkoutTime || '—' }}</span></div>
            <div class="kv"><span class="k">作业时长</span><span class="v mono">{{ detail.duration || '—' }}</span></div>
            <div class="kv"><span class="k">隐患码</span><span class="v mono">{{ (detail.problemCodes || []).join(' ') || 'S0' }}</span></div>
            <div class="kv"><span class="k">上报状态</span><span class="v">{{ reportLabel(detail.reportStatus) }}</span></div>
            <div class="kv"><span class="k">确认状态</span><span class="v">{{ statusLabel(detail.confirmStatus) }}</span></div>
            <div class="kv"><span class="k">使用单位</span><span class="v">{{ ctx.useUnitName || '—' }}</span></div>
            <div class="kv"><span class="k">作业地点</span><span class="v">{{ ctx.address || '—' }}</span></div>
            <div class="kv"><span class="k">记录版本</span><span class="v mono">{{ detail.version ? 'v' + detail.version : 'v1' }}</span></div>
            <div class="kv"><span class="k">创建时间</span><span class="v mono">{{ detail.createdAt }}</span></div>
          </div>
        </div>

        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-clipboard" /></svg>检查项明细
            <span class="sec-tag">{{ detail.itemTotal || 0 }} 项 · 异常 {{ detail.itemAbnormal || 0 }}</span>
          </div>
          <div v-if="detailLoading" class="dim f12">正在加载明细…</div>
          <ul v-else-if="items.length" class="rec-items">
            <li v-for="(it, i) in items" :key="i" :class="['rec-item', it.result === 'ABNORMAL' ? 'bad' : '']">
              <span class="ri-name">{{ it.name }}</span>
              <span class="ri-val">{{ itemNote(it) }}</span>
              <span :class="['tag', it.result === 'ABNORMAL' ? 'tag-signal' : it.result === 'NORMAL' ? 'tag-ok' : 'tag-dim']">{{ resultLabel(it.result) }}</span>
            </li>
          </ul>
          <div v-else class="dim f12">该记录无检查项明细（存量记录可能未冻结明细，以归档 PDF 为准）。</div>
        </div>

        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-eye" /></svg>现场照片
            <span class="sec-tag">{{ photos.length }} 张</span>
          </div>
          <div v-if="photos.length" class="rec-photos">
            <button v-for="(url, i) in photos" :key="url + i" class="rec-photo" type="button"
              :aria-label="'查看现场照片 ' + (i + 1)" @click="openPhoto(url)">
              <img :src="url" :alt="'现场照片 ' + (i + 1)" loading="lazy" />
            </button>
          </div>
          <div v-else class="dim f12">{{ detailLoading ? '正在加载照片…' : '该记录无现场照片（以归档 PDF 为准）' }}</div>
        </div>

        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-signature" /></svg>单据与签署</div>
          <div class="paper">
            <div class="p-head"><span>维保记录单</span><span>{{ detail.originalRecordId || detail.id }}</span></div>
            <h4 class="mt8">{{ detail.elevatorCode }} · {{ detail.elevatorName }}</h4>
            <div class="p-row mt8"><span>作业起止</span><b>{{ detail.checkinTime || '—' }} → {{ detail.checkoutTime || '—' }}</b></div>
            <div class="p-row"><span>作业时长</span><b>{{ detail.duration || '—' }}</b></div>
            <div class="p-row"><span>隐患码</span><b>{{ (detail.problemCodes || []).join(' ') || 'S0' }}</b></div>
            <template v-if="!detailLoading">
            <div class="grid2 mt8">
              <div :class="['sign-box', detail.workerSignatureUrl ? '' : 'todo']">
                <template v-if="detail.workerName || detail.workerSignatureUrl">
                  <div class="sb-name">{{ detail.workerName || '主检' }}</div>
                  <img v-if="detail.workerSignatureUrl" class="sig-img" :src="detail.workerSignatureUrl" alt="主检签字"
                    @click="openPhoto(detail.workerSignatureUrl)" />
                  <div class="sb-meta">主检 · {{ detail.workerSignatureUrl ? '已签字' : '待签字' }}</div>
                </template>
                <template v-else>待主检签字</template>
              </div>
              <div :class="['sign-box', detail.assistantSignatureUrl ? '' : 'todo']">
                <template v-if="detail.assistantName || detail.assistantSignatureUrl">
                  <div class="sb-name">{{ detail.assistantName || '配合人' }}</div>
                  <img v-if="detail.assistantSignatureUrl" class="sig-img" :src="detail.assistantSignatureUrl" alt="配合人签字"
                    @click="openPhoto(detail.assistantSignatureUrl)" />
                  <div class="sb-meta">配合人 · {{ detail.assistantSignatureUrl ? '已签字' : '待签字' }}</div>
                </template>
                <template v-else>待配合人签字</template>
              </div>
            </div>
            <div :class="['sign-box', 'mt8', detail.clientSignatureUrl ? '' : 'todo']">
              <template v-if="detail.clientSignerName || detail.clientSignatureUrl">
                <div class="sb-name">{{ detail.clientSignerName || '使用单位安全管理人员' }}</div>
                <img v-if="detail.clientSignatureUrl" class="sig-img" :src="detail.clientSignatureUrl" alt="使用单位签字"
                  @click="openPhoto(detail.clientSignatureUrl)" />
                <div class="sb-meta">使用单位安全管理人员 · {{ detail.clientSignatureUrl ? ('已签字 ' + (detail.clientSignedAt || '')).trim() : '待签字' }}</div>
              </template>
              <template v-else>待使用单位安全管理人员签字</template>
            </div>
            </template>
            <div v-else class="dim f12 mt8">正在加载签字留痕…</div>
            <div v-if="archiveInfo.hash" class="hash-line">
              <svg class="ic ic-sm" aria-hidden="true"><use href="#i-hash" /></svg>
              <span>SHA-256</span><code>{{ archiveInfo.hash }}</code>
              <span>· 归档 {{ archiveInfo.archivedAt }}</span>
              <a v-if="archiveInfo.url" class="link-btn" :href="archiveInfo.url" target="_blank" rel="noopener">下载归档 PDF</a>
            </div>
          </div>
        </div>
      </div>

      <template #footer>
        <template v-if="detail.confirmStatus === 'PENDING'">
          <PermButton code="record:review" label="复核通过" variant="primary" icon="check" @click="reviewPass" />
          <PermButton code="record:review" label="退回" variant="ghost" icon="refresh" @click="returnModal = true" />
          <PermButton code="record:sign_request" label="送使用单位签字" variant="primary" icon="signature" @click="signRequest" />
        </template>
        <template v-if="detail.confirmStatus === 'CONFIRMED'">
          <PermButton code="record:archive" label="归档投影" variant="ghost" icon="archive" @click="loadArchiveView(true)" />
          <PermButton v-if="detail.lifecycleStatus === 'ARCHIVED'" code="record:amend" label="发起修订" variant="ghost" icon="edit" @click="openAmend(detail)" />
        </template>
        <PermButton code="record:export" label="导出 PDF" variant="ghost" icon="download" @click="exportPdf(detail)" />
      </template>
    </Drawer>

    <!-- 照片 / 签字原图预览（抽屉之上，点遮罩或按钮关闭） -->
    <div v-if="previewUrl" class="rec-lightbox" role="dialog" aria-modal="true" aria-label="原图预览" @click="closePhoto">
      <img :src="previewUrl" alt="原图" />
      <button class="icon-btn rec-lb-x" type="button" aria-label="关闭原图" @click.stop="closePhoto">
        <svg class="ic" aria-hidden="true"><use href="#i-x" /></svg>
      </button>
    </div>

    <!-- 退回 -->
    <Modal :open="returnModal" title="退回记录单" :sub="detail.originalRecordId || detail.id" icon="refresh"
      note="record:review" confirm-label="确认退回" @close="returnModal = false" @confirm="confirmReturn">
      <FormField label="退回原因" required :error="returnErr">
        <textarea v-model="returnReason" class="ctl" placeholder="必填，说明整改项" />
      </FormField>
    </Modal>

    <!-- 发起修订 -->
    <Modal :open="amendModal" title="发起归档后修订" :sub="(amendTarget && (amendTarget.originalRecordId || amendTarget.id)) || ''" icon="edit"
      note="record:amend · 不触发上报（P-5）" :confirm-label="amendSaving ? '提交中…' : '发起修订'" @close="amendModal = false" @confirm="submitAmend">
      <FormField label="修订理由" required :error="amendErr">
        <textarea v-model="amendReason" class="ctl" data-testid="amend-reason" placeholder="必填，说明修订内容" />
      </FormField>
      <div v-if="amendLoading" class="dim">正在加载原记录明细…</div>
      <template v-else>
        <div v-for="(it, i) in amendItems" :key="i" class="sec mt12">
          <div class="sec-t">{{ it.name || ('检查项 ' + (i + 1)) }}</div>
          <FormField label="检查结果">
            <select v-model="it.result" class="ctl" :data-testid="i === 0 ? 'amend-item-result' : undefined">
              <option value="NORMAL">正常</option>
              <option value="ABNORMAL">异常</option>
            </select>
          </FormField>
          <FormField label="实测值">
            <div class="fl">
              <input v-model="it.value" class="ctl" :data-testid="i === 0 ? 'amend-item-value' : undefined" placeholder="读数" />
              <input v-model="it.valueUnit" class="ctl" placeholder="单位" />
            </div>
          </FormField>
          <FormField v-if="it.result === 'ABNORMAL'" label="隐患判定（S0—S7）" required>
            <select v-model="it.problemCode" class="ctl" :data-testid="i === 0 ? 'amend-item-problem' : undefined">
              <option value="">请选择隐患码</option>
              <option v-for="opt in problemOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
            </select>
          </FormField>
          <FormField label="异常说明" :required="it.result === 'ABNORMAL'">
            <textarea v-model="it.abnormalDesc" class="ctl" :data-testid="i === 0 ? 'amend-item-abnormal' : undefined" placeholder="异常项必填，如实描述" />
          </FormField>
        </div>
        <FormField label="待办事项">
          <textarea v-model="amendTodo" class="ctl" data-testid="amend-todo" placeholder="无待办可留空" />
        </FormField>
        <div class="sec mt12">
          <div class="sec-t">安全防护</div>
          <label class="perm-item"><input v-model="amendSafety.warning" type="checkbox" /> 已设置安全警示</label>
          <label class="perm-item"><input v-model="amendSafety.barrier" type="checkbox" data-testid="amend-safety-barrier" /> 已设置现场围蔽</label>
          <label class="perm-item"><input v-model="amendSafety.powerOff" type="checkbox" /> 已执行断电验电</label>
          <label class="perm-item"><input v-model="amendSafety.twoPerson" type="checkbox" /> 已落实双人作业</label>
        </div>
      </template>
      <RuleNote class="mt12">确认后生成新版本并重新进入复核／签署；旧版本与签名永久保留，不触发任何监管上报。</RuleNote>
    </Modal>
  </div>
</template>
<script setup>
import { ref, computed, onMounted } from 'vue'
import http from '../api/client'
import DataTable from '../ui/DataTable.vue'
import Drawer from '../ui/Drawer.vue'
import Modal from '../ui/Modal.vue'
import FormField from '../ui/FormField.vue'
import ViewHead from '../ui/ViewHead.vue'
import PageMeta from '../ui/PageMeta.vue'
import RuleNote from '../ui/RuleNote.vue'
import StatusTag from '../ui/StatusTag.vue'
import PermButton from '../ui/PermButton.vue'
import { useToast } from '../ui/stores/ui'
import { statusLabel, refuseCodeLabel } from '../ui/labels'
import { PROBLEM_CODES } from '../constants'

const toast = useToast()
const REPORT_LABEL = { DRAFT: '草稿', SUBMITTED: '已提交', REPORTING: '上报中', REPORTED: '已上报', RETRY_WAIT: '待重试', FAILED_MAX: '重试超限', FAILED: '上报失败', CONFIRMED: '已确认' }
function reportLabel(s) { return REPORT_LABEL[s] || statusLabel(s) }
function abnormalCodes(codes) { return (codes || []).filter((c) => c && c !== 'S0') }
const problemOptions = Object.entries(PROBLEM_CODES).map(([value, text]) => ({ value, label: `${value} ${text}` }))

const rows = ref([])
const loading = ref(false)
const cols = [
  { key: 'recordNo', label: '记录单号' },
  { key: 'elevatorCode', label: '设备码', sortable: true },
  { key: 'workType', label: '类别' },
  { key: 'signers', label: '主检 / 配合人' },
  { key: 'problemCodes', label: '隐患码' },
  { key: 'confirmStatus', label: '确认 / 上报', sortable: true },
  { key: 'createdAt', label: '创建时间', sortable: true }
]
const filters = [
  { key: 'confirmStatus', label: '确认', options: [{ value: 'PENDING', label: '待确认' }, { value: 'CONFIRMED', label: '已确认' }] },
  { key: 'reportStatus', label: '上报', options: Object.keys(REPORT_LABEL).map((c) => ({ value: c, label: REPORT_LABEL[c] })) },
  { key: 'lifecycleStatus', label: '拒签', options: [{ value: 'REFUSED_RETURNED', label: '已拒签退回' }] }
]

async function load() {
  loading.value = true
  try { rows.value = (await http.get('/admin/records', { params: { size: 200 } })).list || [] }
  catch (e) { toast.error(e.message || '加载记录单失败') } finally { loading.value = false }
}
onMounted(load)

const detailOpen = ref(false)
const detail = ref({})
const archiveInfo = ref({})
// items_json/photos_json 是 MEDIUMTEXT，不能塞进分页列表，所以必须二次拉取。
const detailLoading = ref(false)
const previewUrl = ref('')
const items = computed(() => detail.value.items || [])
const photos = computed(() => detail.value.photos || [])
const ctx = computed(() => detail.value.context || {})
const RESULT_LABEL = { NORMAL: '正常', ABNORMAL: '异常' }
function resultLabel(r) { return RESULT_LABEL[r] || (r || '—') }
// 与小程序 record-detail 同口径：异常说明 > 实测值文本 > 读数 > 未执行原因
function itemNote(it) {
  return it.abnormalDesc || it.valueText
    || (it.value != null && it.value !== '' ? '读数 ' + it.value + (it.valueUnit || '') : '')
    || it.skipReason || ''
}
function openPhoto(url) { if (url) previewUrl.value = url }
function closePhoto() { previewUrl.value = '' }
const dualOk = computed(() => Boolean(detail.value.workerName && detail.value.assistantName && detail.value.workerName !== detail.value.assistantName))

function openDetail(row) {
  detail.value = { ...row }
  archiveInfo.value = {}
  previewUrl.value = ''
  detailOpen.value = true
  loadDetail(row.id)
  if (row.confirmStatus === 'CONFIRMED') loadArchiveView(false)
}
async function loadDetail(id) {
  detailLoading.value = true
  try {
    const d = await http.get(`/admin/records/${id}`)
    // 竞态防护：抽屉已切到另一条记录时丢弃迟到的响应（否则会把 A 的明细显示在 B 上）
    if (!detailOpen.value || detail.value.id !== id) return
    detail.value = { ...detail.value, ...d }
  } catch (e) {
    // 降级不阻断复核：保留列表摘要 + PDF 导出入口，明确提示明细未加载
    toast.warn(e.message || '记录详情加载失败，暂仅显示摘要')
  } finally {
    detailLoading.value = false
  }
}
async function loadArchiveView(notify) {
  const id = detail.value.id
  try {
    const info = await http.get(`/work-orders/${id}/archive`)
    if (detail.value.id !== id) return
    archiveInfo.value = info
    if (notify) toast.success('归档投影已加载', archiveInfo.value.hash)
  } catch (e) {
    if (notify) toast.warn(e.message || '记录尚未归档')
  }
}

// 复核 / 退回 / 送签（服务端 lifecycle 门控，422 拦截透传）
async function reviewPass() {
  try {
    await http.post(`/work-orders/${detail.value.id}/review`, { pass: true })
    toast.success('复核通过 · 待使用单位签署',detail.value.originalRecordId)
    detailOpen.value = false
    await load()
  } catch (e) { toast.error(e.message || '复核失败') }
}
const returnModal = ref(false)
const returnReason = ref('')
const returnErr = ref('')
async function confirmReturn() {
  if (!returnReason.value.trim()) { returnErr.value = '退回原因必填'; return }
  returnErr.value = ''
  try {
    await http.post(`/work-orders/${detail.value.id}/review`, { pass: false, reason: returnReason.value })
    returnModal.value = false
    toast.success('已退回 · 待整改',detail.value.originalRecordId)
    detailOpen.value = false
    await load()
  } catch (e) { toast.error(e.message || '退回失败') }
}
async function signRequest() {
  try {
    await http.post(`/work-orders/${detail.value.id}/sign-request`, {})
    toast.success('已送使用单位签署',detail.value.originalRecordId)
    detailOpen.value = false
    await load()
  } catch (e) { toast.error(e.message || '双签未完成，禁止送使用单位') }
}

const amendModal = ref(false)
const amendTarget = ref(null)
const amendReason = ref('')
const amendErr = ref('')
const amendLoading = ref(false)
const amendReady = ref(false)
const amendSaving = ref(false)
let amendRequestSeq = 0
const amendItems = ref([])
const amendTodo = ref('')
const amendSafety = ref({})
async function openAmend(row) {
  const seq = ++amendRequestSeq
  amendTarget.value = row
  amendReason.value = ''
  amendErr.value = ''
  amendReady.value = false
  amendSaving.value = false
  amendItems.value = []
  amendTodo.value = row.todoDesc || ''
  amendSafety.value = { warning: false, barrier: false, powerOff: false, twoPerson: false }
  amendModal.value = true
  amendLoading.value = true
  try {
    const d = await http.get(`/admin/records/${row.id}`)
    if (seq !== amendRequestSeq || !amendTarget.value || amendTarget.value.id !== row.id) return
    amendItems.value = Array.isArray(d.items) ? d.items.map((it) => ({ ...it })) : []
    amendTodo.value = d.todoDesc || ''
    amendSafety.value = { warning: false, barrier: false, powerOff: false, twoPerson: false, ...(d.safety || {}) }
    amendReady.value = amendItems.value.length > 0
    if (!amendReady.value) amendErr.value = '原记录没有可修订的检查项，禁止生成空清单版本'
  } catch (e) {
    if (seq !== amendRequestSeq) return
    amendReady.value = false
    amendErr.value = e.message || '原记录明细加载失败，请关闭后重试'
  } finally {
    if (seq === amendRequestSeq) amendLoading.value = false
  }
}
async function submitAmend() {
  if (amendSaving.value) return
  if (amendLoading.value || !amendReady.value) { amendErr.value = '原记录明细尚未成功加载，禁止提交'; return }
  if (!amendReason.value.trim()) { amendErr.value = '修订理由必填'; return }
  const invalid = amendItems.value.find((it) => it.result === 'ABNORMAL'
    && (!String(it.abnormalDesc || '').trim() || !/^S[0-7]$/.test(String(it.problemCode || ''))))
  if (invalid) { amendErr.value = `检查项「${invalid.name || '未命名'}」异常说明和 S0—S7 隐患码必填`; return }
  amendErr.value = ''
  const seq = amendRequestSeq
  const target = amendTarget.value
  amendSaving.value = true
  try {
    await http.post(`/records/${target.id}/amend`, {
      reason: amendReason.value,
      items: amendItems.value,
      todoDesc: amendTodo.value,
      safety: amendSafety.value
    })
    if (seq !== amendRequestSeq) return
    amendModal.value = false
    toast.success('已生成修订版 · 待复核／签署（不重报）', target.originalRecordId)
    await load()
  } catch (e) { toast.error(e.message || '修订失败') } finally {
    if (seq === amendRequestSeq) amendSaving.value = false
  }
}

// PDF 导出
async function exportPdf(row) {
  if (!row || !row.id) { toast.warn('请先打开一条记录单'); return }
  try {
    const blob = await http.get(`/admin/records/${row.id}/export-pdf`, { responseType: 'blob', timeout: 120000 })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `record-${row.originalRecordId || row.id}.pdf`
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
  } catch (e) { toast.error(e.message || '导出失败') }
}
function exportCurrent() {
  if (!detail.value.id) { toast.warn('请先打开一条记录单再导出'); return }
  exportPdf(detail.value)
}
</script>