<template>
  <div class="page">
    <div class="page-head">
      <h1>授权管理</h1>
      <div class="acts">
        <PermButton code="invite:manage" label="生成邀请码" @click="showInvite = true" />
        <PermButton code="role:assign" label="发起限时授权" @click="showGrant = true" />
      </div>
    </div>
    <h2 class="label">邀请码 / 组织二维码</h2>
    <DataTable :columns="invCols" :rows="invites" :loading="loading" />
    <h2 class="label">限时授权</h2>
    <DataTable :columns="grantCols" :rows="grants" :loading="loading" />

    <Modal :open="showInvite" title="生成邀请码" @close="showInvite = false" @confirm="submitInvite">
      <FormField label="有效期（天，1—30）"><input type="number" v-model="invDays" /></FormField>
    </Modal>
    <Modal :open="showGrant" title="发起限时授权" @close="showGrant = false" @confirm="submitGrant">
      <Form ref="grantForm" :rules="grantRules" v-model="grantModel" v-slot="{ errors }">
        <FormField label="被授权人 ID" required :error="errors.granteeId"><input v-model="grantModel.granteeId" /></FormField>
        <FormField label="追加权限点" required :error="errors.permCode"><input v-model="grantModel.permCode" placeholder="如 order:dispatch" /></FormField>
        <FormField label="开始" required :error="errors.startAt"><input type="datetime-local" v-model="grantModel.startAt" /></FormField>
        <FormField label="结束" required :error="errors.endAt"><input type="datetime-local" v-model="grantModel.endAt" /></FormField>
      </Form>
    </Modal>
  </div>
</template>
<script setup>
// M3 授权管理：邀请码 + 限时授权（发起/撤回），end>start 校验
import { ref, onMounted } from 'vue'
import DataTable from '../ui/DataTable.vue'
import Modal from '../ui/Modal.vue'
import Form from '../ui/Form.vue'
import FormField from '../ui/FormField.vue'
import PermButton from '../ui/PermButton.vue'
import { useToast } from '../ui/stores/ui'
import { listInvites, createInvite, listGrants, createGrant } from '../api/ledger'
const toast = useToast()
const invites = ref([])
const grants = ref([])
const loading = ref(false)
const showInvite = ref(false)
const showGrant = ref(false)
const invDays = ref(7)
const grantForm = ref(null)
const grantModel = ref({ granteeId: '', permCode: '', startAt: '', endAt: '' })
const grantRules = {
  granteeId: [(v) => (v ? '' : '被授权人必填')],
  permCode: [(v) => (v ? '' : '权限点必填')],
  startAt: [(v) => (v ? '' : '开始必填')],
  endAt: [(v) => (v ? '' : '结束必填')]
}
const invCols = [
  { key: 'code', label: '码值' }, { key: 'type', label: '类型' }, { key: 'validDays', label: '有效期(天)' },
  { key: 'status', label: '状态' }, { key: 'joinedCount', label: '已加入' }
]
const grantCols = [
  { key: 'granteeId', label: '被授权人' }, { key: 'permCode', label: '追加权限点' },
  { key: 'startAt', label: '起' }, { key: 'endAt', label: '止' }, { key: 'status', label: '状态' }
]
async function load() { loading.value = true; try { invites.value = await listInvites(); grants.value = await listGrants() } finally { loading.value = false } }
async function submitInvite() {
  try { await createInvite({ validDays: +invDays.value }); toast.success('邀请码已生成'); showInvite.value = false; load() }
  catch (e) { toast.error(e.message || '生成失败') }
}
async function submitGrant() {
  if (!grantForm.value.validate()) return
  if (new Date(grantModel.value.endAt) <= new Date(grantModel.value.startAt)) { toast.error('结束必须晚于开始'); return }
  try {
    await createGrant({ ...grantModel.value, startAt: grantModel.value.startAt.replace('T', 'T') + ':00', endAt: grantModel.value.endAt + ':00' })
    toast.success('授权已发起'); showGrant.value = false; load()
  } catch (e) { toast.error(e.message || '发起失败') }
}
onMounted(load)
</script>
<style scoped>
.page-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-head h1 { font-size: 20px; }
.acts { display: flex; gap: 8px; }
h2.label { margin: 18px 0 8px; }
.page input { width: 100%; padding: 8px; background: var(--panel-2); border: 1px solid var(--line-2); border-radius: 6px; color: var(--text); }
</style>