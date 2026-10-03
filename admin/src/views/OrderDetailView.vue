<template>
  <el-card shadow="never" v-loading="loading">
    <template #header>
      <div class="head">
        <span>工单详情 {{ order.orderNo ? `（${order.orderNo}）` : '' }}</span>
        <el-space>
          <el-button v-if="order.status === 'PENDING' && auth.canRead" size="small"
                     @click="openTransfer">转派</el-button>
          <el-button size="small" @click="$router.back()">返回</el-button>
        </el-space>
      </div>
    </template>

    <el-descriptions :column="3" border>
      <el-descriptions-item label="电梯">{{ order.elevatorName || '-' }}</el-descriptions-item>
      <el-descriptions-item label="电梯编号">{{ elevator.elevatorCode || '-' }}</el-descriptions-item>
      <el-descriptions-item label="注册代码">{{ elevator.regCode || '-' }}</el-descriptions-item>
      <el-descriptions-item label="类别">{{ order.workType || '-' }}</el-descriptions-item>
      <el-descriptions-item label="计划时间">{{ order.planTime || '-' }}</el-descriptions-item>
      <el-descriptions-item label="状态">{{ order.status || '-' }}</el-descriptions-item>
      <el-descriptions-item label="维保人员1">{{ order.workerName || '-' }}</el-descriptions-item>
      <el-descriptions-item label="维保人员2">{{ order.assistantName || '单人作业' }}</el-descriptions-item>
      <el-descriptions-item label="签到时间">{{ order.checkinTime || '未签到' }}</el-descriptions-item>
      <el-descriptions-item label="签退时间">{{ order.checkoutTime || '未签退' }}</el-descriptions-item>
      <el-descriptions-item label="作业时长">{{ order.duration || '-' }}</el-descriptions-item>
      <el-descriptions-item label="上报状态">{{ order.originalRecordId ? (order.reportStatus || '-') : '未生成记录' }}</el-descriptions-item>
    </el-descriptions>

    <template v-if="recordInfo">
      <h4 class="sec">维保记录 / 使用单位确认</h4>
      <el-descriptions :column="4" border size="small">
        <el-descriptions-item label="确认状态">{{ recordInfo.confirmStatus || 'PENDING' }}</el-descriptions-item>
        <el-descriptions-item label="上报状态">{{ recordInfo.uploadStatus || recordInfo.reportStatus || '-' }}</el-descriptions-item>
        <el-descriptions-item label="满意度">{{ recordInfo.satisfaction ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="操作">
          <el-button size="small" type="primary" @click="openPreview">预览记录</el-button>
          <el-button size="small" @click="onDownloadPdf">下载PDF</el-button>
        </el-descriptions-item>
      </el-descriptions>
    </template>

    <el-drawer v-model="previewVisible" title="维保记录预览（与 PDF 同源）" size="480px">
      <template v-if="preview">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="电梯">{{ preview.elevatorName }}（{{ preview.elevatorCode }}）</el-descriptions-item>
          <el-descriptions-item label="维保类别">{{ preview.workType }}</el-descriptions-item>
          <el-descriptions-item label="签到 / 签退">{{ preview.checkinTime }} ~ {{ preview.checkoutTime }}</el-descriptions-item>
          <el-descriptions-item label="作业时长">{{ preview.duration }}</el-descriptions-item>
          <el-descriptions-item label="维保 / 配合人员">{{ preview.workerName }} / {{ preview.assistantName || '单人' }}</el-descriptions-item>
          <el-descriptions-item label="隐患码">{{ (preview.problemCodes || []).join('、') || 'S0' }}</el-descriptions-item>
          <el-descriptions-item label="上报状态">{{ preview.uploadStatus }}</el-descriptions-item>
        </el-descriptions>
        <h4 class="sec">检查项明细（{{ (preview.items || []).length }} 项）</h4>
        <el-table :data="preview.items" size="small" max-height="220">
          <el-table-column type="index" label="#" width="44" />
          <el-table-column prop="name" label="检查项" min-width="160" show-overflow-tooltip />
          <el-table-column prop="result" label="结果" width="70" />
        </el-table>
        <h4 class="sec">现场照片（{{ (preview.photos || []).length }}）</h4>
        <div v-if="(preview.photos || []).length" class="photos">
          <el-image v-for="(p, i) in preview.photos" :key="i" :src="p" :preview-src-list="preview.photos"
                    fit="cover" class="photo" />
        </div>
        <div v-else class="muted">无</div>
        <h4 class="sec">签字区</h4>
        <div class="signs">
          <el-image v-if="preview.workerSignatureUrl" :src="preview.workerSignatureUrl" fit="contain" class="sign" />
          <el-image v-if="preview.assistantSignatureUrl" :src="preview.assistantSignatureUrl" fit="contain" class="sign" />
          <el-image v-if="preview.signatureUrl" :src="preview.signatureUrl" fit="contain" class="sign" />
        </div>
        <div v-if="!(preview.workerSignatureUrl || preview.assistantSignatureUrl || preview.signatureUrl)" class="muted">无签字图</div>
      </template>
    </el-drawer>

    <h4 class="sec">检查项结果（{{ items.length }} 项）</h4>
    <el-table :data="items" size="small" stripe max-height="420">
      <el-table-column type="index" label="#" width="50" />
      <el-table-column prop="name" label="检查项" min-width="220" show-overflow-tooltip />
      <el-table-column label="结果" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="resultType(row.result)">{{ resultText(row.result) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="abnormalDesc" label="异常描述" min-width="180" show-overflow-tooltip />
      <el-table-column label="隐患码" width="70">
        <template #default="{ row }">{{ row.problemCode || '' }}</template>
      </el-table-column>
      <el-table-column label="照片" width="80">
        <template #default="{ row }">{{ (row.photos && row.photos.length) || 0 }} 张</template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="transferDialog" title="转派工单（班组长及以上）" width="460px">
      <el-form label-width="100px">
        <el-form-item label="转派给" required>
          <el-select v-model="transferForm.toEmployeeId" filterable style="width: 100%">
            <el-option v-for="c in candidates" :key="c.id" :value="c.id" :label="c.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="转派原因">
          <el-input v-model="transferForm.reason" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="transferDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveTransfer">确认转派</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { computed, reactive, ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import * as orderApi from '../api/order'
import * as scheduleApi from '../api/schedule'
import * as reportApi from '../api/report'
import { showErr, ok, downloadBlob } from '../utils/ui'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const auth = useAuthStore()
const loading = ref(false)
const order = ref({})
const items = ref([])
const transferDialog = ref(false)
const saving = ref(false)
const candidates = ref([])
const transferForm = reactive({ toEmployeeId: '', reason: '' })

const elevator = computed(() => order.value.elevator || {})
const recordInfo = computed(() => order.value.recordInfo || null)
const previewVisible = ref(false)
const preview = ref(null)

/** 维保记录预览：与使用单位确认/PDF 同源（GET /unit/records/{id}，docs/03 V2.1 §3.3） */
async function openPreview() {
  if (!recordInfo.value || !recordInfo.value.id) return
  preview.value = await reportApi.getRecord(recordInfo.value.id)
  previewVisible.value = true
}

async function onDownloadPdf() {
  if (!recordInfo.value || !recordInfo.value.id) return
  const blob = await reportApi.exportPdf(recordInfo.value.id)
  downloadBlob(blob, `维保记录-${recordInfo.value.originalRecordId || recordInfo.value.id}.pdf`)
}

async function loadCandidates() {
  try {
    const all = await scheduleApi.employees()
    candidates.value = (all || [])
      .filter((e) => e.role === 'WORKER' || e.role === 'LEADER')
      .map((e) => ({
        id: e.id,
        name: `${e.name}（${e.platformId ? '已同步' : '未同步'}，证至 ${e.workEndDate || '—'}）`
      }))
  } catch (_) {
    candidates.value = []
  }
}

function openTransfer() {
  transferForm.toEmployeeId = ''
  transferForm.reason = ''
  transferDialog.value = true
  loadCandidates()
}

async function saveTransfer() {
  if (!transferForm.toEmployeeId) {
    ElMessage.warning('请选择转派对象')
    return
  }
  saving.value = true
  try {
    const res = await scheduleApi.transferOrder(order.value.id, {
      toEmployeeId: transferForm.toEmployeeId, reason: transferForm.reason
    })
    ok(`已转派给 ${res.workerName}`)
    transferDialog.value = false
    order.value = await orderApi.getOrder(order.value.id)
  } catch (e) {
    showErr(e)
  } finally {
    saving.value = false
  }
}

function resultText(r) {
  return { NORMAL: '正常', ABNORMAL: '异常', SKIP: '不适用' }[r] || r || '-'
}
function resultType(r) {
  return { NORMAL: 'success', ABNORMAL: 'danger', SKIP: 'info' }[r] || 'info'
}

onMounted(async () => {
  loading.value = true
  try {
    order.value = await orderApi.getOrder(route.params.id)
    items.value = order.value.checklist || []
  } catch (e) {
    showErr(e)
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; }
.sec { margin: 16px 0 8px; color: #1d2129; }
</style>
