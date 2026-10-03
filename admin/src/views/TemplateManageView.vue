<template>
  <el-card shadow="never">
    <div class="head">
      <div>
        <el-select v-model="query.templateType" placeholder="类型" clearable style="width: 160px">
          <el-option label="官方模板（附件 A—D）" value="OFFICIAL" />
          <el-option label="自定义模板" value="CUSTOM" />
        </el-select>
        <el-select v-model="query.appendix" placeholder="附件" clearable
                   :disabled="query.templateType === 'CUSTOM'" style="margin-left: 8px">
          <el-option v-for="a in ['A', 'B', 'C', 'D']" :key="a" :label="'附件 ' + a" :value="a" />
        </el-select>
        <el-input v-model="query.keyword" placeholder="名称 / 编号关键字" clearable
                  style="width: 200px; margin-left: 8px" @keyup.enter="load(1)" />
        <el-button type="primary" style="margin-left: 8px" @click="load(1)">查询</el-button>
      </div>
      <el-button type="primary" :disabled="!auth.canWrite" @click="openCreate">新建自定义模板</el-button>
    </div>

    <el-table :data="rows" v-loading="loading" stripe size="small">
      <el-table-column label="类型" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.templateType === 'OFFICIAL' ? 'info' : 'primary'">
            {{ row.templateType === 'OFFICIAL' ? '官方' : '自定义' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="适用范围" min-width="130" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.templateType === 'OFFICIAL' ? `附件 ${row.appendix} · ${row.categoryScope}` : row.categoryScope }}
        </template>
      </el-table-column>
      <el-table-column prop="itemCode" label="编号" width="130" show-overflow-tooltip />
      <el-table-column prop="name" label="检查项" min-width="220" show-overflow-tooltip />
      <el-table-column label="判定方式" width="100">
        <template #default="{ row }">{{ JUDGE[row.judgeType] || row.judgeType || '—' }}</template>
      </el-table-column>
      <el-table-column label="标记" width="120">
        <template #default="{ row }">
          <el-tag v-if="row.isKey" size="small" type="warning">关键项</el-tag>
          <el-tag v-if="row.photoRequired" size="small" type="info">需拍照</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag size="small" :type="row.enabled ? 'success' : 'danger'">{{ row.enabled ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <template v-if="row.templateType === 'CUSTOM'">
            <el-button link type="primary" :disabled="!auth.canWrite" @click="openEdit(row)">编辑</el-button>
            <el-button link :type="row.enabled ? 'danger' : 'success'" :disabled="!auth.canWrite"
                       @click="onToggle(row)">{{ row.enabled ? '停用' : '启用' }}</el-button>
          </template>
          <span v-else style="color: #86909c; font-size: 12px">只读</span>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination class="pager" layout="total, prev, pager, next" :total="total"
                   :page-size="query.size" :current-page="query.page" @current-change="load" />

    <el-dialog v-model="dialog" :title="form.id ? '编辑自定义模板' : '新建自定义模板'" width="560px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="检查项名称" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="适用范围" required>
          <el-select v-model="form.categoryScope" style="width: 100%">
            <el-option label="消防电梯" value="消防电梯" />
            <el-option label="防爆电梯" value="防爆电梯" />
          </el-select>
        </el-form-item>
        <el-form-item label="判定方式">
          <el-select v-model="form.judgeType" style="width: 100%">
            <el-option v-for="(label, v) in JUDGE" :key="v" :label="label" :value="v" />
          </el-select>
        </el-form-item>
        <el-form-item label="检查要求"><el-input v-model="form.requirement" type="textarea" :rows="2" /></el-form-item>
        <el-form-item v-if="form.judgeType === 'NUMERIC'" label="标准限值">
          <el-input v-model="form.valueMin" placeholder="下限" style="width: 48%" />
          <el-input v-model="form.valueMax" placeholder="上限" style="width: 48%; margin-left: 4%" />
          <el-input v-model="form.valueUnit" placeholder="单位" style="width: 48%; margin-top: 6px" />
        </el-form-item>
        <el-form-item label="标记">
          <el-checkbox v-model="form.isKey">关键项</el-checkbox>
          <el-checkbox v-model="form.photoRequired">必须拍照</el-checkbox>
        </el-form-item>
        <div class="tip">官方模板（TSG 附件 A—D 257 项）只读；自定义模板匹配电梯特殊类别后生效</div>
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
import * as templateApi from '../api/template'
import { showErr, ok } from '../utils/ui'

const JUDGE = { NUMERIC: '数值型', STANDARD: '标准值', MANUFACTURER: '厂商值', QUALITATIVE: '定性' }

const auth = useAuthStore()
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const dialog = ref(false)
const saving = ref(false)
const query = reactive({ templateType: '', appendix: '', keyword: '', page: 1, size: 20 })
const empty = { id: null, name: '', categoryScope: '消防电梯', judgeType: 'QUALITATIVE',
  requirement: '', valueMin: '', valueMax: '', valueUnit: '', isKey: false, photoRequired: false }
const form = reactive({ ...empty })

async function load(page) {
  loading.value = true
  if (page) query.page = page
  try {
    const res = await templateApi.list({ ...query })
    rows.value = res.list || []
    total.value = res.total || 0
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
  Object.assign(form, empty, row, {
    requirement: row.requirement || '',
    valueMin: row.payload?.valueMin ?? '',
    valueMax: row.payload?.valueMax ?? '',
    valueUnit: row.payload?.valueUnit ?? ''
  })
  dialog.value = true
}

async function save() {
  if (!form.name) { ok('请填写检查项名称'); return }
  saving.value = true
  try {
    const body = { ...form }
    delete body.id
    delete body.payload
    if (form.id) await templateApi.update(form.id, body)
    else await templateApi.create(body)
    dialog.value = false
    ok(form.id ? '模板已更新' : '自定义模板已创建')
    await load()
  } catch (e) {
    showErr(e)
  } finally {
    saving.value = false
  }
}

async function onToggle(row) {
  try {
    await templateApi.setEnabled(row.id, !row.enabled)
    ok(row.enabled ? '已停用' : '已启用')
    await load()
  } catch (e) {
    showErr(e)
  }
}

onMounted(() => load())
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.tip { color: #86909c; font-size: 12px; }
.pager { margin-top: 12px; justify-content: flex-end; }
</style>
