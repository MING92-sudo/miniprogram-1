<template>
  <div>
    <ViewHead v-if="!embedded" title="单位信息" sub="维保单位档案 · 维保经理为监管平台 2.6 上报必传项">
      <template #meta><PageMeta perm="config:manage" /></template>
    </ViewHead>
    <div class="card card-pad0 mt16">
      <div class="card-head"><h3><svg class="ic" aria-hidden="true"><use href="#i-building" /></svg>维保单位档案</h3></div>
      <div class="card-body">
        <RuleNote icon="alert" class="mb8">
          维保经理姓名 / 手机 = 监管平台 2.6 上报必传项（workMenegerName / workMenegerPhone，规范原文拼写如此）。
          手机与使用单位负责人、安全管理员、维保人员 1/2 五类互斥（冲突 1002）。应急电话按电梯档案逐台维护，不在此处。
        </RuleNote>
        <div class="form-grid">
          <FormField label="维保单位名称" required :error="errors.name">
            <input v-model="form.name" class="ctl" :disabled="!editing" placeholder="如：重庆博威电梯有限公司" />
          </FormField>
          <FormField label="统一社会信用代码" required :error="errors.organizationCode">
            <input v-model="form.organizationCode" class="ctl mono" :disabled="!editing" placeholder="18 位统一社会信用代码" />
          </FormField>
          <FormField label="维保经理姓名" required :error="errors.workMenegerName">
            <input v-model="form.workMenegerName" class="ctl" :disabled="!editing" placeholder="2.6 上报 workMenegerName" />
          </FormField>
          <FormField label="维保经理手机" required :error="errors.workMenegerPhone">
            <input v-model="form.workMenegerPhone" class="ctl mono" :disabled="!editing" placeholder="11 位手机号，与其他四类人员互斥" />
          </FormField>
          <FormField label="平台主体 ID（只读）">
            <input :value="form.entityId || '—'" class="ctl mono" disabled />
          </FormField>
        </div>
        <div class="row mt12">
          <template v-if="editing">
            <PermButton code="config:manage" :label="saving ? '提交中…' : '保存单位信息'" variant="primary" icon="check" @click="save" />
            <PermButton v-if="canRetry" code="config:manage" :label="saving ? '提交中…' : '重试同步主体ID'" variant="ghost" icon="refresh" @click="save" />
          </template>
          <template v-else>
            <PermButton code="config:manage" label="编辑" variant="ghost" icon="edit" @click="startEdit" />
            <PermButton code="config:manage" :label="saving ? '提交中…' : '重新同步'" variant="ghost" icon="refresh" @click="save" />
          </template>
        </div>
        <RuleNote v-if="!editing" class="mt12">
          当前档案已记录平台主体ID，名称与统一社会信用代码以平台为准；需修改请点「编辑」，平台侧变更可点「重新同步」复核。
        </RuleNote>
        <RuleNote v-else-if="syncHint" icon="alert" class="mt12">
          主体ID未同步：{{ syncHint }}。保存内容已生效{{ canRetry ? '，可点「重试同步主体ID」' : '' }}。
        </RuleNote>
      </div>
    </div>
  </div>
</template>
<script setup>
defineProps({ embedded: Boolean })
import { ref, computed, onMounted } from 'vue'
import { company as fetchCompany, updateCompany } from '../api/archive'
import ViewHead from '../ui/ViewHead.vue'
import PageMeta from '../ui/PageMeta.vue'
import RuleNote from '../ui/RuleNote.vue'
import FormField from '../ui/FormField.vue'
import PermButton from '../ui/PermButton.vue'
import { useToast } from '../ui/stores/ui'
const toast = useToast()
const SYNC_HINT = {
  SKIPPED: '监管平台凭证未配置',
  NO_MATCH: '平台未查询到该单位主体，请核对名称与统一社会信用代码',
  FAILED: '平台同步失败，可稍后重试'
}
const form = ref({ name: '', organizationCode: '', workMenegerName: '', workMenegerPhone: '', entityId: '' })
const errors = ref({})
const editing = ref(true)
const saved = ref(false)
const saving = ref(false)
const syncState = ref('')
const syncHint = computed(() => (saved.value ? SYNC_HINT[syncState.value] || '' : ''))
const canRetry = computed(() => saved.value && ['NO_MATCH', 'FAILED'].includes(syncState.value))
function apply(c) {
  form.value = {
    name: c.name || '', organizationCode: c.organizationCode || '',
    workMenegerName: c.workMenegerName || '', workMenegerPhone: c.workMenegerPhone || '',
    entityId: c.entityId || ''
  }
}
async function load() {
  try {
    const c = await fetchCompany()
    apply(c)
    saved.value = true
    syncState.value = ''
    editing.value = !c.entityId
  } catch (e) { toast.error(e.message || '加载失败') }
}
function startEdit() {
  editing.value = true
}
function validate() {
  const e = {}
  if (!form.value.name.trim()) e.name = '维保单位名称必填'
  if (!form.value.organizationCode.trim()) e.organizationCode = '统一社会信用代码必填'
  if (!form.value.workMenegerName.trim()) e.workMenegerName = '维保经理姓名必填（2.6 必传项）'
  if (!/^1[3-9]\d{9}$/.test(form.value.workMenegerPhone.trim())) e.workMenegerPhone = '维保经理手机须为 11 位手机号（2.6 必传项）'
  errors.value = e
  return !Object.keys(e).length
}
async function save() {
  if (saving.value || !validate()) return
  saving.value = true
  try {
    const row = await updateCompany({
      name: form.value.name.trim(), organizationCode: form.value.organizationCode.trim(),
      workMenegerName: form.value.workMenegerName.trim(), workMenegerPhone: form.value.workMenegerPhone.trim()
    })
    apply(row)
    saved.value = true
    syncState.value = row.platformSync || ''
    if (row.platformSync === 'OK' && row.entityId) {
      editing.value = false
      toast.success('单位信息已保存，平台主体ID已同步')
    } else if (row.platformSync === 'SKIPPED') {
      toast.success('单位信息已保存（监管平台凭证未配置，未同步主体ID）')
    } else {
      toast.error('单位信息已保存，但平台主体ID未同步：' + (SYNC_HINT[syncState.value] || '未知原因'))
    }
  } catch (e) { toast.error(e.message || '保存失败') } finally { saving.value = false }
}
onMounted(load)
</script>
