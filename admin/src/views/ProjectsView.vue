<template>
  <div>
    <ViewHead title="项目台账" sub="安装项目由 ERP / 平台同步，业务端只读；维保项目可编辑">
      <template #meta><PageMeta perm="project:view" :extra="[{ k: '项目数', v: rows.length, icon: 'folder', cls: 'count' }]" /></template>
    </ViewHead>
    <div class="mt16">
      <DataTable :columns="cols" :rows="rows" :loading="loading" clickable
        :search-keys="['projectNo', 'name', 'useUnitName']" search-placeholder="搜索项目编号 / 名称 / 使用单位"
        :filters="[{ key: 'status', label: '状态', options: statusOptions }]"
        empty-hint="当前数据范围内没有项目。" @row-click="openDetail">
        <template #toolbar>
          <PermButton code="project:edit" label="新增项目" variant="primary" icon="plus" c-tip="仅维保经理 / 平台" @click="openCreate" />
          <PermButton code="elevator:export" label="导出台账" variant="ghost" icon="download" c-tip="按导出人数据范围导出 CSV" @click="exportLedger" />
        </template>
        <template #cell-projectNo="{ value }"><span class="mono">{{ value }}</span></template>
        <template #cell-name="{ value }"><b>{{ value }}</b></template>
        <template #cell-useUnit="{ row }">{{ row.useUnitName || '—' }}</template>
        <template #cell-maint="{ row }">{{ row.maintOrgName || '—' }}</template>
        <template #cell-count="{ value }"><span class="num">{{ value }}</span></template>
        <template #cell-contract="{ value }"><span class="mono f12">{{ value || '—' }}</span></template>
        <template #cell-status="{ value }"><StatusTag :status="value" /></template>
        <template #cell-last="{ value }"><span class="mono f12">{{ value || '—' }}</span></template>
        <template #row-actions>
          <PermButton code="elevator:view" label="电梯" variant="ghost" size="xs" icon="elevator" @click="$router.push('/elevators')" />
          <PermButton code="contract:view" label="合同" variant="ghost" size="xs" icon="file-sign" @click="$router.push('/contracts')" />
        </template>
      </DataTable>
    </div>

    <!-- 项目详情抽屉（原型 openProjectDetail） -->
    <Drawer :open="detailOpen" :title="current.name || ''" :sub="(current.projectNo || '') + ' · ' + (current.useUnitName || '')" note="project:view" @close="detailOpen = false">
      <template #meta><ScopePill :scope="perspective.scope" :label="scopeLabel(perspective.scope)" /></template>
      <div class="sec">
        <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-folder" /></svg>项目信息</div>
        <div class="kv-grid">
          <div class="kv"><span class="k">项目编号</span><span class="v mono">{{ current.projectNo || '—' }}</span></div>
          <div class="kv"><span class="k">项目名</span><span class="v">{{ current.name || '—' }}</span></div>
          <div class="kv"><span class="k">使用单位</span><span class="v">{{ current.useUnitName || '—' }}</span></div>
          <div class="kv"><span class="k">维保项目部</span><span class="v">{{ current.maintOrgName || '—' }}</span></div>
          <div class="kv"><span class="k">安装单位</span><span class="v">{{ current.installOrgName || '—' }}</span></div>
          <div class="kv"><span class="k">状态</span><span class="v">{{ statusLabel(current.status) }}</span></div>
          <div class="kv"><span class="k">合同号</span><span class="v mono">{{ current.contractId || '—' }}</span></div>
          <div class="kv"><span class="k">合同到期</span><span class="v mono">{{ current.contractEndDate || '—' }}</span></div>
          <div class="kv"><span class="k">最近作业</span><span class="v mono">{{ current.lastWorkDate || '—' }}</span></div>
        </div>
      </div>
      <div class="sec">
        <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-elevator" /></svg>台量分项（合计 {{ detailElevs.length }}）</div>
        <div class="grid3">
          <div v-for="t in ['客梯', '货梯', '扶梯']" :key="t" class="plate">
            <div class="hero-label">{{ t }}</div>
            <div class="hero-value num" style="font-size:22px">{{ byType[t] }} 台</div>
          </div>
        </div>
      </div>
      <div class="sec">
        <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-file-sign" /></svg>关联合同</div>
        <template v-if="detailContracts.length">
          <div v-for="c in detailContracts" :key="c.id" class="kv">
            <span class="k mono">{{ c.contractNo }}</span>
            <span class="v">{{ c.startDate }} ~ {{ c.endDate }} · <StatusTag :status="c.status" /></span>
          </div>
        </template>
        <div v-else class="dim">无</div>
      </div>
      <div class="sec">
        <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-exchange" /></svg>关联移交单</div>
        <template v-if="detailHandovers.length">
          <div v-for="h in detailHandovers" :key="h.id" class="kv">
            <span class="k mono">{{ h.handoverNo || h.id }}</span>
            <span class="v"><StatusTag :status="h.status" /></span>
          </div>
        </template>
        <div v-else class="dim">无</div>
      </div>
      <div class="sec">
        <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-clipboard" /></svg>近 3 条记录单</div>
        <template v-if="detailRecords.length">
          <div v-for="r in detailRecords" :key="r.id" class="kv">
            <span class="k mono">{{ r.recordNo || r.id }}</span>
            <span class="v">{{ r.workDate || '—' }} · <StatusTag :status="r.confirmStatus || r.status" /></span>
          </div>
        </template>
        <div v-else class="dim">无</div>
      </div>
      <template #footer>
        <PermButton code="project:edit" label="编辑项目" variant="ghost" icon="edit" @click="openEdit" />
        <PermButton code="elevator:view" label="查看电梯" variant="primary" icon="elevator" @click="$router.push('/elevators')" />
      </template>
    </Drawer>

    <!-- 新增 / 编辑项目 -->
    <Modal :open="showForm" :title="formMode === 'create' ? '新增项目' : '编辑项目'" sub="仅维保经理 / 平台可编辑" icon="folder" note="project:edit"
      :confirm-label="formMode === 'create' ? '确认新增' : '保存'" @close="showForm = false" @confirm="submit">
      <Form ref="formRef" :rules="rules" v-model="model" v-slot="{ errors }">
        <div class="form-grid">
          <FormField label="项目编号" required :error="errors.projectNo"><input v-model="model.projectNo" class="ctl mono" /></FormField>
          <FormField label="项目名" required :error="errors.name"><input v-model="model.name" class="ctl" /></FormField>
          <FormField label="台量"><input v-model="model.elevatorCount" type="number" class="ctl mono" /></FormField>
        </div>
      </Form>
    </Modal>
  </div>
