<template>
  <div>
    <ViewHead title="授权管理" sub="邀请码 / 组织二维码 + 限时授权（追加权限点，到期自动回收）">
      <template #meta>
        <PageMeta perm="invite:manage" :extra="[{ k: '邀请码', v: invites.length, icon: 'key', cls: 'count' }, { k: '限时授权', v: grants.length, icon: 'shield', cls: 'count' }]" />
      </template>
    </ViewHead>
    <div class="mt16">
      <DataTable :columns="invCols" :rows="invites" :loading="loading" :search-keys="['code', 'creator']"
        search-placeholder="搜索码值 / 创建人" :filters="invFilters" empty-hint="当前范围内没有邀请码。">
        <template #toolbar>
          <PermButton code="invite:manage" label="生成邀请码" variant="primary" icon="plus" @click="openInvite" />
        </template>
        <template #cell-code="{ row }"><b class="mono">{{ row.code }}</b></template>
        <template #cell-type="{ row }"><span class="tag tag-ghost">{{ inviteTypeLabel(row.type) }}</span></template>
        <template #cell-org="{ row }">{{ row.targetOrgName || row.targetOrg || '—' }}</template>
        <template #cell-validDays="{ row }"><span class="num">{{ row.validDays }} 天</span></template>
        <template #cell-joined="{ row }"><span class="num">{{ row.joinedCount ?? row.joined ?? 0 }}</span></template>
        <template #cell-status="{ row }"><StatusTag :status="row.status" /></template>
        <template #cell-createdAt="{ row }"><span class="mono f12">{{ row.createdAt || '—' }}</span></template>
        <template #row-actions="{ row }">
          <PermButton v-if="invActive(row)" code="invite:manage" label="撤回" variant="ghost" size="xs" icon="ban" @click="revokeInvite(row)" />
          <span v-else class="dim f11">—</span>
        </template>
      </DataTable>
    </div>
    <div class="mt16">
      <DataTable :columns="grCols" :rows="grants" :loading="loading" :search-keys="['granteeId', 'granteeName', 'permCode']"
        search-placeholder="搜索被授权人 / 权限点" :filters="grFilters" empty-hint="当前范围内没有限时授权。">
        <template #toolbar>
          <PermButton code="role:assign" label="发起限时授权" variant="primary" icon="key"
            c-tip="由主管发起（MAINT_SUP/INST_MGR/PLATFORM），需 role:assign" @click="openGrant" />
        </template>
        <template #cell-id="{ row }"><b class="mono">{{ row.id || '—' }}</b></template>
        <template #cell-grantee="{ row }">{{ row.granteeName || row.granteeId }}<div class="cell-2 dim f11">{{ roleLabel(row.originalRole) || '—' }}</div></template>
        <template #cell-perms="{ row }">
          <code v-for="p in permArr(row)" :key="p" class="mono f11 c-amber">{{ p }}</code>
        </template>
        <template #cell-scope="{ row }"><span class="scope-pill">{{ row.scope || '—' }}</span></template>
        <template #cell-start="{ row }"><span class="mono f12">{{ row.startAt || '—' }}</span></template>
        <template #cell-end="{ row }"><span class="mono f12">{{ row.endAt || '—' }}</span></template>
        <template #cell-status="{ row }"><StatusTag :status="row.status" /></template>
        <template #row-actions="{ row }">
          <PermButton v-if="grActive(row)" code="role:assign" label="回收" variant="ghost" size="xs" icon="refresh" @click="reclaimGrant(row)" />
          <span v-else class="dim f11">—</span>
        </template>
      </DataTable>
    </div>
    <RuleNote class="mt12">邀请码默认有效期 7 天（roles L177，可 1–30）；限时授权由主管发起、校验 end&gt;start 且权限点 ≥1，到 end 自动回收并写审计「授权」。</RuleNote>

    <!-- 生成邀请码 -->
    <Modal :open="showInvite" title="生成邀请码 / 组织二维码" sub="默认有效期 7 天" icon="key" note="invite:manage"
      confirm-label="生成" @close="showInvite = false" @confirm="submitInvite">
      <Form ref="invForm" :rules="invRules" v-model="invModel" v-slot="{ errors }">
        <div class="form-grid">
          <FormField label="类型" required :error="errors.type">
            <select v-model="invModel.type" class="ctl">
              <option value="INVITE">邀请码</option>
              <option value="UNIT">组织二维码</option>
            </select>
          </FormField>
          <FormField label="目标组织" :error="errors.targetOrg">
            <select v-model="invModel.targetOrg" class="ctl">
              <option value="">请选择组织</option>
              <option v-for="o in orgs" :key="o.id" :value="o.id">{{ o.name }}</option>
            </select>
          </FormField>
          <FormField label="有效期（天）" required hint="1–30" :error="errors.validDays">
            <input type="number" min="1" max="30" v-model="invModel.validDays" class="ctl mono" />
          </FormField>
        </div>
      </Form>
    </Modal>

    <!-- 发起限时授权 -->
    <Modal :open="showGrant" title="发起限时授权" sub="由主管发起；追加权限点，到期自动回收" icon="key" note="role:assign"
      confirm-label="发起授权" @close="showGrant = false" @confirm="submitGrant">
      <Form ref="grantForm" :rules="grantRules" v-model="grantModel" v-slot="{ errors }">
        <div class="form-grid">
          <FormField label="被授权人" required :error="errors.granteeId">
            <select v-model="grantModel.granteeId" class="ctl">
              <option value="">请选择人员</option>
              <option v-for="u in users" :key="u.id" :value="u.id">{{ u.name }} · {{ u.empNo || u.jobNo || u.id }}</option>
            </select>
          </FormField>
          <FormField label="数据范围" required :error="errors.scope">
            <select v-model="grantModel.scope" class="ctl">
              <option v-for="s in ['SELF', 'TEAM', 'PROJECT', 'ORG', 'ORG_FLAT', 'ALL']" :key="s" :value="s">{{ s }}</option>
            </select>
          </FormField>
          <FormField label="开始时间" required :error="errors.startAt"><input type="datetime-local" v-model="grantModel.startAt" class="ctl mono" /></FormField>
          <FormField label="结束时间" required :error="errors.endAt"><input type="datetime-local" v-model="grantModel.endAt" class="ctl mono" /></FormField>
        </div>
        <fieldset class="fs mt12">
          <legend>追加权限点（必选 ≥1）</legend>
          <div class="perm-list">
            <label v-for="c in permCodes" :key="c" :class="['perm-item', { on: grantModel.perms.includes(c) }]">
              <input type="checkbox" class="chk" :value="c" v-model="grantModel.perms" />
              <span>{{ permLabel(c) }}</span><code>{{ c }}</code>
            </label>
          </div>
          <span v-if="permErr" class="err" style="display:flex">
            <svg class="ic ic-sm" aria-hidden="true"><use href="#i-alert" /></svg><span class="err-t">请至少选择一个权限点</span>
          </span>
        </fieldset>
      </Form>
    </Modal>
  </div>
