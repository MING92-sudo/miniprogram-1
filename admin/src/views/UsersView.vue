<template>
  <el-card shadow="never">
    <div class="head">
      <span>用户与权限（系统管理员）</span>
      <el-alert type="info" :closable="false" class="inline-tip"
                title="账号启停/重置密码为 SYS_ADMIN 专属；档案编辑仍走人员档案页（ADMIN）" />
    </div>

    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column prop="name" label="姓名" width="100" />
      <el-table-column prop="phone" label="手机号" width="130" />
      <el-table-column prop="account" label="账号" width="130" />
      <el-table-column prop="roleText" label="角色" width="150" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.enabled ? 'success' : 'danger'">
            {{ row.enabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button link :type="row.enabled ? 'danger' : 'success'" @click="toggle(row)">
            {{ row.enabled ? '停用' : '启用' }}
          </el-button>
          <el-button link type="primary" @click="openReset(row)">重置密码</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-alert type="info" :closable="false" class="mt12"
              title="角色权限矩阵：WORKER/LEADER=小程序作业+只读；ADMIN=全部业务写操作；SYS_ADMIN=账号启停/重置密码/审计日志。账号生成：平台 2.4 登记后经人员档案建档，随机初始密码一次性发放，人员用手机号登录并自助修改。" />

    <el-dialog v-model="resetDialog" title="重置密码" width="420px">
      <el-form label-width="100px">
        <el-form-item label="账号">{{ resetForm.name }}（{{ resetForm.account }}）</el-form-item>
        <el-form-item label="重置方式">
          <span>点击"确认重置"生成 12 位随机新密码（一次性展示）</span>
        </el-form-item>
      </el-form>
      <el-form v-if="newPassword" label-width="100px">
        <el-form-item label="新密码">
          <el-input :model-value="newPassword" readonly />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeReset">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doReset">
          {{ newPassword ? '完成' : '确认重置' }}
        </el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import * as scheduleApi from '../api/schedule'
import * as systemApi from '../api/system'
import { showErr, ok } from '../utils/ui'

const rows = ref([])
const loading = ref(false)
const resetDialog = ref(false)
const newPassword = ref('')
const saving = ref(false)
const resetForm = reactive({ id: '', name: '', account: '' })

async function load() {
  loading.value = true
  try {
    rows.value = await scheduleApi.employees()
  } catch (e) {
    showErr(e)
  } finally {
    loading.value = false
  }
}

async function toggle(row) {
  try {
    await systemApi.setEmployeeEnabled(row.id, !row.enabled)
    ok(row.enabled ? '已停用' : '已启用')
    await load()
  } catch (e) {
    showErr(e)
  }
}

function closeReset() {
  resetDialog.value = false
  newPassword.value = ''
}

function openReset(row) {
  Object.assign(resetForm, { id: row.id, name: row.name, account: row.account })
  resetDialog.value = true
}

async function doReset() {
  if (newPassword.value) {
    closeReset()
    return
  }
  saving.value = true
  try {
    const res = await systemApi.resetEmployeePasswordRandom(resetForm.id)
    newPassword.value = (res && res.initialPassword) || ''
    ok('已生成随机新密码，请复制转发给本人')
  } catch (e) {
    showErr(e)
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.inline-tip { width: auto; }
.mt12 { margin-top: 12px; }
</style>