</template>
<script setup>
// 项目台账（原型 Views.projects + openProjectDetail 对齐）：列表 + 详情抽屉（台量分项/关联合同/移交/记录）+ 新增编辑（project:edit）
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
import { useToast } from '../ui/stores/ui'
import { exportCsv } from '../ui/export-csv'
import { usePerspectiveStore } from '../ui/stores/perspective'
import { statusLabel, scopeLabel } from '../ui/labels'
import { listProjects, createProject, updateProject, listUseUnits, listOrg, listContractsLedger } from '../api/ledger'
import { listHandovers } from '../api/handover'
const toast = useToast()
const perspective = usePerspectiveStore()
const rows = ref([])
const loading = ref(false)
const unitMap = ref({})
const orgMap = ref({})
const cols = [
  { key: 'projectNo', label: '项目编号', sortable: true },
  { key: 'name', label: '项目名', sortable: true },
  { key: 'useUnit', label: '使用单位' },
  { key: 'maint', label: '维保项目部' },
  { key: 'count', label: '台量', cls: 'num', sortable: true },
  { key: 'contract', label: '合同号' },
  { key: 'status', label: '状态', sortable: true },
  { key: 'last', label: '最近作业', sortable: true }
]
const statusOptions = computed(() => [...new Set(rows.value.map(r => r.status).filter(Boolean))].map(s => ({ value: s, label: statusLabel(s) })))
function flattenOrgs(nodes, out = {}) {
  const list = nodes || []
  list.forEach(n => { out[n.id] = n.name; if (n.children) flattenOrgs(n.children, out) })
  return out
}
function decorate(p) {
  return {
    ...p, id: p.id || p.projectNo,
    useUnitName: unitMap.value[p.useUnitId] || p.useUnitId || '',
    maintOrgName: orgMap.value[p.maintOrgId] || p.maintOrgId || '',
    installOrgName: orgMap.value[p.installOrgId] || p.installOrgId || '',
    count: p.elevatorCount, contract: p.contractId, last: p.lastWorkDate
  }
}
async function load() {
  loading.value = true
  try {
    const [proj, units, orgs] = await Promise.all([
      listProjects(),
      listUseUnits().catch(() => []),
      listOrg().catch(() => [])
    ])
    ;(Array.isArray(units) ? units : units.list || []).forEach(u => { unitMap.value[u.id] = u.unitName || u.name })
    orgMap.value = flattenOrgs(Array.isArray(orgs) ? orgs : orgs.list || [])
    const list = Array.isArray(proj) ? proj : proj.list || []
    rows.value = list.map(decorate)
  } catch (e) { toast.error(e.message || '加载失败') } finally { loading.value = false }
}
onMounted(load)

