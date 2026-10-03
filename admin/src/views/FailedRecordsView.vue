<template>
  <el-card shadow="never">
    <div class="filter-bar">
      <el-select v-model="query.reportStatus" placeholder="上报状态" clearable style="width: 150px">
        <el-option label="上报失败" value="FAILED" />
        <el-option label="待上报" value="SUBMITTED" />
        <el-option label="已上报" value="REPORTED" />
      </el-select>
      <el-select v-model="query.confirmStatus" placeholder="确认状态" clearable style="width: 150px">
        <el-option label="待确认" value="PENDING" />
        <el-option label="已确认" value="CONFIRMED" />
      </el-select>
      <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD"
                      start-placeholder="开始日期" end-placeholder="结束日期" style="width: 240px" />
      <el-input v-model="query.keyword" placeholder="人员 / 电梯 / 记录号" clearable style="width: 200px" />
      <el-button type="primary" @click="load(1)">查询</el-button>
    </div>

    <el-alert type="warning" :closable="false" class="tip"
              title="重报仅可逐条人工触发，暂不支持批量自动重试" />

    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column prop="elevatorName" label="电梯" min-width="140" show-overflow-tooltip />
      <el-table-column prop="workType" label="类别" width="100" />
      <el-table-column prop="workerName" label="维保人员" width="90" />
      <el-table-column prop="checkinTime" label="签到" width="160" />
      <el-table-column prop="checkoutTime" label="签退" width="160" />
      <el-table-column label="隐患码" min-width="140">
        <template #default="{ row }">
          <el-tag v-for="c in row.problemCodes" :key="c" size="small" class="hz"
                  :type="c === 'S0' ? 'success' : 'danger'">{{ c }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="上报" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="row.reportStatus === 'FAILED' ? 'danger' : row.reportStatus === 'REPORTED' ? 'success' : 'info'">
            {{ statusText(row.reportStatus) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="retryCount" label="重报次数" width="80" />
      <el-table-column label="确认" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.confirmStatus === 'CONFIRMED' ? 'success' : 'warning'">
            {{ row.confirmStatus === 'CONFIRMED' ? '已确认' : '待确认' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="openDetail(row)">详情</el-button>
          <el-button size="small" link type="primary" :disabled="!auth.canWrite" @click="onExport(row)">导出 PDF</el-button>
          <el-button size="small" link type="danger" :disabled="!auth.canWrite || row.reportStatus !== 'FAILED'"
                     @click="onReupload(row)">重报</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination class="pager" layout="total, prev, pager, next" :total="total"
                   :page-size="query.size" :current-page="query.page" @current-change="load" />

    <el-dialog v-model="detailVisible" title="维保记录详情" width="720px">
      <el-descriptions :column="2" border size="small" v-if="detail">
        <el-descriptions-item label="电梯">{{ detail.elevatorName }}（{{ detail.elevatorCode }}）</el-descriptions-item>
        <el-descriptions-item label="维保类别">{{ detail.workType }}</el-descriptions-item>
        <el-descriptions-item label="时长">{{ detail.duration }}</el-descriptions-item>
        <el-descriptions-item label="上报状态">{{ detail.uploadStatus || detail.reportStatus }}</el-descriptions-item>
        <el-descriptions-item label="维保 / 配合">{{ detail.workerName }} / {{ detail.assistantName || '单人' }}</el-descriptions-item>
        <el-descriptions-item label="隐患码">{{ (detail.problemCodes || []).join('、') || '—' }}</el-descriptions-item>
        <el-descriptions-item label="确认状态">{{ detail.confirmStatus }}</el-descriptions-item>
        <el-descriptions-item label="重报次数">{{ detail.retryCount }}</el-descriptions-item>
      </el-descriptions>
      <div v-if="detail && detail.photos && detail.photos.length" class="photos">
        <el-image v-for="(p, i) in detail.photos" :key="i" :src="p" :preview-src-list="detail.photos"
                  fit="cover" class="photo" />
      </div>
      <div v-else-if="detail" class="muted">无现场照片</div>
      <template v-if="detail">
        <h4 class="sec">检查项明细（{{ (detail.items || []).length }} 项）</h4>
        <el-table :data="detail.items" size="small" max-height="200">
          <el-table-column type="index" label="#" width="44" />
          <el-table-column prop="name" label="检查项" min-width="180" show-overflow-tooltip />
          <el-table-column prop="result" label="结果" width="80" />
        </el-table>
        <h4 class="sec">签字区（维保 / 配合 / 安全管理员）</h4>
        <div class="signs">
          <el-image v-if="detail.workerSignatureUrl" :src="detail.workerSignatureUrl" fit="contain" class="sign" />
          <el-image v-if="detail.assistantSignatureUrl" :src="detail.assistantSignatureUrl" fit="contain" class="sign" />
          <el-image v-if="detail.signatureUrl" :src="detail.signatureUrl" fit="contain" class="sign" />
        </div>
        <div v-if="!(detail.workerSignatureUrl || detail.assistantSignatureUrl || detail.signatureUrl)" class="muted">无签字图</div>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { useAuthStore } from '../stores/auth'
import * as reportApi from '../api/report'
import { REPORT_STATUS } from '../constants'
import { showErr, ok, downloadBlob } from '../utils/ui'

const auth = useAuthStore()
const query = reactive({ page: 1, size: 20, reportStatus: 'FAILED', confirmStatus: '', keyword: '' })
const range = ref(null)
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const detailVisible = ref(false)
const detail = ref(null)

function statusText(s) {
  return REPORT_STATUS[s] || s
}

async function load(page) {
  if (page) query.page = page
  loading.value = true
  try {
    const data = await reportApi.listRecords({
      ...query,
      reportStatus: query.reportStatus || undefined,
      confirmStatus: query.confirmStatus || undefined,
      dateFrom: range.value && range.value[0],
      dateTo: range.value && range.value[1]
    })
    rows.value = data.list || []
    total.value = data.total || 0
  } catch (e) {
    showErr(e)
  } finally {
    loading.value = false
  }
}

async function openDetail(row) {
  try {
    detail.value = await reportApi.getRecord(row.id)
    detailVisible.value = true
  } catch (e) {
    showErr(e)
  }
}

async function onReupload(row) {
  loading.value = true
  try {
    const res = await reportApi.reupload(row.id)
    ok(res.reportStatus === 'REPORTED' ? '重报成功' : `平台返回：${res.reportStatus}`)
    await load()
  } catch (e) {
    showErr(e)
  } finally {
    loading.value = false
  }
}

async function onExport(row) {
  try {
    const blob = await reportApi.exportPdf(row.id)
    downloadBlob(blob, `维保记录-${row.originalRecordId || row.id}.pdf`)
  } catch (e) {
    showErr(e)
  }
}

onMounted(() => load())
</script>

<style scoped>
.filter-bar { display: flex; gap: 10px; margin-bottom: 12px; }
.tip { margin-bottom: 12px; }
.pager { margin-top: 12px; justify-content: flex-end; }
.hz { margin-right: 4px; }
.photos { display: flex; gap: 8px; margin-top: 12px; flex-wrap: wrap; }
.photo { width: 120px; height: 90px; border-radius: 4px; }
.sec { margin: 14px 0 8px; }
.signs { display: flex; gap: 12px; margin-top: 8px; }
.sign { width: 150px; height: 70px; border: 1px dashed #dcdfe6; border-radius: 4px; }
.muted { color: #86909c; margin-top: 12px; }
</style>
