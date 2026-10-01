<template>
  <div>
    <el-card shadow="never">
      <template #header>
        <div class="head">
          <span>预警规则（docs/01 §3.11 九类，可配提前天数与启停）</span>
          <el-button type="primary" size="small" :disabled="!auth.canWrite" :loading="generating" @click="onGenerate">
            生成预警（按启用规则扫描）
          </el-button>
        </div>
      </template>
      <el-table :data="rules" v-loading="loading" stripe size="small">
        <el-table-column prop="name" label="预警类型" width="150" />
        <el-table-column prop="type" label="代码" width="150" />
        <el-table-column label="提前天数" width="160">
          <template #default="{ row }">
            <el-input-number v-model="row.advanceDays" :disabled="!auth.canWrite" size="small"
                             :min="-30" :max="365" @change="saveRule(row)" />
          </template>
        </el-table-column>
        <el-table-column prop="target" label="通知对象" min-width="220" show-overflow-tooltip />
        <el-table-column label="启用" width="90">
          <template #default="{ row }">
            <el-switch v-model="row.enabled" :disabled="!auth.canWrite" @change="saveRule(row)" />
          </template>
        </el-table-column>
      </el-table>
      <div class="note">可生成：年检到期/人员证件到期/维保超期/使用单位确认超时/自行检查未完成；合同/库存/演练超期待对应模块后置</div>
    </el-card>

    <el-card shadow="never" class="mt12">
      <template #header>
        <div class="head">
          <span>预警记录</span>
          <el-space>
            <el-select v-model="query.status" placeholder="状态" clearable style="width: 120px">
              <el-option label="待处理" value="OPEN" />
              <el-option label="已处理" value="RESOLVED" />
            </el-select>
            <el-button type="primary" @click="loadAlerts(1)">查询</el-button>
          </el-space>
        </div>
      </template>
      <el-table :data="alerts" v-loading="loadingAlerts" stripe size="small">
        <el-table-column prop="title" label="标题" width="150" />
        <el-table-column prop="content" label="内容" min-width="300" show-overflow-tooltip />
        <el-table-column prop="target" label="通知对象" min-width="160" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'OPEN' ? 'warning' : 'success'">
              {{ row.status === 'OPEN' ? '待处理' : '已处理' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="生成时间" width="170" />
      </el-table>
      <el-pagination class="pager" layout="total, prev, pager, next" :total="total"
                     :page-size="query.size" :current-page="query.page" @current-change="loadAlerts" />
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { useAuthStore } from '../stores/auth'
import * as alertApi from '../api/alert'
import { showErr, ok } from '../utils/ui'

const auth = useAuthStore()
const rules = ref([])
const alerts = ref([])
const total = ref(0)
const loading = ref(false)
const loadingAlerts = ref(false)
const generating = ref(false)
const query = reactive({ page: 1, size: 20, status: '' })

async function load() {
  loading.value = true
  try {
    rules.value = await alertApi.alertRules()
  } catch (e) {
    showErr(e)
  } finally {
    loading.value = false
  }
}

async function saveRule(row) {
  try {
    await alertApi.updateAlertRule(row.id, { advanceDays: row.advanceDays, enabled: row.enabled })
  } catch (e) {
    showErr(e)
    await load()
  }
}

async function loadAlerts(p) {
  if (p) query.page = p
  loadingAlerts.value = true
  try {
    const data = await alertApi.alerts({ page: query.page, size: query.size, status: query.status || undefined })
    alerts.value = data.list || []
    total.value = data.total || 0
  } catch (e) {
    showErr(e)
  } finally {
    loadingAlerts.value = false
  }
}

async function onGenerate() {
  generating.value = true
  try {
    const res = await alertApi.generateAlerts()
    ok(`生成 ${res.created} 条预警`)
    await loadAlerts(1)
  } catch (e) {
    showErr(e)
  } finally {
    generating.value = false
  }
}

onMounted(() => {
  load()
  loadAlerts()
})
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; }
.pager { margin-top: 12px; justify-content: flex-end; }
.mt12 { margin-top: 12px; }
.note { color: #86909c; font-size: 12px; margin-top: 10px; }
</style>
