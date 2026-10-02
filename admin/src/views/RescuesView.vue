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
    <el-pagination class="pager" layout="total, prev, pager, next" :total="total"
                   :page-size="query.size" :current-page="query.page" @current-change="load" />
  </el-card>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import * as ledgerApi from '../api/ledger'
import { showErr } from '../utils/ui'

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const query = reactive({ page: 1, size: 20 })

async function load(p) {
  if (p) query.page = p
  loading.value = true
  try {
    const data = await ledgerApi.rescues({ page: query.page, size: query.size })
    rows.value = data.list || []
    total.value = data.total || 0
  } catch (e) {
    showErr(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.danger { color: #f53f3f; font-weight: 600; }
.pager { margin-top: 12px; justify-content: flex-end; }
</style>
