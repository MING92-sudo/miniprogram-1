<template>
  <el-card shadow="never">
    <div class="filter-bar">
      <el-select v-model="query.status" placeholder="结果" clearable style="width: 140px">
        <el-option label="成功" value="SUCCESS" />
        <el-option label="失败" value="FAILED" />
      </el-select>
      <el-select v-model="query.action" placeholder="动作" clearable style="width: 150px">
        <el-option label="签退自动上报" value="UPLOAD" />
        <el-option label="手动重报" value="REUPLOAD" />
        <el-option label="存量推送" value="LEGACY" />
      </el-select>
      <el-input v-model="query.originalRecordId" placeholder="originalRecordId" clearable style="width: 240px" />
      <el-button type="primary" @click="load(1)">查询</el-button>
    </div>

    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column prop="createdAt" label="时间" width="170" />
      <el-table-column label="动作" width="120">
        <template #default="{ row }">{{ actionText(row.action) }}</template>
      </el-table-column>
      <el-table-column prop="originalRecordId" label="记录标识" width="220" show-overflow-tooltip />
      <el-table-column label="平台应答" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'SUCCESS' ? 'success' : 'danger'">
            {{ row.status }} {{ row.platformCode }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="platformMessage" label="平台消息" min-width="160" show-overflow-tooltip />
      <el-table-column prop="requestDigest" label="脱敏请求摘要" min-width="260" show-overflow-tooltip />
    </el-table>

    <el-pagination class="pager" layout="total, prev, pager, next" :total="total"
                   :page-size="query.size" :current-page="query.page" @current-change="load" />
  </el-card>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import * as platformApi from '../api/platform'
import { showErr } from '../utils/ui'

const query = reactive({ page: 1, size: 20, status: '', action: '', originalRecordId: '' })
const rows = ref([])
const total = ref(0)
const loading = ref(false)

function actionText(a) {
  return { UPLOAD: '签退自动上报', REUPLOAD: '手动重报', LEGACY: '存量推送' }[a] || a
}

async function load(page) {
  if (page) query.page = page
  loading.value = true
  try {
    const data = await platformApi.uploadLogs({
      ...query,
      status: query.status || undefined,
      action: query.action || undefined,
      originalRecordId: query.originalRecordId || undefined
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
