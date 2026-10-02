<template>
  <el-card shadow="never">
    <div class="filter-bar">
      <el-select v-model="query.due" placeholder="到期筛选" clearable style="width: 150px">
        <el-option label="今日到期" value="today" />
        <el-option label="3 日内到期" value="soon" />
        <el-option label="已超期" value="overdue" />
      </el-select>
      <el-select v-model="query.status" placeholder="状态" clearable style="width: 140px">
        <el-option label="待执行" value="PENDING" />
        <el-option label="进行中" value="PROCESSING" />
        <el-option label="已完成" value="DONE" />
      </el-select>
      <el-input v-model="query.keyword" placeholder="电梯 / 工单号关键字" clearable style="width: 220px" />
      <el-button type="primary" @click="load(1)">查询</el-button>
    </div>

    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column prop="orderNo" label="工单号" width="170" />
      <el-table-column prop="elevatorName" label="电梯" min-width="150" show-overflow-tooltip />
      <el-table-column prop="workType" label="类别" width="110" />
      <el-table-column prop="planTime" label="计划时间" width="160" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="workerName" label="维保人员1" width="100" />
      <el-table-column prop="assistantName" label="维保人员2" width="100" />
      <el-table-column label="上报" width="100">
        <template #default="{ row }">
          <span v-if="row.originalRecordId">{{ row.reportStatus === 'FAILED' ? '失败' : row.reportStatus === 'REPORTED' ? '已上报' : '待上报' }}</span>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="$router.push(`/orders/${row.id}`)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination class="pager" layout="total, prev, pager, next" :total="total"
                   :page-size="query.size" :current-page="query.page" @current-change="load" />
  </el-card>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import * as orderApi from '../api/order'
import { showErr } from '../utils/ui'

const query = reactive({ page: 1, size: 20, due: '', status: '', keyword: '' })
const rows = ref([])
const total = ref(0)
const loading = ref(false)

function statusText(s) {
  return { PENDING: '待执行', PROCESSING: '进行中', DONE: '已完成' }[s] || s
}
function statusType(s) {
  return { PENDING: 'info', PROCESSING: 'warning', DONE: 'success' }[s] || 'info'
}

async function load(page) {
  if (page) query.page = page
  loading.value = true
  try {
    const data = await orderApi.listOrders({
      page: query.page, size: query.size,
      due: query.due || undefined, status: query.status || undefined,
      keyword: query.keyword || undefined
    })
    rows.value = data.list || []
    total.value = data.total || 0
  } catch (e) {
    showErr(e)
  } finally {
    loading.value = false
  }
}

onMounted(() => load())
</script>

<style scoped>
.filter-bar { display: flex; gap: 10px; margin-bottom: 12px; }
.pager { margin-top: 12px; justify-content: flex-end; }
.muted { color: #c9cdd4; }
</style>
