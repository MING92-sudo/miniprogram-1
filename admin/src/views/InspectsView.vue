<template>
  <el-card shadow="never">
    <template #header>年度自行检查台账（依据 TSG T5002，共 257 项模板）</template>
    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column prop="elevatorId" label="电梯" width="160" />
      <el-table-column prop="inspectDate" label="检查日期" width="120" />
      <el-table-column prop="itemTotal" label="检查项数" width="90" />
      <el-table-column label="异常项" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.abnormalCount > 0 ? 'danger' : 'success'">{{ row.abnormalCount }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="problems" label="发现问题" min-width="220" show-overflow-tooltip />
      <el-table-column prop="inspectorSign" label="检查人签字" width="130" />
      <el-table-column prop="reviewerSign" label="复核签字" width="130" />
    </el-table>
  </el-card>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as ledgerApi from '../api/ledger'
import { showErr } from '../utils/ui'

const rows = ref([])
const loading = ref(false)

onMounted(async () => {
  loading.value = true
  try {
    rows.value = await ledgerApi.inspects()
  } catch (e) {
    showErr(e)
  } finally {
    loading.value = false
  }
})
</script>
