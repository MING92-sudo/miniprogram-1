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
      <el-table-column prop="departmentText" label="部门" width="100">
        <template #default="{ row }">{{ row.departmentText || '—' }}</template>
      </el-table-column>
      <el-table-column prop="roleText" label="角色" width="140" />
      <el-table-column label="班组（可直接调度）" width="170">
        <template #default="{ row }">
          <el-select v-model="row.groupName" size="small" filterable allow-create default-first-option
                     :disabled="!auth.canWrite" placeholder="—" @change="saveGroup(row)"
                     style="width: 100%">
            <el-option v-for="g in groupOptions" :key="g" :label="g" :value="g" />
          </el-select>
        </template>
      </el-table-column>
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
      <el-table-column label="绑定状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="Number(row.bindStatus) === 1 ? 'danger' : 'success'">
            {{ Number(row.bindStatus) === 1 ? '中止' : '正常' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="workEndDate" label="证件有效期至" width="120" />
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="!auth.canWrite" @click="openEdit(row)">编辑</el-button>
          <el-button link type="warning" :disabled="!auth.canWrite" @click="onResetPwd(row)">重置密码</el-button>
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
            <el-option-group v-for="g in ROLE_GROUPS" :key="g.label" :label="g.label">
              <el-option v-for="r in g.roles" :key="r.code" :label="r.name + '（' + r.code + '）'" :value="r.code" />
            </el-option-group>
          </el-select>
          <div class="tip" v-if="rolePerm">{{ rolePerm }}</div>
        </el-form-item>
        <el-form-item label="证书编号"><el-input v-model="form.certificate" /></el-form-item>
        <el-form-item label="班组"><el-input v-model="form.groupName" placeholder="如：维保一班；组长按班组查看组员单据" /></el-form-item>
        <el-form-item label="作业开始日期"><el-input v-model="form.workStartDate" placeholder="yyyy-MM-dd" /></el-form-item>
        <el-form-item label="证件有效期至"><el-input v-model="form.workEndDate" placeholder="yyyy-MM-dd" /></el-form-item>
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
import { reactive, ref, computed, onMounted } from 'vue'
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

/**
 * 角色分组（docs/14 §3）：三个平级部门 + 平台层。键值必须与后端 RoleCatalog 一致，
 * 使用单位账号不在此处（在「使用单位档案」开通）。
 */
const ROLE_GROUPS = [
  {
    label: "平台层",
    roles: [
      { code: "ADMIN", name: "平台管理员" },
      { code: "SYS_ADMIN", name: "系统管理员" }
    ]
  },
  {
    label: "安装部",
    roles: [
      { code: "INSTALL_MANAGER", name: "安装项目经理" },
      { code: "INSTALL_CLERK", name: "安装文员" },
      { code: "INSTALL_WORKER", name: "安装员" }
    ]
  },
  {
    label: "维保部",
    roles: [
      { code: "MAINT_MANAGER", name: "维保经理" },
      { code: "MAINT_BIZ", name: "维保商务" },
      { code: "LEADER", name: "维保班组长" },
      { code: "MAINT_QUALITY", name: "维保质量负责人" },
      { code: "WORKER", name: "维保人员" },
      { code: "ASSISTANT", name: "配合维保人员" }
    ]
  }
]

/** 角色权限说明（docs/14 §6 管理端矩阵 / §7 小程序矩阵的通俗版） */
const ROLE_PERM = {
  ADMIN: "平台兜底：三部门数据全量可读，档案维护/重报/账号管理可用",
  SYS_ADMIN: "与平台管理员同权（保留角色）",
  INSTALL_MANAGER: "小程序/管理端：移交与外接建档发起、指派、资料提交",
  INSTALL_CLERK: "管理端：本部门人员只读 + 移交/建档资料上传",
  INSTALL_WORKER: "小程序：现场查验、整改前后照片",
  MAINT_MANAGER: "管理端：维保部全部业务写（派单/模板/重报/人员/账号）",
  MAINT_BIZ: "管理端：合同与使用单位档案读写，不见现场作业数据",
  LEADER: "管理端只读 + 小程序本班组作业（延期/转派）",
  MAINT_QUALITY: "管理端：全维保部只读，模板启停，不可重报/签退",
  WORKER: "小程序：本人工单签到/清单/签退、急修登记",
  ASSISTANT: "小程序：配合人员，本人签字与作业留痕"
}
const rolePerm = computed(() => ROLE_PERM[form.role] || "")
const groupOptions = computed(() => {
  const set = new Set(['维保一组', '维保二组'])
  rows.value.forEach((r) => { if (r.groupName) set.add(r.groupName) })
  return [...set]
})
async function saveGroup(row) {
  try {
    await archiveApi.updateEmployee(row.id, { groupName: row.groupName || '' })
    ok('班组已更新：' + (row.groupName || '（清空）'))
  } catch (e) {
    showErr(e)
    await load()
  }
}

const empty = { id: '', name: '', phone: '', account: '', role: 'WORKER', certificate: '', groupName: '',
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

/** 重置密码（docs/09 V2.2 忘记密码方案 A：12 位随机，一次性弹窗转交本人） */
async function onResetPwd(row) {
  try {
    await ElMessageBox.confirm(`确以为「${row.name}（${row.phone}）」重置密码？将生成 12 位随机密码。`, '重置密码', { type: 'warning' })
    const res = await archiveApi.resetEmployeePassword(row.id)
    await ElMessageBox.alert(
      `新密码：${res.initialPassword}（仅展示一次，请转交本人，登录后可在小程序自助改密）`,
      '重置成功', { type: 'success' })
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
