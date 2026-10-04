<template>
  <el-card shadow="never">
    <div class="head">
      <el-select v-model="status" placeholder="状态" clearable style="width: 140px">
        <el-option label="未闭环" value="OPEN" />
        <el-option label="已闭环" value="CLOSED" />
      </el-select>
      <el-button type="primary" @click="load(1)">查询</el-button>
    </div>

    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column prop="faultNo" label="单号" width="190" show-overflow-tooltip />
      <el-table-column prop="elevatorCode" label="电梯编码" width="140" />
      <el-table-column prop="faultType" label="故障类型" width="110" />
      <el-table-column prop="desc" label="故障描述" min-width="200" show-overflow-tooltip />
      <el-table-column prop="createdByName" label="登记人" width="100" />
      <el-table-column prop="createdAt" label="登记时间" width="170" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'CLOSED' ? 'success' : 'danger'">
            {{ row.status === 'CLOSED' ? '已闭环' : '未闭环' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="80" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row.id)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination class="pager" layout="total, prev, pager, next" :total="total"
                   :page-size="query.size" :current-page="query.page" @current-change="load" />

    <el-dialog v-model="dialog" title="急修单详情" width="640px">
      <el-descriptions v-if="detail" :column="2" border>
        <el-descriptions-item label="单号">{{ detail.faultNo || detail.id }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag size="small" :type="detail.status === 'CLOSED' ? 'success' : 'danger'">
            {{ detail.status === 'CLOSED' ? '已闭环' : '未闭环' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="电梯编码">{{ detail.elevatorCode }}</el-descriptions-item>
        <el-descriptions-item label="故障类型">{{ detail.faultType }}</el-descriptions-item>
        <el-descriptions-item label="登记人">{{ detail.createdByName || '—' }}</el-descriptions-item>
        <el-descriptions-item label="报修时间">{{ detail.createdAt }}</el-descriptions-item>
        <el-descriptions-item label="到场时间（以签到为准）">{{ detail.arrivedAt || "—" }}</el-descriptions-item>
        <el-descriptions-item label="维修结束时间">{{ detail.finishedAt || "—" }}</el-descriptions-item>
        <el-descriptions-item label="故障描述" :span="2">{{ detail.desc }}</el-descriptions-item>
        <el-descriptions-item label="现场情况描述" :span="2">{{ detail.siteDesc || "—" }}</el-descriptions-item>
        <el-descriptions-item label="处理结果" :span="2">{{ detail.result || '—' }}</el-descriptions-item>
        <el-descriptions-item label="闭环时间" :span="2">{{ detail.confirmedAt || '—' }}</el-descriptions-item>
        <el-descriptions-item label="现场照片" :span="2">
          <view style="display:flex;gap:8px;flex-wrap:wrap">
            <el-image v-for="(p, i) in detail.photos" :key="i" :src="p"
                      :preview-src-list="detail.photos" fit="cover"
                      style="width:96px;height:96px;border-radius:6px" />
            <span v-if="!detail.photos.length" style="color:#86909c">无</span>
          </view>
        </el-descriptions-item>
        <el-descriptions-item label="单位签字" :span="2">
          <el-image v-if="detail.signature" :src="detail.signature" fit="contain"
                    :preview-src-list="[detail.signature]" style="height:96px" />
          <span v-else style="color:#86909c">待使用单位签字</span>
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import * as faultApi from '../api/fault'
import { showErr } from '../utils/ui'

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const status = ref('')
const dialog = ref(false)
const detail = ref(null)
const query = reactive({ page: 1, size: 20 })

async function load(page) {
  loading.value = true
  if (page) query.page = page
  try {
    const res = await faultApi.list({ ...query, status: status.value })
    rows.value = res.list || []
    total.value = res.total || 0
  } catch (e) {
    showErr(e)
  } finally {
    loading.value = false
  }
}

async function openDetail(id) {
  try {
    detail.value = await faultApi.get(id)
    dialog.value = true
  } catch (e) {
    showErr(e)
  }
}

onMounted(() => load())
</script>

<style scoped>
.head { display: flex; gap: 8px; margin-bottom: 12px; }
.pager { margin-top: 12px; justify-content: flex-end; }
</style>
