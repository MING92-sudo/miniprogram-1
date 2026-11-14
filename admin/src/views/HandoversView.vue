<template>
  <div class="page">
    <div class="page-head">
      <h1>移交管理</h1>
      <p class="sub">GB/T 10060—2023 表1 · 74 项检查清单 / 1:1 整改 / 一梯一档（内部移交把关，不替代监督检验）</p>
    </div>

    <DataTable :columns="cols" :rows="rows" :loading="loading" clickable
      :search-keys="['handoverNo', 'elevatorCode', 'projectName']" :filters="statusFilters"
      @row-click="openDetail" />

    <Drawer :open="detailOpen" :title="'移交单 ' + (detail.handoverNo || '')" :meta="{ role: roleLabel(auth.role), perm: permLabel('handover:view') }" @close="detailOpen = false">
      <div v-if="detail.id" class="ho-detail">
        <div class="ho-head">
          <StatusTag :status="detail.status" :label="statusLabel(detail.status)" />
          <span class="ho-proj">{{ detail.projectName }}</span>
        </div>
        <dl class="ho-meta">
          <div><dt>设备码</dt><dd>{{ detail.elevatorCode || '—' }}</dd></div>
          <div><dt>品种</dt><dd>{{ detail.category || '—' }}</dd></div>
          <div><dt>安装地址</dt><dd>{{ detail.location || '—' }}</dd></div>
          <div><dt>安装竣工日期</dt><dd>{{ detail.installCompleteDate || '—' }}</dd></div>
          <div><dt>安装单位</dt><dd>{{ detail.installUnitName || '—' }}</dd></div>
          <div><dt>特殊类别</dt><dd>{{ detail.specialType || '—' }}</dd></div>
          <div><dt>接收工程师</dt><dd>{{ detail.receiverName || '—' }}</dd></div>
        </dl>

        <div class="tabs" role="tablist">
          <button v-for="t in tabs" :key="t.key" role="tab" :aria-selected="tab === t.key"
            :class="['tab', { active: tab === t.key }]" @click="switchTab(t.key)">{{ t.label }}</button>
        </div>

        <!-- 检查清单 -->
        <section v-if="tab === 'checklist'" class="tab-body">
          <div v-if="check.conclusion" class="conclusion" :data-conclusion="check.conclusion">
            <strong>{{ conclusionLabel(check.conclusion) }}</strong>
            <span class="counts">应检 {{ check.totalItems || 74 }} · 合格 {{ check.passCount || 0 }} · 不合格 {{ check.failCount || 0 }} · 不适用 {{ check.naCount || 0 }} · 重要项不合格 {{ check.importantFailCount || 0 }}</span>
          </div>
          <p v-if="!editable" class="readonly-hint">当前状态或权限下为只读（仅查验中且具现场查验权限可逐项填报）。</p>
          <div v-for="grp in groups" :key="grp.group" class="item-group">
            <h3 class="group-title">{{ grp.group }}</h3>
            <div v-for="it in grp.items" :key="it.id" class="item" :class="{ important: it.important }">
              <div class="item-head">
                <span class="item-no">{{ it.itemNo }}<span v-if="it.important" class="star">☆</span></span>
                <span class="item-name">{{ it.itemName }}</span>
                <StatusTag v-if="!editable && it.result" :status="it.result" :label="resultLabel(it.result)" />
              </div>
              <p class="item-req">{{ it.requirement }}</p>
              <div v-if="editable" class="item-controls">
                <select v-model="it.result" :aria-label="'第' + it.itemNo + '项结果'" class="inp result">
                  <option value="">未填</option>
                  <option value="PASS">√ 合格</option>
                  <option value="FAIL">× 不合格</option>
                  <option value="NA">— 不适用</option>
                </select>
                <input v-if="isTest(it.itemNo) && (it.result === 'PASS' || it.result === 'FAIL')"
                  v-model="it.measuredValue" class="inp" placeholder="实测值" :aria-label="'第' + it.itemNo + '项实测值'" />
                <input v-if="it.result === 'FAIL'" v-model="it.failDesc" class="inp wide" placeholder="不合格描述（现象与实测值）" />
                <select v-if="it.result === 'NA'" v-model="it.naReason" class="inp" :aria-label="'第' + it.itemNo + '项原因码'">
                  <option value="">选原因码</option>
                  <option v-for="na in naCodes" :key="na.code" :value="na.code">{{ na.code }} {{ na.label }}</option>
                </select>
                <input v-if="it.result === 'NA'" v-model="it.naNote" class="inp wide" placeholder="不适用说明（NA-05 须附见证人）" />
                <label v-if="it.result === 'FAIL' && (it.important || isTest(it.itemNo))" class="photo-btn">
                  上传照片<input type="file" accept="image/*" hidden @change="uploadPhoto(it, $event)" />
                </label>
                <span v-if="it.photoFileIds" class="photo-ok">已附照片</span>
              </div>
              <div v-else class="item-readonly">
                <span v-if="it.measuredValue">实测：{{ it.measuredValue }}</span>
                <span v-if="it.failDesc">描述：{{ it.failDesc }}</span>
                <span v-if="it.naReason">原因：{{ it.naReason }} {{ it.naNote }}</span>
              </div>
            </div>
          </div>
          <div v-if="editable" class="conditional-box">
            <h4>有条件移交例外通道（7.3，仅一般项 1—3 不合格时生效）</h4>
            <label class="chk"><input type="checkbox" v-model="conditional.siteLimited" /> 现场受限无法修复</label>
            <label class="chk"><input type="checkbox" v-model="conditional.tripartiteAgreed" /> 安装/维保/使用三方协商确认</label>
            <input v-model="conditional.controlMeasures" class="inp wide" placeholder="控制措施（必填，缺一即按整改处理）" />
          </div>
        </section>

        <!-- 整改清单 -->
        <section v-else-if="tab === 'rectification'" class="tab-body">
          <p v-if="!rects.length" class="readonly-hint">暂无不合格项整改记录（提交查验判定为整改/不合格时按检查项序号 1:1 自动生成）。</p>
          <div v-for="r in rects" :key="r.id" class="rect">
            <div class="rect-head">
              <span class="item-no">第 {{ r.itemNo }} 项</span>
              <span class="rect-type">{{ r.itemType }}</span>
              <StatusTag :status="r.recheckResult || 'PENDING'" :label="r.recheckResult === 'PASS' ? '复验通过' : r.recheckResult === 'FAIL' ? '复验不通过' : '待复验'" />
            </div>
            <dl class="rect-meta">
              <div><dt>责任方</dt><dd>{{ r.respParty || '—' }}</dd></div>
              <div><dt>期限</dt><dd>{{ r.dueDate || '—' }}</dd></div>
              <div><dt>整改措施</dt><dd>{{ r.rectMeasure || '—' }}</dd></div>
              <div><dt>整改人</dt><dd>{{ r.rectifierName || '—' }}</dd></div>
            </dl>
            <div class="rect-actions">
              <PermButton :role="auth.role" code="handover:rectify" label="上传整改" variant="primary" @click="openRectify(r)" @blocked="onBlocked" />
              <PermButton :role="auth.role" code="handover:review" label="逐条复验" @click="openRecheck(r)" @blocked="onBlocked" />
            </div>
          </div>
        </section>

        <!-- 归档目录 -->
        <section v-else class="tab-body">
          <div class="archive-bar">
            <PermButton :role="auth.role" code="handover:view" label="生成/刷新归档目录" @click="loadArchive" @blocked="onBlocked" />
            <span class="readonly-hint">一梯一档 · 校验指纹 · 留存 ≥4 年</span>
          </div>
          <table v-if="archive.length" class="archive-table">
            <thead><tr><th>序</th><th>类别</th><th>校验指纹</th><th>归档时间</th></tr></thead>
            <tbody>
              <tr v-for="a in archive" :key="a.id">
                <td>{{ a.seq }}</td><td>{{ archiveLabel(a.category) }}</td>
                <td class="sha">{{ a.sha256 }}</td><td>{{ a.archivedAt }}</td>
              </tr>
            </tbody>
          </table>
          <p v-else class="readonly-hint">尚未生成归档目录。</p>
        </section>
      </div>

      <template #footer>
        <PermButton :role="auth.role" code="handover:review" label="☆项 NA 审批" @click="naModal = true" @blocked="onBlocked" />
        <PermButton :role="auth.role" code="handover:export" label="导出检查清单" @click="doExport('CHECK')" @blocked="onBlocked" />
        <PermButton :role="auth.role" code="handover:export" label="导出整改清单" @click="doExport('RECT')" @blocked="onBlocked" />
        <PermButton :role="auth.role" code="handover:export" label="导出归档目录" @click="doExport('INDEX')" @blocked="onBlocked" />
        <PermButton v-if="editable" :role="auth.role" code="handover:inspect" label="提交查验" variant="primary" @click="submit" @blocked="onBlocked" />
      </template>
    </Drawer>

    <!-- 上传整改 -->
    <Modal :open="rectifyModal" title="上传整改（安装部）" confirm-label="保存" @close="rectifyModal = false" @confirm="confirmRectify">
      <FormField label="整改措施" required><textarea v-model="rectForm.rectMeasure" class="inp wide" rows="3" /></FormField>
      <FormField label="整改后照片">
        <label class="photo-btn">选择照片<input type="file" accept="image/*" hidden @change="uploadRectPhoto" /></label>
        <span v-if="rectForm.afterPhotoFileId" class="photo-ok">已上传</span>
      </FormField>
      <FormField label="整改人"><input v-model="rectForm.rectifierName" class="inp wide" /></FormField>
    </Modal>

    <!-- 逐条复验 -->
    <Modal :open="recheckModal" title="逐条复验（原查验工程师）" confirm-label="提交复验" @close="recheckModal = false" @confirm="confirmRecheck">
      <FormField label="复验结果" required>
        <select v-model="recheckForm.result" class="inp wide">
          <option value="PASS">合格</option>
          <option value="FAIL">不通过</option>
        </select>
      </FormField>
      <FormField label="复验人"><input v-model="recheckForm.recheckerName" class="inp wide" /></FormField>
      <p class="readonly-hint">全部整改项复验通过后才回「待确认」。</p>
    </Modal>

    <!-- NA 审批 -->
    <Modal :open="naModal" title="☆项不适用审批（维保部经理）" confirm-label="批准" @close="naModal = false" @confirm="confirmNa">
      <FormField label="审批人"><input v-model="naForm.approverName" class="inp wide" /></FormField>
      <p class="readonly-hint">批准后方可提交含 ☆项不适用的查验清单（记录审批留痕）。</p>
    </Modal>
  </div>
