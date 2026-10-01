<template>
  <el-card shadow="never" v-loading="loading">
    <template #header>
      <div class="head">
        <span>维保单位档案</span>
        <el-button v-if="editing" size="small" @click="cancel">取消</el-button>
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
        <div class="tip">参与五类手机号互斥（docs/01 §3.2.4），保存时服务端预校验（1002）</div>
      </el-form-item>
      <el-form-item label="平台主体ID（entityId）"><el-input v-model="form.entityId" disabled /></el-form-item>
    </el-form>
    <div class="note">entityId 由平台 2.2 查询回填（平台同步页触发），此处只读</div>
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
const form = reactive({ name: '', organizationCode: '', workMenegerName: '', workMenegerPhone: '', entityId: '' })

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
