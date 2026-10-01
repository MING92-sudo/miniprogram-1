<template>
  <el-card shadow="never">
    <div class="filter-bar">
      <el-select v-model="query.channel" placeholder="渠道" clearable style="width: 130px">
        <el-option label="订阅消息" value="SUBSCRIBE" />
        <el-option label="站内消息" value="INBOX" />
      </el-select>
      <el-select v-model="query.status" placeholder="状态" clearable style="width: 120px">
        <el-option label="已发送" value="SENT" />
        <el-option label="失败" value="FAILED" />
        <el-option label="待发送" value="PENDING" />
      </el-select>
      <el-input v-model="query.type" placeholder="消息类型" clearable style="width: 160px" />
      <el-button type="primary" @click="load(1)">查询</el-button>
    </div>

    <el-alert type="info" :closable="false" class="tip"
              title="真实微信订阅消息推送需云端 access_token 与模板配额（后置）；当前为本地发送记录（SENT 表示已入队/模拟）。困人救援不走订阅消息（电话+短信+弹窗）" />

    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column prop="createdAt" label="时间" width="170" />
      <el-table-column prop="type" label="类型" width="120" />
      <el-table-column label="渠道" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.channel === 'INBOX' ? 'info' : 'primary'">
            {{ row.channel === 'INBOX' ? '站内' : '订阅消息' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="title" label="标题" width="180" show-overflow-tooltip />
      <el-table-column prop="content" label="内容" min-width="240" show-overflow-tooltip />
      <el-table-column prop="targetRole" label="接收角色" width="110" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'SENT' ? 'success' : row.status === 'FAILED' ? 'danger' : 'warning'">
            {{ row.status === 'SENT' ? '已发送' : row.status === 'FAILED' ? '失败' : '待发送' }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination class="pager" layout="total, prev, pager, next" :total="total"
                   :page-size="query.size" :current-page="query.page" @current-change="load" />
  </el-card>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import * as notifyApi from '../api/notify'
import { showErr } from '../utils/ui'

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const query = reactive({ page: 1, size: 20, channel: '', status: '', type: '' })

async function load(p) {
  if (p) query.page = p
  loading.value = true
  try {
    const data = await notifyApi.notifyRecords({
      page: query.page, size: query.size,
      channel: query.channel || undefined, status: query.status || undefined, type: query.type || undefined
    })
    rows.value = data.list || []
    total.value = data.total || 0
  } catch (e) {
    showErr(e)
  } finally {
    loading.value = false
  }
}

onMounted(() => load())
</script>

<style scoped>
.filter-bar { display: flex; gap: 10px; margin-bottom: 12px; }
.tip { margin-bottom: 12px; }
.pager { margin-top: 12px; justify-content: flex-end; }
</style>
