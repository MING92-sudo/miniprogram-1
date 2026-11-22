<template>
  <div>
    <ViewHead title="上报日志" sub="平台上报转发日志（reg_upload_log）· 报文摘要脱敏只读">
      <template #meta><PageMeta perm="report:view" :extra="[{ k: '日志数', v: rows.length, icon: 'file-text', cls: 'count' }]" /></template>
    </ViewHead>
    <div class="notice-bar mt16">
      <svg class="ic" aria-hidden="true"><use href="#i-lock" /></svg>
      <span><b>上报日志只读。</b>报文摘要按脱敏口径落库；逐条人工重报见「上报异常清单」。</span>
    </div>
    <div class="mt12">
      <DataTable :columns="cols" :rows="rows" :loading="loading" :search-keys="['originalRecordId', 'platformMessage']"
        search-placeholder="搜索平台记录ID / 返回信息" empty-hint="暂无上报日志。"
        :filters="[{ key: 'status', label: '状态', options: ['SUCCESS', 'FAILED'] }]">
        <template #cell-createdAt="{ value }"><span class="mono f12">{{ value }}</span></template>
        <template #cell-originalRecordId="{ value }"><span class="mono f12">{{ value || '—' }}</span></template>
        <template #cell-status="{ value }"><StatusTag :status="value" /></template>
        <template #cell-platformCode="{ value }"><span :class="['mono', String(value) === '200' ? 'c-ok' : 'c-sig']">{{ value ?? '—' }}</span></template>
      </DataTable>
    </div>
    <RuleNote class="mt12" icon="info">LEGACY 存量上报受 REG_LEGACY_UPLOAD_ENABLED 控制（默认关闭）；平台码兼容数字/字符串 200。</RuleNote>
  </div>
</template>
<script setup>
// 上报日志：GET /reg/upload-logs
import { ref, onMounted } from 'vue'
import DataTable from '../ui/DataTable.vue'
import ViewHead from '../ui/ViewHead.vue'
import PageMeta from '../ui/PageMeta.vue'
import RuleNote from '../ui/RuleNote.vue'
import StatusTag from '../ui/StatusTag.vue'
import { uploadLogs } from '../api/platform'
const rows = ref([])
const loading = ref(false)
const cols = [
  { key: 'createdAt', label: '时间', sortable: true },
  { key: 'originalRecordId', label: '平台记录ID' },
  { key: 'action', label: '接口', format: (v) => ({ UPLOAD: '上报', REUPLOAD: '重报', LEGACY: '存量' }[String(v || '').toUpperCase()] || v || '—') },
  { key: 'status', label: '状态', sortable: true },
  { key: 'platformCode', label: '平台码', cls: 'num' },
  { key: 'platformMessage', label: '平台返回' }
]
onMounted(async () => {
  loading.value = true
  try { const res = await uploadLogs({ size: 200 }); rows.value = res.list || [] }
  finally { loading.value = false }
})
</script>