<template>
  <el-card shadow="never">
    <div class="head">
      <span>使用单位档案（{{ rows.length }}）</span>
      <el-button type="primary" size="small" :disabled="!auth.canWrite" @click="openCreate">新建使用单位</el-button>
    </div>

    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column prop="unitName" label="使用单位" min-width="180" show-overflow-tooltip />
      <el-table-column prop="unitPrincipal" label="负责人" width="100" />
      <el-table-column prop="unitPrincipalPhone" label="负责人手机" width="130" />
      <el-table-column prop="elevatorAdminister" label="安全管理员" width="110" />
      <el-table-column prop="elevatorAdministerPhone" label="安全员手机" width="130" />
      <el-table-column prop="emergencyPhone" label="应急电话" width="130" />
      <el-table-column prop="entityId" label="平台主体ID">
        <template #default="{ row }">{{ row.entityId || '—' }}</template>
      </el-table-column>
      <el-table-column prop="elevatorCount" label="电梯数" width="80" />
      <el-table-column label="操作" width="80" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="!auth.canWrite" @click="openEdit(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog" :title="form.id ? '编辑使用单位' : '新建使用单位'" width="520px">
      <el-form :model="form" label-width="130px">
        <el-form-item label="单位名称" required><el-input v-model="form.unitName" /></el-form-item>
        <el-form-item label="负责人"><el-input v-model="form.unitPrincipal" /></el-form-item>
        <el-form-item label="负责人手机">
          <el-input v-model="form.unitPrincipalPhone" />
        </el-form-item>
        <el-form-item label="安全管理员"><el-input v-model="form.elevatorAdminister" /></el-form-item>
        <el-form-item label="安全员手机"><el-input v-model="form.elevatorAdministerPhone" /></el-form-item>
        <el-form-item label="应急电话"><el-input v-model="form.emergencyPhone" /></el-form-item>
        <div class="tip">
          负责人 / 安全管理员手机参与五类互斥（docs/01 §3.2.4）；冲突时返回 1002 与具体角色对；应急电话不参与。
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { useAuthStore } from '../stores/auth'
import * as archiveApi from '../api/archive'
import { showErr, ok } from '../utils/ui'

const auth = useAuthStore()
const rows = ref([])
const loading = ref(false)
const dialog = ref(false)
const saving = ref(false)
const form = reactive({ id: '', unitName: '', unitPrincipal: '', unitPrincipalPhone: '',
  elevatorAdminister: '', elevatorAdministerPhone: '', emergencyPhone: '' })

async function load() {
  loading.value = true
  try {
    rows.value = await archiveApi.useUnits()
  } catch (e) {
    showErr(e)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(form, { id: '', unitName: '', unitPrincipal: '', unitPrincipalPhone: '',
    elevatorAdminister: '', elevatorAdministerPhone: '', emergencyPhone: '' })
  dialog.value = true
}

function openEdit(row) {
  Object.assign(form, row)
  dialog.value = true
}

async function save() {
  saving.value = true
  try {
    const body = { ...form }
    delete body.id
    delete body.elevatorCount
    if (form.id) {
      Object.assign(form, await archiveApi.updateUseUnit(form.id, body))
    } else {
      Object.assign(form, await archiveApi.createUseUnit(body))
    }
    dialog.value = false
    ok('已保存')
    await load()
  } catch (e) {
    showErr(e)
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.tip { color: #86909c; font-size: 12px; line-height: 1.5; }
</style>
