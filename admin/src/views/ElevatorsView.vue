<template>
  <div>
    <ViewHead title="电梯台账" sub="上次维保 = 最近一条已归档记录单作业日期（不变量 2）">
      <template #meta><PageMeta perm="elevator:view" :extra="[{ k: '台量', v: rows.length, icon: 'elevator', cls: 'count' }]" /></template>
    </ViewHead>
    <div class="mt16">
      <DataTable :columns="cols" :rows="rows" :loading="loading" clickable selectable
        :search-keys="['elevatorCode', 'elevatorName', 'projectName']" search-placeholder="搜索设备码 / 梯号 / 型号"
        :filters="[{ key: 'status', label: '状态', options: statusOptions }, { key: 'projectName', label: '项目', options: projOptions }]"
        :bulk-actions="[{ code: 'elevator:export', label: '批量导出', variant: 'ghost', icon: 'download' }]"
        empty-hint="当前数据范围内没有电梯。" @row-click="openArchive" @bulk="exportElev">
        <template #toolbar>
          <PermButton code="elevator:edit" label="新建电梯" variant="primary" icon="plus" @click="openCreate" />
          <PermButton code="elevator:export" label="导出台账" variant="ghost" icon="download" c-tip="导出需审批，按权限脱敏" @click="exportElev" />
        </template>
        <template #cell-elevatorCode="{ value }"><span class="mono">{{ value }}</span></template>
        <template #cell-elevatorName="{ row }"><b>{{ row.elevatorName }}</b><div class="cell-2">{{ row.model || row.category || '' }}</div></template>
        <template #cell-fld="{ row }"><span class="num">{{ row.stationsDoors || '—' }}</span></template>
        <template #cell-reg="{ row }"><span class="mono f12">{{ row.registrationCode || '—' }}</span></template>
        <template #cell-last="{ row }"><span class="mono f12">{{ row.lastMaintenanceDate || '—' }}</span></template>
        <template #cell-next="{ row }"><span class="mono f12">{{ row.nextMaintenanceDate || '—' }}</span></template>
        <template #cell-status="{ value }"><StatusTag :status="value" /></template>
        <template #cell-addr="{ row }"><span class="f12">{{ row.location || row.address || '—' }}</span></template>
        <template #row-actions="{ row }">
          <PermButton code="elevator:qr" label="设备码" variant="ghost" size="xs" icon="qr" @click="openQr(row)" />
          <PermButton code="elevator:edit" label="编辑" variant="ghost" size="xs" icon="edit" @click="openEditRow(row)" />
          <PermButton code="elevator:transfer" label="移装/停用/报废" variant="danger" size="xs" icon="alert" c-tip="经理 + 平台双人确认" @click="openChange(row)" />
        </template>
      </DataTable>
    </div>

    <!-- 变更单（双人确认，真实流程保留） -->
    <div class="card card-pad0 mt16">
      <div class="card-head">
        <h3><svg class="ic" aria-hidden="true"><use href="#i-exchange" /></svg>变更单（双人确认）</h3>
        <span class="ch-tag tag tag-dim">{{ changes.length }} 单</span>
      </div>
      <div class="card-body">
        <div v-if="changes.length" class="chk-list">
          <div v-for="c in changes" :key="c.id" class="chk-row">
            <svg class="ic" aria-hidden="true"><use href="#i-elevator" /></svg>
            <span class="cr-t">{{ c.elevatorId }} · {{ elevatorActionLabel(c.changeType) }}</span>
            <StatusTag :status="c.status" />
            <span class="cr-d">
              <PermButton v-if="c.status === 'PENDING'" code="elevator:transfer" label="确认" variant="danger" size="xs" icon="user-check" @click="openConfirm(c.id)" />
            </span>
          </div>
        </div>
        <div v-else class="dim f12">暂无变更单。</div>
      </div>
    </div>

    <!-- 电梯档案 + 一梯一档（原型 openElevDetail + 真实 profile/archive） -->
    <Drawer :open="archiveOpen" :title="(current.elevatorName || '') + ' · ' + (current.elevatorCode || '')" :sub="current.projectName || ''" note="elevator:view" @close="archiveOpen = false">
      <template #meta><ScopePill :scope="perspective.scope" :label="scopeLabel(perspective.scope)" /></template>
      <div class="sec">
        <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-elevator" /></svg>设备档案</div>
        <div class="kv-grid">
          <div class="kv"><span class="k">设备码</span><span class="v mono">{{ current.elevatorCode || '—' }}</span></div>
          <div class="kv"><span class="k">梯号</span><span class="v">{{ current.elevatorName || '—' }}</span></div>
          <div class="kv"><span class="k">项目</span><span class="v">{{ current.projectName || '—' }}</span></div>
          <div class="kv"><span class="k">层/站/门</span><span class="v">{{ (profile && profile.local && profile.local?.stationsDoors) || current.stationsDoors || '—' }}</span></div>
          <div class="kv"><span class="k">注册代码</span><span class="v mono">{{ (profile && profile.platform && profile.platform?.registrationCode) || current.registrationCode || '—' }}</span></div>
          <div class="kv"><span class="k">上次维保</span><span class="v mono">{{ current.lastMaintenanceDate || '—' }}</span></div>
          <div class="kv"><span class="k">下次到期</span><span class="v mono">{{ (profile && profile.local && profile.local?.nextMaintenanceDate) || current.nextMaintenanceDate || '—' }}</span></div>
          <div class="kv"><span class="k">状态</span><span class="v">{{ statusLabel(current.status) }}</span></div>
          <div class="kv"><span class="k">详细地址</span><span class="v">{{ (profile && profile.local && profile.local?.address) || current.location || '—' }}</span></div>
        </div>
      </div>
      <div class="sec">
        <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-qr" /></svg>设备码</div>
        <div class="row gap12">
          <div class="thumb" style="width:88px;height:88px"><svg class="ic ic-xl" aria-hidden="true"><use href="#i-qr" /></svg></div>
          <div class="fhelp">扫码进入档案 / 开工。生成 / 打印需 elevator:qr。</div>
        </div>
      </div>
      <template v-if="profile">
        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-file-text" /></svg>基础信息</div>
          <div class="kv-grid">
            <div class="kv"><span class="k">项目名称</span><span class="v">{{ profile.local?.projectName || '—' }}</span></div>
            <div class="kv"><span class="k">品种</span><span class="v">{{ profile.category || '—' }}</span></div>
            <div class="kv"><span class="k">品牌</span><span class="v">{{ profile.local?.brand || '—' }}</span></div>
            <div class="kv"><span class="k">制造单位</span><span class="v">{{ profile.local?.manufacturer || '—' }}</span></div>
            <div class="kv"><span class="k">产品编号</span><span class="v mono">{{ profile.local?.productNo || '—' }}</span></div>
            <div class="kv"><span class="k">驱动方式</span><span class="v">{{ profile.local?.driveMode || '—' }}</span></div>
            <div class="kv"><span class="k">额定载重</span><span class="v">{{ profile.local?.ratedLoad || '—' }}</span></div>
            <div class="kv"><span class="k">额定速度</span><span class="v">{{ profile.local?.ratedSpeed || '—' }}</span></div>
            <div class="kv"><span class="k">下次检验</span><span class="v mono">{{ profile.local?.nextCheckDate || '—' }}</span></div>
          </div>
        </div>
        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-cloud-upload" /></svg>平台同步字段</div>
          <div class="kv-grid">
            <div class="kv"><span class="k">设备代码</span><span class="v mono">{{ profile.platform?.deviceCode || '—' }}</span></div>
            <div class="kv"><span class="k">注册代码</span><span class="v mono">{{ profile.platform?.registrationCode || '—' }}</span></div>
            <div class="kv"><span class="k">出厂编号</span><span class="v mono">{{ profile.platform?.factoryNumber || '—' }}</span></div>
            <div class="kv"><span class="k">使用单位负责人</span><span class="v">{{ profile.platform?.elevatorAdminister || '—' }}</span></div>
            <div class="kv"><span class="k">应急电话</span><span class="v mono">{{ profile.platform?.emergencyPhone || '—' }}</span></div>
            <div class="kv"><span class="k">同步时间</span><span class="v mono">{{ profile.platform?.syncedAt || '—' }}</span></div>
          </div>
        </div>
      </template>
      <div class="sec">
        <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-clipboard" /></svg>维保记录（{{ elevRecords.length }}）</div>
        <template v-if="elevRecords.length">
          <div v-for="r in elevRecords" :key="r.id" class="kv">
            <span class="k mono">{{ r.recordNo || r.id }}</span>
            <span class="v">{{ r.workDate || '—' }} · <StatusTag :status="r.confirmStatus || r.status" /></span>
          </div>
        </template>
        <div v-else class="dim">暂无记录</div>
      </div>
      <div class="sec">
        <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-archive" /></svg>一梯一档归档目录</div>
        <RuleNote icon="lock">归档不可篡改（SHA-256 校验，留存 ≥4 年）；档案基础信息可在下方编辑 / 删除。</RuleNote>
        <div v-if="archive.length" class="tbl-wrap mt8">
          <table class="tbl">
            <thead><tr><th>序</th><th>类别</th><th>校验指纹</th><th>归档时间</th></tr></thead>
            <tbody>
              <tr v-for="a in archive" :key="a.id">
                <td>{{ a.seq }}</td><td>{{ archiveLabel(a.category) }}</td>
                <td><span class="mono f11" style="word-break:break-all">{{ a.sha256 }}</span></td>
                <td><span class="mono f12">{{ a.archivedAt }}</span></td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else class="fhelp mt8">该电梯暂无移交归档目录（完成安装移交后生成）。</p>
      </div>
      <template #footer>
        <PermButton code="elevator:qr" label="生成设备码" variant="ghost" icon="qr" @click="openQr(current)" />
        <PermButton code="elevator:edit" label="编辑" variant="ghost" icon="edit" @click="openEdit" />
        <PermButton code="elevator:transfer" label="移装/停用/报废" variant="danger" icon="alert" @click="openChange(current)" />
        <PermButton code="elevator:edit" label="删除电梯" variant="danger" icon="ban" @click="showDelete = true" />
      </template>
    </Drawer>

    <!-- 设备码 Modal（真实 QR PNG） -->
    <Modal :open="qrOpen" :title="'设备码 · ' + (qrTarget.elevatorCode || '')" :sub="(qrTarget.elevatorName || '') + ' · ' + (qrTarget.projectName || '')" icon="qr" note="elevator:qr"
      confirm-label="打印" @close="closeQr" @confirm="printQr">
      <div class="col center" style="align-items:center">
        <img v-if="qrUrl" :src="qrUrl" alt="设备二维码" style="width:160px;height:160px;border:1px solid var(--line-2);border-radius:var(--r-card)" />
        <div v-else class="thumb" style="width:160px;height:160px"><svg class="ic ic-xl" aria-hidden="true"><use href="#i-qr" /></svg></div>
        <div class="mono mt12">{{ qrTarget.elevatorCode }}</div>
        <div class="fhelp mt6">扫码进入档案 / 开工。打印需 elevator:qr（人员 / 主管 / 使用单位）。</div>
      </div>
    </Modal>

    <!-- 新建 / 编辑电梯 -->
    <Modal :open="showForm" :title="formMode === 'create' ? '新建电梯' : '编辑电梯基础信息'" :sub="(current.elevatorCode || '') + ' · 关键字段变更需审核'" icon="edit" note="elevator:edit"
      :confirm-label="formMode === 'create' ? '确认创建' : '提交'" @close="showForm = false" @confirm="submitForm">
      <Form ref="formRef" :rules="formRules" v-model="formModel" v-slot="{ errors }">
        <div class="form-grid">
          <FormField label="项目名称（小区）" required :error="errors.projectName"><input v-model="formModel.projectName" class="ctl" /></FormField>
          <FormField label="设备代码" :error="errors.elevatorCode"><input v-model="formModel.elevatorCode" class="ctl mono" /></FormField>
          <FormField label="品种" required :error="errors.category">
            <select v-model="formModel.category" class="ctl">
              <option value="">请选择</option>
              <option v-for="c in categories" :key="c" :value="c">{{ c }}</option>
            </select>
          </FormField>
          <FormField label="安装地址"><input v-model="formModel.location" class="ctl" /></FormField>
          <FormField label="品牌"><input v-model="formModel.brand" class="ctl" /></FormField>
          <FormField label="制造单位"><input v-model="formModel.manufacturer" class="ctl" /></FormField>
          <FormField label="产品编号"><input v-model="formModel.productNo" class="ctl mono" /></FormField>
          <FormField label="驱动方式"><input v-model="formModel.driveMode" class="ctl" /></FormField>
        </div>
      </Form>
      <RuleNote class="mt12">注册代码 / 层站门等关键字段变更提交后进入「待关键字段审核」，写审计。</RuleNote>
    </Modal>

    <!-- 删除确认 -->
    <Modal :open="showDelete" title="删除电梯" :sub="current.elevatorCode" icon="ban" danger require-confirm note="elevator:edit"
      confirm-text="我已知悉，确认删除" confirm-label="确认删除" @close="showDelete = false" @confirm="submitDelete">
      <RuleNote icon="alert">确认删除电梯 {{ current.elevatorCode }}？存在在途工单或系统管理员关联时将被拒绝。</RuleNote>
    </Modal>

    <!-- 发起变更（双人确认第一段） -->
    <Modal :open="showChange" title="移装 / 停用 / 报废" :sub="(chgTarget.elevatorCode || '') + ' · 双人确认（发起人 ≠ 确认人）'" icon="alert" danger require-confirm note="elevator:transfer"
      confirm-text="双人确认：发起人与确认人为不同账号，已知悉变更不可逆" confirm-label="发起变更" @close="showChange = false" @confirm="submitChange">
      <Form ref="chgForm" :rules="chgRules" v-model="chgModel" v-slot="{ errors }">
        <div class="form-grid">
          <FormField label="变更类型" required :error="errors.changeType">
            <select v-model="chgModel.changeType" class="ctl"><option value="TRANSFER">移装</option><option value="DISABLE">停用</option><option value="SCRAP">报废</option></select>
          </FormField>
          <FormField label="发起人（当前账号）"><input :value="auth.userName" class="ctl" disabled /></FormField>
          <FormField label="电梯编号" required :error="errors.elevatorId"><input v-model="chgModel.elevatorId" class="ctl mono" /></FormField>
        </div>
        <div class="form-grid one mt12">
          <FormField label="变更原因" required :error="errors.reason"><textarea v-model="chgModel.reason" class="ctl" placeholder="必填，说明移装/停用/报废原因" /></FormField>
        </div>
      </Form>
    </Modal>

    <!-- 确认变更（第二段，须不同于发起人） -->
    <Modal :open="showConfirm" title="确认变更（须不同于发起人）" icon="user-check" danger require-confirm note="elevator:transfer"
      confirm-text="确认人与发起人为不同账号，已知悉变更生效" confirm-label="确认变更" @close="showConfirm = false" @confirm="submitConfirm">
      <div class="form-grid one">
        <FormField label="确认人编号" required><input v-model="confirmerId" class="ctl mono" placeholder="须不同于发起人" /></FormField>
      </div>
      <RuleNote class="mt12">确认后变更生效并写审计；发起人 ≠ 确认人（biz_elevator_change 双人确认）。</RuleNote>
    </Modal>
  </div>
