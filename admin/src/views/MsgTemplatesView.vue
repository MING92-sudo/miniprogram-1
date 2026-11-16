<template>
  <div>
    <ViewHead title="消息模板" sub="微信订阅消息通知模板 · 与「签字模板」区分（此为通知消息）">
      <template #meta><PageMeta perm="config:manage" :extra="[{ k: '模板数', v: rows.length, icon: 'message', cls: 'count' }]" /></template>
    </ViewHead>
    <div class="notice-bar mt16">
      <svg class="ic" aria-hidden="true"><use href="#i-info" /></svg>
      <span><b>演示数据（原型基准集）。</b>使用单位签字提醒走微信订阅消息，不依赖短信；变量由服务端发送时渲染。</span>
    </div>
    <div class="mt12">
      <DataTable :columns="cols" :rows="rows" :search-keys="['name', 'scene', 'content']"
        search-placeholder="搜索模板名 / 场景" empty-hint="暂无消息模板。"
        :filters="[{ key: 'scene', label: '场景', options: ['签字提醒', '工单派单', '合同临期', '超时升级', '计划提醒'] }]"
        clickable @row-click="openEdit">
        <template #toolbar>
          <PermButton code="config:manage" label="新增模板" variant="primary" icon="plus" @click="openAdd" />
        </template>
        <template #cell-name="{ row }"><b>{{ row.name }}</b><div class="cell-2 mono">{{ row.id }}</div></template>
        <template #cell-scene="{ value }"><span class="tag tag-ghost">{{ value }}</span></template>
        <template #cell-channel="{ value }">
          <span class="f12"><svg class="ic ic-sm" aria-hidden="true"><use href="#i-weixin" /></svg> {{ value }}</span>
        </template>
        <template #cell-content="{ value }"><span class="f12 muted">{{ value }}</span></template>
        <template #cell-enabled="{ value }"><span :class="['tag', value ? 'tag-ok' : 'tag-dim']">{{ value ? '启用' : '停用' }}</span></template>
        <template #row-actions="{ row }">
          <button class="btn btn-ghost btn-xs" type="button" @click="openEdit(row)">
            <svg class="ic" aria-hidden="true"><use href="#i-edit" /></svg><span>编辑</span>
          </button>
          <button class="btn btn-ghost btn-xs" type="button" @click="toggle(row)">
            <svg class="ic" aria-hidden="true"><use :href="row.enabled ? '#i-ban' : '#i-check'" /></svg><span>{{ row.enabled ? '停用' : '启用' }}</span>
          </button>
          <button class="btn btn-ghost btn-xs" type="button" @click="preview = row">
            <svg class="ic" aria-hidden="true"><use href="#i-eye" /></svg><span>预览</span>
          </button>
        </template>
      </DataTable>
    </div>
    <RuleNote class="mt12">变量用 <span class="mono">{{ '{{var}}' }}</span> 占位，发送时由服务端渲染；模板启停写审计（演示）。</RuleNote>

    <Modal :open="!!editing" :title="editing ? (isNew ? '新增消息模板' : '编辑消息模板') : ''"
      :sub="editing && !isNew ? editing.id + ' · ' + editing.scene : '模板名 2–20 字'" icon="message" note="config:manage"
      :confirm-label="isNew ? '保存' : '保存'" @close="editing = null" @confirm="save">
      <template v-if="editing">
        <div class="form-grid one">
          <FormField label="模板名" required :error="errors.name">
            <input v-model="editing.name" class="ctl" placeholder="2–20 字" />
          </FormField>
          <FormField label="场景" required :error="errors.scene">
            <input v-model="editing.scene" class="ctl" placeholder="如 工单派单 / 签字提醒" />
          </FormField>
          <FormField label="模板内容" required hint="{{var}}" :error="errors.content">
            <textarea v-model="editing.content" class="ctl" placeholder="变量用 {{var}} 占位" />
          </FormField>
        </div>
        <RuleNote class="mt12" icon="eye">实时预览：</RuleNote>
        <div class="msg-preview mt6">{{ renderPreview(editing.content) }}</div>
      </template>
    </Modal>

    <Modal :open="!!preview" title="模板预览" :sub="preview ? preview.name : ''" icon="message" confirm-label="关闭" @close="preview = null" @confirm="preview = null">
      <template v-if="preview">
        <div class="msg-preview">{{ renderPreview(preview.content) }}</div>
        <div class="fhelp mt8">渠道：{{ preview.channel }} · 变量由服务端在发送时渲染。</div>
      </template>
    </Modal>
  </div>
