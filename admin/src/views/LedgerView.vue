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
      <el-table-column label="下次年检时间" width="120">
        <template #default="{ row }">{{ (row.nextCheckDate || '').slice(0, 7) }}</template>
      </el-table-column>
      <el-table-column prop="nextMaintenanceDate" label="下次维保时间" width="130" />
      <el-table-column label="一梯一档" width="100" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openArchive(row)">档案</el-button>
        </template>
      </el-table-column>
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

  <el-drawer v-model="archiveDrawer" :title="'一梯一档 · ' + (archiveElevator.elevatorName || '')" size="62%">
    <el-tabs v-model="archiveTab">
      <el-tab-pane label="维保记录" name="records">
        <el-table :data="archiveRecords" v-loading="archiveLoading" stripe size="small">
          <el-table-column prop="createdAt" label="签退时间" width="170" />
          <el-table-column prop="workTypeCode" label="类别码" width="80" />
          <el-table-column prop="workerName" label="维保人员" width="100" />
          <el-table-column prop="duration" label="时长" width="90" />
          <el-table-column prop="reportStatus" label="上报状态" width="110" />
          <el-table-column label="操作" width="130" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="previewRecordPdf(row.id)">预览</el-button>
              <el-button link type="primary" @click="downloadRecordPdf(row.id)">下载</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="急修单" name="faults">
        <el-table :data="archiveFaults" stripe size="small">
          <el-table-column prop="faultNo" label="单号" width="190" show-overflow-tooltip />
          <el-table-column prop="createdAt" label="登记时间" width="170" />
          <el-table-column prop="faultType" label="类型" width="100" />
          <el-table-column prop="desc" label="描述" min-width="200" show-overflow-tooltip />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'CLOSED' ? 'success' : 'danger'">
                {{ row.status === 'CLOSED' ? '已闭环' : '未闭环' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="130" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="previewFaultPdf(row.id)">预览</el-button>
              <el-button link type="primary" @click="downloadFaultPdf(row.id)">下载</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="年检报告" name="report">
        <div v-if="archiveElevator.inspectionReportUrl" style="display:flex;gap:10px;align-items:center">
          <span>上传于 {{ archiveElevator.inspectionReportUploadedAt || '—' }}</span>
          <el-button link type="primary" @click="viewReport(archiveElevator)">查看</el-button>
          <el-button link type="primary" @click="downloadReport(archiveElevator)">下载</el-button>
        </div>
        <div v-else style="color:#86909c">尚未上传年检报告，请在台账行内上传</div>
      </el-tab-pane>
    </el-tabs>
  </el-drawer>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useAuthStore } from '../stores/auth'
import * as archiveApi from '../api/archive'
import { uploadFile } from '../api/upload'
import * as reportApi from '../api/report'
import * as faultApi from '../api/fault'
import { showErr, ok, downloadBlob } from '../utils/ui'
import { API_BASE } from '../api/client'

const auth = useAuthStore()
const rows = ref([])
const loading = ref(false)
const archiveDrawer = ref(false)
const archiveTab = ref('records')
const archiveElevator = ref({})
const archiveRecords = ref([])
const archiveFaults = ref([])
const archiveLoading = ref(false)

function viewReport(row) {
  window.open(row.inspectionReportUrl)
}

function downloadReport(row) {
  const a = document.createElement('a')
  a.href = row.inspectionReportUrl
  a.target = '_blank'
  a.click()
}

async function openArchive(row) {
  archiveElevator.value = row
  archiveDrawer.value = true
  archiveLoading.value = true
  try {
    const [r, f] = await Promise.all([
      reportApi.listRecords({ elevatorCode: row.elevatorCode, size: 100 }),
      faultApi.list({ size: 200 })
    ])
    archiveRecords.value = r.list || []
    archiveFaults.value = (f.list || []).filter((x) => x.elevatorCode === row.elevatorCode)
  } catch (e) {
    showErr(e)
  } finally {
    archiveLoading.value = false
  }
}

async function fetchPdf(url) {
  const r = await fetch(url, { headers: { Authorization: 'Bearer ' + localStorage.getItem('admin_token') } })
  if (!r.ok) throw new Error('PDF 生成失败')
  return await r.blob()
}

async function previewRecordPdf(id) {
  try { window.open(URL.createObjectURL(await fetchPdf(API_BASE + `/admin/records/${id}/export-pdf`))) } catch (e) { showErr(e) }
}

async function downloadRecordPdf(id) {
  try { downloadBlob(await fetchPdf(API_BASE + `/admin/records/${id}/export-pdf`), '维保记录-' + id + '.pdf') } catch (e) { showErr(e) }
}

async function previewFaultPdf(id) {
  try { window.open(URL.createObjectURL(await fetchPdf(API_BASE + `/admin/faults/${id}/export-pdf`))) } catch (e) { showErr(e) }
}

async function downloadFaultPdf(id) {
  try { downloadBlob(await fetchPdf(API_BASE + `/admin/faults/${id}/export-pdf`), '急修单-' + id + '.pdf') } catch (e) { showErr(e) }
}

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


onMounted(() => load())
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-size: 15px; font-weight: 600; }
</style>
