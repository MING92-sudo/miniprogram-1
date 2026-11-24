<template>
  <div>
    <ViewHead title="使用单位档案" sub="维护使用单位联系人信息，为客户账号开通做准备">
      <template #meta><PageMeta perm="client:edit" :extra="[{ k: '使用单位', v: rows.length, icon: 'building', cls: 'count' }]" /></template>
    </ViewHead>

    <div class="mt16">
      <DataTable :columns="cols" :rows="rows" :loading="loading" clickable
        :search-keys="['unitName', 'unitPrincipal', 'elevatorAdminister']" search-placeholder="搜索单位名称 / 负责人 / 安全管理员"
        empty-hint="当前数据范围内没有使用单位，点击“新增使用单位”建档。" @row-click="openDetail">
        <template #toolbar>
          <PermButton code="client:edit" label="新增使用单位" variant="primary" icon="plus" @click="openCreate" />
        </template>
        <template #cell-unitName="{ value }"><b>{{ value }}</b></template>
        <template #cell-unitPrincipal="{ value, row }">{{ value }}<span v-if="row.unitPrincipalPhone" class="dim f12 mono"> · {{ row.unitPrincipalPhone }}</span></template>
        <template #cell-elevatorAdminister="{ value, row }">{{ value }}<span v-if="row.elevatorAdministerPhone" class="dim f12 mono"> · {{ row.elevatorAdministerPhone }}</span></template>
        <template #cell-emergencyPhone="{ value }"><span class="mono">{{ value || '—' }}</span></template>
        <template #cell-elevatorCount="{ value }"><span class="num">{{ value ?? 0 }}</span></template>
        <template #cell-entityId="{ value }"><span class="mono f12">{{ value || '—' }}</span></template>
        <template #row-actions="{ row }">
          <PermButton code="client:edit" label="编辑" variant="ghost" size="xs" icon="edit" @click="openEdit(row)" />
          <PermButton code="client:edit" label="删除" variant="ghost" size="xs" icon="x" @click="openDelete(row)" />
        </template>
      </DataTable>
    </div>

    <RuleNote class="mt12">使用单位负责人与安全管理员手机不可相同（平台 V1.5 2.6 互斥口径）。平台 2.7 回填的手机号若为脱敏值，请在此补录真实号码。单位主体ID 通过「监管平台同步」拉取，本页不手工维护。</RuleNote>

    <!-- 详情抽屉 -->
    <Drawer :open="!!detail" :title="detail?.unitName || ''" sub="使用单位档案" note="client:edit" @close="detail = null">
      <template v-if="detail">
        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-building" /></svg>单位信息</div>
          <div class="kv-grid">
            <div class="kv"><span class="k">单位名称</span><span class="v">{{ detail.unitName || '—' }}</span></div>
            <div class="kv"><span class="k">统一社会信用代码</span><span class="v mono">{{ detail.organizationCode || '—' }}</span></div>
            <div class="kv"><span class="k">主体ID</span><span class="v mono">{{ detail.entityId || '—' }}</span></div>
            <div class="kv"><span class="k">在管台量</span><span class="v num">{{ detail.elevatorCount ?? 0 }}</span></div>
          </div>
        </div>
        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-users" /></svg>联系人（平台 2.6 上报字段）</div>
          <div class="kv-grid">
            <div class="kv"><span class="k">使用单位负责人</span><span class="v">{{ detail.unitPrincipal || '—' }}</span></div>
            <div class="kv"><span class="k">负责人手机</span><span class="v mono">{{ detail.unitPrincipalPhone || '—' }}</span></div>
            <div class="kv"><span class="k">使用单位安全管理员</span><span class="v">{{ detail.elevatorAdminister || '—' }}</span></div>
            <div class="kv"><span class="k">安全管理员手机</span><span class="v mono">{{ detail.elevatorAdministerPhone || '—' }}</span></div>
            <div class="kv"><span class="k">紧急联系电话</span><span class="v mono">{{ detail.emergencyPhone || '—' }}</span></div>
          </div>
        </div>
        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-user-check" /></svg>客户账号（P3 独立租户）</div>
          <div class="fhelp mb8">客户账号独立于员工体系，仅可看本单位电梯与待签记录；不进入组织树/统计。账号操作留审计，不删除只停用。</div>
          <div v-if="accountsLoading" class="dim">加载中…</div>
          <div v-else-if="accounts.length" class="tbl-wrap">
            <table class="tbl">
              <thead><tr><th>姓名</th><th>手机号</th><th>角色</th><th>状态</th><th>微信</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="a in accounts" :key="a.id">
                  <td>{{ a.name }}</td>
                  <td class="mono">{{ a.phone }}</td>
                  <td>{{ a.roleText }}</td>
                  <td><StatusTag :status="a.status" /></td>
                  <td>{{ a.wechatBound ? '已绑定' : '—' }}</td>
                  <td class="ops-cell">
                    <button class="link-btn" @click="openAccountEdit(a)">编辑</button>
                    <button v-if="a.status !== 'DISABLED'" class="link-btn" @click="doAccountAction(a, 'disable')">停用</button>
                    <button v-else class="link-btn" @click="doAccountAction(a, 'enable')">启用</button>
                    <button class="link-btn" @click="doAccountAction(a, 'reset')">重置口令</button>
                    <button v-if="a.status === 'DISABLED'" class="link-btn" @click="openGrace(a)">恢复宽限</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <p v-else class="fhelp">该单位尚未开通客户账号。开通后客户可用手机号+口令登录小程序查看本单位电梯并签字。</p>
        </div>
      </template>
      <template #footer>
        <PermButton code="client:edit" label="开通客户账号" variant="primary" icon="plus" @click="openAccountCreate" />
        <PermButton code="client:edit" label="编辑" variant="ghost" icon="edit" @click="openEdit(detail)" />
        <PermButton code="client:edit" label="删除" variant="danger" icon="x" @click="openDelete(detail)" />
      </template>
    </Drawer>

    <!-- 新增 / 编辑 -->
    <Modal :open="showForm" :title="formId ? '编辑使用单位' : '新增使用单位'" sub="维护联系人信息，为客户账号开通做准备" icon="building" note="client:edit"
      :confirm-label="formId ? '保存' : '确认新增'" @close="showForm = false" @confirm="submit">
      <Form ref="formRef" :rules="rules" v-model="model" v-slot="{ errors }">
        <div class="form-grid">
          <FormField label="单位名称" required :error="errors.unitName"><input v-model="model.unitName" class="ctl" placeholder="如：重庆世纪物业管理有限公司" /></FormField>
          <FormField label="统一社会信用代码" :error="errors.organizationCode"><input v-model="model.organizationCode" class="ctl mono" /></FormField>
          <FormField label="使用单位负责人" required :error="errors.unitPrincipal"><input v-model="model.unitPrincipal" class="ctl" /></FormField>
          <FormField label="负责人手机" required :error="errors.unitPrincipalPhone"><input v-model="model.unitPrincipalPhone" class="ctl mono" /></FormField>
          <FormField label="使用单位安全管理员" required :error="errors.elevatorAdminister"><input v-model="model.elevatorAdminister" class="ctl" /></FormField>
          <FormField label="安全管理员手机" required :error="errors.elevatorAdministerPhone"><input v-model="model.elevatorAdministerPhone" class="ctl mono" /></FormField>
          <FormField label="紧急联系电话" required :error="errors.emergencyPhone"><input v-model="model.emergencyPhone" class="ctl mono" /></FormField>
        </div>
      </Form>
    </Modal>

    <!-- 删除确认 -->
    <Modal :open="!!toDelete" title="删除使用单位" :sub="toDelete?.unitName || ''" icon="ban" danger note="client:edit"
      require-confirm confirm-text="我已知悉，确认删除该使用单位档案" confirm-label="确认删除" @close="toDelete = null" @confirm="submitDelete">
      <div class="rule-note">
        <svg class="ic" aria-hidden="true"><use href="#i-alert" /></svg>
        <span>删除后不可恢复。名下有电梯的使用单位将被后端拒绝删除（请先在电梯台账移除关联）。</span>
      </div>
    </Modal>

    <!-- 客户账号 新增/编辑 -->
    <Modal :open="showAccountForm" :title="accountFormId ? '编辑客户账号' : '开通客户账号'"
      sub="客户账号独立于员工体系，绑定本单位" icon="user-check" note="client:edit"
      :confirm-label="accountFormId ? '保存' : '确认开通'" @close="showAccountForm = false" @confirm="submitAccount">
      <Form ref="accountFormRef" :rules="accountRules" v-model="accountModel" v-slot="{ errors }">
        <div class="form-grid">
          <FormField label="客户联系人姓名" required :error="errors.name"><input v-model="accountModel.name" class="ctl" placeholder="如：王芳" /></FormField>
          <FormField label="登录手机号" required :error="errors.phone"><input v-model="accountModel.phone" class="ctl mono" placeholder="11 位手机号" /></FormField>
          <FormField label="客户角色" required>
            <select v-model="accountModel.role" class="ctl">
              <option value="CLIENT_ADMIN">使用单位管理员（主账号/默认联系人）</option>
              <option value="CLIENT_SIGNER">使用单位签字人</option>
            </select>
          </FormField>
          <FormField v-if="!accountFormId" label="初始口令（留空取手机号后 6 位）">
            <input v-model="accountModel.password" class="ctl mono" placeholder="至少 6 位" />
          </FormField>
        </div>
        <div class="fhelp mt8">同一手机号可开通多个单位的客户账号；本单位内手机号唯一，且不能与内部员工手机号冲突。开通凭据仅回显一次，请安全转交。</div>
      </Form>
    </Modal>

    <!-- 临时恢复宽限 -->
    <Modal :open="!!graceAccount" title="临时恢复宽限" :sub="graceAccount?.name || ''" icon="clock" note="client:edit"
      confirm-label="确认恢复" @close="graceAccount = null" @confirm="submitGrace">
      <div class="form-grid">
        <FormField label="宽限截止时间（留空默认 30 天）">
          <input v-model="graceUntil" class="ctl mono" placeholder="yyyy-MM-dd HH:mm:ss" />
        </FormField>
      </div>
      <div class="fhelp mt8">按账号单条恢复，仅保留只读/下载/签待签能力，不恢复报修/评价。操作留审计，不做批量恢复。</div>
    </Modal>

    <!-- 一次性凭据回显（开通/重置） -->
    <Modal :open="!!lastCredential" :title="lastCredential?.title || '凭据'" icon="key" note="client:edit"
      confirm-label="我已保存" @close="lastCredential = null" @confirm="lastCredential = null">
      <div class="rule-note"><svg class="ic" aria-hidden="true"><use href="#i-key" /></svg><span class="mono" style="font-size:16px;letter-spacing:1px">{{ lastCredential?.value }}</span></div>
      <div class="rule-note mt8"><svg class="ic" aria-hidden="true"><use href="#i-alert" /></svg><span>此凭据仅显示一次，关闭后无法再次查看。请立即通过安全渠道转交客户，并提示首次登录后修改。</span></div>
    </Modal>
  </div>
