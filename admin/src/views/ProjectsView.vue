<template>
  <div class="page">
    <div class="page-head">
      <h1>项目台账</h1>
      <PermButton code="project:edit" label="新增项目" @click="showCreate = true" />
    </div>
    <DataTable :columns="cols" :rows="rows" :loading="loading" :search-keys="['name', 'projectNo']" />
    <Modal :open="showCreate" title="新增项目" @close="showCreate = false" @confirm="submit">
      <Form ref="formRef" :rules="rules" v-model="model" v-slot="{ errors }">
        <FormField label="项目编号" required :error="errors.projectNo"><input v-model="model.projectNo" /></FormField>
        <FormField label="项目名" required :error="errors.name"><input v-model="model.name" /></FormField>
        <FormField label="台量"><input type="number" v-model="model.elevatorCount" /></FormField>
      </Form>
    </Modal>
  </div>
</template>
<script setup>
// M3 项目台账：列表 + 新增/编辑（project:edit）
import { ref, onMounted } from 'vue'
import DataTable from '../ui/DataTable.vue'
import Modal from '../ui/Modal.vue'
import Form from '../ui/Form.vue'
import FormField from '../ui/FormField.vue'
import PermButton from '../ui/PermButton.vue'
import { useToast } from '../ui/stores/ui'
import { listProjects, createProject } from '../api/ledger'
const toast = useToast()
const rows = ref([])
const loading = ref(false)
const showCreate = ref(false)
const formRef = ref(null)
const model = ref({ projectNo: '', name: '', elevatorCount: 0 })
const rules = { projectNo: [(v) => (v ? '' : '编号必填')], name: [(v) => (v ? '' : '名称必填')] }
const cols = [
  { key: 'projectNo', label: '项目编号', sortable: true },
  { key: 'name', label: '项目名', sortable: true },
  { key: 'elevatorCount', label: '台量' },
  { key: 'contractEndDate', label: '合同到期' },
  { key: 'status', label: '状态' }
]
async function load() { loading.value = true; try { rows.value = await listProjects() } finally { loading.value = false } }
async function submit() {
  if (!formRef.value.validate()) return
  try { await createProject({ ...model.value, elevatorCount: +model.value.elevatorCount }); toast.success('项目已创建'); showCreate.value = false; load() }
  catch (e) { toast.error(e.message || '创建失败') }
}
onMounted(load)
</script>
<style scoped>
.page-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-head h1 { font-size: 20px; }
.page input { width: 100%; padding: 8px; background: var(--panel-2); border: 1px solid var(--line-2); border-radius: 6px; color: var(--text); }
</style>