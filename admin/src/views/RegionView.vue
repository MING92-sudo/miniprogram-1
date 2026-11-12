<template>
  <el-card shadow="never">
    <div class="head">
      <span>区域主数据（{{ rows.length }}）</span>
      <el-button type="primary" size="small" :disabled="!auth.can('region:write')" @click="openCreate">
        新建区域
      </el-button>
    </div>

    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column prop="name" label="区域名称" min-width="160" />
      <el-table-column prop="sortOrder" label="排序" width="90" />
      <el-table-column prop="remark" label="备注" min-width="200" show-overflow-tooltip />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="!auth.can('region:write')" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" :disabled="!auth.can('region:write')" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="tip">
      区域决定维保人员的可见范围（docs/14 §5）：员工只看到本区域电梯；未分区（region 为空）保持现状可见性。
    </div>

    <el-dialog v-model="dialog" :title="form.id ? '编辑区域' : '新建区域'" width="420px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="名称" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="排序"><el-input v-model="form.sortOrder" placeholder="数字，越小越前" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import * as regionApi from '../api/region'
import { showErr, ok } from '../utils/ui'

const auth = useAuthStore()
const rows = ref([])
const loading = ref(false)
const dialog = ref(false)
const saving = ref(false)
const form = reactive({ id: '', name: '', sortOrder: '', remark: '' })

async function load() {
  loading.value = true
  try {
    rows.value = await regionApi.listRegions()
  } catch (e) {
    showErr(e)
    rows.value = []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(form, { id: '', name: '', sortOrder: '', remark: '' })
  dialog.value = true
}

function openEdit(row) {
  Object.assign(form, {
    id: row.id,
    name: row.name || '',
    sortOrder: row.sortOrder == null ? '' : String(row.sortOrder),
    remark: row.remark || ''
  })
  dialog.value = true
}

async function save() {
  if (!form.name.trim()) {
    return showErr(new Error('区域名称必填'))
  }
  saving.value = true
  try {
    const body = {
      name: form.name.trim(),
      sortOrder: form.sortOrder === '' ? null : Number(form.sortOrder),
      remark: form.remark || ''
    }
    if (form.id) {
      await regionApi.updateRegion(form.id, body)
      ok('区域已更新')
    } else {
      await regionApi.createRegion(body)
      ok('区域已创建')
    }
    dialog.value = false
    await load()
  } catch (e) {
    showErr(e)
  } finally {
    saving.value = false
  }
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(`删除区域「${row.name}」？已分配该区域的电梯/员工会失去区域归属。`, '删除区域', { type: 'warning' })
  } catch (e) {
    return
  }
  try {
    await regionApi.removeRegion(row.id)
    ok('已删除')
    await load()
  } catch (e) {
    showErr(e)
  }
}

onMounted(load)
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.tip { margin-top: 12px; color: #86909c; font-size: 12px; line-height: 1.6; }
</style>