</template>
<script setup>
// 电梯台账（原型 Views.elevators + openElevDetail/doElevTransfer/doElevQr/doElevEdit 对齐）：
// 档案增删改查（elevator:edit）+ 一梯一档归档目录（只读 SHA-256）+ 移装/停用/报废双人确认（elevator:transfer）+ 真实设备码 PNG
import { ref, computed, onMounted } from 'vue'
import http from '../api/client'
import DataTable from '../ui/DataTable.vue'
import Drawer from '../ui/Drawer.vue'
import Modal from '../ui/Modal.vue'
import Form from '../ui/Form.vue'
import FormField from '../ui/FormField.vue'
import PermButton from '../ui/PermButton.vue'
import ViewHead from '../ui/ViewHead.vue'
import PageMeta from '../ui/PageMeta.vue'
import StatusTag from '../ui/StatusTag.vue'
import ScopePill from '../ui/ScopePill.vue'
import RuleNote from '../ui/RuleNote.vue'
import { useToast } from '../ui/stores/ui'
import { usePerspectiveStore } from '../ui/stores/perspective'
import { useAuthStore } from '../stores/auth'
import { elevatorActionLabel, statusLabel, scopeLabel } from '../ui/labels'
import { listChanges, initiateChange, confirmChange } from '../api/ledger'
import { createElevator, updateElevator, deleteElevator, elevatorProfile, elevatorArchive, elevatorQrPng } from '../api/archive'
const toast = useToast()
const perspective = usePerspectiveStore()
const auth = useAuthStore()
const rows = ref([])
const changes = ref([])
const loading = ref(false)
const categories = ['曳引与强制驱动电梯', '液压驱动电梯', '杂物电梯', '自动扶梯与自动人行道']
const cols = [
  { key: 'elevatorCode', label: '设备码', sortable: true },
  { key: 'projectName', label: '项目' },
  { key: 'elevatorName', label: '梯号' },
  { key: 'fld', label: '层/站/门', cls: 'num' },
  { key: 'reg', label: '注册代码' },
  { key: 'last', label: '上次维保', sortable: true },
  { key: 'next', label: '下次到期', sortable: true },
  { key: 'status', label: '状态', sortable: true },
  { key: 'addr', label: '详细地址' }
]
const statusOptions = computed(() => [...new Set(rows.value.map(r => r.status).filter(Boolean))].map(s => ({ value: s, label: statusLabel(s) })))
const projOptions = computed(() => [...new Set(rows.value.map(r => r.projectName).filter(Boolean))])
function archiveLabel(c) {
  return { CHECKLIST: '检查清单', RECTIFICATION: '整改清单', DOCS: '移交资料', PHOTOS: '逐项+定角照片', CONFIRMATION: '交接确认单', FIRST_ORDER: '首保工单关联', PENDING_DOCS: '待补件清单' }[c] || c
}
async function load() {
  loading.value = true
  try {
    const e = await http.get('/admin/elevators')
    rows.value = (Array.isArray(e) ? e : e.list || []).map(r => ({ ...r, last: r.lastMaintenanceDate || '', next: r.nextMaintenanceDate || '' }))
    changes.value = await listChanges()
  } catch (e) { toast.error(e.message || '加载失败') } finally { loading.value = false }
}
onMounted(load)
function exportElev() { toast.success('已导出 电梯台账（演示）· 按导出人权限脱敏', '写审计 导出') }

