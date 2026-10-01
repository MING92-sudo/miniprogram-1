<template>
  <div>
    <el-card shadow="never">
      <template #header>
        <div class="head">
          <span>平台同步状态</span>
          <el-button type="primary" size="small" :loading="syncing" :disabled="!auth.canWrite"
                     @click="onSync">触发同步（2.2 / 2.5 / 2.7）</el-button>
        </div>
      </template>
      <el-alert v-if="status && !status.platformConfigured" type="error" :closable="false"
                title="监管平台凭证未配置（REG_* 环境变量仅在云托管服务设置中配置，管理端不提供凭证界面，AGENTS §2.1）"
                class="mb12" />
      <el-row :gutter="12">
        <el-col :span="6">
          <el-card shadow="never" class="stat"><div class="num">{{ st.employeePendingSync }}</div><div class="lab">待同步人员（platform_id 缺失）</div></el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="never" class="stat warn"><div class="num">{{ st.elevatorGeoMissing }}</div><div class="lab">位置待补电梯（坐标缺失）</div></el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="never" class="stat"><div class="num">{{ st.employeeTotal }}</div><div class="lab">人员总数</div></el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="never" class="stat"><div class="num sm">{{ st.lastSyncAt || '从未同步' }}</div><div class="lab">最近同步时间</div></el-card>
        </el-col>
      </el-row>
    </el-card>

    <el-row :gutter="12" class="mt12">
      <el-col :span="12">
        <el-card shadow="never">
          <template #header>待同步人员（{{ pendingEmployees.length }}）</template>
          <el-table :data="pendingEmployees" size="small" stripe max-height="360">
            <el-table-column prop="name" label="姓名" width="110" />
            <el-table-column prop="certificate" label="证书编号" min-width="150" />
            <el-table-column prop="platformId" label="platform_id">
              <template #default="{ row }">{{ row.platformId || '—' }}</template>
            </el-table-column>
            <el-table-column prop="syncStatus" label="状态" width="110" />
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never">
          <template #header>位置待补电梯（{{ geoMissing.length }}）</template>
          <el-table :data="geoMissing" size="small" stripe max-height="360">
            <el-table-column prop="elevatorCode" label="电梯编号" width="140" />
            <el-table-column prop="elevatorName" label="电梯名称" min-width="160" />
            <el-table-column label="处理" width="110">
              <template #default>
                <el-tag size="small" type="danger">待补录坐标</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, ref, onMounted } from 'vue'
import { useAuthStore } from '../stores/auth'
import * as platformApi from '../api/platform'
import { showErr, ok } from '../utils/ui'

const auth = useAuthStore()
const status = ref(null)
const syncing = ref(false)

const st = computed(() => status.value || {})
const pendingEmployees = computed(() => (status.value && status.value.employeePendingList) || [])
const geoMissing = computed(() => (status.value && status.value.elevatorGeoMissingList) || [])

async function load() {
  try {
    status.value = await platformApi.syncStatus()
  } catch (e) {
    showErr(e)
  }
}

async function onSync() {
  syncing.value = true
  try {
    const res = await platformApi.sync()
    ok(`同步完成：主体 ${res.entitySynced}，人员 ${res.workerSynced}，电梯 ${res.elevatorSynced}，存量 ${res.legacyUploaded}`)
    await load()
  } catch (e) {
    showErr(e)
  } finally {
    syncing.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; }
.stat { text-align: center; }
.stat .num { font-size: 26px; font-weight: 700; }
.stat .num.sm { font-size: 15px; }
.stat .lab { color: #86909c; font-size: 12px; margin-top: 4px; }
.stat.warn .num { color: #f53f3f; }
.mt12 { margin-top: 12px; }
.mb12 { margin-bottom: 12px; }
</style>
