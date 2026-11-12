<template>
  <el-card shadow="never">
    <div class="head">
      <span>使用单位档案（{{ rows.length }}）</span>
      <el-button type="primary" size="small" :disabled="!auth.canWrite" @click="openCreate">新建使用单位</el-button>
    </div>

    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column prop="unitName" label="使用单位" min-width="180" show-overflow-tooltip />
      <el-table-column prop="organizationCode" label="统一社会信用代码" min-width="180" show-overflow-tooltip />
      <el-table-column prop="unitPrincipal" label="负责人" width="100" />
      <el-table-column prop="unitPrincipalPhone" label="负责人手机" width="130" />
      <el-table-column prop="elevatorAdminister" label="安全管理员" width="110" />
      <el-table-column prop="elevatorAdministerPhone" label="安全员手机" width="130" />
      <el-table-column prop="emergencyPhone" label="应急电话" width="130" />
      <el-table-column prop="entityId" label="平台主体ID">
        <template #default="{ row }">{{ row.entityId || '—' }}</template>
      </el-table-column>
      <el-table-column prop="elevatorCount" label="电梯数" width="80" />
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="!auth.canWrite" @click="openEdit(row)">编辑</el-button>
          <el-button link type="warning" :disabled="!auth.canWrite" @click="openAccount(row)">开通账号</el-button>
          
          <el-button link type="danger" :disabled="!auth.canWrite" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog" :title="form.id ? '编辑使用单位' : '新建使用单位'" width="520px">
      <el-form :model="form" label-width="130px">
        <el-form-item label="单位名称" required><el-input v-model="form.unitName" /></el-form-item>
        <el-form-item label="统一社会信用代码"><el-input v-model="form.organizationCode" placeholder="选填" /></el-form-item>
        <el-form-item label="负责人"><el-input v-model="form.unitPrincipal" /></el-form-item>
        <el-form-item label="负责人手机">
          <el-input v-model="form.unitPrincipalPhone" />
        </el-form-item>
        <el-form-item label="安全管理员"><el-input v-model="form.elevatorAdminister" /></el-form-item>
        <el-form-item label="安全员手机"><el-input v-model="form.elevatorAdministerPhone" /></el-form-item>
        <el-form-item label="应急电话"><el-input v-model="form.emergencyPhone" /></el-form-item>
        <div class="tip">负责人 / 安全管理员手机参与五类互斥，应急电话不参与</div>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
      <el-dialog v-model="accDialog" title="使用单位门户账号" width="460px">
      <div v-if="accResult">
        <el-alert type="success" :closable="false" show-icon
                  title="账号已开通/重置，口令只显示这一次，请复制转交安全管理员" />
        <el-descriptions :column="1" border style="margin-top: 12px">
          <el-descriptions-item label="使用单位">{{ accResult.unitName }}</el-descriptions-item>
          <el-descriptions-item label="登录账号">{{ accResult.account }}</el-descriptions-item>
          <el-descriptions-item label="初始口令">{{ accResult.initialPassword }}</el-descriptions-item>
          <el-descriptions-item label="微信邀请码">{{ accResult.inviteCode }}</el-descriptions-item>
        </el-descriptions>
        <div class="tip">安全管理员在小程序「使用单位」入口用邀请码绑定微信后，即可查看本梯记录并报修。</div>
      </div>
      <el-empty v-else description="点击下方按钮生成账号与邀请码" :image-size="60" />
      <template #footer>
        <el-button @click="accDialog = false">关闭</el-button>
        <el-button type="primary" :loading="accSaving" @click="createAccount">生成/重置</el-button>
      </template>
    </el-dialog>

</el-card>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { useAuthStore } from '../stores/auth'
import * as archiveApi from '../api/archive'
import * as unitApi from '../api/unit'
import { showErr, ok } from '../utils/ui'
import { ElMessageBox } from 'element-plus'

const auth = useAuthStore()
const rows = ref([])
const accDialog = ref(false)
const accSaving = ref(false)
const accResult = ref(null)
const accUnit = ref(null)
const loading = ref(false)
const dialog = ref(false)
const saving = ref(false)
const form = reactive({ id: '', unitName: '', unitPrincipal: '', unitPrincipalPhone: '',
  organizationCode: '', elevatorAdminister: '', elevatorAdministerPhone: '', emergencyPhone: '' })

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
    organizationCode: '', elevatorAdminister: '', elevatorAdministerPhone: '', emergencyPhone: '' })
  dialog.value = true
}

  async function onDelete(row) {
    try {
      await ElMessageBox.confirm(`确认删除使用单位「${row.unitName}」？名下有电梯时将被拒绝`, '删除使用单位', { type: 'warning' })
      await archiveApi.deleteUseUnit(row.id)
      ok('已删除')
      await load()
    } catch (e) {
      if (e !== 'cancel') showErr(e)
    }
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

function openAccount(row) {
  accUnit.value = row
  accResult.value = null
  accDialog.value = true
}

async function createAccount() {
  if (!accUnit.value) return
  accSaving.value = true
  try {
    accResult.value = await unitApi.createUnitAccount({ useUnitId: accUnit.value.id })
    ok('账号已生成，请复制下方口令')
  } catch (e) {
    showErr(e)
  } finally {
    accSaving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.tip { color: #86909c; font-size: 12px; line-height: 1.5; }
</style>