// 档案 + 一梯一档
const archiveOpen = ref(false)
const current = ref({})
const profile = ref(null)
const archive = ref([])
const records = ref([])
const recordsLoaded = ref(false)
async function ensureRecords() {
  if (recordsLoaded.value) return
  recordsLoaded.value = true
  try { const r = await http.get('/admin/records'); records.value = Array.isArray(r) ? r : r.list || [] } catch { records.value = [] }
}
const elevRecords = computed(() => records.value.filter(r => r.elevatorCode === current.value.elevatorCode))
async function openArchive(row) {
  current.value = row
  profile.value = null
  archive.value = []
  archiveOpen.value = true
  ensureRecords()
  try { profile.value = await elevatorProfile(row.id) } catch (e) { toast.error(e.message || '档案加载失败') }
  try { archive.value = await elevatorArchive(row.id) } catch { archive.value = [] }
}

// 设备码（真实 PNG）
const qrOpen = ref(false)
const qrTarget = ref({})
const qrUrl = ref('')
async function openQr(row) {
  if (!row || !row.id) return
  qrTarget.value = row
  qrUrl.value = ''
  qrOpen.value = true
  try {
    const blob = await elevatorQrPng(row.id)
    qrUrl.value = URL.createObjectURL(blob)
  } catch { qrUrl.value = '' }
}
function closeQr() { if (qrUrl.value) URL.revokeObjectURL(qrUrl.value); qrUrl.value = ''; qrOpen.value = false }
function printQr() {
  if (!qrUrl.value) { toast.warn('设备码生成失败，无法打印'); return }
  const w = window.open('', '_blank')
  if (w) { w.document.write('<img src="' + qrUrl.value + '" style="width:240px" onload="window.print()" />'); w.document.close() }
}

