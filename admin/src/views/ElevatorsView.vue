<template>
  <div class="page">
    <div class="page-head">
      <h1>电梯台账</h1>
      <span class="acts">
        <PermButton code="elevator:edit" label="新建电梯" variant="primary" @click="openCreate" @blocked="onBlocked" />
        <PermButton code="elevator:transfer" label="移装/停用/报废" @click="showChange = true" @blocked="onBlocked" />
      </span>
    </div>
    <DataTable :columns="cols" :rows="rows" :loading="loading" clickable
      :search-keys="['elevatorCode', 'elevatorName', 'projectName']" @row-click="openArchive" />
    <p class="hint">点击行查看电梯档案与一梯一档归档目录。</p>

    <h2 class="label">变更单（双人确认）</h2>
    <ul class="chg-list">
      <li v-for="c in changes" :key="c.id">
        <span>{{ c.elevatorId }} · {{ elevatorActionLabel(c.changeType) }} · {{ statusLabel(c.status) }}</span>
        <PermButton v-if="c.status === 'PENDING'" code="elevator:transfer" label="确认" variant="danger" @click="openConfirm(c.id)" />
      </li>
    </ul>

    <!-- 电梯档案 + 一梯一档 -->
    <Drawer :open="archiveOpen" :title="'电梯档案 ' + (current.elevatorCode || '')" :meta="{ perm: '电梯档案 / 一梯一档' }" @close="archiveOpen = false">
      <div v-if="profile" class="profile">
        <h3 class="label">基础信息</h3>
        <dl class="kv">
          <div><dt>项目名称</dt><dd>{{ profile.local.projectName || '—' }}</dd></div>
          <div><dt>品种</dt><dd>{{ profile.category || '—' }}</dd></div>
          <div><dt>安装地址</dt><dd>{{ profile.local.address || '—' }}</dd></div>
          <div><dt>品牌</dt><dd>{{ profile.local.brand || '—' }}</dd></div>
          <div><dt>制造单位</dt><dd>{{ profile.local.manufacturer || '—' }}</dd></div>
          <div><dt>产品编号</dt><dd>{{ profile.local.productNo || '—' }}</dd></div>
          <div><dt>驱动方式</dt><dd>{{ profile.local.driveMode || '—' }}</dd></div>
          <div><dt>额定载重</dt><dd>{{ profile.local.ratedLoad || '—' }}</dd></div>
          <div><dt>额定速度</dt><dd>{{ profile.local.ratedSpeed || '—' }}</dd></div>
          <div><dt>层站门</dt><dd>{{ profile.local.stationsDoors || '—' }}</dd></div>
          <div><dt>下次检验</dt><dd>{{ profile.local.nextCheckDate || '—' }}</dd></div>
          <div><dt>下次维保</dt><dd>{{ profile.local.nextMaintenanceDate || '—' }}</dd></div>
        </dl>
        <h3 class="label">平台同步字段</h3>
        <dl class="kv">
          <div><dt>设备代码</dt><dd>{{ profile.platform.deviceCode || '—' }}</dd></div>
          <div><dt>注册代码</dt><dd>{{ profile.platform.registrationCode || '—' }}</dd></div>
          <div><dt>出厂编号</dt><dd>{{ profile.platform.factoryNumber || '—' }}</dd></div>
          <div><dt>使用单位负责人</dt><dd>{{ profile.platform.elevatorAdminister || '—' }}</dd></div>
          <div><dt>应急电话</dt><dd>{{ profile.platform.emergencyPhone || '—' }}</dd></div>
          <div><dt>同步时间</dt><dd>{{ profile.platform.syncedAt || '—' }}</dd></div>
        </dl>
        <h3 class="label">一梯一档归档目录</h3>
        <p class="hint">归档不可篡改（SHA-256 校验，留存 ≥4 年）；档案基础信息可在下方编辑/删除。</p>
        <table v-if="archive.length" class="archive-table">
          <thead><tr><th>序</th><th>类别</th><th>校验指纹</th><th>归档时间</th></tr></thead>
          <tbody>
            <tr v-for="a in archive" :key="a.id"><td>{{ a.seq }}</td><td>{{ archiveLabel(a.category) }}</td><td class="sha">{{ a.sha256 }}</td><td>{{ a.archivedAt }}</td></tr>
          </tbody>
        </table>
        <p v-else class="hint">该电梯暂无移交归档目录（完成安装移交后生成）。</p>
      </div>
      <p v-else class="hint">档案加载中…</p>
      <template #footer>
        <PermButton code="elevator:edit" label="编辑档案" @click="openEdit" @blocked="onBlocked" />
        <PermButton code="elevator:edit" label="删除电梯" variant="danger" @click="showDelete = true" @blocked="onBlocked" />
      </template>
    </Drawer>

    <!-- 新建/编辑电梯 -->
    <Modal :open="showForm" :title="formMode === 'create' ? '新建电梯' : '编辑电梯档案'" @close="showForm = false" @confirm="submitForm">
      <Form ref="formRef" :rules="formRules" v-model="formModel" v-slot="{ errors }">
        <FormField label="项目名称（小区）" required :error="errors.projectName"><input v-model="formModel.projectName" /></FormField>
        <FormField label="设备代码" :error="errors.elevatorCode"><input v-model="formModel.elevatorCode" /></FormField>
        <FormField label="品种" required :error="errors.category">
          <select v-model="formModel.category">
            <option value="">请选择</option>
            <option v-for="c in categories" :key="c" :value="c">{{ c }}</option>
          </select>
        </FormField>
        <FormField label="安装地址"><input v-model="formModel.location" /></FormField>
        <FormField label="品牌"><input v-model="formModel.brand" /></FormField>
        <FormField label="制造单位"><input v-model="formModel.manufacturer" /></FormField>
        <FormField label="产品编号"><input v-model="formModel.productNo" /></FormField>
        <FormField label="驱动方式"><input v-model="formModel.driveMode" /></FormField>
      </Form>
    </Modal>

    <!-- 删除确认 -->
    <Modal :open="showDelete" title="删除电梯" danger require-confirm @close="showDelete = false" @confirm="submitDelete">
      <p>确认删除电梯 {{ current.elevatorCode }}？存在在途工单或系统管理员关联时将被拒绝。</p>
    </Modal>

    <!-- 变更 -->
    <Modal :open="showChange" title="发起电梯变更" danger @close="showChange = false" @confirm="submitChange">
      <Form ref="chgForm" :rules="chgRules" v-model="chgModel" v-slot="{ errors }">
        <FormField label="电梯编号" required :error="errors.elevatorId"><input v-model="chgModel.elevatorId" /></FormField>
        <FormField label="类型" required :error="errors.changeType">
          <select v-model="chgModel.changeType"><option value="TRANSFER">移装</option><option value="DISABLE">停用</option><option value="SCRAP">报废</option></select>
        </FormField>
        <FormField label="原因" required :error="errors.reason"><input v-model="chgModel.reason" /></FormField>
      </Form>
    </Modal>
    <Modal :open="showConfirm" title="确认变更（须不同于发起人）" danger require-confirm @close="showConfirm = false" @confirm="submitConfirm">
      <FormField label="确认人编号"><input v-model="confirmerId" /></FormField>
    </Modal>
  </div>