// 详情抽屉 + 辅助数据（懒加载一次）
const detailOpen = ref(false)
const current = ref({})
const auxLoaded = ref(false)
const elevs = ref([])
const contracts = ref([])
const handovers = ref([])
const records = ref([])
async function ensureAux() {
  if (auxLoaded.value) return
  auxLoaded.value = true
  try { const e = await http.get('/admin/elevators'); elevs.value = Array.isArray(e) ? e : e.list || [] } catch { elevs.value = [] }
  try { const c = await listContractsLedger(); contracts.value = Array.isArray(c) ? c : c.list || [] } catch { contracts.value = [] }
  try { const h = await listHandovers(); handovers.value = Array.isArray(h) ? h : h.list || [] } catch { handovers.value = [] }
  try { const r = await http.get('/admin/records'); records.value = Array.isArray(r) ? r : r.list || [] } catch { records.value = [] }
}
function openDetail(row) { current.value = row; detailOpen.value = true; ensureAux() }
const detailElevs = computed(() => elevs.value.filter(e => e.projectName === current.value.name))
const byType = computed(() => {
  const t = { 客梯: 0, 货梯: 0, 扶梯: 0 }
  detailElevs.value.forEach(e => {
    const n = e.elevatorName || ''
    if (/客梯/.test(n)) t.客梯++
    else if (/货梯/.test(n)) t.货梯++
    else t.扶梯++
  })
  return t
})
const detailContracts = computed(() => contracts.value.filter(c => c.id === current.value.contractId || c.contractNo === current.value.contractId))
const detailHandovers = computed(() => handovers.value.filter(h => h.projectName === current.value.name || h.projectId === current.value.id))
const detailRecords = computed(() => records.value.filter(r => r.projectName === current.value.name).slice(0, 3))

// 新增 / 编辑
const showForm = ref(false)
const formMode = ref('create')
const formRef = ref(null)
const model = ref({ projectNo: '', name: '', elevatorCount: 0 })
const rules = { projectNo: [(v) => (v ? '' : '编号必填')], name: [(v) => (v ? '' : '名称必填')] }
function openCreate() { formMode.value = 'create'; model.value = { projectNo: '', name: '', elevatorCount: 0 }; showForm.value = true }
function openEdit() {
  formMode.value = 'edit'
  model.value = { projectNo: current.value.projectNo || '', name: current.value.name || '', elevatorCount: current.value.elevatorCount || 0 }
  showForm.value = true
}
async function submit() {
  if (!formRef.value.validate()) return
  try {
    const body = { ...model.value, elevatorCount: +model.value.elevatorCount }
    if (formMode.value === 'create') { await createProject(body); toast.success('项目已创建') }
    else { await updateProject(current.value.id, body); toast.success('项目已保存') }
    showForm.value = false
    detailOpen.value = false
    load()
  } catch (e) { toast.error(e.message || '保存失败') }
}
function exportLedger() {
  const n = exportCsv('项目台账', cols, rows.value)
  toast.success('已导出 ' + n + ' 条项目台账', 'CSV · 按导出人权限脱敏')
}
</script>