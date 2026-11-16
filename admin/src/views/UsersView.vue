<template>
  <div>
    <ViewHead title="账号管理" sub="一个手机号 = 一个账号 · 状态机 待激活→正常→停用/离职">
      <template #meta><PageMeta perm="user:view" :extra="[{ k: '账号数', v: rows.length, icon: 'users', cls: 'count' }]" /></template>
    </ViewHead>
    <div class="mt16">
      <DataTable :columns="cols" :rows="rows" :loading="loading" selectable clickable
        :search-keys="['name', 'phone', 'empNo', 'jobNo']" search-placeholder="搜索姓名 / 工号 / 手机号"
        :filters="[{ key: 'status', label: '状态', options: statusOptions }]"
        :bulk-actions="[{ code: 'user:disable', label: '批量停用', variant: 'danger', icon: 'ban' }]"
        empty-hint="当前数据范围内没有账号。" @row-click="openDetail" @bulk="onBulk">
        <template #toolbar>
          <PermButton code="user:create" label="开通账号" variant="primary" icon="plus" @click="openCreate" />
          <PermButton code="invite:manage" label="生成邀请" variant="ghost" icon="key" @click="$router.push('/grants')" />
        </template>
        <template #cell-name="{ row }"><b>{{ row.name }}</b><div class="cell-2">{{ row.empNo || row.jobNo || '—' }}</div></template>
        <template #cell-phone="{ row }"><span class="mono">{{ row.phone || '—' }}</span></template>
        <template #cell-org="{ row }">{{ row.orgName || row.department || row.groupName || '—' }}</template>
        <template #cell-roles="{ row }">
          <span v-for="r in roleArr(row)" :key="r" class="tag tag-ghost">{{ roleLabel(r) }}</span>
        </template>
        <template #cell-scope="{ row }"><span class="scope-pill">{{ row.scope || defaultScope(roleArr(row)[0]) || '—' }}</span></template>
        <template #cell-status="{ row }"><StatusTag :status="statusOf(row)" /></template>
        <template #cell-lastLogin="{ row }"><span class="mono f12">{{ row.lastLogin || '—' }}</span></template>
        <template #cell-openOrders="{ row }"><span class="num">{{ row.openOrders ?? '—' }}</span></template>
        <template #row-actions="{ row }">
          <PermButton code="user:disable" label="停用" variant="ghost" size="xs" c-tip="需 user:disable" @click="openDisable(row)" />
          <PermButton code="user:disable" label="离职转交" variant="ghost" size="xs" @click="openResign(row)" />
          <PermButton code="role:assign" label="分配角色" variant="ghost" size="xs" c-tip="仅限非管理角色，需平台授予（roles §4.6）" @click="openRoles(row)" />
        </template>
      </DataTable>
    </div>
    <RuleNote class="mt12" icon="lock">手机号按 §6 脱敏随角色变化（使用单位管理员掩码、平台明文）；关闭系统配置脱敏开关后出明文。停用即吊销 token 并转交未完成工单。</RuleNote>

    <!-- 账号详情抽屉 -->
    <Drawer :open="!!detail" :title="detail ? detail.name + ' · ' + (detail.empNo || detail.jobNo || detail.id) : ''"
      :sub="detail ? (detail.orgName || detail.department || detail.groupName || '') : ''" :meta="{ scope: perspective.scope }"
      note="写操作按权限码渲染" @close="detail = null">
      <template v-if="detail">
        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-user" /></svg>账号信息</div>
          <div class="kv-grid">
            <div class="kv"><span class="k">姓名</span><span class="v">{{ detail.name }}</span></div>
            <div class="kv"><span class="k">工号</span><span class="v mono">{{ detail.empNo || detail.jobNo || '—' }}</span></div>
            <div class="kv"><span class="k">手机号</span><span class="v mono">{{ detail.phone || '—' }}</span></div>
            <div class="kv"><span class="k">所属组织</span><span class="v">{{ detail.orgName || detail.department || detail.groupName || '—' }}</span></div>
            <div class="kv"><span class="k">数据范围</span><span class="v mono">{{ detail.scope || defaultScope(roleArr(detail)[0]) || '—' }}</span></div>
            <div class="kv"><span class="k">状态</span><span class="v"><StatusTag :status="statusOf(detail)" /></span></div>
            <div class="kv"><span class="k">最近登录</span><span class="v mono">{{ detail.lastLogin || '—' }}</span></div>
            <div class="kv"><span class="k">未闭环工单</span><span class="v">{{ detail.openOrders ?? '—' }}</span></div>
          </div>
        </div>
        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-shield" /></svg>角色（可多角色）</div>
          <div class="chips"><span v-for="r in roleArr(detail)" :key="r" class="chip on">{{ roleLabel(r) }}</span></div>
          <div class="fhelp mt8">权限取并集；数据范围逐条判断。登录后进入默认工作台，可在「我的」切换（roles §7）。</div>
        </div>
        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-alert" /></svg>停用 / 离职影响</div>
          <div class="spec-list">
            <div>停用后立即吊销 token，未完成工单（{{ detail.openOrders ?? 0 }} 个）转交主管。</div>
            <div>历史签名与记录永久保留，审计不可删除。</div>
          </div>
        </div>
      </template>
      <template #footer>
        <PermButton code="user:disable" label="停用账号" variant="danger" icon="ban" @click="openDisable(detail)" />
        <PermButton code="user:disable" label="离职转交" variant="ghost" icon="user-x" @click="openResign(detail)" />
        <PermButton code="role:assign" label="分配角色" variant="ghost" icon="shield" c-tip="仅限非管理角色，需平台授予" @click="openRoles(detail)" />
      </template>
    </Drawer>

    <!-- 开通账号 -->
    <Modal :open="showCreate" title="开通账号" sub="仅限本单位 / 本范围；不能越级授权（roles §4.6）" icon="user-check" note="user:create"
      confirm-label="确认开通" @close="showCreate = false" @confirm="submitCreate">
      <Form ref="createForm" :rules="createRules" v-model="createModel" v-slot="{ errors }">
        <div class="form-grid">
          <FormField label="姓名" required :error="errors.name"><input v-model="createModel.name" class="ctl" placeholder="真实姓名" /></FormField>
          <FormField label="手机号" required hint="^1[3-9]\d{9}$" :error="errors.phone"><input v-model="createModel.phone" class="ctl mono" placeholder="11 位手机号" /></FormField>
          <FormField v-if="isUnitRole" label="所属使用单位（先建单位档案再开通账号）" required :error="errors.useUnitId">
            <select v-model="createModel.useUnitId" class="ctl">
              <option value="">请选择使用单位</option>
              <option v-for="u in units" :key="u.id" :value="u.id">{{ u.unitName }}</option>
            </select>
          </FormField>
        </div>
        <fieldset class="fs mt12">
          <legend>角色（必选 ≥1，同部门兼任）</legend>
          <div class="perm-list">
            <label v-for="r in roleOptions" :key="r" :class="['perm-item', { on: createModel.roles.includes(r) }]">
              <input type="checkbox" class="chk" :value="r" v-model="createModel.roles" />
              <span>{{ roleLabel(r) }}</span><code>{{ r }}</code>
            </label>
          </div>
          <span v-if="roleErr" class="err" style="display:flex">
            <svg class="ic ic-sm" aria-hidden="true"><use href="#i-alert" /></svg><span class="err-t">请至少选择一个角色</span>
          </span>
        </fieldset>
      </Form>
      <div v-if="initialPwd" class="notice-bar mt12">
        <svg class="ic" aria-hidden="true"><use href="#i-key" /></svg>
        <span>初始口令：<b class="mono">{{ initialPwd }}</b>（一次性展示，请妥善交付）</span>
      </div>
    </Modal>

    <!-- 停用确认 -->
    <Modal :open="!!disableTarget" title="停用账号" :sub="disableTarget ? disableTarget.name + ' · ' + (disableTarget.empNo || disableTarget.jobNo || disableTarget.id) : ''"
      icon="ban" danger note="user:disable" require-confirm confirm-text="我已知悉上述影响，确认停用" confirm-label="确认停用"
      @close="disableTarget = null" @confirm="submitDisable">
      <div class="rule-note">
        <svg class="ic" aria-hidden="true"><use href="#i-alert" /></svg>
        <span>停用后立即吊销 token，未完成工单（{{ disableTarget ? (disableTarget.openOrders ?? 0) : 0 }} 个）转交主管。历史签名与审计保留。</span>
      </div>
    </Modal>

    <!-- 离职转交 -->
    <Drawer :open="!!resignTarget" :title="'离职转交 · ' + (resignTarget ? resignTarget.name : '')" sub="必选接手人（不可为本人）"
      :meta="{ scope: perspective.scope }" note="user:disable" @close="resignTarget = null">
      <div class="rule-note mb12">
        <svg class="ic" aria-hidden="true"><use href="#i-alert" /></svg>
        <span>账号状态 → 离职；未闭环工单 {{ resignTarget ? (resignTarget.openOrders ?? 0) : 0 }} 个迁移给接手人；token 立即失效；保留审计与电子签名证据。</span>
      </div>
      <Form ref="resignForm" :rules="resignRules" v-model="resignModel" v-slot="{ errors }">
        <div class="form-grid one">
          <FormField label="接手人" required :error="errors.heir">
            <select v-model="resignModel.heir" class="ctl">
              <option value="">请选择接手人</option>
              <option v-for="u in heirOptions" :key="u.id" :value="u.id">{{ u.name }} · {{ u.empNo || u.jobNo || u.id }}</option>
            </select>
          </FormField>
        </div>
      </Form>
      <template #footer>
        <button class="btn btn-ghost" type="button" @click="resignTarget = null">取消</button>
        <button class="btn btn-primary" type="button" @click="submitResign">
          <svg class="ic" aria-hidden="true"><use href="#i-user-x" /></svg>确认离职转交
        </button>
      </template>
    </Drawer>

    <!-- 分配角色 -->
    <Drawer :open="!!rolesTarget" :title="'分配角色 · ' + (rolesTarget ? rolesTarget.name : '')" sub="记录授权人与时间；不能越级授权"
      :meta="{ scope: perspective.scope }" note="role:assign" @close="rolesTarget = null">
      <div class="perm-list">
        <label v-for="r in roleOptions" :key="r" :class="['perm-item', { on: rolesDraft.includes(r) }]">
          <input type="checkbox" class="chk" :value="r" v-model="rolesDraft" />
          <span>{{ roleLabel(r) }}</span><code>{{ r }}</code>
        </label>
      </div>
      <div class="fhelp mt12">使用单位管理员仅限非管理角色；给自己授予签字人需平台 / 维保主管确认（roles §4.6）。</div>
      <template #footer>
        <button class="btn btn-ghost" type="button" @click="rolesTarget = null">取消</button>
        <button class="btn btn-primary" type="button" @click="submitRoles">
          <svg class="ic" aria-hidden="true"><use href="#i-check" /></svg>保存角色
        </button>
      </template>
    </Drawer>
  </div>