</template>
<script setup>
// 授权管理（原型 Views.grants 对齐）：邀请码 + 限时授权双表；生成/撤回/发起/回收走真实 API，end>start 校验
import { ref, computed, onMounted } from 'vue'
import ViewHead from '../ui/ViewHead.vue'
import PageMeta from '../ui/PageMeta.vue'
import RuleNote from '../ui/RuleNote.vue'
import DataTable from '../ui/DataTable.vue'
import Modal from '../ui/Modal.vue'
import Form from '../ui/Form.vue'
import FormField from '../ui/FormField.vue'
import PermButton from '../ui/PermButton.vue'
import StatusTag from '../ui/StatusTag.vue'
import { useToast } from '../ui/stores/ui'
import { inviteTypeLabel, permLabel, roleLabel, statusLabel } from '../ui/labels'
import { canonicalCodes } from '../ui/permission-v2'
import { listInvites, createInvite, withdrawInvite, listGrants, createGrant, withdrawGrant, listOrg, listUsers } from '../api/ledger'
const toast = useToast()
const permCodes = [...canonicalCodes()]
const invites = ref([])
const grants = ref([])
const orgs = ref([])
const users = ref([])
const loading = ref(false)
const showInvite = ref(false)
const showGrant = ref(false)
const permErr = ref(false)
const invForm = ref(null)
const grantForm = ref(null)
const invModel = ref({ type: 'INVITE', targetOrg: '', validDays: 7 })
const grantModel = ref({ granteeId: '', scope: 'PROJECT', startAt: '', endAt: '', perms: [] })
const invRules = {
  type: [(v) => (v ? '' : '类型必填')],
  validDays: [(v) => { const n = parseInt(v, 10); return n >= 1 && n <= 30 ? '' : '有效期须为 1–30 天' }]
}
const grantRules = {
  granteeId: [(v) => (v ? '' : '请选择被授权人')],
  scope: [(v) => (v ? '' : '数据范围必填')],
  startAt: [(v) => (v ? '' : '起止时间必填')],
  endAt: [
    (v) => (v ? '' : '起止时间必填'),
    (v) => (!v || !grantModel.value.startAt || new Date(v) > new Date(grantModel.value.startAt) ? '' : '结束日期必须晚于开始日期')
  ]
}
const invCols = [
  { key: 'code', label: '码值', sortable: true },
  { key: 'type', label: '类型' },
  { key: 'org', label: '目标组织', sortable: true },
  { key: 'creator', label: '创建人' },
  { key: 'validDays', label: '有效期', cls: 'num', sortable: true },
  { key: 'joined', label: '已加入', cls: 'num', sortable: true },
  { key: 'status', label: '状态', sortable: true },
  { key: 'createdAt', label: '创建时间', sortable: true }
]
const grCols = [
  { key: 'id', label: '编号', sortable: true },
  { key: 'grantee', label: '被授权人', sortable: true },
  { key: 'perms', label: '追加权限点' },
  { key: 'scope', label: '数据范围' },
  { key: 'start', label: '起', sortable: true },
  { key: 'end', label: '止', sortable: true },
  { key: 'status', label: '状态', sortable: true }
]
function dynOptions(list) {
  return [...new Set(list.map(x => x.status).filter(Boolean))].map(v => ({ value: v, label: statusLabel(v) }))
}
const invFilters = computed(() => [{ key: 'status', label: '状态', options: dynOptions(invites.value) }])
const grFilters = computed(() => [{ key: 'status', label: '状态', options: dynOptions(grants.value) }])
function invActive(row) { return ['ACTIVE', 'VALID', '有效'].includes(row.status) }
function grActive(row) { return ['ACTIVE', 'PENDING', '生效中', '待生效'].includes(row.status) }
function permArr(row) { return row.addedPerms || (row.permCode ? [row.permCode] : []) }
async function load() {
  loading.value = true
  try { invites.value = await listInvites(); grants.value = await listGrants() } finally { loading.value = false }
}
function openInvite() { invModel.value = { type: 'INVITE', targetOrg: '', validDays: 7 }; showInvite.value = true }
async function submitInvite() {
  if (!invForm.value.validate()) return
  try {
    const body = { validDays: parseInt(invModel.value.validDays, 10), type: invModel.value.type }
    if (invModel.value.targetOrg) body.targetOrg = invModel.value.targetOrg
    await createInvite(body)
    toast.success('邀请码已生成'); showInvite.value = false; load()
  } catch (e) { toast.error(e.message || '生成失败') }
}
async function revokeInvite(row) {
  try { await withdrawInvite(row.id || row.code); toast.success('邀请码已撤回', row.code); load() }
  catch (e) { toast.error(e.message || '撤回失败') }
}
function openGrant() { grantModel.value = { granteeId: '', scope: 'PROJECT', startAt: '', endAt: '', perms: [] }; permErr.value = false; showGrant.value = true }
async function submitGrant() {
  permErr.value = grantModel.value.perms.length === 0
  if (!grantForm.value.validate() || permErr.value) { toast.warn('请修正表单错误后再提交'); return }
  try {
    const base = {
      granteeId: grantModel.value.granteeId,
      scope: grantModel.value.scope,
      startAt: grantModel.value.startAt + ':00',
      endAt: grantModel.value.endAt + ':00'
    }
    // 后端契约每单一权限点；多选时逐码发起（与原型多权限点 UX 对齐）
    for (const permCode of grantModel.value.perms) await createGrant({ ...base, permCode })
    toast.success('限时授权已发起', grantModel.value.perms.join(','))
    showGrant.value = false; load()
  } catch (e) { toast.error(e.message || '发起失败') }
}
async function reclaimGrant(row) {
  try { await withdrawGrant(row.id); toast.success('授权已回收', row.id); load() }
  catch (e) { toast.error(e.message || '回收失败') }
}
onMounted(() => {
  load()
  listOrg().then(o => { orgs.value = o || [] }).catch(() => {})
  listUsers().then(u => { users.value = u || [] }).catch(() => {})
})
</script>