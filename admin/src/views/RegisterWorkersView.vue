<template>
  <div>
    <ViewHead title="平台登记人员" sub="监管平台人员登记（接口 2.3/2.4）· 数据来自智慧平台实时查询（2.5）">
      <template #meta><PageMeta perm="platform:worker_view" :extra="[{ k: '人员数', v: rows.length, icon: 'user-check', cls: 'count' }]" /></template>
    </ViewHead>
    <div v-if="error" class="alert-strip as-sig mt16">
      <svg class="ic" aria-hidden="true"><use href="#i-alert" /></svg>
      <b>查询失败</b><span>{{ error }}</span>
      <span class="as-link" @click="load">重试<svg class="ic ic-sm" aria-hidden="true"><use href="#i-refresh" /></svg></span>
    </div>
    <div class="alert-strip as-ok mt16" v-if="backfillResult">
      <svg class="ic" aria-hidden="true"><use href="#i-exchange" /></svg>
      <b>回填完成</b>
      <span>扫描 {{ backfillResult.scanned }} 人 · 成功 {{ backfillResult.synced }} · 未找到 {{ backfillResult.notFound }} · 证书重复 {{ backfillResult.ambiguous }} · 无证书号 {{ backfillResult.noCertificate }}</span>
      <span v-if="backfillResult.conflictCertificates && backfillResult.conflictCertificates.length" class="as-sig">证书号重复需人工处理：{{ backfillResult.conflictCertificates.join('、') }}</span>
    </div>
    <div class="row gap10 mt16">
      <PermButton code="platform:worker_register" label="维保人员建档" variant="primary" icon="plus" @click="openCreate" />
      <button class="btn btn-ghost btn-sm" type="button" :disabled="backfilling" @click="backfill">
        <svg class="ic" aria-hidden="true"><use href="#i-exchange" /></svg>
        <span>{{ backfilling ? '回填中…' : '回填缺失的人员ID' }}</span>
      </button>
      <span class="dim f12">建档走平台 2.4；确认后自动调 2.5 全量回填全单位人员ID（供维保记录上报使用）</span>
    </div>
    <div class="mt16">
      <DataTable :columns="cols" :rows="rows" :loading="loading" :search-keys="['name', 'certificateNo']"
        search-placeholder="搜索姓名 / 证书编号" empty-hint="暂无平台登记人员。">
        <template #cell-certificateNo="{ value }"><span class="mono f12">{{ value || '—' }}</span></template>
        <template #cell-phone="{ value }"><span class="mono f12">{{ value || '—' }}</span></template>
        <template #cell-bindStatus="{ value }">
          <span :class="['tag', String(value).toUpperCase() === 'BOUND' ? 'tag-ok' : 'tag-dim']">{{ { BOUND: '已绑定', UNBOUND: '未绑定' }[String(value || '').toUpperCase()] || value || '—' }}</span>
        </template>
      </DataTable>
    </div>

    <!-- 建档成功回显：一次性账号/初始密码 + 全量回填统计 -->
    <div class="alert-strip as-ok mt16" v-if="registerResult">
      <svg class="ic" aria-hidden="true"><use href="#i-user-check" /></svg>
      <div>
        <b>建档成功</b>
        <span v-if="registerResult.account">已开通维保账号：{{ registerResult.account }}</span>
        <span v-if="registerResult.initialPassword"> · 初始密码：{{ registerResult.initialPassword }}（仅显示一次，请安全转交）</span>
        <span v-if="registerResult.platformBackfill"> · 2.5 全量回填：平台 {{ registerResult.platformBackfill.platformWorkers ?? '—' }} 人 / 本地回填 {{ registerResult.platformBackfill.synced ?? '—' }} 人 / 冲突 {{ registerResult.platformBackfill.ambiguous ?? '—' }} 人（{{ registerResult.platformBackfill.status || '' }}）</span>
      </div>
      <span class="as-link" @click="registerResult = null">关闭</span>
    </div>

    <!-- 维保人员建档（平台 2.4 /entity/updateWorkState，multipart + certificateFile） -->
    <Modal :open="showRegister" title="维保人员建档" sub="平台 2.4 登记 · 确认后自动调 2.5 全量回填人员ID"
      icon="user-check" note="platform:worker_register" :confirm-label="registering ? '提交中…' : '确认建档'"
      @close="closeRegister" @confirm="submitRegister">
      <Form ref="registerFormRef" :rules="registerRules" v-model="registerModel" v-slot="{ errors }">
        <div class="form-grid">
          <FormField label="作业人员姓名" required :error="errors.workManName">
            <input v-model="registerModel.workManName" class="ctl" placeholder="如：张伟" />
          </FormField>
          <FormField label="作业人员证书编号" required :error="errors.workManCertificate">
            <input v-model="registerModel.workManCertificate" class="ctl mono" placeholder="证书编号（平台内唯一匹配键）" />
          </FormField>
          <FormField label="劳务合同生效日期" required :error="errors.workStartDate">
            <input v-model="registerModel.workStartDate" class="ctl" type="date" />
          </FormField>
          <FormField label="劳务合同截止日期" required :error="errors.workEndDate">
            <input v-model="registerModel.workEndDate" class="ctl" type="date" />
          </FormField>
          <FormField label="维保人员电话" required :error="errors.workManPhone">
            <input v-model="registerModel.workManPhone" class="ctl mono" placeholder="11 位手机号，用作登录账号" />
          </FormField>
          <FormField label="建立状态">
            <select v-model="registerModel.changState" class="ctl">
              <option value="0">建立（建档 / 绑定）</option>
              <option value="1">终止（解除绑定）</option>
            </select>
          </FormField>
          <FormField label="作业人员电子证书（PDF）" required :error="errors.certificateFile">
            <input type="file" accept="application/pdf,.pdf" class="ctl" @change="onCertificateChange" />
            <span v-if="registerModel.certificateFileName" class="fhelp">已选择：{{ registerModel.certificateFileName }}</span>
          </FormField>
        </div>
        <div class="fhelp mt8">证书文件为平台 2.4 必传项（PDF，≤20M）。确认建档后，平台 2.4 登记成功即自动生成本地维保账号，并调 2.5 查询整个单位所有绑定人员回填平台ID。</div>
      </Form>
    </Modal>

    <RuleNote class="mt12" icon="lock">登记写入（2.3/2.4 multipart：contractFile/certificateFile）按 platform:worker_register；openid 只信服务端换取；平台凭证仅存环境变量。</RuleNote>
  </div>
