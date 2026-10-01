<template>
  <el-card shadow="never">
    <template #header>困人救援台账（≤30 分钟达标，docs/01 §3.9.3）</template>
    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column prop="elevatorCode" label="电梯编号" width="130" />
      <el-table-column prop="trappedCount" label="被困人数" width="90" />
      <el-table-column prop="alarmAt" label="报警时间" width="160" />
      <el-table-column prop="arriveMinutes" label="到场耗时(分)" width="110">
        <template #default="{ row }">
          <span :class="{ danger: row.arriveMinutes > 30 }">{{ row.arriveMinutes }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="rescuedMinutes" label="解救耗时(分)" width="110">
        <template #default="{ row }">
          <span :class="{ danger: row.rescuedMinutes > 30 }">{{ row.rescuedMinutes }}</span>
        </template>
      </el-table-column>
      <el-table-column label="超时" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.overtime ? 'danger' : 'success'">{{ row.overtime ? '超时' : '达标' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="descr" label="情况描述" min-width="200" show-overflow-tooltip />
      <el-table-column prop="status" label="状态" width="90" />
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
    const data = await ledgerApi.rescues({ page: 1, size: 100 })
    rows.value = data.list || []
  } catch (e) {
    showErr(e)
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.danger { color: #f53f3f; font-weight: 600; }
</style>
