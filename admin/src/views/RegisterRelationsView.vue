<template>
  <el-card shadow="never">
    <div class="head">
      <span>服务关系维护（2.3：使用单位 ↔ 维保单位；multipart + contractFile，docs/07 实测口径）</span>
      <el-button type="primary" size="small" :disabled="!auth.canWrite" @click="openNew(null)">新建服务关系</el-button>
    </div>

    <el-table :data="units" v-loading="loading" stripe>
      <el-table-column prop="unitName" label="使用单位" min-width="180" show-overflow-tooltip />
      <el-table-column prop="entityId" label="使用单位主体ID" min-width="180" show-overflow-tooltip />
      <el-table-column prop="elevatorCount" label="在保电梯数" width="100" />
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="!auth.canWrite" @click="openNew(row)">登记 2.3</el-button>
          <el-button link type="danger" :disabled="!auth.canWrite" @click="onTerminate(row)">终止（changState=1）</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-alert type="info" :closable="false" class="tip"
              title="平台 2.3 无查询接口：登记/终止记录以 reg_upload_log（action=REGISTER）为准，可在【上报日志】页查询；终止与新建同一端点，仅 changState 不同（docs/04 A.0.2）" />

    <el-dialog v-model="dialog" :title="terminateMode ? '终止服务关系（2.3 changState=1）' : '新建服务关系（2.3）'" width="560px">
      <el-form label-width="140px">
        <el-form-item label="使用单位">
          <el-select v-if="!terminateMode" v-model="form.useUnitName" filterable style="width: 100%" @change="onUnitChange">
            <el-option v-for="u in units" :key="u.id" :value="u.unitName" :label="u.unitName" />
          </el-select>
          <el-input v-else v-model="form.useUnitName" disabled />
        </el-form-item>
        <el-form-item label="使用单位主体ID" required>
          <el-input v-model="form.useUnitEntityID" placeholder="为空请先到【使用单位档案】点“同步主体ID（2.2）”" />
        </el-form-item>
        <el-form-item label="服务开始日期" required><el-date-picker v-model="form.serviceStartDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="服务结束日期" required><el-date-picker v-model="form.serviceEndDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="合同文件" required>
          <input type="file" @change="onFile" />
          <div class="tip">contractFile 必填（平台 2.3 multipart 实测口径，建立/终止一致）</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ terminateMode ? '提交终止' : '登记到平台' }}</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as archiveApi from '../api/archive'
import * as platformApi from '../api/platform'
import { showErr, ok } from '../utils/ui'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const dialog = ref(false)
const terminateMode = ref(false)
const units = ref([])
const form = ref({ useUnitName: '', useUnitEntityID: '', serviceStartDate: '', serviceEndDate: '' })
let contractFile = null

async function load() {
  loading.value = true
  try {
    units.value = await archiveApi.useUnits()
  } catch (e) {
    showErr(e)
  } finally {
    loading.value = false
  }
}

function onUnitChange(name) {
  const u = units.value.find((x) => x.unitName === name)
  form.value.useUnitEntityID = u && u.entityId ? u.entityId : ''
}

function openNew(row) {
  terminateMode.value = false
  form.value = { useUnitName: row ? row.unitName : '', useUnitEntityID: row ? row.entityId || '' : '', serviceStartDate: '', serviceEndDate: '' }
  contractFile = null
  dialog.value = true
}

function onTerminate(row) {
  terminateMode.value = true
  form.value = { useUnitName: row.unitName, useUnitEntityID: row.entityId || '', serviceStartDate: '', serviceEndDate: '' }
  contractFile = null
  dialog.value = true
}

function onFile(e) {
  contractFile = e.target.files[0] || null
}

async function save() {
  const f = form.value
  if (!f.useUnitName || !f.useUnitEntityID || !f.serviceStartDate || !f.serviceEndDate || !contractFile) {
    ok('请补全：使用单位/主体ID/起止日期/合同文件（均为平台必填）')
    return
  }
  saving.value = true
  try {
    await platformApi.registerService({
      useUnitName: f.useUnitName,
      useUnitEntityID: f.useUnitEntityID,
      changState: terminateMode.value ? 1 : 0,
      serviceStartDate: f.serviceStartDate,
      serviceEndDate: f.serviceEndDate,
      contractFile
    })
    dialog.value = false
    ok(terminateMode.value ? '2.3 终止请求已提交（changState=1）' : '2.3 登记成功')
  } catch (e) {
    showErr(e)
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.tip { color: #86909c; font-size: 12px; margin-top: 8px; }
</style>
