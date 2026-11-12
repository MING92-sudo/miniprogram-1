<template>
  <el-card shadow="never">
    <div class="head">
      <span>维保合同（{{ rows.length }}）</span>
      <el-button type="primary" size="small" :disabled="!auth.can('contract:write')" @click="openCreate">
        新建合同
      </el-button>
    </div>

    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column prop="contractNo" label="合同号" width="160" />
      <el-table-column label="使用单位" min-width="180">
        <template #default="{ row }">{{ unitName(row.useUnitId) }}</template>
      </el-table-column>
      <el-table-column prop="startDate" label="起始日" width="120" />
      <el-table-column prop="endDate" label="终止日" width="120" />
      <el-table-column label="金额" width="110">
        <template #default="{ row }">{{ row.amount == null ? '—' : row.amount }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
            {{ row.status === 'ACTIVE' ? '生效' : '终止' }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog" title="新建合同" width="480px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="合同号" required><el-input v-model="form.contractNo" /></el-form-item>
        <el-form-item label="使用单位" required>
          <el-select v-model="form.useUnitId" filterable style="width: 100%">
            <el-option v-for="u in units" :key="u.id" :label="u.unitName" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="起始日" required>
          <el-date-picker v-model="form.startDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="终止日" required>
          <el-date-picker v-model="form.endDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="金额"><el-input v-model="form.amount" placeholder="选填" /></el-form-item>
        <div class="tip">起始日不能晚于今天；同梯已有生效合同时会拦截。合同是外接建档的前置条件。</div>
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
import { useAuthStore } from '../stores/auth'
import * as contractApi from '../api/contract'
import * as archiveApi from '../api/archive'
import { showErr, ok } from '../utils/ui'

const auth = useAuthStore()
const rows = ref([])
const units = ref([])
const loading = ref(false)
const dialog = ref(false)
const saving = ref(false)
const form = reactive({ contractNo: '', useUnitId: '', startDate: '', endDate: '', amount: '' })

const unitName = (id) => {
  const u = units.value.find((x) => x.id === id)
  return u ? u.unitName : id || '—'
}

async function load() {
  loading.value = true
  try {
    rows.value = await contractApi.listContracts()
  } catch (e) {
    showErr(e)
    rows.value = []
  } finally {
    loading.value = false
  }
}

async function loadUnits() {
  try {
    units.value = await archiveApi.useUnits()
  } catch (e) {
    units.value = []
  }
}

function openCreate() {
  Object.assign(form, { contractNo: '', useUnitId: '', startDate: '', endDate: '', amount: '' })
  dialog.value = true
}

async function save() {
  if (!form.contractNo.trim() || !form.useUnitId || !form.startDate || !form.endDate) {
    return showErr(new Error('合同号、使用单位、起止日期均为必填'))
  }
  saving.value = true
  try {
    await contractApi.createContract({
      contractNo: form.contractNo.trim(),
      useUnitId: form.useUnitId,
      startDate: form.startDate,
      endDate: form.endDate,
      amount: form.amount === '' ? null : Number(form.amount)
    })
    ok('合同已创建')
    dialog.value = false
    await load()
  } catch (e) {
    showErr(e)
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  load()
  loadUnits()
})
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.tip { color: #86909c; font-size: 12px; }
</style>
