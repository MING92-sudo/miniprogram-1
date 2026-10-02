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

    <el-row :gutter="12" class="mt12">
      <el-col :span="12">
        <el-card shadow="never">
          <template #header>服务关系登记（2.3 · contractFile 必填）</template>
          <el-form label-width="110px" size="small">
            <el-form-item label="使用单位" required>
              <el-select v-model="svc.unitId" filterable placeholder="选择使用单位（自动带出 entityID）" style="width: 100%"
                         @change="onUnitChange">
                <el-option v-for="u in useUnits" :key="u.id" :value="u.id"
                           :label="u.unitName + (u.entityId ? '（已同步主体）' : '（主体未同步）')" />
              </el-select>
            </el-form-item>
            <el-form-item label="主体 entityID" required>
              <el-input v-model="svc.useUnitEntityID" placeholder="随使用单位带出，也可手填（先触发同步获取）" />
            </el-form-item>
            <el-form-item label="服务期限" required>
              <el-date-picker v-model="svc.dates" type="daterange" value-format="YYYY-MM-DD"
                              start-placeholder="开始" end-placeholder="结束" style="width: 100%" />
            </el-form-item>
            <el-form-item label="合同文件" required>
              <el-upload :auto-upload="false" :limit="1" accept=".pdf" :on-change="onSvcFile"
                         :on-remove="() => (svc.file = null)">
                <el-button size="small">选择合同 PDF</el-button>
              </el-upload>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="svc.submitting" :disabled="!auth.canWrite || !svcReady"
                         @click="submitService">登记服务关系</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never">
          <template #header>维保人员登记（2.4 · certificateFile 必填）</template>
          <el-form label-width="110px" size="small">
            <el-form-item label="人员" required>
              <el-select v-model="wk.empId" filterable placeholder="选择维保人员（自动带出证书/手机号/工期）"
                         style="width: 100%" @change="onEmpChange">
                <el-option v-for="e in workers" :key="e.id" :value="e.id"
                           :label="e.name + '（' + (e.certificate || '无证书编号') + '）'" />
              </el-select>
            </el-form-item>
            <el-form-item label="姓名" required>
              <el-input v-model="wk.workManName" />
            </el-form-item>
            <el-form-item label="证书编号" required>
              <el-input v-model="wk.workManCertificate" />
            </el-form-item>
            <el-form-item label="手机号" required>
              <el-input v-model="wk.workManPhone" />
            </el-form-item>
            <el-form-item label="作业期限" required>
              <el-date-picker v-model="wk.dates" type="daterange" value-format="YYYY-MM-DD"
                              start-placeholder="开始" end-placeholder="结束" style="width: 100%" />
            </el-form-item>
            <el-form-item label="证书文件" required>
              <el-upload :auto-upload="false" :limit="1" accept=".pdf,.jpg,.jpeg,.png" :on-change="onWkFile"
                         :on-remove="() => (wk.file = null)">
                <el-button size="small">选择证书文件</el-button>
              </el-upload>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="wk.submitting" :disabled="!auth.canWrite || !wkReady"
                         @click="submitWorker">登记维保人员</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, ref, onMounted } from 'vue'
import { useAuthStore } from '../stores/auth'
import * as platformApi from '../api/platform'
import * as archiveApi from '../api/archive'
import { showErr, ok } from '../utils/ui'

const auth = useAuthStore()
const status = ref(null)
const syncing = ref(false)

const st = computed(() => status.value || {})
const pendingEmployees = computed(() => (status.value && status.value.employeePendingList) || [])
const geoMissing = computed(() => (status.value && status.value.elevatorGeoMissingList) || [])

// ── 平台登记（2.3 / 2.4） ──
const useUnits = ref([])
const workers = ref([])
const svc = ref({ unitId: '', useUnitName: '', useUnitEntityID: '', dates: null, file: null, submitting: false })
const wk = ref({ empId: '', workManName: '', workManCertificate: '', workManPhone: '', dates: null, file: null, submitting: false })
const svcReady = computed(() => svc.value.useUnitName && svc.value.useUnitEntityID
  && svc.value.dates && svc.value.dates[0] && svc.value.file)
const wkReady = computed(() => wk.value.workManName && wk.value.workManCertificate && wk.value.workManPhone
  && wk.value.dates && wk.value.dates[0] && wk.value.file)

function onUnitChange(id) {
  const u = useUnits.value.find((x) => x.id === id)
  svc.value.useUnitName = u ? u.unitName : ''
  svc.value.useUnitEntityID = u ? u.entityId : ''
}
function onEmpChange(id) {
  const e = workers.value.find((x) => x.id === id)
  if (!e) return
  wk.value.workManName = e.name
  wk.value.workManCertificate = e.certificate
  wk.value.workManPhone = e.phone
  wk.value.dates = e.workStartDate && e.workEndDate ? [e.workStartDate, e.workEndDate] : null
}
function onSvcFile(file) { svc.value.file = file.raw }
function onWkFile(file) { wk.value.file = file.raw }

async function submitService() {
  svc.value.submitting = true
  try {
    await platformApi.registerService({
      useUnitName: svc.value.useUnitName,
      useUnitEntityID: svc.value.useUnitEntityID,
      changState: 0,
      serviceStartDate: svc.value.dates[0],
      serviceEndDate: svc.value.dates[1],
      contractFile: svc.value.file,
    })
    ok('服务关系登记成功（2.3）')
  } catch (e) {
    showErr(e)
  } finally {
    svc.value.submitting = false
  }
}

async function submitWorker() {
  wk.value.submitting = true
  try {
    await platformApi.registerWorker({
      workManName: wk.value.workManName,
      workManCertificate: wk.value.workManCertificate,
      workStartDate: wk.value.dates[0],
      workEndDate: wk.value.dates[1],
      workManPhone: wk.value.workManPhone,
      changState: 0,
      certificateFile: wk.value.file,
    })
    ok('维保人员登记成功（2.4），请触发同步回填 platform_id')
    await load()
  } catch (e) {
    showErr(e)
  } finally {
    wk.value.submitting = false
  }
}

async function loadArchives() {
  try {
    const [uu, em] = await Promise.all([archiveApi.useUnits(), archiveApi.employees()])
    useUnits.value = uu || []
    workers.value = (em || []).filter((e) => e.role === 'WORKER' || e.role === 'LEADER')
  } catch (e) {
    showErr(e)
  }
}

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
onMounted(loadArchives)
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
