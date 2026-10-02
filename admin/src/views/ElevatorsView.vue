<template>
  <el-card shadow="never">
    <div class="head">
      <span>电梯档案（{{ rows.length }}）</span>
      <div>
        <el-button size="small" :loading="syncing" :disabled="!auth.canWrite" @click="onSync">平台回填（2.7）</el-button>
        <el-button type="primary" size="small" :disabled="!auth.canWrite" @click="openCreate">新建电梯</el-button>
      </div>
    </div>

    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column prop="elevatorName" label="电梯名称" min-width="150" show-overflow-tooltip />
      <el-table-column prop="elevatorCode" label="平台电梯编码" width="130">      </el-table-column>
      <el-table-column prop="useUnitName" label="使用单位" min-width="150" show-overflow-tooltip />
      <el-table-column label="位置待补" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.geoStatus === 'MISSING'" size="small" type="danger">待补坐标</el-tag>
          <el-tag v-else size="small" type="success">正常</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="workerName" label="维保人员" width="90" />
      <el-table-column prop="workTypeCode" label="周期码" width="80" />
      <el-table-column prop="nextCheckDate" label="下次检验" width="110" />
      <el-table-column prop="platformSyncedAt" label="最近同步" width="160" />
      <el-table-column label="操作" width="80" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="!auth.canWrite" @click="openEdit(row)">编辑</el-button>
          <el-button link type="warning" :disabled="!auth.canWrite" @click="onDispatch(row)">立即派单</el-button>
          <el-button link type="danger" :disabled="!auth.canWrite" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog" :title="form.id ? '编辑电梯' : '新建电梯'" width="640px">
      <el-form :model="form" label-width="130px">
<el-form-item label="平台电梯编码" required><el-input v-model="form.elevatorCode"
  placeholder="由平台分配（2.7 查询后自动回填）；自编编号无法上报 2.6" /></el-form-item>
<el-form-item v-if="platformHint" label=" ">
  <el-text size="small" type="success">{{ platformHint }}</el-text>
</el-form-item>
        <el-form-item label="电梯名称" required><el-input v-model="form.elevatorName" /></el-form-item>
        <el-form-item label="安装地点"><el-input v-model="form.location" /></el-form-item>
        <el-form-item label="使用单位">
          <el-select v-model="form.useUnitId" clearable style="width: 100%">
            <el-option v-for="u in units" :key="u.id" :label="u.unitName" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="注册代码"><el-input v-model="form.regCode" /></el-form-item>
<el-form-item label="设备代码">
  <el-input v-model="form.deviceCode">
    <template #append v-if="!form.id">
      <el-button :loading="querying" @click="queryFromPlatform">平台查询(2.7)</el-button>
    </template>
  </el-input>
</el-form-item>
        <el-form-item label="品种">
          <el-select v-model="form.category" style="width: 100%">
            <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="维保周期码">
          <el-select v-model="form.workTypeCode" style="width: 100%">
            <el-option label="FM 半月" value="FM" />
            <el-option label="HM 季度" value="HM" />
            <el-option label="TM 半年" value="TM" />
            <el-option label="SM 年度" value="SM" />
            <el-option label="OY 其它" value="OY" />
          </el-select>
        </el-form-item>
        <el-form-item label="下次检验日期"><el-input v-model="form.nextCheckDate" placeholder="yyyy-MM-dd" /></el-form-item>
        <el-form-item label="经纬度">
          <el-input v-model="form.lng" placeholder="经度 lng" style="width: 48%" />
          <el-input v-model="form.lat" placeholder="纬度 lat" style="width: 48%; margin-left: 4%" />
        </el-form-item>
        <el-form-item label="维保人员手机">
          <el-select v-model="form.workerId" filterable clearable placeholder="选择维保人员1（自动带出平台ID）"
                     style="width: 100%" @change="onWorkerChange">
            <el-option v-for="e in staff" :key="e.id" :value="e.id"
                       :label="e.name + '（' + (e.platformId || '未同步平台ID') + '）'" />
          </el-select>
          <el-select v-model="form.assistantEmployeeId" filterable clearable placeholder="选择维保人员2（可选）"
                     style="width: 100%; margin-top: 6px" @change="onAssistantChange">
            <el-option v-for="e in staff" :key="e.id" :value="e.id"
                       :label="e.name + '（' + (e.platformId || '未同步平台ID') + '）'" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { useAuthStore } from '../stores/auth'
import * as archiveApi from '../api/archive'
import * as platformApi from '../api/platform'
import { showErr, ok } from '../utils/ui'
import { ElMessageBox } from 'element-plus'

const auth = useAuthStore()
const rows = ref([])
const units = ref([])
const loading = ref(false)
const dialog = ref(false)
const saving = ref(false)
const syncing = ref(false)
const querying = ref(false)
const staff = ref([])
const workerEmployeeId = ref('')
const assistantEmployeeId = ref('')
const platformHint = ref('')
const categories = ['曳引与强制驱动电梯', '液压驱动电梯', '杂物电梯', '自动扶梯与自动人行道']

const empty = { id: '', elevatorCode: '', elevatorName: '', location: '', useUnitId: '', regCode: '',
  deviceCode: '', category: '曳引与强制驱动电梯', workTypeCode: 'HM', nextCheckDate: '',
  lng: '', lat: '', workerPhone: '' }
const form = reactive({ ...empty })