</template>

<script setup>
// C-12 移交管理（docs/15）：列表 + 详情抽屉三 Tab（74 项检查清单逐项留证 / 1:1 整改逐条复验 / 一梯一档归档）。
// 判定结论由后端计算冻结，前端不改写；门控走 permission-v2 三态（后端 MultiRoleGate 唯一权威）。
import { ref, reactive, computed, onMounted } from 'vue'
import DataTable from '../ui/DataTable.vue'
import Drawer from '../ui/Drawer.vue'
import Modal from '../ui/Modal.vue'
import StatusTag from '../ui/StatusTag.vue'
import PermButton from '../ui/PermButton.vue'
import FormField from '../ui/FormField.vue'
import { useToast } from '../ui/stores/ui'
import { useAuthStore } from '../stores/auth'
import { uploadFile } from '../api/upload'
import { roleLabel, permLabel, statusLabel } from '../ui/labels'
import * as api from '../api/handover'

const auth = useAuthStore()
const toast = useToast()

const rows = ref([])
const loading = ref(false)
const cols = [
  { key: 'handoverNo', label: '移交单号', sortable: true },
  { key: 'elevatorCode', label: '设备码' },
  { key: 'projectName', label: '项目名称' },
  { key: 'status', label: '状态' },
  { key: 'installUnitName', label: '安装单位' },
  { key: 'createdAt', label: '创建' }
]
const statusFilters = [{ key: 'status', label: '状态', options: ['DOC_SUBMITTING', 'INSPECTING', 'RECTIFYING', 'PENDING_CONFIRM', 'ARCHIVED'].map((c) => ({ value: c, label: statusLabel(c) })) }]