</template>
<script setup>
// 字段口径：unitPrincipal=使用单位负责人、elevatorAdminister=使用单位安全管理员（平台 2.6 冻结字段）。
import { ref, onMounted } from 'vue'
import DataTable from '../ui/DataTable.vue'
import Drawer from '../ui/Drawer.vue'
import Modal from '../ui/Modal.vue'
import Form from '../ui/Form.vue'
import FormField from '../ui/FormField.vue'
import PermButton from '../ui/PermButton.vue'
import StatusTag from '../ui/StatusTag.vue'
import ViewHead from '../ui/ViewHead.vue'
import PageMeta from '../ui/PageMeta.vue'
import RuleNote from '../ui/RuleNote.vue'
import { useToast } from '../ui/stores/ui'
import {
  useUnits, createUseUnit, updateUseUnit, deleteUseUnit,
  clientAccounts, createClientAccount, updateClientAccount,
  disableClientAccount, enableClientAccount, resetClientPassword, restoreClientGrace
} from '../api/archive'
const toast = useToast()
const rows = ref([])
const loading = ref(false)
const detail = ref(null)
const cols = [
  { key: 'unitName', label: '单位名称', sortable: true },
  { key: 'unitPrincipal', label: '负责人 / 手机' },
  { key: 'elevatorAdminister', label: '安全管理员 / 手机' },
  { key: 'emergencyPhone', label: '紧急联系电话' },
  { key: 'elevatorCount', label: '台量', cls: 'num', sortable: true },
  { key: 'entityId', label: '主体ID' }
]
async function load() {
  loading.value = true
  try {
    const list = await useUnits()
    rows.value = Array.isArray(list) ? list : (list && list.list) || []
  } catch (e) { toast.error(e.message || '加载失败') } finally { loading.value = false }
}
function openDetail(row) { detail.value = row; loadAccounts(row.id) }

