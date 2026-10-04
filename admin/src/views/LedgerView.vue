<template>
  <el-card shadow="never">
    <div class="head">
      <span class="title">电梯管理台账</span>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column prop="elevatorName" label="项目名称" min-width="150" show-overflow-tooltip />
      <el-table-column prop="useUnitName" label="使用单位" min-width="150" show-overflow-tooltip />
      <el-table-column prop="deviceCode" label="设备代码" width="170" show-overflow-tooltip />
      <el-table-column prop="regCode" label="注册代码" width="170" show-overflow-tooltip />
      <el-table-column prop="elevatorAdminister" label="安全管理员" width="100" />
      <el-table-column prop="elevatorAdministerPhone" label="管理员电话" width="130" />
      <el-table-column prop="nextCheckDate" label="下次年检时间" width="120" />
      <el-table-column prop="nextMaintenanceDate" label="下次维保时间" width="130" />
      <el-table-column label="年检报告" min-width="220">
        <template #default="{ row }">
          <template v-if="row.inspectionReportUrl">
            <el-button link type="primary" @click="viewReport(row)">查看</el-button>
            <el-button link type="primary" @click="downloadReport(row)">下载</el-button>
            <el-upload v-if="auth.canWrite" :show-file-list="false" accept="application/pdf"
                       :http-request="(o) => uploadReport(row, o)" style="display:inline-block;margin-left:10px">
              <el-button link type="warning">重新上传</el-button>
            </el-upload>
          </template>
          <el-upload v-else :show-file-list="false" accept="application/pdf"
                     :http-request="(o) => uploadReport(row, o)">
            <el-button link type="primary" :disabled="!auth.canWrite">上传年检报告（PDF）</el-button>
          </el-upload>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useAuthStore } from '../stores/auth'
import * as archiveApi from '../api/archive'
import { uploadFile } from '../api/upload'
import { showErr, ok } from '../utils/ui'

const auth = useAuthStore()
const rows = ref([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    rows.value = await archiveApi.elevatorsAdmin()
  } catch (e) {
    showErr(e)
  } finally {
    loading.value = false
  }
}

async function uploadReport(row, options) {
  const file = options.file
  if (!file.name.toLowerCase().endsWith('.pdf')) {
    return ok('年检报告仅支持 PDF 格式')
  }
  try {
    const up = await uploadFile(file)
    await archiveApi.updateElevator(row.id, {
      inspectionReportFileId: up.fileId,
      inspectionReportUrl: up.url
    })
    ok(`「${row.elevatorName}」年检报告已上传`)
    await load()
  } catch (e) {
    showErr(e)
  }
}

function viewReport(row) {
  window.open(row.inspectionReportUrl)
}

function downloadReport(row) {
  const a = document.createElement('a')
  a.href = row.inspectionReportUrl
  a.target = '_blank'
  a.click()
}

onMounted(() => load())
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-size: 15px; font-weight: 600; }
</style>
