<template>
  <div>
    <ViewHead title="电梯台账" sub="上次维保 = 最近一条已归档记录单作业日期（不变量 2）">
      <template #meta><PageMeta perm="elevator:view" :extra="[{ k: '台量', v: rows.length, icon: 'elevator', cls: 'count' }]" /></template>
    </ViewHead>
    <div class="mt16">
      <DataTable :columns="cols" :rows="rows" :loading="loading" clickable selectable
        :search-keys="['elevatorCode', 'deviceCode', 'registrationCode', 'insideNumber', 'projectName', 'regionName', 'useUnitName']" search-placeholder="搜索小区名称 / 内部编号 / 注册代码 / 电梯编码 / 使用单位"
        :filters="[{ key: 'status', label: '状态', options: statusOptions }, { key: 'projectName', label: '项目', options: projOptions }]"
        :bulk-actions="[{ code: 'elevator:export', label: '批量导出', variant: 'ghost', icon: 'download' }]"
        empty-hint="当前数据范围内没有电梯。" @row-click="openArchive" @bulk="exportElev">
        <template #toolbar>
          <PermButton code="elevator:edit" label="新建设备信息档案" variant="primary" icon="plus" @click="openCreate" />
          <PermButton code="elevator:export" label="导出台账" variant="ghost" icon="download" c-tip="按导出人数据范围导出 CSV" @click="exportElev" />
        </template>
        <template #cell-elevatorCode="{ row }">
          <span class="mono">{{ row.elevatorCode || '—' }}</span>
          <span v-if="missingOf(row).length" class="tag tag-amber f11" style="margin-left:6px"
            :title="'缺必传项：' + missingOf(row).join('、')">待补全</span>
        </template>
        <template #cell-insideNumber="{ value }"><span class="mono f12">{{ value || '—' }}</span></template>
        <template #cell-regionName="{ value }"><span class="f12">{{ value || '重庆市（全市）' }}</span></template>
        <template #cell-registrationCode="{ value }"><span class="mono f12">{{ value || '—' }}</span></template>
        <template #row-actions="{ row }">
          <PermButton code="elevator:view" label="电梯档案" variant="ghost" size="xs" icon="file-text" @click="openDetail(row)" />
          <PermButton code="elevator:qr" label="设备码" variant="ghost" size="xs" icon="qr" @click="openQr(row)" />
          <PermButton code="elevator:edit" label="编辑" variant="ghost" size="xs" icon="edit" @click="openEditRow(row)" />
          <PermButton v-if="canGeoEdit" code="elevator:edit" label="定位" variant="ghost" size="xs" icon="map-pin" @click="openGeo(row)" />
          <PermButton v-if="row.status === 'INACTIVE'" code="elevator:edit" label="启用" variant="ghost" size="xs" icon="check" @click="toggleSuspend(row)" />
          <PermButton v-else code="elevator:edit" label="停保" variant="ghost" size="xs" icon="ban" c-tip="停保后不派单、小程序不可见" @click="toggleSuspend(row)" />
          <PermButton code="elevator:edit" label="删除电梯" variant="danger" size="xs" icon="ban" @click="askDelete(row)" />
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
        <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-elevator" /></svg>设备信息档案</div>
        <RuleNote v-if="missingOf(current).length" icon="alert" class="mb8">
          上传维保记录的必传项缺失：{{ missingOf(current).join('、') }}（补齐后方可上传维保记录）
        </RuleNote>
        <div class="kv-grid">
          <div v-for="f in DEVICE_FIELDS" :key="f.key" class="kv">
            <span class="k">{{ f.label }}<em v-if="f.required" class="req-mark">必传</em></span>
            <span class="v" :class="f.mono ? 'mono' : ''">{{ devInfo[f.key] || '—' }}</span>
          </div>
          <div class="kv"><span class="k">上次维保</span><span class="v mono">{{ current.lastMaintenanceDate || '—' }}</span></div>
          <div class="kv"><span class="k">下次到期</span><span class="v mono">{{ current.nextMaintenanceDate || '—' }}</span></div>
          <div class="kv"><span class="k">状态</span><span class="v">{{ statusLabel(current.status) }}</span></div>
          <div class="kv"><span class="k">详细地址</span><span class="v">{{ current.location || '—' }}</span></div>
          <div class="kv"><span class="k">经度（GCJ-02）</span><span class="v mono">{{ current.lng || '—' }}</span></div>
          <div class="kv"><span class="k">纬度（GCJ-02）</span><span class="v mono">{{ current.lat || '—' }}</span></div>
          <div class="kv"><span class="k">定位状态</span><span class="v">{{ current.geoStatus === 'OK' ? '已定位' : '待补 / 待重新采集' }}</span></div>
          <div class="kv"><span class="k">主维保（历史绑定）</span><span class="v">{{ current.workerName || '—' }}</span></div>
          <div class="kv"><span class="k">配合人员（历史绑定）</span><span class="v">{{ current.assistantName || '—' }}</span></div>
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
            <div class="kv"><span class="k">出厂编号</span><span class="v mono">{{ profile.local?.factoryNumber || '—' }}</span></div>
            <div class="kv"><span class="k">驱动方式</span><span class="v">{{ profile.local?.driveMode || '—' }}</span></div>
            <div class="kv"><span class="k">额定载重</span><span class="v">{{ profile.local?.ratedLoad || '—' }}</span></div>
            <div class="kv"><span class="k">额定速度</span><span class="v">{{ profile.local?.ratedSpeed || '—' }}</span></div>
          </div>
        </div>
        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-cloud-upload" /></svg>平台同步信息</div>
          <div class="kv-grid">
            <div class="kv"><span class="k">使用单位安全管理员</span><span class="v">{{ profile.platform?.elevatorAdminister || '—' }}</span></div>
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
        <PermButton v-if="canGeoEdit" code="elevator:edit" label="修改定位" variant="ghost" icon="map-pin" @click="openGeo(current)" />
        <PermButton v-if="current.status === 'INACTIVE'" code="elevator:edit" label="启用" variant="ghost" icon="check" @click="toggleSuspend(current)" />
        <PermButton v-else code="elevator:edit" label="停保" variant="ghost" icon="ban" @click="toggleSuspend(current)" />
        <PermButton code="elevator:edit" label="删除电梯" variant="danger" icon="ban" @click="showDelete = true" />
      </template>
    </Drawer>

    <ElevatorGeoModal :open="geoOpen" :row="current" @close="geoOpen = false" @saved="geoSaved" />

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

    <!-- 新建 / 编辑：设备信息档案（8 字段；前 5 项为上传维保记录的必传项） -->
    <Modal :open="showForm" :title="formMode === 'create' ? '新建设备信息档案' : '编辑设备信息档案'"
      :sub="formMode === 'create' ? '前 5 项为上传维保记录的必传项' : (current.elevatorCode || '') + ' · 必传项不可清空'"
      icon="edit" note="elevator:edit" :confirm-label="formMode === 'create' ? '确认创建' : '提交'"
      @close="showForm = false" @confirm="submitForm">
      <Form ref="formRef" :rules="formRules" v-model="formModel" v-slot="{ errors }">
        <div class="form-grid">
          <FormField v-for="f in DEVICE_FIELDS" :key="f.key" :label="f.label" :required="f.required" :error="errors[f.key]">
            <select v-if="f.type === 'region'" v-model="formModel.regionId" class="ctl">
              <option value="">重庆市（全市 · 不限区域）</option>
              <option v-for="r in regions" :key="r.id" :value="r.id">{{ r.name }}</option>
            </select>
            <input v-else-if="f.type === 'date'" v-model="formModel.nextCheckDate" class="ctl mono" type="date" />
            <input v-else v-model="formModel[f.key]" class="ctl" :class="f.mono ? 'mono' : ''" :placeholder="f.placeholder || ''" />
          </FormField>
          <FormField label="品种（选填，影响检查项模板）">
            <select v-model="formModel.category" class="ctl">
              <option value="">请选择</option>
              <option v-for="c in categories" :key="c" :value="c">{{ c }}</option>
            </select>
          </FormField>
          <FormField label="使用单位（选填，保存后 2.7 可按平台主体自动挂接）">
            <select v-model="formModel.useUnitId" class="ctl">
              <option value="">未关联</option>
              <option v-for="u in useUnitList" :key="u.id" :value="u.id">{{ u.unitName }}</option>
            </select>
          </FormField>
        </div>
        <div class="sec-t mt16">其他信息（选填，不参与上传维保记录校验）</div>
        <div class="form-grid">
          <FormField label="安装地址"><input v-model="formModel.location" class="ctl" /></FormField>
          <FormField label="品牌"><input v-model="formModel.brand" class="ctl" /></FormField>
          <FormField label="制造单位"><input v-model="formModel.manufacturer" class="ctl" /></FormField>
          <FormField label="驱动方式"><input v-model="formModel.driveMode" class="ctl" /></FormField>
        </div>
        <div v-if="formMode === 'create'" class="sec-t mt16">排期基线（建档即排全年计划）</div>
        <div v-if="formMode === 'create'" class="form-grid">
          <FormField label="上次维保日期（存量选填）">
            <input v-model="formModel.lastMaintenanceAt" class="ctl mono" type="date" />
          </FormField>
        </div>
        <RuleNote v-if="formMode === 'create'" class="mt8" icon="calendar">留空则以建档日期为基线：首保 = 基线 + 15 天，此后按半月 / 季 / 半年 / 年累加排满一年并进入维保日历，到期前一日自动派单；存量电梯请填实际上次维保日期。建档后基线由签退自动滚动，不可手改。</RuleNote>
        <RuleNote class="mt8" icon="alert">派单已改为区域抢单池（2026-11-20 D2）：不再需要绑定维保人员；电梯归属 = 「所在区域」，留空 = 不限区域（全员可见）。</RuleNote>
      </Form>
      <RuleNote class="mt12">前 5 项（电梯编码 / 设备代码 / 注册代码 / 单位内编号 / 出厂编号）为上传维保记录的必传项，缺任一项签退即被拦截；项目名称（小区名称）、所在区域、年检日期可留空后补。</RuleNote>
    </Modal>

    <!-- 删除确认 -->
    <Modal :open="showDelete" title="删除电梯" :sub="current.elevatorCode" icon="ban" danger require-confirm note="elevator:edit"
      confirm-text="我已知悉，确认删除" confirm-label="确认删除" @close="showDelete = false" @confirm="submitDelete">
      <RuleNote icon="alert">确认删除电梯 {{ current.elevatorCode }}？存在在途工单或系统管理员关联时将被拒绝。</RuleNote>
    </Modal>

    <!-- 确认变更（第二段，确认人取登录身份，须不同于发起人） -->
    <Modal :open="showConfirm" title="确认变更（须不同于发起人）" icon="user-check" danger require-confirm note="elevator:transfer"
      confirm-text="确认人与发起人为不同账号，已知悉变更生效" confirm-label="确认变更" @close="showConfirm = false" @confirm="submitConfirm">
      <RuleNote icon="user-check">确认人以当前登录账号留痕，由后端取登录身份判定，须不同于发起人。</RuleNote>
      <RuleNote class="mt12">确认后变更生效并写审计；发起人 ≠ 确认人（biz_elevator_change 双人确认）。</RuleNote>
    </Modal>
  </div>