const detailOpen = ref(false)
const detail = ref({})
const tab = ref('checklist')
const tabs = [{ key: 'checklist', label: '检查清单' }, { key: 'rectification', label: '整改清单' }, { key: 'archive', label: '归档目录' }]
const check = ref({})
const items = ref([])
const rects = ref([])
const archive = ref([])
const conditional = reactive({ siteLimited: false, tripartiteAgreed: false, controlMeasures: '' })

const naCodes = [
  { code: 'NA-01', label: '本梯无此配置/部件' },
  { code: 'NA-02', label: '品种/型式不涉及' },
  { code: 'NA-03', label: '建筑无此要求' },
  { code: 'NA-04', label: '特殊类别不涉及' },
  { code: 'NA-05', label: '现场条件限制无法检测' }
]

const editable = computed(() => detail.value.status === 'INSPECTING' && auth.can('handover:inspect'))
const groups = computed(() => {
  const map = new Map()
  for (const it of items.value) {
    const g = it.itemGroup || 'G00'
    if (!map.has(g)) map.set(g, [])
    map.get(g).push(it)
  }
  return [...map.entries()].sort((a, b) => a[0].localeCompare(b[0])).map(([group, its]) => ({ group, items: its }))
})

function isTest(no) { return no >= 57 && no <= 74 }
function resultLabel(r) { return { PASS: '合格', FAIL: '不合格', NA: '不适用' }[r] || r }
function conclusionLabel(c) {
  return { QUALIFIED: '合格，准予移交', RECTIFY: '一般项不合格，须整改复检', DISQUALIFIED: '判定不合格，全面修复后重新验收', CONDITIONAL: '有条件移交（7.3 例外通道）' }[c] || c
}
function archiveLabel(c) {
  return { CHECKLIST: '检查清单', RECTIFICATION: '整改清单', DOCS: '移交资料 D1—D20', PHOTOS: '逐项+定角照片', CONFIRMATION: '交接确认单', FIRST_ORDER: '首保工单关联', PENDING_DOCS: '待补件清单' }[c] || c
}
function onBlocked(code) { toast.warn('需审批或前置未满足：' + code) }

