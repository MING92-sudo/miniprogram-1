<template>
  <div>
    <ViewHead title="系统配置" sub="仅平台管理员 · 保存即时生效并写审计">
      <template #meta><PageMeta perm="config:manage" /></template>
    </ViewHead>
        <div class="tab-bar mt16">
      <button v-if="canConfig" type="button" :class="['tab-i', { on: tab === 'config' }]" @click="go('config')">系统配置</button>
      <button v-if="canRegions" type="button" :class="['tab-i', { on: tab === 'regions' }]" @click="go('regions')">区域管理</button>
      <button v-if="canTemplates" type="button" :class="['tab-i', { on: tab === 'templates' }]" @click="go('templates')">检查项模板</button>
      <button v-if="canConfig" type="button" :class="['tab-i', { on: tab === 'company' }]" @click="go('company')">单位信息</button>
    </div>
    <RegionView v-if="tab === 'regions'" embedded />
    <TemplateManageView v-else-if="tab === 'templates'" embedded />
    <CompanyView v-else-if="tab === 'company'" embedded />
    <template v-else>
<div class="mt16">
      <!-- SLA 时限 -->
      <div class="card">
        <div class="card-head">
          <h3><svg class="ic" aria-hidden="true"><use href="#i-clock" /></svg>SLA 时限（分钟）</h3>
          <span class="ch-tag tag tag-dim">1–240</span>
        </div>
        <div class="card-body">
          <div class="form-grid">
            <FormField label="P1 困人" required :error="errors.sla_minutes">
              <input v-model="model.sla_minutes" type="number" min="1" max="240" class="ctl mono" />
            </FormField>
            <FormField label="一般急修" required :error="errors.sla_urgent_minutes">
              <input v-model="model.sla_urgent_minutes" type="number" min="1" max="240" class="ctl mono" />
            </FormField>
            <FormField label="报修响应" required :error="errors.sla_report_minutes">
              <input v-model="model.sla_report_minutes" type="number" min="1" max="240" class="ctl mono" />
            </FormField>
          </div>
          <div class="form-foot mt12">
            <PermButton code="config:manage" label="保存 SLA" variant="primary" icon="check" @click="saveSla" />
          </div>
        </div>
      </div>

      <!-- 保养周期 / 抽查 -->
      <div class="card mt16">
        <div class="card-head">
          <h3><svg class="ic" aria-hidden="true"><use href="#i-calendar" /></svg>保养周期（天）</h3>
          <span class="ch-tag tag tag-dim">1–90</span>
        </div>
        <div class="card-body">
          <div class="form-grid">
            <FormField label="半月保间隔" required :error="errors.maint_cycle_days">
              <input v-model="model.maint_cycle_days" type="number" min="1" max="90" class="ctl mono" />
            </FormField>
            <FormField label="季度保间隔" required :error="errors.maint_cycle_quarter_days">
              <input v-model="model.maint_cycle_quarter_days" type="number" min="1" max="90" class="ctl mono" />
            </FormField>
            <FormField label="抽查比例（%）" required :error="errors.spotcheck_ratio">
              <input v-model="model.spotcheck_ratio" type="number" min="1" max="100" class="ctl mono" />
            </FormField>
          </div>
          <div class="form-foot mt12">
            <PermButton code="config:manage" label="保存周期 / 抽查" variant="primary" icon="check" @click="saveCycle" />
          </div>
        </div>
      </div>

      <!-- 脱敏规则 -->
      <div class="card mt16">
        <div class="card-head"><h3><svg class="ic" aria-hidden="true"><use href="#i-eye-off" /></svg>脱敏规则（6 类字段）</h3></div>
        <div class="card-body">
          <div class="perm-list">
            <label v-for="m in maskFields" :key="m.key" :class="['perm-item', { on: model[m.key] === '1' }]" style="cursor:pointer">
              <span :class="['switch', { on: model[m.key] === '1' }]" @click.prevent="toggleMask(m.key)" />
              <span>{{ m.label }}</span>
              <code>{{ model[m.key] === '1' ? '脱敏' : '明文' }}</code>
            </label>
          </div>
          <RuleNote class="mt12">开关保存到后端配置（<span class="mono">PUT /settings/{key}</span>），脱敏在服务端执行；关闭后对应字段以明文返回。</RuleNote>
        </div>
      </div>

      <!-- 签字模板 -->
      <div class="card mt16">
        <div class="card-head">
          <h3><svg class="ic" aria-hidden="true"><use href="#i-file-text" /></svg>签字模板</h3>
          <PermButton code="config:manage" label="新增模板" variant="ghost" size="xs" icon="plus" @click="tplAddOpen = true" />
        </div>
        <div class="card-body">
          <div class="chk-list">
            <div v-for="t in templates" :key="t.id" :class="['chk-row', { pass: t.enabled }]">
              <svg class="ic" aria-hidden="true"><use href="#i-file-text" /></svg>
              <span class="cr-t">{{ t.name }}</span>
              <span class="cr-d">
                <span :class="['switch', { on: t.enabled }]" style="vertical-align:middle" @click="toggleTpl(t)" />
                <button class="btn btn-ghost btn-xs" type="button" @click="previewTpl(t)">预览</button>
              </span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 模板预览 -->
    <Modal :open="previewOpen" title="模板预览" :sub="previewing ? previewing.name : ''" icon="file-text" confirm-label="关闭"
      @close="previewOpen = false" @confirm="previewOpen = false">
      <div v-if="previewing" class="paper">
        <div class="p-head"><span>{{ previewing.id }}</span><span>{{ previewing.name }}</span></div>
        <h4 class="mt8">电梯维保记录单</h4>
        <div class="p-row mt8"><span>设备码</span><b>DT-CB-0318</b></div>
        <div class="p-row"><span>作业日期</span><b>2026-11-13</b></div>
        <div class="grid2 mt8">
          <div class="sign-box todo">主检签字</div>
          <div class="sign-box todo">配合人签字</div>
        </div>
        <div class="hash-line">
          <svg class="ic ic-sm" aria-hidden="true"><use href="#i-hash" /></svg>
          <span>SHA-256</span><code>preview</code>
        </div>
      </div>
    </Modal>

    <!-- 新增模板 -->
    <Modal :open="tplAddOpen" title="新增签字模板" sub="模板名 2–20 字" icon="plus" note="config:manage" confirm-label="保存"
      @close="tplAddOpen = false" @confirm="addTpl">
      <div class="form-grid one">
        <FormField label="模板名称" required :error="tplErr">
          <input v-model="tplName" class="ctl" placeholder="2–20 字" />
        </FormField>
      </div>
    </Modal>
  </template>
