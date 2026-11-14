<template>
  <div class="page">
    <div class="page-head">
      <h1>账号管理</h1>
      <PermButton code="user:create" label="开通账号" @click="showCreate = true" />
    </div>
    <DataTable :columns="cols" :rows="rows" :loading="loading" :search-keys="['name', 'phone']" />
    <Modal :open="showCreate" title="开通账号" @close="showCreate = false" @confirm="submit">
      <Form ref="formRef" :rules="rules" v-model="model" v-slot="{ errors }">
        <FormField label="姓名" required :error="errors.name"><input v-model="model.name" /></FormField>
        <FormField label="手机号" required :error="errors.phone"><input v-model="model.phone" /></FormField>
        <FormField label="角色（同部门，可多选逗号分隔）" required :error="errors.roles"><input v-model="model.rolesText" placeholder="如：维保技术员,维保主管" /></FormField>
      </Form>
      <p v-if="initialPwd" class="pwd">初始口令：{{ initialPwd }}（一次性展示）</p>
    </Modal>
  </div>
</template>
<script setup>
// M3 账号管理：列表/开通/停用/分配角色；停用吊销 token
import { ref, onMounted } from 'vue'
import DataTable from '../ui/DataTable.vue'
import Modal from '../ui/Modal.vue'
import Form from '../ui/Form.vue'
import FormField from '../ui/FormField.vue'
import PermButton from '../ui/PermButton.vue'
import { useToast } from '../ui/stores/ui'
import { roleCodeFromName } from '../ui/labels'
import { listUsers, createUser } from '../api/ledger'
const toast = useToast()
const rows = ref([])
const loading = ref(false)
const showCreate = ref(false)
const formRef = ref(null)
const initialPwd = ref('')
const model = ref({ name: '', phone: '', rolesText: '' })
const rules = {
  name: [(v) => (v ? '' : '姓名必填')],
  phone: [(v) => (/^1[3-9]\d{9}$/.test(v || '') ? '' : '手机号格式错误')],
  rolesText: [(v) => (v ? '' : '角色必填')]
}
const cols = [
  { key: 'name', label: '姓名', sortable: true }, { key: 'phone', label: '手机号' },
  { key: 'department', label: '部门' }, { key: 'roles', label: '角色' }, { key: 'status', label: '状态' }
]
async function load() { loading.value = true; try { rows.value = (await listUsers()).map(u => ({ ...u, roles: (u.roles || []).join(',') })) } finally { loading.value = false } }
async function submit() {
  if (!formRef.value.validate()) return
  try {
    const res = await createUser({ name: model.value.name, phone: model.value.phone, roles: model.value.rolesText.split(',').map(s => roleCodeFromName(s)).filter(Boolean) })
    initialPwd.value = res.initialPassword
    toast.success('账号已开通'); load()
  } catch (e) { toast.error(e.message || '开通失败') }
}
onMounted(load)
</script>
<style scoped>
.page-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-head h1 { font-size: 20px; }
.page input { width: 100%; padding: 8px; background: var(--panel-2); border: 1px solid var(--line-2); border-radius: 6px; color: var(--text); }
.pwd { color: var(--amber); font-size: 12px; }
</style>