<template>
  <div>
    <ViewHead title="迁移核对" sub="客户迁移遗留问题 · 单条人工核对与裁定（无批量入口）">
      <template #meta>
        <PageMeta perm="audit:view" :extra="[{ k: '待核对', v: pending, icon: 'clock', cls: 'count' }]" />
      </template>
    </ViewHead>
    <div class="notice-bar mt16">
      <svg class="ic" aria-hidden="true"><use href="#i-lock" /></svg>
      <span><b>仅支持单条裁定。</b>回填类裁定必须指定单位并填写说明；所有写操作即时追加审计日志。</span>
    </div>
    <div class="filter-bar mt12">
      <div class="fl">
        <label for="mi-type">问题类型</label>
        <select id="mi-type" v-model="query.type" class="ctl" aria-label="问题类型">
          <option value="">全部</option>
          <option v-for="t in issueTypes" :key="t" :value="t">{{ t }}</option>
        </select>
      </div>
      <div class="fl">
        <label for="mi-resolution">状态</label>
        <select id="mi-resolution" v-model="query.resolution" class="ctl" aria-label="状态">
          <option value="">全部</option>
          <option v-for="r in RESOLUTIONS" :key="r.value" :value="r.value">{{ r.label }}</option>
        </select>
      </div>
      <button class="btn btn-primary" type="button" @click="onQuery">查询</button>
      <button class="btn btn-ghost" type="button" @click="onReset">重置</button>
    </div>
    <div class="mt12">
      <DataTable :columns="cols" :rows="rows" :loading="loading" empty-hint="没有符合条件的迁移问题。">
        <template #cell-object="{ row }">
          <span class="mono f12">{{ row.objectType }}</span>
          <div class="cell-2 dim f11">{{ row.objectId }}</div>
        </template>
        <template #cell-evidence="{ row }"><span class="f12">{{ row.evidence || '—' }}</span></template>
        <template #cell-resolution="{ row }">
          <span :class="['tag', row.resolution === 'PENDING' ? 'tag-amber' : 'tag-ok']">{{ resolutionLabel(row.resolution) }}</span>
        </template>
        <template #row-actions="{ row }">
          <PermButton code="config:manage" label="裁定" variant="ghost" size="xs" @click="openRuling(row)" />
        </template>
      </DataTable>
    </div>
    <Modal :open="modalOpen" title="单条裁定" :sub="'问题 ' + current.id" confirm-label="提交裁定"
      note="裁定后不可撤销，请确认证据与回填单位。" @close="closeRuling" @confirm="submitRuling">
      <div class="form-row">
        <label>裁定结果</label>
        <div class="radio-row">
          <label v-for="opt in RULINGS" :key="opt.value" class="perm-item">
            <input v-model="form.resolution" type="radio" :value="opt.value" /> {{ opt.label }}
          </label>
        </div>
      </div>
      <div v-if="form.resolution === 'BACKFILLED'" class="form-row">
        <label for="mi-backfill">回填单位 ID</label>
        <input id="mi-backfill" v-model="form.backfillUnitId" class="ctl" type="text" placeholder="请输入回填单位 ID" />
      </div>
      <div class="form-row">
        <label for="mi-note">裁定说明（必填）</label>
        <textarea id="mi-note" v-model="form.note" class="ctl" rows="3" placeholder="请填写裁定说明（必填）"></textarea>
      </div>
    </Modal>
  </div>
</template>
<script setup>
// 后端 GET/POST 强校验为准，前端仅体验优化。
import { ref, reactive, computed, onMounted } from 'vue'
import DataTable from '../ui/DataTable.vue'
import Modal from '../ui/Modal.vue'
import PageMeta from '../ui/PageMeta.vue'
import PermButton from '../ui/PermButton.vue'
import ViewHead from '../ui/ViewHead.vue'
import { useToast } from '../ui/stores/ui'
import { listMigrationIssues, resolveMigrationIssue } from '../api/migration'

const toast = useToast()
const rows = ref([])
const loading = ref(false)
const pending = ref(0)
const page = ref(1)
const size = 200
const query = reactive({ type: '', resolution: '' })
const RULINGS = [
  { value: 'BACKFILLED', label: '已回填' },
  { value: 'SKIPPED', label: '跳过' },
  { value: 'MANUAL_DONE', label: '人工完成' }
]
const RESOLUTIONS = [{ value: 'PENDING', label: '待核对' }, ...RULINGS]
const ISSUE_TYPES = ['TRANSFER_SUSPECT', 'UNMATCHED', 'CODE_AMBIGUOUS', 'DEDUP_CONFLICT', 'NO_ANCHOR']
const issueTypes = computed(() => [...new Set([...ISSUE_TYPES, ...rows.value.map(r => r.issueType).filter(Boolean)])])
const cols = [
  { key: 'issueType', label: '类型' },
  { key: 'object', label: '对象' },
  { key: 'elevatorCode', label: '电梯码' },
  { key: 'currentUnitId', label: '当前单位' },
  { key: 'candidateUnitId', label: '候选单位' },
  { key: 'evidence', label: '证据' },
  { key: 'confidence', label: '置信度' },
  { key: 'resolution', label: '状态' }
]
const modalOpen = ref(false)
const current = ref({})
const form = reactive({ resolution: 'BACKFILLED', backfillUnitId: '', note: '' })
const submitting = ref(false)

function resolutionLabel(r) {
  const hit = RESOLUTIONS.find(o => o.value === r)
  return hit ? hit.label : (r || '—')
}
async function load() {
  loading.value = true
  try {
    const d = await listMigrationIssues({ type: query.type, resolution: query.resolution, page: page.value, size })
    rows.value = Array.isArray(d && d.list) ? d.list : []
    pending.value = Number(d && d.pending) || 0
  } catch (e) {
    toast.error(e.message || '迁移核对清单加载失败')
  } finally {
    loading.value = false
  }
}
function onQuery() { page.value = 1; load() }
function onReset() { query.type = ''; query.resolution = ''; page.value = 1; load() }
function openRuling(row) {
  current.value = row
  form.resolution = 'BACKFILLED'
  form.backfillUnitId = ''
  form.note = ''
  modalOpen.value = true
}
function closeRuling() { modalOpen.value = false }
async function submitRuling() {
  if (submitting.value) return
  const unit = String(form.backfillUnitId || '').trim()
  const note = String(form.note || '').trim()
  if (form.resolution === 'BACKFILLED' && !unit) {
    toast.warn('BACKFILLED 需填写回填单位 ID')
    return
  }
  if (!note) {
    toast.warn('请填写裁定说明')
    return
  }
  submitting.value = true
  try {
    await resolveMigrationIssue(current.value.id, {
      resolution: form.resolution,
      backfillUnitId: form.resolution === 'BACKFILLED' ? unit : null,
      note
    })
    toast.success('裁定已提交')
    modalOpen.value = false
    await load()
  } catch (e) {
    toast.error(e.message || '裁定提交失败')
  } finally {
    submitting.value = false
  }
}
onMounted(load)
</script>