</div>
</template>
<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import RegionView from './RegionView.vue'
import TemplateManageView from './TemplateManageView.vue'
import CompanyView from './CompanyView.vue'
import http from '../api/client'
import ViewHead from '../ui/ViewHead.vue'
import PageMeta from '../ui/PageMeta.vue'
import RuleNote from '../ui/RuleNote.vue'
import PermButton from '../ui/PermButton.vue'
import Modal from '../ui/Modal.vue'
import FormField from '../ui/FormField.vue'
import { useToast } from '../ui/stores/ui'
import { state } from '../ui/permission-v2'
import { usePerspectiveStore } from '../ui/stores/perspective'
const toast = useToast()
const route = useRoute()
const router = useRouter()
const perspective = usePerspectiveStore()
function allow(code) { try { const s = state(perspective.activeRole, code); return s === 'Y' || s === 'C' } catch { return false } }
const canConfig = computed(() => allow('config:manage'))
const canRegions = computed(() => allow('region:view'))
const canTemplates = computed(() => allow('template:view'))
const TAB_PERMS = { config: canConfig, regions: canRegions, templates: canTemplates, company: canConfig }
const tab = ref(['regions', 'templates', 'company'].includes(String(route.query.tab)) ? String(route.query.tab) : 'config')
if (!TAB_PERMS[tab.value].value) {
  const first = ['config', 'regions', 'templates', 'company'].find((t) => TAB_PERMS[t].value)
  if (first) tab.value = first
}
function go(t) { tab.value = t; router.replace({ query: t === 'config' ? {} : { tab: t } }) }
// 同路由分页跳转（/regions、/templates 重定向回自身、浏览器前进后退）时同步分页
watch(() => route.query.tab, (t) => {
  const v = ['regions', 'templates', 'company'].includes(String(t)) ? String(t) : 'config'
  if (v !== tab.value) tab.value = v
})

