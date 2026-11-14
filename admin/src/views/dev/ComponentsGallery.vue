<template>
  <div class="ui-scope gallery">
    <h1>组件廊（开发态）</h1>
    <section><h2 class="label">状态标签</h2><StatusTag status="ARCHIVED" label="已归档" /><StatusTag status="PROCESSING" /><StatusTag status="TRAPPED" label="困人" /></section>
    <section><h2 class="label">权限按钮</h2><PermButton code="handover:review" role="INST_MGR" label="审核（可操作）" /><PermButton code="handover:review" role="INST_CLERK" label="审核（禁止）" /><PermButton code="handover:view" role="INST_CLERK" label="查看（需审批）" /></section>
    <section><h2 class="label">数据表</h2><DataTable :columns="cols" :rows="rows" selectable :search-keys="['name']" :bulk-actions="[{ code: 'user:disable', label: '停用' }]" /></section>
    <section><h2 class="label">四态</h2><EmptyState :chips="['状态: 启用']" clearable /><ErrorState reason="网络超时" /><ForbiddenState code="contract:view" :allowed-roles="['MAINT_MGR']" current-role="INST_MGR" /></section>
  </div>
</template>
<script setup>
// M2 开发态组件廊（D-A3，走查基准）
import StatusTag from '../../ui/StatusTag.vue'
import PermButton from '../../ui/PermButton.vue'
import DataTable from '../../ui/DataTable.vue'
import EmptyState from '../../ui/states/EmptyState.vue'
import ErrorState from '../../ui/states/ErrorState.vue'
import ForbiddenState from '../../ui/states/ForbiddenState.vue'
const cols = [{ key: 'name', label: '名称', sortable: true }, { key: 'status', label: '状态' }]
const rows = [{ id: 1, name: 'alpha', status: 'ACTIVE' }, { id: 2, name: 'beta', status: 'DISABLED' }]
</script>
<style scoped>
.gallery { padding: 24px; }
.gallery section { margin: 24px 0; display: flex; gap: 12px; flex-wrap: wrap; align-items: center; }
.gallery h1 { font-size: 20px; }
</style>