// 新建 / 编辑
const showForm = ref(false)
const formMode = ref('create')
const formRef = ref(null)
const formModel = ref({})
const formRules = { projectName: [(v) => (v ? '' : '项目名称必填')], category: [(v) => (v ? '' : '品种必填')] }
function blankForm() { return { projectName: '', elevatorCode: '', category: '', location: '', brand: '', manufacturer: '', productNo: '', driveMode: '' } }
function openCreate() { formMode.value = 'create'; current.value = {}; formModel.value = blankForm(); showForm.value = true }
async function openEditRow(row) {
  current.value = row
  profile.value = null
  try { profile.value = await elevatorProfile(row.id) } catch { profile.value = null }
  openEdit()
}
function openEdit() {
  formMode.value = 'edit'
  const p = profile.value || {}
  formModel.value = {
    projectName: (p.local && p.local.projectName) || current.value.projectName || '',
    elevatorCode: current.value.elevatorCode || '',
    category: p.category || '',
    location: (p.local && p.local.address) || '',
    brand: (p.local && p.local.brand) || '',
    manufacturer: (p.local && p.local.manufacturer) || '',
    productNo: (p.local && p.local.productNo) || '',
    driveMode: (p.local && p.local.driveMode) || ''
  }
  showForm.value = true
}
async function submitForm() {
  if (!formRef.value.validate()) return
  try {
    if (formMode.value === 'create') { await createElevator(formModel.value); toast.success('电梯已创建') }
    else { await updateElevator(current.value.id, formModel.value); toast.success('已提交 · 关键字段进入审核') }
    showForm.value = false
    await load()
    if (formMode.value === 'edit' && archiveOpen.value) openArchive(current.value)
  } catch (e) { toast.error(e.message || '保存失败') }
}