async function loadList() {
  loading.value = true
  try { rows.value = (await api.listHandovers()).list || [] } catch (e) { toast.error(e.message || '加载移交单失败') } finally { loading.value = false }
}
onMounted(loadList)

async function openDetail(row) {
  detail.value = row
  detailOpen.value = true
  tab.value = 'checklist'
  archive.value = []
  await Promise.all([loadChecklist(), loadRects()])
}
function switchTab(key) {
  tab.value = key
  if (key === 'archive' && !archive.value.length) loadArchive()
}
async function loadChecklist() {
  try {
    const data = await api.getChecklist(detail.value.id)
    check.value = data.check || {}
    items.value = (data.items || []).map((it) => ({ ...it }))
  } catch (e) { toast.error(e.message || '加载检查清单失败') }
}
async function loadRects() {
  try { rects.value = await api.getRectifications(detail.value.id) } catch (e) { rects.value = [] }
}
async function loadArchive() {
  try { archive.value = await api.getArchive(detail.value.id) } catch (e) { toast.error(e.message || '加载归档目录失败') }
}

async function uploadPhoto(it, ev) {
  const file = ev.target.files && ev.target.files[0]
  if (!file) return
  try {
    const res = await uploadFile(file)
    it.photoFileIds = it.photoFileIds ? it.photoFileIds + ',' + res.fileId : res.fileId
    toast.success('照片已上传')
  } catch (e) { toast.error(e.message || '照片上传失败') } finally { ev.target.value = '' }
}

