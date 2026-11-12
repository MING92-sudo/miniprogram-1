<template>
  <el-card shadow="never">
    <div class="head">
      <el-select v-model="status" placeholder="全部状态" clearable style="width: 160px" @change="load(1)">
        <el-option v-for="s in STATUSES" :key="s.value" :label="s.label" :value="s.value" />
      </el-select>
      <el-button type="primary" @click="load(1)">查询</el-button>
    </div>

    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column prop="filingNo" label="单号" width="190" show-overflow-tooltip />
      <el-table-column prop="projectName" label="项目名称" min-width="150" show-overflow-tooltip />
      <el-table-column prop="elevatorCode" label="电梯编码" width="130" />
      <el-table-column prop="elevatorName" label="电梯名称" min-width="140" show-overflow-tooltip />
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag size="small" :type="tagType(row.status)">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="initiatorName" label="发起人" width="100" />
      <el-table-column label="发起时间" width="170">
        <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row.id)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination class="pager" layout="total, prev, pager, next" :total="total"
                   :page-size="query.size" :current-page="query.page" @current-change="load" />

    <el-drawer v-model="drawer" title="外接建档单详情" size="640px">
      <template v-if="detail">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="单号">{{ detail.filingNo || detail.id }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="tagType(detail.status)">{{ statusText(detail.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="使用单位">{{ detail.useUnitId || '—' }}</el-descriptions-item>
          <el-descriptions-item label="项目名称">{{ detail.projectName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="电梯">{{ detail.elevatorName || '—' }}（{{ detail.elevatorCode || '—' }}）</el-descriptions-item>
          <el-descriptions-item label="发起人">{{ detail.initiatorName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="关联合同">{{ detail.contractId || '—' }}</el-descriptions-item>
          <el-descriptions-item label="确认人">{{ detail.confirmedByName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="发起时间">{{ fmt(detail.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="确认时间">{{ fmt(detail.confirmedAt) }}</el-descriptions-item>
        </el-descriptions>

        <div class="section">建档资料（缺件标红）</div>
        <el-empty v-if="!detail.docs || !detail.docs.length" description="尚未提交资料" :image-size="60" />
        <el-table v-else :data="detail.docs" size="small" border>
          <el-table-column label="资料类别" width="160">
            <template #default="{ row }">{{ docTypeText(row.docType) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.missingFlag ? 'danger' : 'success'">
                {{ row.missingFlag ? '缺件待补办' : '已上传' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="remark" label="备注" min-width="140" />
        </el-table>

        <div class="section">接管查验（不合格项须写责任归属）</div>
        <el-empty v-if="!detail.takeoverChecks || !detail.takeoverChecks.length" description="尚未查验" :image-size="60" />
        <template v-else>
          <div v-for="(check, ci) in detail.takeoverChecks" :key="check.id" class="check-block">
            <div class="check-head">
              第 {{ detail.takeoverChecks.length - ci }} 次查验 · {{ check.checkerName || '—' }} · {{ fmt(check.checkedAt) }}
            </div>
            <el-table :data="check.items || []" size="small" border>
              <el-table-column label="查验项" width="130">
                <template #default="{ row }">{{ itemText(row.itemType) }}</template>
              </el-table-column>
              <el-table-column label="结果" width="80">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.result === 'PASS' ? 'success' : row.result === 'FAIL' ? 'danger' : 'info'">
                    {{ { PASS: '合格', FAIL: '不合格', NA: '不适用' }[row.result] || row.result }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="责任归属" width="130">
                <template #default="{ row }">{{ responsibilityText(row.responsibility) }}</template>
              </el-table-column>
              <el-table-column prop="remark" label="备注" min-width="120" />
            </el-table>
          </div>
        </template>

        <div class="section">交接确认</div>
        <el-empty v-if="!detail.confirmation" description="尚未交接确认" :image-size="60" />
        <el-descriptions v-else :column="1" border>
          <el-descriptions-item label="钥匙工具核对">{{ detail.confirmation.keyToolCheck || '—' }}</el-descriptions-item>
          <el-descriptions-item label="遗留问题">{{ detail.confirmation.leftoverIssue || '—' }}</el-descriptions-item>
          <el-descriptions-item label="对方签字人">{{ detail.confirmation.counterpartSignName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="确认时间">{{ fmt(detail.confirmation.at) }}</el-descriptions-item>
        </el-descriptions>
      </template>
    </el-drawer>
  </el-card>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import * as filingApi from '../api/filing'
import { showErr } from '../utils/ui'

const STATUSES = [
  { value: 'DOC_COLLECTING', label: '资料收集中' },
  { value: 'TAKEOVER_CHECKING', label: '接管查验中' },
  { value: 'PENDING_CONFIRM', label: '待确认' },
  { value: 'ARCHIVED', label: '已归档' }
]
const STATUS_TEXT = Object.fromEntries(STATUSES.map((s) => [s.value, s.label]))
const TAG_TYPE = {
  DOC_COLLECTING: 'info', TAKEOVER_CHECKING: 'primary',
  PENDING_CONFIRM: 'warning', ARCHIVED: 'success'
}
const DOC_TYPE_TEXT = {
  USE_REG_CERT: '使用登记证', INSPECTION_REPORT: '检验报告', MAINTENANCE_RECORD: '前维保记录',
  MAINTENANCE_MANUAL: '维保说明书', ELEVATOR_DRAWING: '电梯图纸', OTHER: '其他'
}
const ITEM_TEXT = {
  RUNNING: '运行', SAFETY_DEVICE: '安全装置', BRAKE: '制动器',
  EMERGENCY_OPERATION: '紧急操作', MACHINE_ROOM_SHAFT: '机房井道', ACCESSORIES: '随附物品'
}
const RESP_TEXT = { PREV_MAINT: '前维保单位', USE_UNIT: '使用单位', OURS_AFTER_TAKEOVER: '我方接管后' }

const statusText = (s) => STATUS_TEXT[s] || s || '—'
const tagType = (s) => TAG_TYPE[s] || 'info'
const docTypeText = (t) => DOC_TYPE_TEXT[t] || t
const itemText = (t) => ITEM_TEXT[t] || t
const responsibilityText = (r) => (r ? RESP_TEXT[r] || r : '—')
const fmt = (v) => (v ? String(v).replace('T', ' ').slice(0, 19) : '—')

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const status = ref('')
const query = reactive({ page: 1, size: 20 })
const drawer = ref(false)
const detail = ref(null)

async function load(page) {
  if (page) query.page = page
  loading.value = true
  try {
    const data = await filingApi.listFilings({ status: status.value || '', page: query.page, size: query.size })
    rows.value = data.list || []
    total.value = data.total || 0
  } catch (e) {
    showErr(e)
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function openDetail(id) {
  drawer.value = true
  detail.value = null
  try {
    detail.value = await filingApi.filingDetail(id)
  } catch (e) {
    showErr(e)
  }
}

onMounted(() => load(1))
</script>

<style scoped>
.head { display: flex; gap: 10px; margin-bottom: 12px; }
.pager { margin-top: 12px; }
.section { margin: 16px 0 8px; font-weight: 600; }
.check-block { margin-bottom: 10px; }
.check-head { font-size: 12px; color: #86909c; margin-bottom: 4px; }
</style>
