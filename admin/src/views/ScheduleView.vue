<template>
  <div>
    <el-card shadow="never">
      <template #header>
        <div class="head">
          <el-space>
            <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD"
                            start-placeholder="计划起" end-placeholder="计划止" style="width: 240px" />
            <el-select v-model="status" placeholder="状态" clearable style="width: 150px">
              <el-option label="待指派" value="UNASSIGNED" />
              <el-option label="已指派" value="ASSIGNED" />
              <el-option label="延期待审" value="POSTPONE_PENDING" />
              <el-option label="已取消" value="CANCELLED" />
            </el-select>
            <el-button type="primary" @click="load(1)">查询</el-button>
          </el-space>
          <el-button type="primary" :disabled="!auth.canWrite" :loading="generating" @click="onGenerate">
            生成排班池（按到期日）
          </el-button>
        </div>
      </template>

      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column prop="planDate" label="计划日期" width="110" />
        <el-table-column prop="elevatorName" label="电梯" min-width="150" show-overflow-tooltip />
        <el-table-column prop="useUnitName" label="使用单位" min-width="150" show-overflow-tooltip />
        <el-table-column prop="workType" label="类别" width="100" />
        <el-table-column label="主维保 / 配合" min-width="140">
          <template #default="{ row }">
            {{ row.principal ? row.principal.name : '—' }} / {{ row.assistant ? row.assistant.name : '单人' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="工单" width="170">
          <template #default="{ row }">
            <span v-if="row.orderNo">{{ row.orderNo }}（{{ row.orderStatus }}）</span>
            <span v-else class="muted">未派工</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="!auth.canWrite" @click="openAssign(row)">指派</el-button>
            <el-button link type="warning" :disabled="!auth.canRead" @click="openDelay(row)">申请延期</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" layout="total, prev, pager, next" :total="total"
                     :page-size="size" :current-page="page" @current-change="load" />
    </el-card>

    <el-card shadow="never" class="mt12">
      <template #header>
        <div class="head">
          <span>延期审批</span>
          <el-radio-group v-model="delayStatus" size="small" @change="loadDelays">
            <el-radio-button value="PENDING">待审批</el-radio-button>
            <el-radio-button value="APPROVED">已通过</el-radio-button>
            <el-radio-button value="REJECTED">已驳回</el-radio-button>
          </el-radio-group>
        </div>
      </template>
      <el-table :data="delays" v-loading="loadingDelays" size="small" stripe>
        <el-table-column prop="elevatorName" label="电梯" min-width="140" show-overflow-tooltip />
        <el-table-column prop="currentPlanDate" label="原计划日期" width="110" />
        <el-table-column prop="expectedDate" label="期望延至" width="110" />
        <el-table-column prop="reason" label="原因" min-width="180" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'APPROVED' ? 'success' : row.status === 'REJECTED' ? 'danger' : 'warning'">
              {{ row.status === 'APPROVED' ? '已通过' : row.status === 'REJECTED' ? '已驳回' : '待审批' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="审批" width="150">
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING'">
              <el-button link type="success" :disabled="!auth.canWrite" @click="decide(row, true)">通过</el-button>
              <el-button link type="danger" :disabled="!auth.canWrite" @click="decide(row, false)">驳回</el-button>
            </template>
            <span v-else class="muted">{{ row.approveComment || '—' }}</span>
          </template>
        </el-table-column>
      </el-table>
      <div class="note">已上报成功的电梯其平台侧"下次维保日期"已固化，审批通过会返回 platformDateSynced=false 提示（docs/04 A.5）</div>
    </el-card>

    <el-dialog v-model="assignDialog" title="排班指派" width="620px">
      <el-descriptions :column="2" border size="small" class="mb12">
        <el-descriptions-item label="电梯">{{ current.elevatorName }}</el-descriptions-item>
        <el-descriptions-item label="计划日期">{{ current.planDate }}（{{ current.workType }}）</el-descriptions-item>
      </el-descriptions>
      <el-form label-width="110px">
        <el-form-item label="计划日期">
          <el-date-picker v-model="assignForm.planDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="主维保" required>
          <el-select v-model="assignForm.principalId" filterable style="width: 100%">
            <el-option v-for="c in candidates" :key="'p' + c.id" :value="c.id"
                       :label="`${c.name}（在办 ${c.openOrders}，${c.assignable ? '可派' : '不可派'}）`"
                       :disabled="!c.assignable" />
          </el-select>
        </el-form-item>
        <el-form-item label="维保人员2">
          <el-select v-model="assignForm.assistantId" filterable clearable style="width: 100%">
            <el-option v-for="c in candidates" :key="'a' + c.id" :value="c.id"
                       :label="`${c.name}（在办 ${c.openOrders}，${c.assignable ? '可派' : '不可派'}）`"
                       :disabled="!c.assignable" />
          </el-select>
        </el-form-item>
        <div v-if="conflictLines.length" class="conflicts">
          <div v-for="(c, i) in conflictLines" :key="i">⚠ {{ c }}</div>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="assignDialog = false">取消</el-button>
        <el-button :disabled="!current.id" @click="loadSuggestion">刷新排班建议</el-button>
        <el-button type="primary" :loading="saving" @click="saveAssign">确认指派</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="delayDialog" title="申请延期" width="460px">
      <el-form label-width="100px">
        <el-form-item label="期望延至" required>
          <el-date-picker v-model="delayForm.expectedDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="延期天数">
          <el-input-number v-model="delayForm.delayDays" :min="1" :max="365" />
        </el-form-item>
        <el-form-item label="原因" required>
          <el-input v-model="delayForm.reason" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="delayDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveDelay">提交申请</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { useAuthStore } from '../stores/auth'
import * as scheduleApi from '../api/schedule'
import { showErr, ok } from '../utils/ui'

const auth = useAuthStore()
const rows = ref([])
const delays = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const range = ref(null)
const status = ref('')
const loading = ref(false)
const delayStatus = ref('PENDING')
const loadingDelays = ref(false)
const generating = ref(false)
const saving = ref(false)
const assignDialog = ref(false)
const delayDialog = ref(false)
const current = reactive({})
const assignForm = reactive({ planDate: '', principalId: '', assistantId: '' })
const delayForm = reactive({ expectedDate: '', delayDays: 1, reason: '' })
const candidates = ref([])
const conflictLines = ref([])

function statusText(s) {
  return { UNASSIGNED: '待指派', ASSIGNED: '已指派', POSTPONE_PENDING: '延期待审', DISPATCHED: '已派工', CANCELLED: '已取消' }[s] || s
}
function statusType(s) {
  return { UNASSIGNED: 'info', ASSIGNED: 'success', POSTPONE_PENDING: 'warning', CANCELLED: 'danger' }[s] || 'info'
}

async function load(p) {
  if (p) page.value = p
  loading.value = true
  try {
    const data = await scheduleApi.listPlans({
      page: page.value, size: size.value, status: status.value || undefined,
      dateFrom: range.value && range.value[0], dateTo: range.value && range.value[1]
    })
    rows.value = data.list || []
    total.value = data.total || 0
  } catch (e) {
    showErr(e)
  } finally {
    loading.value = false
  }
}

async function loadDelays() {
  loadingDelays.value = true
  try {
    const data = await scheduleApi.listDelays(delayStatus.value)
    delays.value = data.list || []
  } catch (e) {
    showErr(e)
  } finally {
    loadingDelays.value = false
  }
}

async function onGenerate() {
  if (!range.value || !range.value[0]) {
    ok('请先选择计划日期范围')
    return
  }
  generating.value = true
  try {
    const res = await scheduleApi.generatePlans({ dateFrom: range.value[0], dateTo: range.value[1] })
    ok(`生成 ${res.generated} 条计划，跳过 ${(res.skipped || []).length} 条`)
    await load(1)
  } catch (e) {
    showErr(e)
  } finally {
    generating.value = false
  }
}

async function openAssign(row) {
  Object.assign(current, row)
  assignForm.planDate = row.planDate
  assignForm.principalId = row.principal ? row.principal.id : ''
  assignForm.assistantId = row.assistant ? row.assistant.id : ''
  conflictLines.value = []
  assignDialog.value = true
  await loadSuggestion()
  await refreshConflicts()
}

async function loadSuggestion() {
  try {
    const data = await scheduleApi.planSuggestion(current.id)
    candidates.value = data.candidates || []
  } catch (e) {
    showErr(e)
  }
}

async function refreshConflicts() {
  try {
    const data = await scheduleApi.planConflicts(current.id)
    conflictLines.value = (data.conflicts || []).map((c) => c.message)
  } catch (e) {
    showErr(e)
  }
}

async function saveAssign() {
  if (!assignForm.principalId) {
    ok('请选择主维保')
    return
  }
  saving.value = true
  try {
    const res = await scheduleApi.assignPlan(current.id, {
      principalId: assignForm.principalId,
      assistantId: assignForm.assistantId || undefined,
      planDate: assignForm.planDate
    })
    ok(`已指派并生成工单 ${res.orderNo}`)
    assignDialog.value = false
    await load()
  } catch (e) {
    showErr(e)
  } finally {
    saving.value = false
  }
}

function openDelay(row) {
  Object.assign(current, row)
  delayForm.expectedDate = ''
  delayForm.delayDays = 1
  delayForm.reason = ''
  delayDialog.value = true
}

async function saveDelay() {
  if (!delayForm.expectedDate || !delayForm.reason) {
    ok('请填写期望日期与原因')
    return
  }
  saving.value = true
  try {
    await scheduleApi.applyDelay(current.id, {
      reason: delayForm.reason, delayDays: delayForm.delayDays, expectedDate: delayForm.expectedDate
    })
    ok('延期申请已提交，等待管理员审批')
    delayDialog.value = false
    await Promise.all([load(), loadDelays()])
  } catch (e) {
    showErr(e)
  } finally {
    saving.value = false
  }
}

async function decide(row, approved) {
  saving.value = true
  try {
    const res = await scheduleApi.decideDelay(row.id, approved, approved ? '' : '不予延期')
    if (approved && res.platformDateSynced === false) {
      showErr({ message: res.platformFixedNote || '平台侧日期已固化，请注意核对' })
    } else {
      ok(approved ? '已通过并更新计划日期' : '已驳回')
    }
    await Promise.all([load(), loadDelays()])
  } catch (e) {
    showErr(e)
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  load()
  loadDelays()
})
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; }
.pager { margin-top: 12px; justify-content: flex-end; }
.mt12 { margin-top: 12px; }
.mb12 { margin-bottom: 12px; }
.muted { color: #c9cdd4; }
.note { color: #86909c; font-size: 12px; margin-top: 10px; }
.conflicts { background: #fff7e8; border: 1px solid #ffd591; border-radius: 4px; padding: 8px 10px; color: #d46b08; font-size: 12px; line-height: 1.6; }
</style>