</template>
<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
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
import ElevatorGeoModal from '../ui/ElevatorGeoModal.vue'
import { useToast } from '../ui/stores/ui'
import { exportCsv, selectExportRows } from '../ui/export-csv'
import { DEVICE_FIELDS, deviceRules, deviceFormOf, missingRequiredOf } from '../ui/device-fields'
import { usePerspectiveStore } from '../ui/stores/perspective'
import { useAuthStore } from '../stores/auth'
import { canEditElevator } from '../ui/permission-v2'
import { elevatorActionLabel, statusLabel, scopeLabel } from '../ui/labels'
import { listChanges, confirmChange } from '../api/ledger'
import { createElevator, updateElevator, deleteElevator, elevatorProfile, elevatorArchive, elevatorQrPng, useUnits } from '../api/archive'
const toast = useToast()
const router = useRouter()
const perspective = usePerspectiveStore()
const auth = useAuthStore()
const canGeoEdit = computed(() => canEditElevator(auth.role))
// 操作列「电梯档案」→ 下级页（5 Tab：基本信息/电梯参数/维保计划/维保工单/维修工单）
function openDetail(row) { if (row && row.id) router.push('/elevator/' + row.id) }
const rows = ref([])
const changes = ref([])
const loading = ref(false)
const categories = ['曳引与强制驱动电梯', '液压驱动电梯', '杂物电梯', '自动扶梯与自动人行道']
// 设备代码、上次/下次维保、状态、详细地址从表格拿掉，仅在档案抽屉与电梯档案页可见
const cols = [
  { key: 'projectName', label: '小区名称', sortable: true },
  { key: 'insideNumber', label: '内部编号' },
  { key: 'regionName', label: '所在区域' },
  { key: 'registrationCode', label: '注册代码' },
  { key: 'elevatorCode', label: '电梯编码', sortable: true },
  { key: 'useUnitName', label: '使用单位' }
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
// 所在区域下拉（GET /regions，管理端主数据；空列表时仅可留空）
const regions = ref([])
async function loadRegions() {
  try {
    const r = await http.get('/regions')
    regions.value = Array.isArray(r) ? r : r.list || []
  } catch { regions.value = [] }
}
// 使用单位下拉（新建/编辑表单关联本地使用单位档案）
const useUnitList = ref([])
async function loadUseUnits() {
  try {
    const u = await useUnits()
    useUnitList.value = Array.isArray(u) ? u : u.list || []
  } catch { useUnitList.value = [] }
}
onMounted(() => { load(); loadRegions(); loadUseUnits() })
function exportElev(payload) {
  const ids = payload && Array.isArray(payload.ids) ? payload.ids : []
  const n = exportCsv('电梯台账', cols, selectExportRows(rows.value, ids))
  toast.success('已导出 ' + n + ' 台电梯', 'CSV · 按导出人权限脱敏')
}

// 档案 + 一梯一档
const archiveOpen = ref(false)
const current = ref({})
const profile = ref(null)
const archive = ref([])
const geoOpen = ref(false)
function openGeo(row) {
  if (!row || !row.id) return
  current.value = row
  geoOpen.value = true
}
async function geoSaved(updated) {
  const merged = { ...current.value, ...updated }
  current.value = merged
  await load()
  current.value = rows.value.find(r => r.id === merged.id) || merged
}
// 档案展示值：所在区域显示名称（不暴露内部 id）；缺必传项时抽屉顶部给出明确清单
const devInfo = computed(() => {
  const f = deviceFormOf(current.value, profile.value)
  f.regionId = current.value.regionName || (regions.value.find(r => r.id === f.regionId) || {}).name
    || (f.regionId ? '' : '重庆市（全市）')
  return f
})
const missingOf = missingRequiredOf
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

// 新建 / 编辑（设备信息档案：8 字段，前 5 项必传——校验文案带标签，如「注册代码必填」）
const showForm = ref(false)
const formMode = ref('create')
const formRef = ref(null)
const formModel = ref({})
const formRules = deviceRules()
function blankForm() {
  return {
    ...deviceFormOf(null, null),
    useUnitId: '',
    location: '', brand: '', manufacturer: '', driveMode: '',
    lastMaintenanceAt: ''
  }
}
function openCreate() { formMode.value = 'create'; current.value = {}; formModel.value = blankForm(); showForm.value = true }
async function openEditRow(row) {
  current.value = row
  profile.value = null
  try { profile.value = await elevatorProfile(row.id) } catch { profile.value = null }
  openEdit()
}
function openEdit() {
  formMode.value = 'edit'
  const l = (profile.value && profile.value.local) || {}
  formModel.value = {
    ...deviceFormOf(current.value, profile.value),
    useUnitId: current.value.useUnitId || '',
    // 其他信息（选填）：非设备档案 8 字段，沿用既有的本地维护字段
    location: l.address || current.value.location || '',
    brand: l.brand || '', manufacturer: l.manufacturer || '',
    driveMode: l.driveMode || ''
  }
  showForm.value = true
}
function syncHint(s) {
  return { OK: ' · 平台信息已同步', NO_MATCH: ' · 平台 2.7 未查到该梯', FAILED: ' · 平台同步失败，可稍后在平台同步重试' }[s] || ''
}
async function submitForm() {
  if (!formRef.value.validate()) return
  try {
    const body = { ...formModel.value }
    if (formMode.value === 'create') {
      const res = await createElevator(body)
      toast.success('电梯已创建' + syncHint(res && res.platformSync))
    } else {
      const res = await updateElevator(current.value.id, body)
      toast.success('已提交 · 关键字段进入审核' + syncHint(res && res.platformSync))
    }
    showForm.value = false
    await load()
    if (formMode.value === 'edit' && archiveOpen.value) openArchive(current.value)
  } catch (e) { toast.error(e.message || '保存失败') }
}

// 删除
const showDelete = ref(false)
function askDelete(row) { current.value = row; showDelete.value = true }
async function submitDelete() {
  try { await deleteElevator(current.value.id); toast.success('电梯已删除'); showDelete.value = false; archiveOpen.value = false; await load() }
  catch (e) { toast.error(e.message || '删除失败') }
}

async function toggleSuspend(row) {
  if (!row || !row.id) return
  const suspend = row.status !== 'INACTIVE'
  try {
    await updateElevator(row.id, { status: suspend ? 'INACTIVE' : 'ACTIVE' })
    toast.success((row.elevatorCode || '电梯') + (suspend ? ' 已停保' : ' 已恢复启用'))
    await load()
    if (archiveOpen.value && current.value.id === row.id) openArchive(row)
  } catch (e) { toast.error(e.message || '操作失败') }
}

// 变更确认（双人确认第二段；发起入口已由「停保/删除电梯」直操作替代，仅保留存量变更单确认）
const showConfirm = ref(false)
const confirmId = ref('')
function openConfirm(id) { confirmId.value = id; showConfirm.value = true }
async function submitConfirm() {
  try { await confirmChange(confirmId.value); toast.success('已确认 · 双人确认留痕'); showConfirm.value = false; load() }
  catch (e) { toast.error(e.message || '确认失败') }
}
</script>