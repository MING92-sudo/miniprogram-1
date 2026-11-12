<template>
  <el-card shadow="never">
    <div class="head">
      <el-select v-model="status" placeholder="全部状态" clearable style="width: 160px" @change="load(1)">
        <el-option v-for="s in STATUSES" :key="s.value" :label="s.label" :value="s.value" />
      </el-select>
      <el-button type="primary" @click="load(1)">查询</el-button>
    </div>

    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column prop="handoverNo" label="单号" width="190" show-overflow-tooltip />
      <el-table-column prop="projectName" label="项目名称" min-width="150" show-overflow-tooltip />
      <el-table-column prop="elevatorCode" label="电梯编码" width="130" />
      <el-table-column prop="elevatorName" label="电梯名称" min-width="140" show-overflow-tooltip />
      <el-table-column label="状态" width="110">
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

    <el-drawer v-model="drawer" title="移交单详情" size="640px">
      <template v-if="detail">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="单号">{{ detail.handoverNo || detail.id }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="tagType(detail.status)">{{ statusText(detail.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="项目名称">{{ detail.projectName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="电梯">{{ detail.elevatorName || '—' }}（{{ detail.elevatorCode || '—' }}）</el-descriptions-item>
          <el-descriptions-item label="发起人">{{ detail.initiatorName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="发起时间">{{ fmt(detail.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="接收工程师">{{ detail.receiverName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="监督检验合格日">{{ detail.supervisionPassDate || '—' }}</el-descriptions-item>
          <el-descriptions-item label="确认人">{{ detail.confirmedByName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="确认时间">{{ fmt(detail.confirmedAt) }}</el-descriptions-item>
        </el-descriptions>

        <div class="section">3 必传资料</div>
        <el-descriptions :column="1" border>
          <el-descriptions-item label="监督检验报告">{{ docText(detail.supervisionReportFileId) }}</el-descriptions-item>
          <el-descriptions-item label="维保说明书">{{ docText(detail.maintenanceManualFileId) }}</el-descriptions-item>
          <el-descriptions-item label="整机质量证明">{{ docText(detail.qualityCertificateFileId) }}</el-descriptions-item>
        </el-descriptions>

        <div class="section">现场查验</div>
        <el-empty v-if="!detail.checks || !detail.checks.length" description="尚未查验" :image-size="60" />
        <template v-else>
          <div v-for="(check, ci) in detail.checks" :key="check.id" class="check-block">
            <div class="check-head">
              第 {{ detail.checks.length - ci }} 次查验 · {{ check.checkerName || '—' }} · {{ fmt(check.checkedAt) }}
            </div>
            <el-table :data="check.items || []" size="small" border>
              <el-table-column label="查验项" width="140">
                <template #default="{ row }">{{ itemText(row.itemType) }}</template>
              </el-table-column>
              <el-table-column label="结果" width="80">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.result === 'PASS' ? 'success' : row.result === 'FAIL' ? 'danger' : 'info'">
                    {{ { PASS: '合格', FAIL: '不合格', NA: '不适用' }[row.result] || row.result }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="remark" label="备注" min-width="140" />
            </el-table>
          </div>
        </template>

        <div class="section">整改记录</div>
        <el-empty v-if="!detail.rectifications || !detail.rectifications.length" description="无整改" :image-size="60" />
        <el-table v-else :data="detail.rectifications" size="small" border>
          <el-table-column label="整改项" width="140">
            <template #default="{ row }">{{ itemText(row.itemType) }}</template>
          </el-table-column>
          <el-table-column label="整改前" width="90">
            <template #default="{ row }">{{ row.beforePhotoFileId ? '已上传' : '—' }}</template>
          </el-table-column>
          <el-table-column label="整改后" width="90">
            <template #default="{ row }">{{ row.afterPhotoFileId ? '已上传' : '—' }}</template>
          </el-table-column>
          <el-table-column label="复验" width="90">
            <template #default="{ row }">{{ row.recheckResult || '待复验' }}</template>
          </el-table-column>
          <el-table-column prop="recheckerName" label="复验人" width="100" />
        </el-table>

        <div v-if="detail.status === 'PENDING_CONFIRM'" class="actions">
          <el-button type="primary" :disabled="!auth.can('handover:write')" :loading="saving" @click="onConfirm">
            确认归档（经理）
          </el-button>
          <span class="tip">确认=归档：归档后自动建电梯与首保工单；签字仍由各节点角色在小程序完成。</span>
        </div>
      </template>
    </el-drawer>
  </el-card>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import * as handoverApi from '../api/handover'
import { showErr, ok } from '../utils/ui'

const auth = useAuthStore()
const STATUSES = [
  { value: 'DOC_SUBMITTING', label: '资料提交中' },
  { value: 'INSPECTING', label: '现场查验中' },
  { value: 'RECTIFYING', label: '整改中' },
  { value: 'PENDING_CONFIRM', label: '待确认' },
  { value: 'ARCHIVED', label: '已归档' }
]
const STATUS_TEXT = Object.fromEntries(STATUSES.map((s) => [s.value, s.label]))
const TAG_TYPE = {
  DOC_SUBMITTING: 'info', INSPECTING: 'primary', RECTIFYING: 'warning',
  PENDING_CONFIRM: 'warning', ARCHIVED: 'success'
}

const ITEM_TEXT = {
  RUNNING: '运行', SAFETY_DEVICE: '安全装置', BRAKE: '制动器',
  EMERGENCY_OPERATION: '紧急操作', MACHINE_ROOM_SHAFT: '机房井道', ACCESSORIES: '随附物品'
}
const statusText = (s) => STATUS_TEXT[s] || s || '—'
const tagType = (s) => TAG_TYPE[s] || 'info'
const itemText = (t) => ITEM_TEXT[t] || t
const docText = (id) => (id ? '已上传' : '未上传')
const fmt = (v) => (v ? String(v).replace('T', ' ').slice(0, 19) : '—')

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const status = ref('')
const query = reactive({ page: 1, size: 20 })
const drawer = ref(false)
const detail = ref(null)
const saving = ref(false)

async function load(page) {
  if (page) query.page = page
  loading.value = true
  try {
    const data = await handoverApi.listHandovers({ status: status.value || '', page: query.page, size: query.size })
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
    detail.value = await handoverApi.handoverDetail(id)
  } catch (e) {
    showErr(e)
  }
}

async function onConfirm() {
  const d = detail.value
  if (!d) return
  try {
    await ElMessageBox.confirm(
      `确认归档移交单 ${d.handoverNo || d.id}？归档后立即建电梯并生成首保工单，不可撤销。`,
      '确认归档', { type: 'warning' }
    )
  } catch (e) {
    return
  }
  saving.value = true
  try {
    await handoverApi.confirmHandover(d.id, { confirmedByName: auth.userName })
    ok('已归档')
    drawer.value = false
    await load()
  } catch (e) {
    showErr(e)
  } finally {
    saving.value = false
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
.actions { margin-top: 16px; display: flex; align-items: center; gap: 10px; }
.tip { color: #86909c; font-size: 12px; }
</style>