async function load() {
  loading.value = true
  try {
    const [elevators, unitList, employeeList] = await Promise.all([archiveApi.elevatorsAdmin(), archiveApi.useUnits(), archiveApi.employees()])
    rows.value = elevators
    units.value = unitList
    staff.value = (employeeList || []).filter((e) => e.role === 'WORKER' || e.role === 'LEADER')
    rows.value = elevators
    units.value = unitList
  } catch (e) {
    showErr(e)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(form, empty)
  platformHint.value = ''
  dialog.value = true
}

  async function onDelete(row) {
    try {
      await ElMessageBox.confirm(`确认删除电梯「${row.elevatorName}（${row.elevatorCode}）」？有在途工单时将被拒绝`, '删除电梯', { type: 'warning' })
      await archiveApi.deleteElevator(row.id)
      ok('已删除')
      await load()
    } catch (e) {
      if (e !== 'cancel') showErr(e)
    }
  }
function openEdit(row) {
  Object.assign(form, empty, row)
  syncEmployeeIds()
  dialog.value = true
}

async function save() {
  if (!form.id && form.workerId) {
    const e = staff.value.find((x) => x.id === form.workerId)
    if (e) { form.workerName = e.name; form.workerPhone = e.phone; form.workerPlatformId = e.platformId }
    const a = staff.value.find((x) => x.id === form.assistantEmployeeId)
    if (a) { form.assistantName = a.name; form.assistantPlatformId = a.platformId }
  }
  saving.value = true
  try {
    const body = { ...form }
    delete body.id
    delete body.useUnitName
    delete body.geoStatus
    delete body.platformSyncedAt
    if (form.id) {
      Object.assign(form, await archiveApi.updateElevator(form.id, body))
    } else {
      Object.assign(form, await archiveApi.createElevator(body))
    }
    dialog.value = false
    ok('已保存')
    await load()
  } catch (e) {
    showErr(e)
  } finally {
    saving.value = false
  }
}

async function onSync() {
  syncing.value = true
  try {
    const res = await platformApi.sync()
    ok(`同步完成：主体 ${res.entitySynced}，人员 ${res.workerSynced}，电梯 ${res.elevatorSynced}`)
    await load()
  } catch (e) {
    showErr(e)
  } finally {
    syncing.value = false
  }
}

/** 2.7 单梯查询回填：设备代码/注册代码/应急电话/安全管理员/使用单位主体（docs/07 实测口径） */
function onWorkerChange(id) {
  const e = staff.value.find((x) => x.id === id)
  form.workerName = e ? e.name : ''
  form.workerPhone = e ? e.phone : ''
  form.workerPlatformId = e ? e.platformId : ''
}
function onAssistantChange(id) {
  const e = staff.value.find((x) => x.id === id)
  form.assistantName = e ? e.name : ''
  form.assistantPlatformId = e ? e.platformId : ''
}
function syncEmployeeIds() {
  workerEmployeeId.value = (staff.value.find((x) => x.name === form.workerName) || {}).id || ''
  assistantEmployeeId.value = (staff.value.find((x) => x.name === form.assistantName) || {}).id || ''
  form.workerId = workerEmployeeId.value
  form.assistantEmployeeId = assistantEmployeeId.value
}
async function onDispatch(row) {
  try {
    await ElMessageBox.confirm(
      `确认为「${row.elevatorName}（${row.elevatorCode}）」立即生成维保工单？（用于首保/补单，跳过到期检查）`,
      '立即派单', { type: 'warning' })
    const o = await archiveApi.dispatchElevator(row.id)
    ok('已派单：' + ((o && o.orderNo) || ''))
    await load()
  } catch (e) {
    if (e !== 'cancel') showErr(e)
  }
}
async function queryFromPlatform() {
  querying.value = true
  platformHint.value = ''
  try {
    const cond = {}
    if (form.deviceCode && form.deviceCode.trim()) cond.deviceCode = form.deviceCode.trim()
    else if (form.regCode && form.regCode.trim()) cond.registrationCode = form.regCode.trim()
    if (!Object.keys(cond).length) {
      platformHint.value = '请先填写 设备代码（或注册代码）——实测平台不支持按电梯编号查询'
      return
    }
    const res = await platformApi.queryElevator(cond)
    if (!res.found) {
      platformHint.value = '平台未回填：' + (res.reason || '未查询到')
      return
    }
    const p = res.elevator || {}
    const picked = []
    if (p.elevatorCode !== undefined && p.elevatorCode !== null && String(p.elevatorCode) !== '') {
      form.elevatorCode = String(p.elevatorCode)
      picked.push('电梯编号')
    }
    const put = (src, key, label) => {
      const v = p[src]
      if (v !== undefined && v !== null && String(v) !== '') {
        form[key] = String(v)
        picked.push(label)
      }
    }
    put('deviceCode', 'deviceCode', '设备代码')
    put('regCode', 'regCode', '注册代码')
    put('registrationCode', 'regCode', '注册代码')
    put('emergencyPhone', 'emergencyPhone', '应急电话')
    put('elevatorAdminister', 'elevatorAdminister', '安全管理员')
    put('elevatorAdministerPhone', 'elevatorAdministerPhone', '管理员电话')
    put('useUnitEntityId', 'useUnitEntityId', '使用单位主体')
    if (!form.elevatorName && p.elevatorName) {
      form.elevatorName = String(p.elevatorName)
      picked.push('电梯名称')
    }
    if (p.useUnitEntityId) {
      const hit = units.value.find((u) => u.entityId === String(p.useUnitEntityId))
      if (hit && !form.useUnitId) form.useUnitId = hit.id
    }
    platformHint.value = picked.length ? '已从平台回填：' + picked.join('、') : '平台已查询到该电梯，但无可回填字段'
  } catch (e) {
    showErr(e)
  } finally {
    querying.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.tip { color: #86909c; font-size: 12px; }
</style>