async function submit() {
  const payload = items.value.map((it) => ({
    itemNo: it.itemNo, result: it.result || null, measuredValue: it.measuredValue || null,
    failDesc: it.failDesc || null, naReason: it.naReason || null, naNote: it.naNote || null, photoFileIds: it.photoFileIds || null
  }))
  try {
    const order = await api.submitChecklist(detail.value.id, { items: payload, conditional: { ...conditional } })
    detail.value = { ...detail.value, status: order.status }
    toast.success('查验已提交：' + conclusionLabel(check.value.conclusion))
    await Promise.all([loadChecklist(), loadRects(), loadList()])
  } catch (e) { toast.error(e.message || '提交查验失败') }
}

// 整改
const rectifyModal = ref(false)
const currentRect = ref(null)
const rectForm = reactive({ rectMeasure: '', afterPhotoFileId: '', rectifierName: '' })
function openRectify(r) {
  currentRect.value = r
  rectForm.rectMeasure = r.rectMeasure || ''
  rectForm.afterPhotoFileId = r.afterPhotoFileId || ''
  rectForm.rectifierName = auth.userName
  rectifyModal.value = true
}
async function uploadRectPhoto(ev) {
  const file = ev.target.files && ev.target.files[0]
  if (!file) return
  try { const res = await uploadFile(file); rectForm.afterPhotoFileId = res.fileId; toast.success('照片已上传') }
  catch (e) { toast.error(e.message || '照片上传失败') } finally { ev.target.value = '' }
}
async function confirmRectify() {
  try {
    await api.rectifyItem(detail.value.id, currentRect.value.id, { ...rectForm })
    rectifyModal.value = false
    toast.success('整改已上传')
    await loadRects()
  } catch (e) { toast.error(e.message || '上传整改失败') }
}

// 复验
const recheckModal = ref(false)
const recheckForm = reactive({ result: 'PASS', recheckerName: '' })
function openRecheck(r) {
  currentRect.value = r
  recheckForm.result = 'PASS'
  recheckForm.recheckerName = auth.userName
  recheckModal.value = true
}
async function confirmRecheck() {
  try {
    const order = await api.recheckItem(detail.value.id, currentRect.value.id, { ...recheckForm, recheckerId: auth.user?.id })
    recheckModal.value = false
    detail.value = { ...detail.value, status: order.status }
    toast.success('复验已提交')
    await Promise.all([loadRects(), loadList()])
  } catch (e) { toast.error(e.message || '复验失败') }
}

// NA 审批
const naModal = ref(false)
const naForm = reactive({ approverName: '' })
async function confirmNa() {
  try {
    await api.naApprove(detail.value.id, { approverId: auth.user?.id, approverName: naForm.approverName || auth.userName })
    naModal.value = false
    toast.success('NA 审批已记录')
  } catch (e) { toast.error(e.message || 'NA 审批失败') }
}

// PDF 导出（blob 下载）
async function doExport(form) {
  try {
    const blob = await api.exportPdf(detail.value.id, form)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `handover-${detail.value.handoverNo || detail.value.id}-${form}.pdf`
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
  } catch (e) { toast.error(e.message || '导出失败') }
}
</script>

