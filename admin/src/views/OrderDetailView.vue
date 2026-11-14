<template>
  <div class="page">
    <div class="page-head">
      <h1>工单详情 {{ id }}</h1>
      <PermButton code="order:create_proxy" label="代报修" @click="showProxy = true" />
    </div>
    <div v-if="order" class="detail">
      <p><span class="label">状态</span> {{ order.status }}</p>
      <p><span class="label">设备</span> {{ order.elevatorCode }}</p>
      <p><span class="label">SLA</span> {{ order.slaMinutes }} 分</p>
    </div>
    <Timeline :items="timeline" />
    <Modal :open="showProxy" title="代报修" @close="showProxy = false" @confirm="submitProxy">
      <FormField label="来源"><select v-model="proxy.proxySource"><option>PHONE</option><option>WECHAT</option><option>ONSITE</option></select></FormField>
      <FormField label="录音编号（REC-xxxx）"><input v-model="proxy.recordingNo" /></FormField>
    </Modal>
  </div>
</template>
<script setup>
// M4 工单详情：7 态时间轴 + 代报修表单（order:create_proxy）
import { ref, onMounted, computed } from 'vue'
import { useRoute } from 'vue-router'
import http from '../api/client'
import Timeline from '../ui/Timeline.vue'
import Modal from '../ui/Modal.vue'
import FormField from '../ui/FormField.vue'
import PermButton from '../ui/PermButton.vue'
import { useToast } from '../ui/stores/ui'
const toast = useToast()
const route = useRoute()
const id = computed(() => route.params.id)
const order = ref(null)
const showProxy = ref(false)
const proxy = ref({ proxySource: 'PHONE', recordingNo: '' })
const timeline = computed(() => order.value ? [
  { actor: order.value.createdByName || '系统', role: '创建', time: String(order.value.createdAt || ''), text: '工单创建' },
  { actor: order.value.workerName || '-', role: '维修', time: String(order.value.finishedAt || ''), text: '状态 ' + order.value.status }
] : [])
async function load() { try { order.value = await http.get(`/faults/${id.value}`) } catch (e) { order.value = null } }
async function submitProxy() {
  try { await http.post(`/faults/${id.value}/proxy`, proxy.value); toast.success('代报修已登记'); showProxy.value = false; load() }
  catch (e) { toast.error(e.message || '登记失败') }
}
onMounted(load)
</script>
<style scoped>
.page-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-head h1 { font-size: 20px; }
.detail p { font-size: 13px; margin: 6px 0; }
.detail .label { margin-right: 8px; }
.page input, .page select { width: 100%; padding: 8px; background: var(--panel-2); border: 1px solid var(--line-2); border-radius: 6px; color: var(--text); }
</style>