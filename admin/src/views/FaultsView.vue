<template>
  <el-card shadow="never">
    <template #header>故障记录台账</template>
    <div class="filter-bar">
      <el-select v-model="status" placeholder="状态" clearable style="width: 140px">
        <el-option label="未闭环" value="OPEN" />
        <el-option label="已闭环" value="CLOSED" />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
    </div>
    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column prop="elevatorCode" label="电梯编号" width="130" />
      <el-table-column prop="faultType" label="故障类别" width="120" />
      <el-table-column prop="descr" label="描述" min-width="200" show-overflow-tooltip />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'OPEN' ? 'danger' : 'success'">
            {{ row.status === 'OPEN' ? '未闭环' : '已闭环' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="handleDesc" label="处理记录" min-width="180" show-overflow-tooltip />
      <el-table-column prop="createdAt" label="上报时间" width="160" />
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
const status = ref('')
const query = reactive({ page: 1, size: 20 })

async function load(p) {
  if (p) query.page = p
  loading.value = true
  try {
    const data = await ledgerApi.faults({ page: query.page, size: query.size, status: status.value || undefined })
    rows.value = data.list || []
    total.value = data.total || 0
  } catch (e) {
    showErr(e)
  } finally {
    loading.value = false
  }
}

function search() {
  query.page = 1
  load()
}

onMounted(load)
</script>

<style scoped>
.filter-bar { display: flex; gap: 10px; margin-bottom: 12px; }
.pager { margin-top: 12px; justify-content: flex-end; }
</style>
