<template>
  <div>
    <ViewHead title="平台登记人员" sub="监管平台人员登记（接口 2.3/2.4）· 数据来自智慧平台实时查询（2.5）">
      <template #meta><PageMeta perm="platform:worker_view" :extra="[{ k: '人员数', v: rows.length, icon: 'user-check', cls: 'count' }]" /></template>
    </ViewHead>
    <div v-if="error" class="alert-strip as-sig mt16">
      <svg class="ic" aria-hidden="true"><use href="#i-alert" /></svg>
      <b>查询失败</b><span>{{ error }}</span>
      <span class="as-link" @click="load">重试<svg class="ic ic-sm" aria-hidden="true"><use href="#i-refresh" /></svg></span>
    </div>
    <div class="mt16">
      <DataTable :columns="cols" :rows="rows" :loading="loading" :search-keys="['name', 'certificateNo']"
        search-placeholder="搜索姓名 / 证书编号" empty-hint="暂无平台登记人员。">
        <template #cell-certificateNo="{ value }"><span class="mono f12">{{ value || '—' }}</span></template>
        <template #cell-phone="{ value }"><span class="mono f12">{{ value || '—' }}</span></template>
        <template #cell-bindStatus="{ value }">
          <span :class="['tag', String(value).toUpperCase() === 'BOUND' ? 'tag-ok' : 'tag-dim']">{{ { BOUND: '已绑定', UNBOUND: '未绑定' }[String(value || '').toUpperCase()] || value || '—' }}</span>
        </template>
      </DataTable>
    </div>
    <RuleNote class="mt12" icon="lock">登记写入（2.3/2.4 multipart：contractFile/certificateFile）按 platform:worker_register；openid 只信服务端换取；平台凭证仅存环境变量。</RuleNote>
  </div>
</template>
<script setup>
// 平台登记人员：GET /platform/workers（平台 2.5 实时查询，platform:worker_view）
import { ref, onMounted } from 'vue'
import http from '../api/client'
import DataTable from '../ui/DataTable.vue'
import ViewHead from '../ui/ViewHead.vue'
import PageMeta from '../ui/PageMeta.vue'
import RuleNote from '../ui/RuleNote.vue'
const rows = ref([])
const loading = ref(false)
const error = ref('')
const cols = [
  { key: 'name', label: '姓名', sortable: true },
  { key: 'certificateNo', label: '证书编号' },
  { key: 'phone', label: '手机号' },
  { key: 'bindStatus', label: '绑定状态' }
]
async function load() {
  loading.value = true; error.value = ''
  try { rows.value = await http.get('/platform/workers') }
  catch (e) { error.value = '平台人员查询失败：' + (e.message || '监管平台暂不可达') + '（需平台凭证有效且服务器 IP 已加白名单）' }
  finally { loading.value = false }
}
onMounted(load)
</script>