</template>
<script setup>
// 电梯台账：档案增删改查（elevator:edit）+ 一梯一档归档目录（只读）+ 移装/停用/报废双人确认（elevator:transfer）
import { ref, onMounted } from 'vue'
import http from '../api/client'
import DataTable from '../ui/DataTable.vue'
import Drawer from '../ui/Drawer.vue'
import Modal from '../ui/Modal.vue'
import Form from '../ui/Form.vue'
import FormField from '../ui/FormField.vue'
import PermButton from '../ui/PermButton.vue'
import { useToast } from '../ui/stores/ui'
import { elevatorActionLabel, statusLabel } from '../ui/labels'
import { listChanges, initiateChange, confirmChange } from '../api/ledger'
import { createElevator, updateElevator, deleteElevator, elevatorProfile, elevatorArchive } from '../api/archive'
const toast = useToast()
const rows = ref([])
const changes = ref([])
const loading = ref(false)
const categories = ['曳引与强制驱动电梯', '液压驱动电梯', '杂物电梯', '自动扶梯与自动人行道']
const cols = [
  { key: 'elevatorCode', label: '设备码', sortable: true }, { key: 'elevatorName', label: '梯号' },
  { key: 'projectName', label: '项目' }, { key: 'status', label: '状态' }
]
function archiveLabel(c) {
  return { CHECKLIST: '检查清单', RECTIFICATION: '整改清单', DOCS: '移交资料', PHOTOS: '逐项+定角照片', CONFIRMATION: '交接确认单', FIRST_ORDER: '首保工单关联', PENDING_DOCS: '待补件清单' }[c] || c
}
function onBlocked(code) { toast.warn('需审批或前置未满足：' + code) }
async function load() {
  loading.value = true
  try { rows.value = await http.get('/admin/elevators'); changes.value = await listChanges() }
  catch (e) { toast.error(e.message || '加载失败') } finally { loading.value = false }
}
onMounted(load)

