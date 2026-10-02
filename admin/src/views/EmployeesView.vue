<template>
  <el-card shadow="never">
    <div class="head">
      <span>人员档案（{{ rows.length }}）</span>
      <el-button type="primary" size="small" :disabled="!auth.canWrite" @click="openCreate">新建人员</el-button>
    </div>

    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column prop="name" label="姓名" width="100" />
      <el-table-column prop="phone" label="手机号" width="130" />
      <el-table-column prop="account" label="账号" width="130" />
      <el-table-column prop="roleText" label="角色" width="140" />
      <el-table-column prop="certificate" label="证书编号" width="140">
        <template #default="{ row }">{{ row.certificate || '—' }}</template>
      </el-table-column>
      <el-table-column label="platform_id" min-width="150">
        <template #default="{ row }">{{ row.platformId || '—' }}</template>
      </el-table-column>
      <el-table-column label="同步状态" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="row.syncStatus === 'SYNCED' ? 'success' : 'info'">{{ row.syncStatus }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="workEndDate" label="证件有效期至" width="120" />
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="!auth.canWrite" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" :disabled="!auth.canWrite" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog" :title="form.id ? '编辑人员' : '新建人员'" width="520px">
      <el-form :model="form" label-width="120px">
        <el-form-item label="姓名" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="手机号" required><el-input v-model="form.phone" /></el-form-item>
        <el-form-item label="账号">
          <el-input v-model="form.account" :placeholder="form.id ? '留空不修改' : '默认=手机号'" :disabled="Boolean(form.id)" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.role" style="width: 100%">
            <el-option label="维保人员（WORKER）" value="WORKER" />
            <el-option label="班组长（LEADER）" value="LEADER" />
            <el-option label="维保部管理员（ADMIN）" value="ADMIN" />
            <el-option label="系统管理员（SYS_ADMIN）" value="SYS_ADMIN" />
          </el-select>
        </el-form-item>
        <el-form-item label="证书编号"><el-input v-model="form.certificate" /></el-form-item>
        <el-form-item label="作业开始日期"><el-input v-model="form.workStartDate" placeholder="yyyy-MM-dd" /></el-form-item>
        <el-form-item label="证件有效期至"><el-input v-model="form.workEndDate" placeholder="yyyy-MM-dd" /></el-form-item>
        <div class="tip">维保人员/班组长手机参与五类互斥（docs/01 §3.2.4）；使用单位账号经小程序绑定产生，不在建档范围。</div>
        <div class="tip">新建后系统生成随机初始密码（一次性展示），人员用手机号登录后自行修改。</div>
      </el-form>
      <el-dialog v-model="pwdDialog" title="初始密码（一次性展示，请复制转发给本人）" width="460px" append-to-body>
        <el-input :model-value="initialPassword" readonly />
        <div class="tip">关闭后不再显示；人员首次登录用手机号 + 该密码，随后在"我的 → 修改密码"自助修改。</div>
        <template #footer><el-button type="primary" @click="pwdDialog = false">我已保存</el-button></template>
      </el-dialog>
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
import { ElMessageBox } from 'element-plus'

const auth = useAuthStore()
const rows = ref([])
const loading = ref(false)
const dialog = ref(false)
const saving = ref(false)
const pwdDialog = ref(false)
const initialPassword = ref('')

const empty = { id: '', name: '', phone: '', account: '', role: 'WORKER', certificate: '',
  workStartDate: '', workEndDate: '', password: '', platformId: '', syncStatus: '' }
const form = reactive({ ...empty })

async function load() {
  loading.value = true
  try {
    rows.value = await archiveApi.employees()
  } catch (e) {
    showErr(e)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(form, empty)
  dialog.value = true
}

function openEdit(row) {
  Object.assign(form, empty, row)
  form.password = ''
  dialog.value = true
}

async function onDelete(row) {
  try {
    await ElMessageBox.prompt(`确认删除人员「${row.name}」？输入其手机号 ${row.phone} 以确认`, '删除人员', {
      confirmButtonText: '删除', cancelButtonText: '取消', inputPattern: new RegExp(`^${row.phone}$`), inputErrorMessage: '输入与手机号不一致'
    })
    await archiveApi.deleteEmployee(row.id)
    ok('已删除')
    await load()
  } catch (e) {
    if (e !== 'cancel' && e.message !== 'cancel') showErr(e)
  }
}
async function save() {
  saving.value = true
  try {
    const body = { ...form }
    delete body.id
    if (!body.password) delete body.password
    if (form.id) {
      Object.assign(form, await archiveApi.updateEmployee(form.id, body))
    } else {
      Object.assign(form, await archiveApi.createEmployee(body))
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
