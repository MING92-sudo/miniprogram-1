<template>
  <el-card shadow="never">
    <div class="head">
      <span>人员管理</span>
      <div>
        <el-button size="small" :loading="polling" @click="refreshPlatform">刷新平台名单</el-button>
        <el-button size="small" :loading="syncing" :disabled="!auth.canWrite" @click="onSync">同步本地档案</el-button>
        <el-button type="primary" size="small" :disabled="!auth.canWrite" @click="openNew">登记维保人员</el-button>
      </div>
    </div>

    <h4 class="sec">平台人员比对</h4>
    <el-table :data="platformList" v-loading="polling" stripe size="small">
      <el-table-column prop="workManName" label="姓名" width="100" />
      <el-table-column prop="workManCertificate" label="证书编号" min-width="180" show-overflow-tooltip />
      <el-table-column label="绑定状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="String(row.changState) === '1' ? 'danger' : 'success'">
            {{ String(row.changState) === '1' ? '中止' : '正常' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110">
        <template #default="{ row }">
          <el-button v-if="String(row.changState) !== '1'" link type="danger"
                     :disabled="!auth.canWrite" @click="openTerminate(row)">中止绑定</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog" title="登记维保人员" width="560px">
      <el-form label-width="110px">
        <el-form-item label="姓名" required><el-input v-model="form.workManName" /></el-form-item>
        <el-form-item label="证书编号" required><el-input v-model="form.workManCertificate" /></el-form-item>
        <el-form-item label="手机号" required><el-input v-model="form.workManPhone" /></el-form-item>
        <el-form-item label="开始日期" required><el-date-picker v-model="form.workStartDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="结束日期" required><el-date-picker v-model="form.workEndDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="证书文件" required>
          <input type="file" @change="onFile" />
          <div class="tip">证书文件必填；成功后自动本地建档并返回初始密码</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">登记到平台</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="terminateDialog" title="中止平台绑定（2.4 changState=1）" width="520px">
      <el-form label-width="110px">
        <el-form-item label="人员">
          <el-input :model-value="terminateRow ? terminateRow.workManName : ''" disabled />
        </el-form-item>
        <el-form-item label="证书文件" required>
          <input type="file" @change="onTerminateFile" />
          <div class="tip">平台 2.4 建立与中止同端点，证书文件均必填</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="terminateDialog = false">取消</el-button>
        <el-button type="danger" :loading="saving" @click="saveTerminate">确认中止</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref } from 'vue'
import * as platformApi from '../api/platform'
import { showErr, ok } from '../utils/ui'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const saving = ref(false)
const polling = ref(false)
const syncing = ref(false)
const dialog = ref(false)
const terminateDialog = ref(false)
const platformList = ref([])
const terminateRow = ref(null)
const form = ref({})
let certificateFile = null
let terminateFile = null

async function refreshPlatform() {
  polling.value = true
  try {
    // 合并 2.5 两态名单：changState=0 建立（正常）/ 1 中止，绑定状态随平台展示
    const [active, terminated] = await Promise.all([
      platformApi.platformWorkers(0),
      platformApi.platformWorkers(1)
    ])
    platformList.value = [...active, ...terminated]
    ok('平台名单已刷新')
  } catch (e) {
    showErr(e)
  } finally {
    polling.value = false
  }
}

async function onSync() {
  syncing.value = true
  try {
    const res = await platformApi.sync()
    ok(`本地档案已同步：回填 platform_id ${res.workerSynced ?? 0} 人`)
  } catch (e) {
    showErr(e)
  } finally {
    syncing.value = false
  }
}

function openTerminate(row) {
  terminateRow.value = row
  terminateFile = null
  terminateDialog.value = true
}

function onTerminateFile(e) {
  terminateFile = e.target.files[0] || null
}

async function saveTerminate() {
  const r = terminateRow.value
  if (!r || !terminateFile) {
    ok('请选择证书文件')
    return
  }
  saving.value = true
  try {
    await platformApi.registerWorker({
      workManName: r.workManName,
      workManCertificate: r.workManCertificate,
      workStartDate: r.workStartDate,
      workEndDate: r.workEndDate,
      workManPhone: r.workManPhone,
      changState: 1,
      certificateFile: terminateFile
    })
    terminateDialog.value = false
    ok(`${r.workManName} 平台绑定已中止`)
    await refreshPlatform()
  } catch (e) {
    showErr(e)
  } finally {
    saving.value = false
  }
}

function openNew() {
  form.value = { workManName: '', workManCertificate: '', workManPhone: '', workStartDate: '', workEndDate: '' }
  certificateFile = null
  dialog.value = true
}

function onFile(e) {
  certificateFile = e.target.files[0] || null
}

async function save() {
  const f = form.value
  if (!f.workManName || !f.workManCertificate || !f.workManPhone || !f.workStartDate || !f.workEndDate || !certificateFile) {
    ok('请补全全部必填项（含证书文件）')
    return
  }
  saving.value = true
  try {
    const res = await platformApi.registerWorker({ ...f, changState: 0, certificateFile })
    dialog.value = false
    ok(res && res.initialPassword
      ? `登记成功，已自动建档。初始密码（仅展示一次）：${res.initialPassword}`
      : '登记成功')
    await refreshPlatform()
  } catch (e) {
    showErr(e)
  } finally {
    saving.value = false
  }
}

</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.sec { margin: 16px 0 8px; }
.tip { color: #86909c; font-size: 12px; }
</style>