const accounts = ref([])
const accountsLoading = ref(false)
async function loadAccounts(useUnitId) {
  accountsLoading.value = true
  try {
    const list = await clientAccounts(useUnitId)
    accounts.value = Array.isArray(list) ? list : (list && list.list) || []
  } catch (e) { accounts.value = []; toast.error(e.message || '账号列表加载失败') }
  finally { accountsLoading.value = false }
}

const showAccountForm = ref(false)
const accountFormId = ref(null)
const accountModel = ref({})
const accountRules = {
  name: [(v) => (v && v.trim() ? '' : '请输入客户联系人姓名')],
  phone: [(v) => (/^1\d{10}$/.test((v || '').trim()) ? '' : '请输入 11 位手机号')]
}
function openAccountCreate() {
  accountFormId.value = null
  accountModel.value = { name: '', phone: '', role: 'CLIENT_ADMIN', password: '' }
  showAccountForm.value = true
}
function openAccountEdit(a) {
  accountFormId.value = a.id
  accountModel.value = { name: a.name || '', phone: a.phone || '', role: a.role || 'CLIENT_ADMIN', password: '' }
  showAccountForm.value = true
}
const lastCredential = ref(null) // 开通/重置后的一次性凭据回显
async function submitAccount() {
  const unitId = detail.value && detail.value.id
  if (!unitId || !accountModel.value.name || !/^1\d{10}$/.test((accountModel.value.phone || '').trim())) {
    toast.error('请填写姓名与 11 位手机号'); return
  }
  const body = {
    name: accountModel.value.name.trim(),
    phone: accountModel.value.phone.trim(),
    role: accountModel.value.role
  }
  if (accountFormId.value) {
    delete body.password
    body.role = accountModel.value.role
  } else if (accountModel.value.password) {
    body.password = accountModel.value.password
  }
  try {
    let res
    if (accountFormId.value) {
      res = await updateClientAccount(unitId, accountFormId.value, body)
      toast.success('客户账号已保存')
    } else {
      res = await createClientAccount(unitId, body)
      toast.success('客户账号已开通')
      const pwd = (res && (res.initialPassword || res.password)) || body.password || ''
      if (pwd) lastCredential.value = { title: '初始凭据（仅显示一次，请立即转交客户）', value: pwd }
    }
    showAccountForm.value = false
    loadAccounts(unitId)
  } catch (e) { toast.error(e.message || '保存客户账号失败') }
}
async function doAccountAction(a, action) {
  const unitId = detail.value && detail.value.id
  if (!unitId) return
  try {
    if (action === 'disable') { await disableClientAccount(unitId, a.id, '管理端停用'); toast.success('已停用') }
    else if (action === 'enable') { await enableClientAccount(unitId, a.id); toast.success('已启用') }
    else if (action === 'reset') {
      const res = await resetClientPassword(unitId, a.id)
      const pwd = res && res.password
      if (pwd) lastCredential.value = { title: '新口令（仅显示一次，请立即转交客户）', value: pwd }
      toast.success('口令已重置')
    }
    loadAccounts(unitId)
  } catch (e) { toast.error(e.message || '操作失败') }
}
const graceAccount = ref(null)
const graceUntil = ref('')
function openGrace(a) { graceAccount.value = a; graceUntil.value = '' }
async function submitGrace() {
  const unitId = detail.value && detail.value.id
  try {
    await restoreClientGrace(unitId, graceAccount.value.id, graceUntil.value)
    toast.success('已临时恢复宽限')
    graceAccount.value = null
    loadAccounts(unitId)
  } catch (e) { toast.error(e.message || '恢复宽限失败') }
}