// 删除
const showDelete = ref(false)
async function submitDelete() {
  try { await deleteElevator(current.value.id); toast.success('电梯已删除'); showDelete.value = false; archiveOpen.value = false; await load() }
  catch (e) { toast.error(e.message || '删除失败') }
}

// 变更（双人确认）
const showChange = ref(false)
const showConfirm = ref(false)
const chgTarget = ref({})
const confirmerId = ref('')
const confirmId = ref('')
const chgForm = ref(null)
const chgModel = ref({ elevatorId: '', changeType: 'DISABLE', reason: '' })
const chgRules = {
  elevatorId: [(v) => (v ? '' : '电梯必填')],
  changeType: [(v) => (v ? '' : '类型必填')],
  reason: [(v) => (v ? '' : '原因必填')]
}
function openChange(row) {
  chgTarget.value = row || {}
  chgModel.value = { elevatorId: (row && row.id) || '', changeType: 'DISABLE', reason: '' }
  showChange.value = true
}
async function submitChange() {
  if (!chgForm.value.validate()) return
  try { await initiateChange(chgModel.value); toast.success('变更已发起，待双人确认'); showChange.value = false; load() }
  catch (e) { toast.error(e.message || '发起失败') }
}
function openConfirm(id) { confirmId.value = id; confirmerId.value = ''; showConfirm.value = true }
async function submitConfirm() {
  if (!confirmerId.value) { toast.warn('确认人编号必填'); return }
  try { await confirmChange(confirmId.value, { confirmerId: confirmerId.value }); toast.success('已确认 · 双人确认留痕'); showConfirm.value = false; load() }
  catch (e) { toast.error(e.message || '确认失败') }
}
</script>