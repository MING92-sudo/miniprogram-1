<template>
  <div>
    <ViewHead title="组织架构" sub="公司 → 部门/项目部；使用单位为单级节点">
      <template #actions>
        <PermButton code="role:assign" label="新建组织" variant="primary" icon="plus" @click="openForm(null, null)" />
        <PermButton code="user:view" label="导出名单" variant="ghost" icon="download" c-tip="需 user:view，当前只读" @click="exportOrg" />
      </template>
      <template #meta><PageMeta perm="user:view" :extra="[{ k: '组织数', v: rows.length, icon: 'sitemap', cls: 'count' }]" /></template>
    </ViewHead>
    <div class="card mt16">
      <div class="card-head">
        <h3><svg class="ic" aria-hidden="true"><use href="#i-sitemap" /></svg>组织树</h3>
        <span class="ch-tag tag tag-dim">{{ scopeLabel(perspective.scope) }}</span>
      </div>
      <div class="card-body">
        <LoadingSkeleton v-if="loading" :rows="6" />
        <div v-else class="tree" @click="onTreeClick" v-html="treeHtml" />
      </div>
    </div>
    <RuleNote class="mt12">使用单位节点禁止再挂子节点（单级，roles L15）；新建/编辑/停用按 role:assign 渲染（仅平台可操作，使用单位管理员置灰）。联系电话按 §6 脱敏。</RuleNote>

    <Drawer :open="!!detail" :title="detail ? detail.name : ''" :sub="detail ? orgTypeLabel(detail.type) + ' · ' + (detail.code || '—') : ''"
      :meta="{ scope: perspective.scope }" note="role:assign" @close="detail = null">
      <template v-if="detail">
        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-building" /></svg>组织信息</div>
          <div class="kv-grid">
            <div class="kv"><span class="k">组织名称</span><span class="v">{{ detail.name }}</span></div>
            <div class="kv"><span class="k">类型</span><span class="v">{{ orgTypeLabel(detail.type) }}</span></div>
            <div class="kv"><span class="k">编码</span><span class="v mono">{{ detail.code || '—' }}</span></div>
            <div class="kv"><span class="k">负责人</span><span class="v">{{ detail.leader || '—' }}</span></div>
            <div class="kv"><span class="k">联系电话</span><span class="v mono">{{ detail.phone || '—' }}</span></div>
            <div class="kv"><span class="k">在管台量</span><span class="v">{{ detail.elevatorCount ?? '—' }}</span></div>
            <div class="kv"><span class="k">账号数</span><span class="v">{{ detail.accountCount ?? '—' }}</span></div>
            <div class="kv"><span class="k">状态</span><span class="v"><StatusTag :status="detail.status || 'NORMAL'" /></span></div>
          </div>
        </div>
        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-layers" /></svg>下级组织</div>
          <div v-if="isFlat(detail)" class="rule-note">
            <svg class="ic" aria-hidden="true"><use href="#i-ban" /></svg>
            <span>使用单位为单级节点，禁止再挂子节点（roles L15）。「新增下级」已禁用。</span>
          </div>
          <div v-else-if="childrenOf(detail.id).length" class="chips">
            <span v-for="k in childrenOf(detail.id)" :key="k.id" class="chip" @click="detail = k">{{ k.name }}</span>
          </div>
          <div v-else class="dim f12">暂无下级组织。</div>
        </div>
      </template>
      <template #footer>
        <span v-if="detail && isFlat(detail)" class="perm-wrap" data-tip="使用单位单级节点，禁止新增下级">
          <button class="btn btn-ghost" disabled type="button"><svg class="ic" aria-hidden="true"><use href="#i-plus" /></svg><span>新增下级</span></button>
        </span>
        <PermButton v-else code="role:assign" label="新增下级" variant="ghost" icon="plus" @click="openForm(null, detail.id)" />
        <PermButton code="role:assign" label="编辑" variant="ghost" icon="edit" @click="openForm(detail.id, null)" />
        <PermButton code="role:assign" label="停用" variant="danger" icon="ban" @click="showDisable = true" />
      </template>
    </Drawer>

    <Modal :open="showForm" :title="formId ? '编辑组织' : '新建组织'" :sub="formParentName" icon="sitemap" note="role:assign"
      confirm-label="保存" @close="showForm = false" @confirm="submitForm">
      <Form ref="formRef" :rules="rules" v-model="model" v-slot="{ errors }">
        <div class="form-grid">
          <FormField label="组织名称" required :error="errors.name"><input v-model="model.name" class="ctl" /></FormField>
          <FormField label="类型" required :error="errors.type">
            <select v-model="model.type" class="ctl">
              <option value="COMPANY">公司</option><option value="DEPT">部门</option>
              <option value="TEAM">班组</option><option value="UNIT">使用单位（单级）</option>
            </select>
          </FormField>
          <FormField label="编码"><input v-model="model.code" class="ctl mono" /></FormField>
          <FormField label="负责人"><input v-model="model.leader" class="ctl" /></FormField>
          <FormField label="联系电话"><input v-model="model.phone" class="ctl mono" /></FormField>
          <FormField v-if="!formId" label="上级组织">
            <select v-model="model.parentId" class="ctl">
              <option value="">无（顶级组织）</option>
              <option v-for="o in parentOptions" :key="o.id" :value="o.id">{{ o.name }}</option>
            </select>
          </FormField>
        </div>
      </Form>
    </Modal>

    <Modal :open="showDisable" title="停用组织" :sub="detail ? detail.name : ''" icon="ban" danger note="role:assign"
      require-confirm confirm-text="我已知悉，确认停用" confirm-label="确认停用" @close="showDisable = false" @confirm="submitDisable">
      <div class="rule-note">
        <svg class="ic" aria-hidden="true"><use href="#i-alert" /></svg>
        <span>停用后该组织账号登录受限，历史数据与审计保留。确认停用？</span>
      </div>
    </Modal>
  </div>
