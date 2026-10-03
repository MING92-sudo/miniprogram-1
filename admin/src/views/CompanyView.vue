<template>
  <el-card shadow="never" v-loading="loading">
    <template #header>
      <div class="head">
        <span>维保单位档案</span>
        <el-button v-if="editing" size="small" @click="cancel">取消</el-button>
      <el-button size="small" :loading="syncing" :disabled="!auth.canWrite || editing" @click="onSyncEntity">同步主体ID</el-button>
        <el-button v-if="!editing" size="small" type="primary" :disabled="!auth.canWrite" @click="editing = true">编辑</el-button>
        <el-button v-else size="small" type="primary" :loading="saving" @click="save">保存</el-button>
      </div>
    </template>

    <el-form :model="form" label-width="150px" :disabled="!editing" style="max-width: 640px">
      <el-form-item label="单位名称"><el-input v-model="form.name" /></el-form-item>
      <el-form-item label="统一社会信用代码"><el-input v-model="form.organizationCode" /></el-form-item>
      <el-form-item label="维保经理"><el-input v-model="form.workMenegerName" /></el-form-item>
      <el-form-item label="维保经理手机号">
        <el-input v-model="form.workMenegerPhone" />
      </el-form-item>
      <el-form-item label="平台主体ID"><el-input v-model="form.entityId" disabled /></el-form-item>
    </el-form>
  </el-card>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { useAuthStore } from '../stores/auth'
import * as archiveApi from '../api/archive'
import { showErr, ok } from '../utils/ui'

const auth = useAuthStore()
const loading = ref(false)
const editing = ref(false)
const saving = ref(false)
const syncing = ref(false)
const form = reactive({ name: '', organizationCode: '', workMenegerName: '', workMenegerPhone: '', entityId: '' })

/** 2.2 单主体同步（docs/09 V3.1 ④）：按单位名称拉取 entityID 落库 */
async function onSyncEntity() {
  syncing.value = true
  try {
    const res = await archiveApi.syncCompanyEntityId()
    if (res.found) {
      form.entityId = res.entityId
      ok('主体ID 已同步：' + res.entityId)
    } else {
      ok('未同步：' + (res.reason || '平台未查询到该主体'))
    }
  } catch (e) {
    showErr(e)
  } finally {
    syncing.value = false
  }
}

async function load() {
  loading.value = true
  try {
    Object.assign(form, await archiveApi.company())
  } catch (e) {
    showErr(e)
  } finally {
    loading.value = false
  }
}

function cancel() {
  editing.value = false
  load()
}

async function save() {
  saving.value = true
  try {
    Object.assign(form, await archiveApi.updateCompany({
      name: form.name, organizationCode: form.organizationCode,
      workMenegerName: form.workMenegerName, workMenegerPhone: form.workMenegerPhone
    }))
    editing.value = false
    ok('已保存')
  } catch (e) {
    showErr(e)
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; }
.tip { color: #86909c; font-size: 12px; line-height: 1.4; }
.note { color: #86909c; font-size: 12px; margin-top: 8px; }
</style>