</template>
<script setup>
// 二期：消息模板（原型 Views.templates 对齐；演示数据＝原型基准集，真实接口后续接入）
import { ref, reactive } from 'vue'
import { M } from '../ui/prototype-mock'
import { useToast } from '../ui/stores/ui'
import DataTable from '../ui/DataTable.vue'
import Modal from '../ui/Modal.vue'
import FormField from '../ui/FormField.vue'
import ViewHead from '../ui/ViewHead.vue'
import PageMeta from '../ui/PageMeta.vue'
import RuleNote from '../ui/RuleNote.vue'
import PermButton from '../ui/PermButton.vue'
const toast = useToast()
const rows = reactive(M.MSG_TEMPLATES.map(t => ({ ...t })))
const editing = ref(null)
const isNew = ref(false)
const preview = ref(null)
const errors = reactive({ name: '', scene: '', content: '' })
const cols = [
  { key: 'name', label: '模板名', sortable: true },
  { key: 'scene', label: '场景' },
  { key: 'channel', label: '渠道' },
  { key: 'content', label: '模板内容' },
  { key: 'enabled', label: '状态' }
]
function renderPreview(content) {
  return String(content || '')
    .replace(/\{\{unit\}\}/g, '启元物业').replace(/\{\{project\}\}/g, '城东大厦').replace(/\{\{elevator\}\}/g, '2# 客梯')
    .replace(/\{\{recordNo\}\}/g, 'WB-2026-1113-0231').replace(/\{\{deadline\}\}/g, '2026-11-14 18:00').replace(/\{\{type\}\}/g, '急修')
    .replace(/\{\{orderNo\}\}/g, 'JX-2026-1113-007').replace(/\{\{fault\}\}/g, '困人 · 2# 客梯停在 8-9 层间')
    .replace(/\{\{endDate\}\}/g, '2026-12-31').replace(/\{\{daysLeft\}\}/g, '46').replace(/\{\{overtimeMin\}\}/g, '12').replace(/\{\{planDate\}\}/g, '2026-11-25')
}
function openEdit(t) { isNew.value = false; errors.name = errors.scene = errors.content = ''; editing.value = { ...t, _ref: t } }
function openAdd() { isNew.value = true; errors.name = errors.scene = errors.content = ''; editing.value = { name: '', scene: '', content: '', channel: '微信订阅消息', enabled: true } }
function toggle(t) { t.enabled = !t.enabled; toast.success('模板已' + (t.enabled ? '启用' : '停用'), t.id) }
function save() {
  const e = editing.value
  errors.name = (e.name.length >= 2 && e.name.length <= 20) ? '' : '模板名须为 2–20 字'
  errors.scene = e.scene ? '' : '场景必填'
  errors.content = e.content.length >= 5 ? '' : '模板内容至少 5 字'
  if (errors.name || errors.scene || errors.content) { toast.warn('请修正表单错误后再提交'); return }
  if (isNew.value) {
    const id = 'MSG-' + String(rows.length + 1).padStart(2, '0')
    rows.push({ id, name: e.name, scene: e.scene, channel: '微信订阅消息', content: e.content, enabled: true })
    toast.success('模板已新增', id)
  } else {
    Object.assign(e._ref, { name: e.name, scene: e.scene, content: e.content })
    toast.success('模板已保存', e._ref.id)
  }
  editing.value = null
}
</script>