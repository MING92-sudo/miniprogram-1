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
            <input v-model="form.name" class="ctl" placeholder="如：重庆博威电梯有限公司" />
          </FormField>
          <FormField label="统一社会信用代码" required :error="errors.organizationCode">
            <input v-model="form.organizationCode" class="ctl mono" placeholder="18 位统一社会信用代码" />
          </FormField>
          <FormField label="维保经理姓名" required :error="errors.workMenegerName">
            <input v-model="form.workMenegerName" class="ctl" placeholder="2.6 上报 workMenegerName" />
          </FormField>
          <FormField label="维保经理手机" required :error="errors.workMenegerPhone">
            <input v-model="form.workMenegerPhone" class="ctl mono" placeholder="11 位手机号，与其他四类人员互斥" />
          </FormField>
          <FormField label="平台主体 ID（只读）">
            <input :value="form.entityId || '—'" class="ctl mono" disabled />
          </FormField>
          <FormField label="平台 orgid（只读）">
            <input :value="form.orgId || '—'" class="ctl mono" disabled />
          </FormField>
        </div>
        <div class="row mt12">
          <PermButton code="config:manage" label="保存单位信息" variant="primary" icon="check" @click="save" />
        </div>
      </div>
    </div>
  </div>
</template>
<script setup>
defineProps({ embedded: Boolean })
import { ref, onMounted } from 'vue'
import { company as fetchCompany, updateCompany } from '../api/archive'
import ViewHead from '../ui/ViewHead.vue'
import PageMeta from '../ui/PageMeta.vue'
import RuleNote from '../ui/RuleNote.vue'
import FormField from '../ui/FormField.vue'
import PermButton from '../ui/PermButton.vue'
import { useToast } from '../ui/stores/ui'
const toast = useToast()
const form = ref({ name: '', organizationCode: '', workMenegerName: '', workMenegerPhone: '', entityId: '', orgId: '' })
const errors = ref({})
async function load() {
  try {
    const c = await fetchCompany()
    form.value = {
      name: c.name || '', organizationCode: c.organizationCode || '',
      workMenegerName: c.workMenegerName || '', workMenegerPhone: c.workMenegerPhone || '',
      entityId: c.entityId || '', orgId: c.orgId || ''
    }
  } catch (e) { toast.error(e.message || '加载失败') }
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
  if (!validate()) return
  try {
    await updateCompany({
      name: form.value.name.trim(), organizationCode: form.value.organizationCode.trim(),
      workMenegerName: form.value.workMenegerName.trim(), workMenegerPhone: form.value.workMenegerPhone.trim()
    })
    toast.success('单位信息已保存')
    await load()
  } catch (e) { toast.error(e.message || '保存失败') }
}
onMounted(load)
</script>
