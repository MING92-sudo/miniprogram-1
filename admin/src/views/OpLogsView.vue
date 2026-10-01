<template>
  <el-card shadow="never">
    <div class="filter-bar">
      <el-input v-model="query.path" placeholder="路径关键字（如 /admin/plans）" clearable style="width: 260px" />
      <el-input v-model="query.operatorId" placeholder="操作人 ID" clearable style="width: 180px" />
      <el-button type="primary" @click="load(1)">查询</el-button>
    </div>

    <el-table :data="rows" v-loading="loading" stripe size="small">
      <el-table-column prop="createdAt" label="时间" width="170" />
      <el-table-column prop="operatorName" label="操作人" width="110" />
      <el-table-column prop="method" label="方法" width="80">
        <template #default="{ row }">
          <el-tag size="small" :type="row.method === 'GET' ? 'info' : 'warning'">{{ row.method }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="path" label="路径" min-width="240" show-overflow-tooltip />
      <el-table-column label="结果" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.result === 'SUCCESS' ? 'success' : 'danger'">{{ row.result }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="ip" label="IP" width="120" />
    </el-table>
    <el-pagination class="pager" layout="total, prev, pager, next" :total="total"
                   :page-size="query.size" :current-page="query.page" @current-change="load" />
    <div class="note">op_log 全量操作留痕 ≥3 年（docs/02 §5）；仅记录管理端写操作</div>
  </el-card>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import * as auditApi from '../api/audit'
import { showErr } from '../utils/ui'

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const query = reactive({ page: 1, size: 20, path: '', operatorId: '' })

async function load(p) {
  if (p) query.page = p
  loading.value = true
  try {
    const data = await auditApi.opLogs({
      page: query.page, size: query.size,
      path: query.path || undefined, operatorId: query.operatorId || undefined
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
.note { color: #86909c; font-size: 12px; margin-top: 10px; }
</style>
