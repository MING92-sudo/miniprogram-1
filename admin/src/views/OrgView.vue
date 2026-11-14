<template>
  <div class="page">
    <div class="page-head">
      <h1>组织架构</h1>
      <PermButton code="role:assign" label="新建下级组织" @click="openCreate" />
    </div>
    <DataTable :columns="cols" :rows="rows" :loading="loading" search-key="name" :search-keys="['name', 'code']" />
    <Modal :open="showCreate" title="新建组织" @close="showCreate = false" @confirm="submit">
      <Form ref="formRef" :rules="rules" v-model="model" v-slot="{ errors }">
        <FormField label="名称" required :error="errors.name"><input v-model="model.name" /></FormField>
        <FormField label="类型" required :error="errors.type">
          <select v-model="model.type"><option>COMPANY</option><option>DEPT</option><option>TEAM</option><option>UNIT</option></select>
        </FormField>
        <FormField label="上级组织"><select v-model="model.parentId"><option value="">无</option><option v-for="o in rows" :key="o.id" :value="o.id">{{ o.name }}</option></select></FormField>
      </Form>
      <p class="hint">使用单位节点为单级，禁止挂子节点。</p>
    </Modal>
  </div>
</template>
<script setup>
// M3 组织架构：树列表 + 新建（role:assign），使用单位单级
import { ref, onMounted } from 'vue'
import DataTable from '../ui/DataTable.vue'
import Modal from '../ui/Modal.vue'
import Form from '../ui/Form.vue'
import FormField from '../ui/FormField.vue'
import PermButton from '../ui/PermButton.vue'
import { useToast } from '../ui/stores/ui'
import { listOrg, createOrg } from '../api/ledger'
const toast = useToast()
const rows = ref([])
const loading = ref(false)
const showCreate = ref(false)
const formRef = ref(null)
const model = ref({ name: '', type: 'DEPT', parentId: '' })
const rules = { name: [(v) => (v ? '' : '名称必填')], type: [(v) => (v ? '' : '类型必填')] }
const cols = [
  { key: 'name', label: '组织名称', sortable: true },
  { key: 'type', label: '类型' },
  { key: 'code', label: '编码' },
  { key: 'status', label: '状态' }
]
async function load() { loading.value = true; try { rows.value = await listOrg() } finally { loading.value = false } }
function openCreate() { model.value = { name: '', type: 'DEPT', parentId: '' }; showCreate.value = true }
async function submit() {
  if (!formRef.value.validate()) return
  try { await createOrg({ ...model.value, parentId: model.value.parentId || null }); toast.success('组织已创建'); showCreate.value = false; load() }
  catch (e) { toast.error(e.message || '创建失败') }
}
onMounted(load)
</script>
<style scoped>
.page-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-head h1 { font-size: 20px; }
.hint { color: var(--dim); font-size: 12px; }
.page input, .page select { width: 100%; padding: 8px; background: var(--panel-2); border: 1px solid var(--line-2); border-radius: 6px; color: var(--text); }
</style>