// 档案 + 一梯一档
const archiveOpen = ref(false)
const current = ref({})
const profile = ref(null)
const archive = ref([])
async function openArchive(row) {
  current.value = row
  profile.value = null
  archive.value = []
  archiveOpen.value = true
  try { profile.value = await elevatorProfile(row.id) } catch (e) { toast.error(e.message || '档案加载失败') }
  try { archive.value = await elevatorArchive(row.id) } catch (e) { archive.value = [] }
}

// 新建/编辑
const showForm = ref(false)
const formMode = ref('create')
const formRef = ref(null)
const formModel = ref({})
const formRules = {
  projectName: [(v) => (v ? '' : '项目名称必填')],
  category: [(v) => (v ? '' : '品种必填')]
}
function blankForm() { return { projectName: '', elevatorCode: '', category: '', location: '', brand: '', manufacturer: '', productNo: '', driveMode: '' } }
function openCreate() { formMode.value = 'create'; formModel.value = blankForm(); showForm.value = true }
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
    else { await updateElevator(current.value.id, formModel.value); toast.success('档案已更新') }
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

// 变更
const showChange = ref(false)
const showConfirm = ref(false)
const confirmerId = ref('')
const confirmId = ref('')
const chgForm = ref(null)
const chgModel = ref({ elevatorId: '', changeType: 'DISABLE', reason: '' })
const chgRules = {
  elevatorId: [(v) => (v ? '' : '电梯必填')],
  changeType: [(v) => (v ? '' : '类型必填')],
  reason: [(v) => (v ? '' : '原因必填')]
}
async function submitChange() {
  if (!chgForm.value.validate()) return
  try { await initiateChange(chgModel.value); toast.success('变更已发起，待双人确认'); showChange.value = false; load() }
  catch (e) { toast.error(e.message || '发起失败') }
}
function openConfirm(id) { confirmId.value = id; confirmerId.value = ''; showConfirm.value = true }
async function submitConfirm() {
  try { await confirmChange(confirmId.value, { confirmerId: confirmerId.value }); toast.success('已确认'); showConfirm.value = false; load() }
  catch (e) { toast.error(e.message || '确认失败') }
}
</script>
<style scoped>
.page-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-head h1 { font-size: 20px; }
.acts { display: flex; gap: 8px; }
h2.label { margin: 18px 0 8px; }
h3.label { margin: 14px 0 8px; }
.hint { color: var(--dim); font-size: 12px; margin-top: 8px; }
.chg-list { list-style: none; padding: 0; }
.chg-list li { display: flex; justify-content: space-between; align-items: center; padding: 8px 12px; background: var(--panel); border: 1px solid var(--line); border-radius: 6px; margin-bottom: 6px; font-size: 13px; }
.profile { display: flex; flex-direction: column; gap: 6px; }
.kv { display: grid; grid-template-columns: 1fr 1fr; gap: 6px 16px; margin: 0; }
.kv div { display: flex; gap: 6px; font-size: 12px; }
.kv dt { color: var(--muted); min-width: 96px; }
.kv dd { margin: 0; }
.archive-table { width: 100%; border-collapse: collapse; font-size: 12px; margin-top: 6px; }
.archive-table th { text-align: left; padding: 6px 8px; background: var(--panel-2); color: var(--muted); border-bottom: 1px solid var(--line-2); }
.archive-table td { padding: 6px 8px; border-bottom: 1px solid var(--line); }
.sha { font-family: var(--font-mono); font-size: 11px; word-break: break-all; }
.page input, .page select { width: 100%; padding: 8px; background: var(--panel-2); border: 1px solid var(--line-2); border-radius: 6px; color: var(--text); }
</style>