</template>
<script setup>
// 组织架构（原型 Views.org/openOrgDetail/openOrgForm 对齐）：组织树 + 单级禁挂 + 详情抽屉 + 新建/编辑/停用
import { ref, computed, onMounted } from 'vue'
import ViewHead from '../ui/ViewHead.vue'
import PageMeta from '../ui/PageMeta.vue'
import RuleNote from '../ui/RuleNote.vue'
import Drawer from '../ui/Drawer.vue'
import Modal from '../ui/Modal.vue'
import Form from '../ui/Form.vue'
import FormField from '../ui/FormField.vue'
import PermButton from '../ui/PermButton.vue'
import StatusTag from '../ui/StatusTag.vue'
import LoadingSkeleton from '../ui/states/LoadingSkeleton.vue'
import { useToast } from '../ui/stores/ui'
import { exportCsv } from '../ui/export-csv'
import { usePerspectiveStore } from '../ui/stores/perspective'
import { orgTypeLabel, scopeLabel } from '../ui/labels'
import { listOrg, createOrg, updateOrg } from '../api/ledger'
const toast = useToast()
const perspective = usePerspectiveStore()
const rows = ref([])
const loading = ref(false)
const detail = ref(null)
const showForm = ref(false)
const showDisable = ref(false)
const formId = ref(null)
const formParent = ref(null)
const formRef = ref(null)
const model = ref({ name: '', type: 'DEPT', code: '', leader: '', phone: '', parentId: '' })
const rules = { name: [(v) => (v ? '' : '请输入组织名称')], type: [(v) => (v ? '' : '类型必填')] }
function isFlat(o) { return o && (o.type === 'UNIT' || o.flat) }
function childrenOf(id) { return rows.value.filter(o => o.parentId === id) }
const parentOptions = computed(() => rows.value.filter(o => !isFlat(o)))
const formParentName = computed(() => {
  if (formId.value) return '编辑组织信息'
  const p = formParent.value ? rows.value.find(o => o.id === formParent.value) : null
  return p ? '上级：' + p.name : '顶级组织'
})
// 原型 nodeHtml 同款树 markup（事件经容器委托）
function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])) }
function nodeHtml(o) {
  const kids = childrenOf(o.id)
  const toggle = kids.length
    ? '<span class="tree-toggle open"><svg class="ic" aria-hidden="true"><use href="#i-chevron-right"/></svg></span>'
    : '<span class="tree-toggle"><svg class="ic" aria-hidden="true"><use href="#i-minus"/></svg></span>'
  const ico = o.type === 'UNIT' ? 'building' : o.type === 'COMPANY' ? 'sitemap' : 'sitemap'
  let h = '<li><div class="tree-node" data-org="' + esc(o.id) + '">' + toggle +
    '<span class="tn-ico"><svg class="ic" aria-hidden="true"><use href="#i-' + ico + '"/></svg></span>' +
    '<span class="tn-name">' + esc(o.name) + '</span>' +
    '<span class="tn-type">' + esc(orgTypeLabel(o.type)) + (isFlat(o) ? ' · 单级' : '') + '</span>' +
    '<span class="tn-meta">' + (o.status === 'DISABLED' ? '<span class="tag tag-dim">停用</span>' : '') +
    '台量 ' + esc(o.elevatorCount ?? '—') + ' · 账号 ' + esc(o.accountCount ?? '—') + '</span></div>'
  if (kids.length) h += '<ul>' + kids.map(nodeHtml).join('') + '</ul>'
  return h + '</li>'
}
const treeHtml = computed(() => {
  const ids = new Set(rows.value.map(o => o.id))
  const roots = rows.value.filter(o => !o.parentId || !ids.has(o.parentId))
  return '<ul>' + roots.map(nodeHtml).join('') + '</ul>'
})
function onTreeClick(e) {
  const n = e.target.closest('[data-org]')
  if (!n) return
  const o = rows.value.find(x => x.id === n.getAttribute('data-org'))
  if (o) detail.value = o
}
async function load() { loading.value = true; try { rows.value = await listOrg() } finally { loading.value = false } }
function openForm(id, parentId) {
  if (parentId) {
    const p = rows.value.find(o => o.id === parentId)
    if (p && isFlat(p)) { toast.warn('使用单位单级节点，禁止新增下级'); return }
  }
  formId.value = id
  formParent.value = parentId
  const o = id ? rows.value.find(x => x.id === id) : null
  model.value = { name: o ? o.name : '', type: o ? o.type : 'DEPT', code: o ? o.code || '' : '', leader: o ? o.leader || '' : '', phone: o ? o.phone || '' : '', parentId: parentId || '' }
  showForm.value = true
}
async function submitForm() {
  if (!formRef.value.validate()) return
  const body = { name: model.value.name, type: model.value.type, code: model.value.code, leader: model.value.leader, phone: model.value.phone }
  try {
    if (formId.value) { await updateOrg(formId.value, body); toast.success('组织已保存') }
    else { await createOrg({ ...body, parentId: model.value.parentId || null }); toast.success('组织已保存') }
    showForm.value = false; detail.value = null; load()
  } catch (e) { toast.error(e.message || '保存失败') }
}
async function submitDisable() {
  try {
    await updateOrg(detail.value.id, { status: 'DISABLED' })
    toast.success('组织已停用'); showDisable.value = false; detail.value = null; load()
  } catch (e) { toast.error(e.message || '停用失败') }
}
function exportOrg() {
  const n = exportCsv('组织名单', [
    { key: 'name', label: '组织名称' },
    { key: 'type', label: '类型' },
    { key: 'code', label: '编码' },
    { key: 'leader', label: '负责人' },
    { key: 'phone', label: '联系电话' },
    { key: 'status', label: '状态' }
  ], rows.value)
  toast.success('已导出 ' + n + ' 个组织', 'CSV · 按导出人权限脱敏')
}
onMounted(load)
</script>