// 新增 / 编辑
const showForm = ref(false)
const formId = ref(null)
const formRef = ref(null)
const empty = () => ({ unitName: '', organizationCode: '', unitPrincipal: '', unitPrincipalPhone: '', elevatorAdminister: '', elevatorAdministerPhone: '', emergencyPhone: '' })
const model = ref(empty())
const rules = {
  unitName: [(v) => (v && v.trim() ? '' : '请输入单位名称')],
  unitPrincipal: [(v) => (v && v.trim() ? '' : '请输入使用单位负责人')],
  unitPrincipalPhone: [(v) => (v && v.trim() ? '' : '请输入负责人手机')],
  elevatorAdminister: [(v) => (v && v.trim() ? '' : '请输入安全管理员')],
  elevatorAdministerPhone: [(v) => (v && v.trim() ? '' : '请输入安全管理员手机')],
  emergencyPhone: [(v) => (v && v.trim() ? '' : '请输入紧急联系电话')]
}
function openCreate() { formId.value = null; model.value = empty(); showForm.value = true }
function openEdit(row) {
  formId.value = row.id
  model.value = {
    unitName: row.unitName || '', organizationCode: row.organizationCode || '',
    unitPrincipal: row.unitPrincipal || '', unitPrincipalPhone: row.unitPrincipalPhone || '',
    elevatorAdminister: row.elevatorAdminister || '', elevatorAdministerPhone: row.elevatorAdministerPhone || '',
    emergencyPhone: row.emergencyPhone || ''
  }
  detail.value = null
  showForm.value = true
}
function conflictMessage(e) {
  if (e && e.code === 1002 && Array.isArray(e.data && e.data.conflicts) && e.data.conflicts.length) {
    return '手机号互斥：' + e.data.conflicts.join('；')
  }
  return (e && e.message) || '保存失败'
}
function syncHint(s) {
  return { OK: ' · 平台主体ID已回填', NO_MATCH: ' · 平台未查到该单位主体，请核对名称与统一社会信用代码', FAILED: ' · 平台同步失败，可稍后重试' }[s] || ''
}
async function submit() {
  if (!formRef.value.validate()) return
  if (model.value.unitPrincipalPhone.trim() === model.value.elevatorAdministerPhone.trim()) {
    toast.error('负责人手机与安全管理员手机不可相同'); return
  }
  const body = {
    unitName: model.value.unitName.trim(), organizationCode: model.value.organizationCode.trim(),
    unitPrincipal: model.value.unitPrincipal.trim(), unitPrincipalPhone: model.value.unitPrincipalPhone.trim(),
    elevatorAdminister: model.value.elevatorAdminister.trim(), elevatorAdministerPhone: model.value.elevatorAdministerPhone.trim(),
    emergencyPhone: model.value.emergencyPhone.trim()
  }
  try {
    if (formId.value) {
      const res = await updateUseUnit(formId.value, body)
      toast.success('使用单位已保存' + syncHint(res && res.platformSync))
    } else {
      const res = await createUseUnit(body)
      toast.success('使用单位已创建' + syncHint(res && res.platformSync))
    }
    showForm.value = false
    load()
  } catch (e) { toast.error(conflictMessage(e)) }
}

// 删除
const toDelete = ref(null)
function openDelete(row) { toDelete.value = row; detail.value = null }
async function submitDelete() {
  try {
    await deleteUseUnit(toDelete.value.id)
    toast.success('使用单位已删除')
    toDelete.value = null
    load()
  } catch (e) { toast.error(e.message || '删除失败') }
}
onMounted(load)
</script>
