<template>
  <el-card shadow="never">
    <div class="filter-bar">
      <el-select v-model="query.status" placeholder="状态" clearable style="width: 130px">
        <el-option label="待审核" value="PENDING" />
        <el-option label="已通过" value="APPROVED" />
        <el-option label="已驳回" value="REJECTED" />
      </el-select>
      <el-button type="primary" @click="load(1)">查询</el-button>
    </div>

    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column prop="orderNo" label="工单号" width="150" />
      <el-table-column prop="elevatorName" label="电梯" min-width="140" show-overflow-tooltip />
      <el-table-column prop="employeeName" label="申述人" width="100" />
      <el-table-column label="距离/阈值" width="130">
        <template #default="{ row }">{{ row.distance }}m / {{ row.threshold }}m</template>
      </el-table-column>
      <el-table-column prop="reason" label="申述原因" min-width="200" show-overflow-tooltip />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'APPROVED' ? 'success' : row.status === 'REJECTED' ? 'danger' : 'warning'">
            {{ row.status === 'APPROVED' ? '已通过' : row.status === 'REJECTED' ? '已驳回' : '待审核' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="审核" width="150" fixed="right">
        <template #default="{ row }">
          <template v-if="row.status === 'PENDING'">
            <el-button link type="success" :disabled="!auth.canWrite" @click="audit(row, true)">通过</el-button>
            <el-button link type="danger" :disabled="!auth.canWrite" @click="audit(row, false)">驳回</el-button>
          </template>
          <span v-else class="muted">{{ row.reviewComment || '—' }}</span>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination class="pager" layout="total, prev, pager, next" :total="total"
                   :page-size="query.size" :current-page="query.page" @current-change="load" />
    <div class="note">审核通过后工单标记"补签到"（checkin_extra.locationAppealApproved=true），定位超阈可继续签到（docs/02）</div>
  </el-card>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { useAuthStore } from '../stores/auth'
import * as approvalApi from '../api/approval'
import { showErr, ok } from '../utils/ui'

const auth = useAuthStore()
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const query = reactive({ page: 1, size: 20, status: 'PENDING' })

async function load(p) {
  if (p) query.page = p
  loading.value = true
  try {
    const data = await approvalApi.approvals({ page: query.page, size: query.size, status: query.status || undefined })
    rows.value = data.list || []
    total.value = data.total || 0
  } catch (e) {
    showErr(e)
  } finally {
    loading.value = false
  }
}

async function audit(row, approved) {
  try {
    await approvalApi.auditApproval(row.id, approved, approved ? '' : '未达申诉条件')
    ok(approved ? '已通过（补签到解锁）' : '已驳回')
    await load()
  } catch (e) {
    showErr(e)
  }
}

onMounted(() => load())
</script>

<style scoped>
.filter-bar { display: flex; gap: 10px; margin-bottom: 12px; }
.pager { margin-top: 12px; justify-content: flex-end; }
.note { color: #86909c; font-size: 12px; margin-top: 10px; }
.muted { color: #c9cdd4; }
</style>
