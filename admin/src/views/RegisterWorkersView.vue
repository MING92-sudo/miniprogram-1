<template>
  <el-card shadow="never">
    <div class="head">
      <span>人员管理（2.4：登记维保人员 → 2.5 回填 platform_id）</span>
      <div>
        <el-button size="small" :loading="polling" @click="refreshPlatform">刷新平台名单（2.5）</el-button>
        <el-button type="primary" size="small" :disabled="!auth.canWrite" @click="openNew">登记维保人员</el-button>
      </div>
    </div>

    <h4 class="sec">平台人员比对（2.5）</h4>
    <el-table :data="platformList" v-loading="polling" stripe size="small">
      <el-table-column prop="workManName" label="姓名" width="100" />
      <el-table-column prop="workManCertificate" label="证书编号" min-width="180" show-overflow-tooltip />
      <el-table-column label="本地档案" width="140">
        <template #default>
          <el-tag size="small" type="info">以【平台同步】回填结果为准</el-tag>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog" title="登记维保人员（2.4）" width="560px">
      <el-form label-width="110px">
        <el-form-item label="姓名" required><el-input v-model="form.workManName" /></el-form-item>
        <el-form-item label="证书编号" required><el-input v-model="form.workManCertificate" /></el-form-item>
        <el-form-item label="手机号" required><el-input v-model="form.workManPhone" /></el-form-item>
        <el-form-item label="开始日期" required><el-date-picker v-model="form.workStartDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="结束日期" required><el-date-picker v-model="form.workEndDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="证书文件" required>
          <input type="file" @change="onFile" />
          <div class="tip">certificateFile 必填（平台 2.4 multipart 实测口径）；成功后自动本地建档并返回初始密码</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">登记到平台</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as platformApi from '../api/platform'
import { showErr, ok } from '../utils/ui'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const saving = ref(false)
const polling = ref(false)
const dialog = ref(false)
const platformList = ref([])
const form = ref({})
let certificateFile = null

async function refreshPlatform() {
  polling.value = true
  try {
    platformList.value = await platformApi.platformWorkers(0)
    ok('平台名单已刷新')
  } catch (e) {
    showErr(e)
  } finally {
    polling.value = false
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
      ? `2.4 登记成功，已自动建档。初始密码（仅展示一次）：${res.initialPassword}`
      : '2.4 登记成功')
    await refreshPlatform()
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
.sec { margin: 16px 0 8px; }
.tip { color: #86909c; font-size: 12px; }
</style>