</template>
<script setup>
// 平台登记人员：GET /platform/workers（平台 2.5 实时查询，platform:worker_view）
import { ref, onMounted } from 'vue'
import http from '../api/client'
import { backfillWorkers, registerWorker } from '../api/platform'
import DataTable from '../ui/DataTable.vue'
import ViewHead from '../ui/ViewHead.vue'
import PageMeta from '../ui/PageMeta.vue'
import RuleNote from '../ui/RuleNote.vue'
import PermButton from '../ui/PermButton.vue'
import Modal from '../ui/Modal.vue'
import Form from '../ui/Form.vue'
import FormField from '../ui/FormField.vue'
import { useToast } from '../ui/stores/ui'
const toast = useToast()
const rows = ref([])
const loading = ref(false)
const error = ref('')
const backfilling = ref(false)
const backfillResult = ref(null)

// ── 维保人员建档（平台 2.4）──
const showRegister = ref(false)
const registering = ref(false)
const registerFormRef = ref(null)
const registerResult = ref(null)
const emptyRegister = () => ({ workManName: '', workManCertificate: '', workStartDate: '', workEndDate: '', workManPhone: '', changState: '0', certificateFile: null, certificateFileName: '' })
const registerModel = ref(emptyRegister())
const registerRules = {
  workManName: [(v) => (v && v.trim() ? '' : '请输入作业人员姓名')],
  workManCertificate: [(v) => (v && v.trim() ? '' : '请输入证书编号')],
  workStartDate: [(v) => (v ? '' : '请选择合同生效日期')],
  workEndDate: [(v) => (v ? '' : '请选择合同截止日期')],
  workManPhone: [(v) => (/^1\d{10}$/.test((v || '').trim()) ? '' : '请输入 11 位手机号')],
  certificateFile: [(v) => (v ? '' : '请上传作业人员电子证书（PDF）')]
}
function openCreate() {
  registerModel.value = emptyRegister()
  registerResult.value = null
  showRegister.value = true
}
function closeRegister() { if (!registering.value) showRegister.value = false }
function onCertificateChange(ev) {
  const file = ev.target.files && ev.target.files[0]
  registerModel.value.certificateFile = file || null
  registerModel.value.certificateFileName = file ? file.name : ''
}
async function submitRegister() {
  if (!registerFormRef.value.validate()) return
  const m = registerModel.value
  if (m.workStartDate > m.workEndDate) { toast.error('合同生效日期不能晚于截止日期'); return }
  registering.value = true
  try {
    const res = await registerWorker({
      workManName: m.workManName.trim(),
      workManCertificate: m.workManCertificate.trim(),
      workStartDate: m.workStartDate,
      workEndDate: m.workEndDate,
      workManPhone: m.workManPhone.trim(),
      changState: String(m.changState),
      certificateFile: m.certificateFile
    })
    registerResult.value = res || { account: m.workManPhone.trim() }
    showRegister.value = false
    toast.success(m.changState === '1' ? '平台 2.4 已终止绑定' : '建档成功，已自动回填平台人员ID')
    await load()
  } catch (e) { toast.error('建档失败：' + (e.message || '监管平台暂不可达')) }
  finally { registering.value = false }
}
async function backfill() {
  backfilling.value = true
  backfillResult.value = null
  try {
    backfillResult.value = await backfillWorkers()
    await load()
  } catch (e) {
    error.value = '回填失败：' + (e.message || '监管平台暂不可达')
  } finally {
    backfilling.value = false
  }
}
const cols = [
  { key: 'name', label: '姓名', sortable: true },
  { key: 'certificateNo', label: '证书编号' },
  { key: 'phone', label: '手机号' },
  { key: 'bindStatus', label: '绑定状态' }
]
async function load() {
  loading.value = true; error.value = ''
  try {
    const workers = await http.get('/platform/workers')
    rows.value = workers.map((w) => ({
      ...w,
      name: w.workManName || '',
      certificateNo: w.workManCertificate || '',
      phone: w.workManPhone || '',
      bindStatus: 'BOUND'
    }))
  }
  catch (e) { error.value = '平台人员查询失败：' + (e.message || '监管平台暂不可达') + '（需平台凭证有效且服务器 IP 已加白名单）' }
  finally { loading.value = false }
}
onMounted(load)
</script>