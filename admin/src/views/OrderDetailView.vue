<template>
  <el-card shadow="never" v-loading="loading">
    <template #header>
      <div class="head">
        <span>工单详情 {{ order.orderNo ? `（${order.orderNo}）` : '' }}</span>
        <el-button size="small" @click="$router.back()">返回</el-button>
      </div>
    </template>

    <el-descriptions :column="3" border>
      <el-descriptions-item label="电梯">{{ order.elevatorName || '-' }}</el-descriptions-item>
      <el-descriptions-item label="电梯编号">{{ elevator.elevatorCode || '-' }}</el-descriptions-item>
      <el-descriptions-item label="注册代码">{{ elevator.regCode || '-' }}</el-descriptions-item>
      <el-descriptions-item label="类别">{{ order.workType || '-' }}</el-descriptions-item>
      <el-descriptions-item label="计划时间">{{ order.planTime || '-' }}</el-descriptions-item>
      <el-descriptions-item label="状态">{{ order.status || '-' }}</el-descriptions-item>
      <el-descriptions-item label="维保人员">{{ order.workerName || '-' }}</el-descriptions-item>
      <el-descriptions-item label="配合人员">{{ order.assistantName || '单人作业' }}</el-descriptions-item>
      <el-descriptions-item label="签到时间">{{ order.checkinTime || '未签到' }}</el-descriptions-item>
      <el-descriptions-item label="签退时间">{{ order.checkoutTime || '未签退' }}</el-descriptions-item>
      <el-descriptions-item label="作业时长">{{ order.duration || '-' }}</el-descriptions-item>
      <el-descriptions-item label="上报状态">{{ order.originalRecordId ? (order.reportStatus || '-') : '未生成记录' }}</el-descriptions-item>
    </el-descriptions>

    <template v-if="recordInfo">
      <h4 class="sec">维保记录 / 使用单位确认</h4>
      <el-descriptions :column="3" border size="small">
        <el-descriptions-item label="确认状态">{{ recordInfo.confirmStatus || 'PENDING' }}</el-descriptions-item>
        <el-descriptions-item label="上报状态">{{ recordInfo.uploadStatus || recordInfo.reportStatus || '-' }}</el-descriptions-item>
        <el-descriptions-item label="满意度">{{ recordInfo.satisfaction ?? '-' }}</el-descriptions-item>
      </el-descriptions>
    </template>

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
  </el-card>
</template>

<script setup>
import { computed, ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import * as orderApi from '../api/order'
import { showErr } from '../utils/ui'

const route = useRoute()
const loading = ref(false)
const order = ref({})
const items = ref([])

const elevator = computed(() => order.value.elevator || {})
const recordInfo = computed(() => order.value.recordInfo || null)

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
