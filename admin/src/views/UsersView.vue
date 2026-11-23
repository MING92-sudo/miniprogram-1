<template>
  <div>
    <ViewHead title="账号管理" sub="一个手机号 = 一个账号 · 状态机 待激活→正常→停用/离职">
      <template #meta><PageMeta perm="user:view" :extra="[{ k: '账号数', v: rows.length, icon: 'users', cls: 'count' }]" /></template>
    </ViewHead>
    <div class="mt16">
      <DataTable :columns="cols" :rows="rows" :loading="loading" clickable
        :search-keys="['name', 'phone']" search-placeholder="请输入员工姓名 / 电话"
        :filters="[{ key: 'status', label: '状态', options: statusOptions }]"
        empty-hint="当前数据范围内没有账号，点击「新增用户」创建。" @row-click="openDetail">
        <template #toolbar>
          <PermButton code="user:create" label="新增用户" variant="primary" icon="plus" @click="openCreate" />
        </template>
        <template #cell-name="{ row }"><b>{{ row.name }}</b></template>
        <template #cell-phone="{ row }"><span class="mono">{{ row.phone || '—' }}</span></template>
        <template #cell-roles="{ row }">
          <span v-for="r in roleArr(row)" :key="r" class="tag tag-ghost">{{ roleLabel(r) }}</span>
        </template>
        <template #cell-regionName="{ row }">{{ row.regionName || '—' }}</template>
        <template #cell-status="{ row }"><StatusTag :status="statusOf(row)" /></template>
        <template #row-actions="{ row }">
          <PermButton code="user:view" label="用户详情" variant="ghost" size="xs" icon="user" @click="openDetail(row)" />
          <PermButton code="user:create" label="修改用户" variant="ghost" size="xs" icon="edit" @click="openEdit(row)" />
          <MoreMenu>
            <PermButton code="role:assign" label="分配角色" variant="ghost" size="xs" icon="shield" @click="openRoles(row)" />
            <PermButton code="user:reset_password" label="重置密码" variant="ghost" size="xs" icon="key" @click="openReset(row)" />
            <PermButton code="user:disable" label="离职转交" variant="ghost" size="xs" icon="user-x" @click="openResign(row)" />
            <PermButton code="user:disable" label="停用账号" variant="danger" size="xs" icon="ban" @click="openDisable(row)" />
          </MoreMenu>
        </template>
      </DataTable>
    </div>
    <RuleNote class="mt12" icon="lock">手机号按 §6 脱敏随角色变化（使用单位管理员掩码、平台明文）；关闭系统配置脱敏开关后出明文。停用即吊销 token；离职转交将未完成工单迁移给同区域接手人。</RuleNote>

    <!-- 账号详情抽屉 -->
    <Drawer :open="!!detail" :title="detail ? detail.name + ' · ' + (detail.empNo || detail.jobNo || detail.id) : ''"
      :sub="detail ? (detail.orgName || deptLabel(detail.department) || detail.groupName || '') : ''" :meta="{ scope: perspective.scope }"
      note="写操作按权限码渲染" @close="detail = null">
      <template v-if="detail">
        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-user" /></svg>账号信息</div>
          <div class="kv-grid">
            <div class="kv"><span class="k">姓名</span><span class="v">{{ detail.name }}</span></div>
            <div class="kv"><span class="k">工号</span><span class="v mono">{{ detail.empNo || detail.jobNo || '—' }}</span></div>
            <div class="kv"><span class="k">手机号</span><span class="v mono">{{ detail.phone || '—' }}</span></div>
            <div class="kv"><span class="k">所属组织</span><span class="v">{{ detail.orgName || deptLabel(detail.department) || detail.groupName || '—' }}</span></div>
            <div class="kv"><span class="k">所属区域</span><span class="v">{{ detail.regionName || '—' }}</span></div>
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
            <div>停用后立即吊销 token；未完成工单（{{ detail.openOrders ?? 0 }} 个）保留原负责人，如需迁移请使用「离职转交」。</div>
            <div>历史签名与记录永久保留，审计不可删除。</div>
          </div>
        </div>
      </template>
      <template #footer>
        <PermButton code="user:create" label="编辑账号" variant="ghost" icon="edit" c-tip="需 user:create（ADMIN+）" @click="openEdit(detail)" />
        <PermButton code="user:disable" label="停用账号" variant="danger" icon="ban" @click="openDisable(detail)" />
        <PermButton code="user:disable" label="离职转交" variant="ghost" icon="user-x" @click="openResign(detail)" />
        <PermButton code="role:assign" label="分配角色" variant="ghost" icon="shield" c-tip="仅限非管理角色，需平台授予" @click="openRoles(detail)" />
      </template>
    </Drawer>

    <!-- 编辑账号（姓名 / 手机号；后端同步默认登录账号 account） -->
    <Modal :open="!!editTarget" :title="'编辑账号 · ' + (editTarget ? (editTarget.name || editTarget.id) : '')"
      sub="姓名 / 手机号；改手机号会自动同步默认登录账号" icon="edit" note="user:create"
      @close="editTarget = null" @confirm="submitEdit">
      <Form ref="editForm" :rules="editRules" v-model="editModel" v-slot="{ errors }">
        <div class="form-grid">
          <FormField label="姓名" required :error="errors.name"><input v-model="editModel.name" class="ctl" placeholder="真实姓名" /></FormField>
          <FormField label="手机号" required hint="同时作为默认登录账号" :error="errors.phone">
            <input v-model="editModel.phone" class="ctl mono" placeholder="11 位手机号" />
          </FormField>
          <FormField label="所属区域" hint="不选择=不限区域">
            <select v-model="editModel.regionId" class="ctl">
              <option value="">不限区域</option>
              <option v-for="r in regions" :key="r.id" :value="r.id">{{ r.name }}</option>
            </select>
          </FormField>
        </div>
        <div class="fhelp mt8">手机号须符合 11 位格式且不与既有账号冲突（冲突由服务端 1002 拦截）。角色请通过「分配角色」单独维护。</div>
      </Form>
    </Modal>

    <!-- 开通账号 -->
    <Modal :open="showCreate" title="开通账号" sub="仅限本单位 / 本范围；不能越级授权（roles §4.6）" icon="user-check" note="user:create"
      confirm-label="确认开通" @close="showCreate = false" @confirm="submitCreate">
      <Form ref="createForm" :rules="createRules" v-model="createModel" v-slot="{ errors }">
        <div class="form-grid">
          <FormField label="姓名" required :error="errors.name"><input v-model="createModel.name" class="ctl" placeholder="真实姓名" /></FormField>
          <FormField label="手机号" required hint="^1[3-9]\d{9}$" :error="errors.phone"><input v-model="createModel.phone" class="ctl mono" placeholder="11 位手机号" /></FormField>
          <FormField label="所属区域" hint="不选择=不限区域">
            <select v-model="createModel.regionId" class="ctl">
              <option value="">不限区域</option>
              <option v-for="r in regions" :key="r.id" :value="r.id">{{ r.name }}</option>
            </select>
          </FormField>
        </div>
        <RuleNote class="mt12">
          使用单位客户账号不在此开通：请到「使用单位档案 → 客户账号」开通，客户登录走「使用单位客户」入口。
        </RuleNote>
        <fieldset class="fs mt12">
          <legend>角色（必选 ≥1，同部门兼任）</legend>
          <div class="perm-list">
            <label v-for="r in roleOptions" :key="r" :class="['perm-item', { on: createModel.roles.includes(r) }]">
              <input type="checkbox" class="chk" :value="r" v-model="createModel.roles" />
              <span>{{ roleLabel(r) }}</span><code>{{ r }}</code>
            </label>
          </div>
          <span v-if="roleErr" class="err" style="display:flex">
            <svg class="ic ic-sm" aria-hidden="true"><use href="#i-alert" /></svg><span class="err-t">{{ roleErr }}</span>
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
        <span>停用后立即吊销 token；未完成工单（{{ disableTarget ? (disableTarget.openOrders ?? 0) : 0 }} 个）保留原负责人，如需迁移请使用「离职转交」。历史签名与审计保留。</span>
      </div>
    </Modal>

    <!-- 离职转交 -->
    <Drawer :open="!!resignTarget" :title="'离职转交 · ' + (resignTarget ? resignTarget.name : '')" sub="必选接手人（不可为本人）"
      :meta="{ scope: perspective.scope }" note="user:disable" @close="resignTarget = null">
      <div class="rule-note mb12">
        <svg class="ic" aria-hidden="true"><use href="#i-alert" /></svg>
        <span>账号状态 → 停用；未闭环工单与急修单迁移给接手人（当前工单 {{ resignTarget ? (resignTarget.openOrders ?? 0) : 0 }} 个）；token 立即失效；保留审计与电子签名证据。</span>
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

    <!-- 重置密码（12 位随机一次性展示） -->
    <Modal :open="!!resetTarget" title="重置密码" :sub="resetTarget ? resetTarget.name + ' · ' + (resetTarget.phone || '') : ''"
      icon="key" danger require-confirm confirm-text="我已知悉，确认重置该账号密码" confirm-label="确认重置"
      @close="resetTarget = null; resetPwd = ''" @confirm="submitReset">
      <RuleNote icon="alert">重置后旧密码立即失效，新密码仅展示一次，请妥善转交本人。</RuleNote>
      <div v-if="resetPwd" class="notice-bar mt12">
        <svg class="ic" aria-hidden="true"><use href="#i-key" /></svg>
        <span>新密码：<b class="mono">{{ resetPwd }}</b>（一次性展示）</span>
      </div>
    </Modal>

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
import { roleLabel, deptLabel, roleDept } from '../ui/labels'
import { canonicalRoles, defaultScope } from '../ui/permission-v2'
import { listUsers, disableUser, resignUser, assignRoles } from '../api/ledger'
import { employees, createEmployee, updateEmployee, resetEmployeePassword } from '../api/archive'
import http from '../api/client'
import MoreMenu from '../ui/MoreMenu.vue'
const toast = useToast()
const perspective = usePerspectiveStore()
const rows = ref([])
const loading = ref(false)
const detail = ref(null)
const showCreate = ref(false)
const disableTarget = ref(null)
const resignTarget = ref(null)
const rolesTarget = ref(null)
const editTarget = ref(null)
const editModel = ref({ name: '', phone: '', regionId: '' })
const editForm = ref(null)
const initialPwd = ref('')
const roleErr = ref('')
const createForm = ref(null)
const resignForm = ref(null)
const createModel = ref({ name: '', phone: '', roles: [], regionId: '' })
const resignModel = ref({ heir: '' })
const rolesDraft = ref([])
const regions = ref([])
const resetTarget = ref(null)
const resetPwd = ref('')
// 「分配电梯」入口已下线
function openReset(row) { if (row) { resetPwd.value = ''; resetTarget.value = row } }
async function submitReset() {
  try {
    const r = await resetEmployeePassword(resetTarget.value.id)
    resetPwd.value = r.initialPassword || ''
    if (resetPwd.value) toast.success('密码已重置（仅展示一次）', resetTarget.value.name)
    else { toast.success('密码已重置'); resetTarget.value = null }
  } catch (e) { toast.error(e.message || '重置失败') }
}
// 客户角色（CLIENT_*）不在人员目录分配：客户账号在「使用单位档案 → 客户账号」独立开通
const CUSTOMER_ROLES = ['CLIENT_ADMIN', 'CLIENT_SIGNER']
const roleOptions = [...canonicalRoles()].filter(r => !CUSTOMER_ROLES.includes(r))
const statusOptions = [
  { value: 'NORMAL', label: '正常' }, { value: 'PENDING', label: '待激活' },
  { value: 'DISABLED', label: '停用' }, { value: 'LEFT', label: '离职' }
]
function roleArr(row) { return Array.isArray(row.roles) ? row.roles : String(row.roles || row.role || '').split(',').map(s => s.trim()).filter(Boolean) }
function statusOf(row) { return row.status || (row.enabled === false ? 'DISABLED' : 'NORMAL') }
const createRules = {
  name: [(v) => (v ? '' : '请输入姓名')],
  phone: [
    (v) => (/^1[3-9]\d{9}$/.test(v || '') ? '' : '手机号格式不正确（11 位，1[3-9] 开头）'),
    (v) => (rows.value.some(u => u.phone === v) ? '该手机号已存在账号（一个手机号 = 一个账号）' : '')
  ]
}
const resignRules = { heir: [(v) => (v ? '' : '请选择接手人')] }
const editRules = {
  name: [(v) => (v ? '' : '请输入姓名')],
  phone: [(v) => (/^1[3-9]\d{9}$/.test(v || '') ? '' : '手机号格式不正确（11 位，1[3-9] 开头）')]
}
const heirOptions = computed(() => rows.value.filter(u => resignTarget.value && u.id !== resignTarget.value.id && statusOf(u) === 'NORMAL'))
const cols = [
  { key: 'name', label: '员工姓名', sortable: true },
  { key: 'phone', label: '联系电话' },
  { key: 'roles', label: '角色名称' },
  { key: 'regionName', label: '区域' },
  { key: 'status', label: '状态', sortable: true }
]
async function load() {
  loading.value = true
  try {
    // 区域富化失败不得拖垮主列表——employees() 独立降级为空（口径同下方 loadRegions）
    const [list, emps] = await Promise.all([listUsers(), employees().catch(() => [])])
    const regionById = new Map((Array.isArray(emps) ? emps : []).map(e => [e.id, e]))
    rows.value = (Array.isArray(list) ? list : []).map(r => {
      const emp = regionById.get(r.id) || {}
      return {
        ...r,
        regionId: r.regionId || emp.regionId || '',
        regionName: r.regionName || emp.regionName || '',
        org: r.orgName || deptLabel(r.department) || r.groupName || '—'
      }
    })
  } finally { loading.value = false }
}
// 区域下拉选项（开通/编辑表单共用）；区域为主数据只读消费，失败时降级为空下拉
async function loadRegions() {
  try { regions.value = await http.get('/regions') } catch { regions.value = [] }
}
function openDetail(row) { detail.value = row }
function openCreate() { createModel.value = { name: '', phone: '', roles: [], regionId: '' }; initialPwd.value = ''; roleErr.value = ''; showCreate.value = true }
// 预检不通过不发起任何请求——否则会出现「POST /employees 建档成功、assignRoles 422 跨部门」半成品（账号已建但只有主角色、初始口令随 catch 丢失、重试撞账号查重）
function roleErrOf(roles) {
  if (!roles.length) return '请至少选择一个角色'
  const depts = new Set(roles.map(roleDept).filter(Boolean))
  if (depts.size > 1) return '跨部门兼任禁止（R7）：所选角色须同属一个部门'
  return ''
}
async function submitCreate() {
  roleErr.value = roleErrOf(createModel.value.roles)
  if (!createForm.value.validate() || roleErr.value) { toast.warn('请修正表单错误后再提交'); return }
  try {
    const body = {
      name: createModel.value.name,
      phone: createModel.value.phone,
      role: createModel.value.roles[0],
      regionId: createModel.value.regionId || ''
    }
    const res = await createEmployee(body)
    // 多角色：建档写入主角色行后，再经「分配角色」端点补齐完整角色集（与行内分配角色同口径）
    if (createModel.value.roles.length > 1) {
      await assignRoles(res.id, { roles: createModel.value.roles })
    }
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
function openResign(row) { if (row) { resignModel.value = { heir: '' }; resignTarget.value = row } }
async function submitResign() {
  if (!resignForm.value.validate()) return
  try {
    const res = await resignUser(resignTarget.value.id, resignModel.value.heir)
    const orders = res?.orders ?? 0
    const faults = res?.faults ?? 0
    toast.success(`离职转交完成 · 迁移工单 ${orders} 张、急修单 ${faults} 条`, resignTarget.value.id)
    resignTarget.value = null; detail.value = null; load()
  } catch (e) { toast.error(e.message || '离职转交失败') }
}
function openEdit(row) {
  if (!row) return
  editModel.value = { name: row.name || '', phone: row.phone || '', regionId: row.regionId || '' }
  editTarget.value = row
}
async function submitEdit() {
  if (!editForm.value.validate()) { toast.warn('请修正表单错误后再提交'); return }
  try {
    await updateEmployee(editTarget.value.id, {
      name: editModel.value.name,
      phone: editModel.value.phone,
      regionId: editModel.value.regionId || ''
    })
    toast.success('账号已更新', editTarget.value.id)
    editTarget.value = null; detail.value = null; load()
  } catch (e) { toast.error(e.message || '更新失败') }
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
onMounted(() => { load(); loadRegions() })
</script>