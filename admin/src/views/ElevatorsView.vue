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
      <el-table-column prop="elevatorCode" label="电梯编号" width="130" />
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
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog" :title="form.id ? '编辑电梯' : '新建电梯'" width="640px">
      <el-form :model="form" label-width="130px">
        <el-form-item label="电梯编号" required><el-input v-model="form.elevatorCode" /></el-form-item>
        <el-form-item label="电梯名称" required><el-input v-model="form.elevatorName" /></el-form-item>
        <el-form-item label="安装地点"><el-input v-model="form.location" /></el-form-item>
        <el-form-item label="使用单位">
          <el-select v-model="form.useUnitId" clearable style="width: 100%">
            <el-option v-for="u in units" :key="u.id" :label="u.unitName" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="注册代码"><el-input v-model="form.regCode" /></el-form-item>
        <el-form-item label="设备代码"><el-input v-model="form.deviceCode" /></el-form-item>
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
          <el-input v-model="form.workerPhone" />
          <div class="tip">参与五类手机号互斥（docs/01 §3.2.4）</div>
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

const auth = useAuthStore()
const rows = ref([])
const units = ref([])
const loading = ref(false)
const dialog = ref(false)
const saving = ref(false)
const syncing = ref(false)
const categories = ['曳引与强制驱动电梯', '液压驱动电梯', '杂物电梯', '自动扶梯与自动人行道']

const empty = { id: '', elevatorCode: '', elevatorName: '', location: '', useUnitId: '', regCode: '',
  deviceCode: '', category: '曳引与强制驱动电梯', workTypeCode: 'HM', nextCheckDate: '',
  lng: '', lat: '', workerPhone: '' }
const form = reactive({ ...empty })

async function load() {
  loading.value = true
  try {
    const [elevators, unitList] = await Promise.all([archiveApi.elevatorsAdmin(), archiveApi.useUnits()])
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
  dialog.value = true
}

function openEdit(row) {
  Object.assign(form, empty, row)
  dialog.value = true
}

async function save() {
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

onMounted(load)
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.tip { color: #86909c; font-size: 12px; }
</style>