<style scoped>
.page-head { margin-bottom: 16px; }
.page-head h1 { font-size: 20px; }
.sub { color: var(--muted); font-size: 12px; margin-top: 4px; }
.ho-detail { display: flex; flex-direction: column; gap: 14px; }
.ho-head { display: flex; align-items: center; gap: 10px; }
.ho-proj { font-weight: 600; font-size: 14px; }
.ho-meta { display: grid; grid-template-columns: 1fr 1fr; gap: 8px 16px; margin: 0; }
.ho-meta div { display: flex; gap: 6px; font-size: 12px; }
.ho-meta dt { color: var(--muted); min-width: 84px; }
.ho-meta dd { margin: 0; color: var(--text); }
.tabs { display: flex; gap: 6px; border-bottom: 1px solid var(--line-2); }
.tab { background: none; border: none; color: var(--muted); padding: 8px 12px; cursor: pointer; font-size: 13px; border-bottom: 2px solid transparent; }
.tab.active { color: var(--amber); border-bottom-color: var(--amber); font-weight: 600; }
.tab-body { display: flex; flex-direction: column; gap: 12px; }
.readonly-hint { color: var(--muted); font-size: 12px; }
.conclusion { padding: 10px 12px; border: 1px solid var(--line-2); border-radius: 8px; background: var(--panel-2); display: flex; flex-direction: column; gap: 4px; }
.conclusion[data-conclusion="DISQUALIFIED"] { border-color: var(--signal); }
.conclusion[data-conclusion="QUALIFIED"] { border-color: var(--ok); }
.conclusion[data-conclusion="CONDITIONAL"] { border-color: var(--amber); }
.counts { color: var(--muted); font-size: 12px; font-family: var(--font-mono); }
.item-group { display: flex; flex-direction: column; gap: 8px; }
.group-title { font-size: 12px; color: var(--muted); font-family: var(--font-mono); letter-spacing: .04em; margin: 6px 0 0; }
.item { border: 1px solid var(--line); border-radius: 8px; padding: 10px 12px; }
.item.important { border-left: 3px solid var(--amber); }
.item-head { display: flex; align-items: center; gap: 8px; }
.item-no { font-family: var(--font-mono); font-size: 12px; color: var(--muted); }
.star { color: var(--amber); margin-left: 2px; }
.item-name { font-size: 13px; font-weight: 600; flex: 1; }
.item-req { color: var(--muted); font-size: 12px; margin: 6px 0; }
.item-controls { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.item-readonly { display: flex; flex-wrap: wrap; gap: 12px; color: var(--muted); font-size: 12px; }
.inp { background: var(--panel-2); border: 1px solid var(--line-2); color: var(--text); padding: 6px 10px; border-radius: 6px; font-size: 12px; }
.inp.wide { flex: 1; min-width: 180px; }
.inp.result { min-width: 110px; }
.photo-btn { display: inline-block; padding: 6px 10px; border: 1px dashed var(--line-2); border-radius: 6px; font-size: 12px; color: var(--amber); cursor: pointer; }
.photo-ok { color: var(--ok); font-size: 12px; }
.conditional-box { border: 1px solid var(--line-2); border-radius: 8px; padding: 12px; display: flex; flex-direction: column; gap: 8px; }
.conditional-box h4 { font-size: 12px; color: var(--muted); margin: 0; }
.chk { display: flex; align-items: center; gap: 6px; font-size: 12px; }
.rect { border: 1px solid var(--line); border-radius: 8px; padding: 10px 12px; display: flex; flex-direction: column; gap: 8px; }
.rect-head { display: flex; align-items: center; gap: 8px; }
.rect-type { font-size: 12px; color: var(--muted); flex: 1; }
.rect-meta { display: grid; grid-template-columns: 1fr 1fr; gap: 6px 16px; margin: 0; }
.rect-meta div { display: flex; gap: 6px; font-size: 12px; }
.rect-meta dt { color: var(--muted); min-width: 64px; }
.rect-meta dd { margin: 0; }
.rect-actions { display: flex; gap: 8px; }
.archive-bar { display: flex; align-items: center; gap: 12px; }
.archive-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.archive-table th { text-align: left; padding: 8px; background: var(--panel-2); color: var(--muted); border-bottom: 1px solid var(--line-2); }
.archive-table td { padding: 8px; border-bottom: 1px solid var(--line); }
.sha { font-family: var(--font-mono); font-size: 11px; word-break: break-all; }
</style>