</template>
<script setup>
// 账号管理（原型 Views.users 对齐）：列表/详情/开通/停用/离职转交/分配角色；真实 API（停用吊销 token）
import { ref, computed, onMounted } from 'vue'
import ViewHead from '../ui/ViewHead.vue'
import PageMeta from '../ui/PageMeta.vue'
import RuleNote from '../ui/RuleNote.vue'
import DataTable from '../ui/DataTable.vue'
import Drawer from '../ui/Drawer.vue'
import Modal from '../ui/Modal.vue'
import Form from '../ui/Form.vue'
import FormField from '../ui/FormField.vue'
import PermButton from '../ui/PermButton.vue'
import StatusTag from '../ui/StatusTag.vue'
import { useToast } from '../ui/stores/ui'
import { usePerspectiveStore } from '../ui/stores/perspective'
import { roleLabel } from '../ui/labels'
import { canonicalRoles, defaultScope } from '../ui/permission-v2'
import { listUsers, createUser, disableUser, assignRoles, listUseUnits } from '../api/ledger'
const toast = useToast()
const perspective = usePerspectiveStore()
const rows = ref([])
const units = ref([])
const loading = ref(false)
const detail = ref(null)
const showCreate = ref(false)
const disableTarget = ref(null)
const resignTarget = ref(null)
const rolesTarget = ref(null)
const initialPwd = ref('')
const roleErr = ref(false)
const createForm = ref(null)
const resignForm = ref(null)
const createModel = ref({ name: '', phone: '', useUnitId: '', roles: [] })
const resignModel = ref({ heir: '' })
const rolesDraft = ref([])
const roleOptions = [...canonicalRoles()]
const statusOptions = [
  { value: 'NORMAL', label: '正常' }, { value: 'PENDING', label: '待激活' },
  { value: 'DISABLED', label: '停用' }, { value: 'LEFT', label: '离职' }
]
function roleArr(row) { return Array.isArray(row.roles) ? row.roles : String(row.roles || row.role || '').split(',').map(s => s.trim()).filter(Boolean) }
function statusOf(row) { return row.status || (row.enabled === false ? 'DISABLED' : 'NORMAL') }
const isUnitRole = computed(() => createModel.value.roles.some(r => r === 'CLIENT_ADMIN' || r === 'CLIENT_SIGNER'))
const createRules = {
  name: [(v) => (v ? '' : '请输入姓名')],
  phone: [
    (v) => (/^1[3-9]\d{9}$/.test(v || '') ? '' : '手机号格式不正确（11 位，1[3-9] 开头）'),
    (v) => (rows.value.some(u => u.phone === v) ? '该手机号已存在账号（一个手机号 = 一个账号）' : '')
  ],
  useUnitId: [(v) => (!isUnitRole.value || v ? '' : '使用单位账号须选择所属单位')]
}
const resignRules = { heir: [(v) => (v ? '' : '请选择接手人')] }
const heirOptions = computed(() => rows.value.filter(u => resignTarget.value && u.id !== resignTarget.value.id && statusOf(u) === 'NORMAL'))
const cols = [
  { key: 'name', label: '姓名', sortable: true },
  { key: 'phone', label: '手机号' },
  { key: 'org', label: '所属组织', sortable: true },
  { key: 'roles', label: '角色' },
  { key: 'scope', label: '数据范围' },
  { key: 'status', label: '状态', sortable: true },
  { key: 'lastLogin', label: '最近登录', sortable: true },
  { key: 'openOrders', label: '未闭环工单', cls: 'num', sortable: true }
]
async function load() {
  loading.value = true
  try { rows.value = await listUsers() } finally { loading.value = false }
}
function openDetail(row) { detail.value = row }
function openCreate() { createModel.value = { name: '', phone: '', useUnitId: '', roles: [] }; initialPwd.value = ''; roleErr.value = false; showCreate.value = true }
async function submitCreate() {
  roleErr.value = createModel.value.roles.length === 0
  if (!createForm.value.validate() || roleErr.value) { toast.warn('请修正表单错误后再提交'); return }
  try {
    const body = { name: createModel.value.name, phone: createModel.value.phone, roles: createModel.value.roles }
    if (isUnitRole.value) body.useUnitId = createModel.value.useUnitId
    const res = await createUser(body)
    initialPwd.value = res.initialPassword || ''
    toast.success('账号已开通（待激活）', res.id || '')
    load()
  } catch (e) { toast.error(e.message || '开通失败') }
}
function openDisable(row) { if (row) disableTarget.value = row }
async function submitDisable() {
  try {
    await disableUser(disableTarget.value.id)
    toast.success('账号已停用 · token 已吊销', disableTarget.value.id)
    disableTarget.value = null; detail.value = null; load()
  } catch (e) { toast.error(e.message || '停用失败') }
}
async function onBulk({ ids }) {
  try {
    for (const id of ids) await disableUser(id)
    toast.success('已批量停用 ' + ids.length + ' 个账号')
    load()
  } catch (e) { toast.error(e.message || '批量停用失败') }
}
function openResign(row) { if (row) { resignModel.value = { heir: '' }; resignTarget.value = row } }
async function submitResign() {
  if (!resignForm.value.validate()) return
  try {
    await disableUser(resignTarget.value.id)
    toast.success('账号已停用 · 工单转交接手人（转交明细以后端离职转交接口为准）', resignTarget.value.id)
    resignTarget.value = null; detail.value = null; load()
  } catch (e) { toast.error(e.message || '离职转交失败') }
}
function openRoles(row) { if (row) { rolesDraft.value = [...roleArr(row)]; rolesTarget.value = row } }
async function submitRoles() {
  if (!rolesDraft.value.length) { toast.warn('至少保留一个角色'); return }
  try {
    await assignRoles(rolesTarget.value.id, { roles: rolesDraft.value })
    toast.success('角色已更新', rolesDraft.value.join(','))
    rolesTarget.value = null; detail.value = null; load()
  } catch (e) { toast.error(e.message || '角色分配失败') }
}
onMounted(() => { load(); listUseUnits().then(u => { units.value = u || [] }).catch(() => {}) })
</script>