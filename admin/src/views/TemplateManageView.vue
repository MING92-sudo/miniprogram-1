<template>
  <el-card shadow="never">
    <div class="head">
      <el-space>
        <el-select v-model="query.templateType" placeholder="类型" clearable style="width: 160px">
          <el-option label="官方模板（TSG 附件 A—D）" value="OFFICIAL" />
          <el-option label="自定义模板（消防/防爆等）" value="CUSTOM" />
        </el-select>
        <el-select v-model="query.appendix" placeholder="附件" clearable style="width: 130px" :disabled="query.templateType === 'CUSTOM'">
          <el-option v-for="a in ['A', 'B', 'C', 'D']" :key="a" :label="'附件 ' + a" :value="a" />
        </el-select>
        <el-input v-model="query.keyword" placeholder="名称 / 编号关键字" clearable style="width: 200px" />
        <el-button type="primary" @click="load(1)">查询</el-button>
      </el-space>
      <el-button type="primary" :disabled="!auth.canWrite" @click="openCreate">新建自定义模板</el-button>
    </div>

    <el-alert type="info" :closable="false" class="tip"
              title="官方模板 257 行（TSG 附件 A—D）只读；消防/防爆电梯须按制造单位要求配置自定义模板（TSG 第二条），缺失会在平台同步页提示（1006）" />

    <el-table :data="rows" v-loading="loading" stripe size="small">
      <el-table-column prop="itemCode" label="编号" width="110" />
      <el-table-column prop="name" label="检查项" min-width="220" show-overflow-tooltip />
      <el-table-column label="类型" width="170">
        <template #default="{ row }">
          <el-tag size="small" :type="row.templateType === 'OFFICIAL' ? 'info' : 'success'">
            {{ row.templateType === 'OFFICIAL' ? '官方 ' + (row.appendix || '') : row.categoryScope }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="freq" label="频次" width="100" />
      <el-table-column label="判定" width="110">
        <template #default="{ row }">{{ judgeText(row.judgeType) }}</template>
      </el-table-column>
      <el-table-column label="关键/拍照" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.isKey" size="small" type="danger">关键</el-tag>
          <el-tag v-if="row.photoRequired" size="small" type="warning" class="ml4">拍照</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag size="small" :type="row.enabled ? 'success' : 'danger'">{{ row.enabled ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <template v-if="row.templateType === 'CUSTOM'">
            <el-button link type="primary" :disabled="!auth.canWrite" @click="openEdit(row)">编辑</el-button>
            <el-button link :type="row.enabled ? 'danger' : 'success'" :disabled="!auth.canWrite"
                       @click="toggle(row)">{{ row.enabled ? '停用' : '启用' }}</el-button>
          </template>
          <span v-else class="muted">只读</span>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination class="pager" layout="total, prev, pager, next" :total="total"
                   :page-size="query.size" :current-page="query.page" @current-change="load" />

    <el-dialog v-model="dialog" :title="form.id ? '编辑自定义模板' : '新建自定义模板'" width="560px">
      <el-form label-width="120px">
        <el-form-item label="适用类别" required>
          <el-select v-model="form.categoryScope" style="width: 100%">
            <el-option v-for="s in scopes" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="检查项名称" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="要求（原文）"><el-input v-model="form.requirement" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="判定方式">
          <el-select v-model="form.judgeType" style="width: 100%">
            <el-option label="数值判定 NUMERIC" value="NUMERIC" />
            <el-option label="标准判定 STANDARD" value="STANDARD" />
            <el-option label="制造单位要求 MANUFACTURER" value="MANUFACTURER" />
            <el-option label="定性判断 QUALITATIVE" value="QUALITATIVE" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键项"><el-switch v-model="form.isKey" /></el-form-item>
        <el-form-item label="强制拍照"><el-switch v-model="form.photoRequired" /></el-form-item>
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

const auth = useAuthStore()
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const dialog = ref(false)
const saving = ref(false)
const scopes = ['消防电梯', '防爆电梯']

const query = reactive({ page: 1, size: 20, templateType: '', appendix: '', keyword: '' })
const empty = { id: null, name: '', requirement: '', judgeType: 'QUALITATIVE',
  isKey: false, photoRequired: false, categoryScope: '消防电梯' }
const form = reactive({ ...empty })

function judgeText(j) {
  return { NUMERIC: '数值', STANDARD: '标准', MANUFACTURER: '制造单位', QUALITATIVE: '定性' }[j] || j || '-'
}

async function load(p) {
  if (p) query.page = p
  loading.value = true
  try {
    const data = await templateApi.listTemplates({
      ...query,
      templateType: query.templateType || undefined,
      appendix: query.templateType === 'CUSTOM' ? undefined : (query.appendix || undefined),
      keyword: query.keyword || undefined
    })
    rows.value = data.list || []
    total.value = data.total || 0
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
  Object.assign(form, empty, {
    id: row.id, name: row.name, requirement: row.requirement,
    judgeType: row.judgeType, isKey: row.isKey, photoRequired: row.photoRequired,
    categoryScope: row.categoryScope
  })
  dialog.value = true
}

async function save() {
  if (!form.name) {
    ok('请填写检查项名称')
    return
  }
  saving.value = true
  try {
    if (form.id) {
      await templateApi.updateTemplate(form.id, { ...form })
    } else {
      await templateApi.createTemplate({ ...form })
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

async function toggle(row) {
  try {
    await templateApi.setTemplateEnabled(row.id, !row.enabled)
    ok(row.enabled ? '已停用' : '已启用')
    await load()
  } catch (e) {
    showErr(e)
  }
}

onMounted(() => load())
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; }
.tip { margin-bottom: 12px; }
.pager { margin-top: 12px; justify-content: flex-end; }
.muted { color: #c9cdd4; }
.ml4 { margin-left: 4px; }
</style>
