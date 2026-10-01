<template>
  <el-card shadow="never">
    <template #header>应急演练台账（半年品种覆盖，docs/01 §3.18）</template>
    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column prop="drillDate" label="演练日期" width="120" />
      <el-table-column prop="category" label="品种" width="160" />
      <el-table-column prop="scene" label="场景" width="120" />
      <el-table-column prop="participants" label="参加人员" min-width="150" />
      <el-table-column prop="process" label="过程" min-width="220" show-overflow-tooltip />
      <el-table-column prop="problems" label="发现问题" min-width="150" show-overflow-tooltip />
      <el-table-column prop="actions" label="整改措施" min-width="150" show-overflow-tooltip />
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
    rows.value = await ledgerApi.drills()
  } catch (e) {
    showErr(e)
  } finally {
    loading.value = false
  }
})
</script>