const DEFAULTS = {
  sla_minutes: '30', sla_urgent_minutes: '120', sla_report_minutes: '240',
  maint_cycle_days: '15', maint_cycle_quarter_days: '90', spotcheck_ratio: '20',
  mask_contact_phone: '1', mask_staff_phone: '1', mask_id_reg: '1', mask_amount: '1', mask_photo: '1', mask_address: '1'
}
const model = reactive({ ...DEFAULTS })
const errors = reactive({})
const templates = ref([])
const maskFields = [
  { key: 'mask_contact_phone', label: '使用单位联系人手机号' },
  { key: 'mask_staff_phone', label: '安装/维保人员手机号' },
  { key: 'mask_id_reg', label: '身份证 / 注册代码' },
  { key: 'mask_amount', label: '合同金额' },
  { key: 'mask_photo', label: '故障现场照片' },
  { key: 'mask_address', label: '电梯详细地址' }
]
async function load() {
  try {
    const cfg = await http.get('/settings')
    Object.keys(DEFAULTS).forEach(k => { if (cfg && cfg[k] != null && cfg[k] !== '') model[k] = String(cfg[k]) })
    if (cfg && cfg.sign_templates) {
      try { const a = JSON.parse(cfg.sign_templates); if (Array.isArray(a) && a.length) templates.value = a } catch { /* 保留默认模板 */ }
    }
  } catch (e) { toast.error(e.message || '加载配置失败') }
}
onMounted(() => { if (canConfig.value) load() }) // 模板/区域分页用户不拉系统配置（403 噪声）
async function put(key, value) { await http.put(`/settings/${key}`, { value: String(value) }) }
function numErr(key, lo, hi, msg) {
  const v = parseInt(model[key], 10)
  if (!(v >= lo && v <= hi)) { errors[key] = msg; return null }
  errors[key] = ''
  return v
}
async function saveSla() {
  const p1 = numErr('sla_minutes', 1, 240, 'SLA 须为 1–240 整数分钟')
  const u = numErr('sla_urgent_minutes', 1, 240, 'SLA 须为 1–240 整数分钟')
  const rp = numErr('sla_report_minutes', 1, 240, 'SLA 须为 1–240 整数分钟')
  if (p1 == null || u == null || rp == null) return
  try {
    await put('sla_minutes', p1); await put('sla_urgent_minutes', u); await put('sla_report_minutes', rp)
    toast.success('SLA 已保存 · 工单倒计时阈值即时更新', 'config:manage')
  } catch (e) { toast.error(e.message || '保存失败') }
}
async function saveCycle() {
  const half = numErr('maint_cycle_days', 1, 90, '周期须为 1–90 天')
  const q = numErr('maint_cycle_quarter_days', 1, 90, '周期须为 1–90 天')
  const sr = numErr('spotcheck_ratio', 1, 100, '抽查比例须为 1–100')
  if (half == null || q == null || sr == null) return
  try {
    await put('maint_cycle_days', half); await put('maint_cycle_quarter_days', q); await put('spotcheck_ratio', sr)
    toast.success('周期 / 抽查已保存 · 记录单抽查标即时更新', 'config:manage')
  } catch (e) { toast.error(e.message || '保存失败') }
}
async function toggleMask(key) {
  const next = model[key] === '1' ? '0' : '1'
  try {
    await put(key, next)
    model[key] = next
    toast.success('脱敏规则已更新', key)
  } catch (e) { toast.error(e.message || '保存失败') }
}
// ── 签字模板 ──
async function saveTpls() { await put('sign_templates', JSON.stringify(templates.value)) }
async function toggleTpl(t) {
  t.enabled = !t.enabled
  try { await saveTpls(); toast.success('模板已' + (t.enabled ? '启用' : '停用'), t.id) }
  catch (e) { t.enabled = !t.enabled; toast.error(e.message || '保存失败') }
}
const previewOpen = ref(false)
const previewing = ref(null)
function previewTpl(t) { previewing.value = t; previewOpen.value = true }
const tplAddOpen = ref(false)
const tplName = ref('')
const tplErr = ref('')
async function addTpl() {
  const n = tplName.value.trim()
  if (n.length < 2 || n.length > 20) { tplErr.value = '模板名须为 2–20 字'; return }
  tplErr.value = ''
  const id = 'TPL-' + String(templates.value.length + 1).padStart(2, '0')
  templates.value.push({ id, name: n, enabled: true })
  try {
    await saveTpls()
    toast.success('模板已新增', id)
    tplAddOpen.value = false
    tplName.value = ''
  } catch (e) { templates.value.pop(); toast.error(e.message || '保存失败') }
}
</script>