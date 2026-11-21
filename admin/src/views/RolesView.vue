<template>
  <div>
    <ViewHead :title="'角色权限矩阵'" :sub="roles.length + ' 角色 × ' + rows.length + ' 权限码 × 6 数据范围 · Y 允许 / C 只读·需审批 / – 禁止'">
      <template #actions>
        <PermButton code="stats:export" label="导出矩阵" variant="ghost" icon="download" @click="exportMatrix" />
      </template>
      <template #meta>
        <PageMeta perm="role:assign" :extra="[{ k: '权限码', v: rows.length, icon: 'key', cls: 'count' }, { k: '模式', v: '只读（写入口二期）', icon: 'lock' }]" />
      </template>
    </ViewHead>
        <div class="tab-bar mt16">
      <button v-if="canMatrix" type="button" :class="['tab-i', { on: tab === 'matrix' }]" @click="go('matrix')">角色权限矩阵</button>
      <button v-if="canGrants" type="button" :class="['tab-i', { on: tab === 'grants' }]" @click="go('grants')">授权管理</button>
    </div>
    <GrantsView v-if="tab === 'grants'" embedded />
    <template v-else>
<div class="mt16">
      <div class="chips mb12">
        <span :class="['chip', { on: domain === 'all' }]" @click="domain = 'all'">全部 {{ rows.length }}</span>
        <span v-for="d in domains" :key="d.k" :class="['chip', { on: domain === d.k }]" @click="domain = d.k">{{ d.label }} {{ countOf(d.k) }}</span>
      </div>
      <LoadingSkeleton v-if="loading" :rows="8" />
      <div v-else class="matrix-wrap">
        <table class="matrix">
          <thead>
            <tr>
              <th class="fixed">权限码 / 功能</th>
              <th v-for="r in roles" :key="r" title="查看角色详情" @click="openRole(r)">{{ roleLabel(r) }}</th>
              <th>Web 落地</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="d in domains" :key="d.k">
              <tr v-if="domain === 'all' || domain === d.k" class="grp">
                <td class="fixed" :colspan="roles.length + 2">{{ d.label }} · {{ countOf(d.k) }} 码</td>
              </tr>
              <template v-if="domain === 'all' || domain === d.k">
                <tr v-for="row in rowsOf(d.k)" :key="row.code">
                  <td class="fixed"><code>{{ row.code }}</code><div class="cell-2 dim f11">{{ permLabel(row.code) }}</div></td>
                  <td v-for="r in roles" :key="r" style="text-align:center">
                    <span :class="['lv', lvClass(row.states[r])]">{{ lvText(row.states[r]) }}</span>
                  </td>
                  <td class="mapped">
                    <router-link v-if="webView(row.code)" :to="webView(row.code)">{{ webView(row.code) }}</router-link>
                    <template v-else>小程序侧</template>
                  </td>
                </tr>
              </template>
            </template>
          </tbody>
        </table>
      </div>
    </div>
    <RuleNote class="mt12" icon="lock">矩阵以后端权限矩阵为唯一事实源（permission-v2 镜像），本期只读，无「编辑权限」入口（写入口标注二期）。点角色列头查看默认数据范围与可进入视图。</RuleNote>

    <Drawer :open="!!roleDetail" :title="roleDetail ? roleLabel(roleDetail) : ''" :sub="roleDetail ? roleDetail + ' · 默认 ' + (defaultScope(roleDetail) || '—') : ''"
      note="矩阵只读" @close="roleDetail = null">
      <template v-if="roleDetail">
        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-shield" /></svg>角色定义</div>
          <div class="kv-grid">
            <div class="kv"><span class="k">角色码</span><span class="v mono">{{ roleDetail }}</span></div>
            <div class="kv"><span class="k">名称</span><span class="v">{{ roleLabel(roleDetail) }}</span></div>
            <div class="kv"><span class="k">所属</span><span class="v">{{ deptOf(roleDetail) }}</span></div>
            <div class="kv"><span class="k">默认数据范围</span><span class="v mono">{{ defaultScope(roleDetail) || '—' }}</span></div>
          </div>
        </div>
        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-gauge" /></svg>权限概览</div>
          <div class="kv-grid">
            <div class="kv"><span class="k">可操作 Y</span><span class="v">{{ countState(roleDetail, 'Y') }} 码</span></div>
            <div class="kv"><span class="k">只读/需审批 C</span><span class="v">{{ countState(roleDetail, 'C') }} 码</span></div>
            <div class="kv"><span class="k">可进入 Web 视图</span><span class="v">{{ visibleViews(roleDetail).length }} 个</span></div>
          </div>
        </div>
        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-eye" /></svg>可进入视图</div>
          <div class="chips"><span v-for="v in visibleViews(roleDetail)" :key="v.path" class="chip on">{{ v.title }}</span></div>
        </div>
        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-ban" /></svg>不可见 / 不可操作</div>
          <div class="spec-list">
            <div v-for="v in hiddenViews(roleDetail)" :key="v.path">{{ v.title }}（{{ v.perm }} = DENY）</div>
            <div v-if="!hiddenViews(roleDetail).length">无隐藏视图</div>
            <div>前端隐藏不作为安全边界，后端每个接口都会校验（roles §9）。</div>
          </div>
        </div>
      </template>
      <template #footer>
        <button class="btn btn-ghost" type="button" @click="roleDetail = null">关闭</button>
      </template>
    </Drawer>
  </template>
</div>
</template>
<script setup>
// 角色权限矩阵（原型 Views.roles 对齐）：域筛选 chips + 固定首列矩阵 + 角色详情抽屉；数据源 permission-v2 真实矩阵
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import GrantsView from './GrantsView.vue'
import ViewHead from '../ui/ViewHead.vue'
import PageMeta from '../ui/PageMeta.vue'
import RuleNote from '../ui/RuleNote.vue'
import Drawer from '../ui/Drawer.vue'
import PermButton from '../ui/PermButton.vue'
import LoadingSkeleton from '../ui/states/LoadingSkeleton.vue'
import { useToast } from '../ui/stores/ui'
import { exportCsv } from '../ui/export-csv'
import { roleLabel, permLabel, roleDept, deptLabel } from '../ui/labels'
import { canonicalRoles, canonicalCodes, state, defaultScope } from '../ui/permission-v2'
import { usePerspectiveStore } from '../ui/stores/perspective'
import { MENU_GROUPS } from '../ui/menu-config'
import { rolesMatrix } from '../api/ledger'
const toast = useToast()
const route = useRoute()
const router = useRouter()
const perspective = usePerspectiveStore()
// 合并页分页门禁（Tab 合并 2026-11-21 审查修复）：矩阵=role:assign，授权=invite:manage；
// 与路由 meta.permByTab 同口径，无宿主权限但有分页权限的角色落地时收敛到可用分页
function allow(code) { try { const s = state(perspective.activeRole, code); return s === 'Y' || s === 'C' } catch { return false } }
const canMatrix = computed(() => allow('role:assign'))
const canGrants = computed(() => allow('invite:manage'))
const tab = ref((route.query.tab === 'grants') ? String(route.query.tab) : 'matrix')
if (tab.value === 'matrix' && !canMatrix.value && canGrants.value) tab.value = 'grants'
function go(t) { tab.value = t; router.replace({ query: t === 'matrix' ? {} : { tab: t } }) }
// 同路由分页跳转（矩阵「前往」/grants 重定向回自身、浏览器前进后退）时同步分页
watch(() => route.query.tab, (t) => {
  const v = t === 'grants' ? 'grants' : 'matrix'
  if (v !== tab.value) tab.value = v
})

const rows = ref([])
const roles = ref([...canonicalRoles()])
const loading = ref(false)
const domain = ref('all')
const roleDetail = ref(null)
const domains = [
  { k: 'handover', label: '安装移交' }, { k: 'ledger', label: '台账' }, { k: 'maint', label: '维保作业' },
  { k: 'order', label: '急修报修' }, { k: 'client', label: '使用单位' }, { k: 'org', label: '组织权限' },
  { k: 'ops', label: '监管与运营' }
]
function domainOf(code) {
  const p = code.split(':')[0]
  if (p === 'handover') return 'handover'
  if (['project', 'contract', 'elevator'].includes(p)) return 'ledger'
  if (['plan', 'task', 'checklist', 'record'].includes(p)) return 'maint'
  if (p === 'order') return 'order'
  if (p === 'client') return 'client'
  if (['report', 'filing', 'region', 'template', 'platform'].includes(p)) return 'ops'
  return 'org'
}
function rowsOf(k) { return rows.value.filter(r => domainOf(r.code) === k) }
function countOf(k) { return rowsOf(k).length }
function lvClass(v) { return v === 'Y' ? 'y' : v === 'C' ? 'c' : 'n' }
function lvText(v) { return v === 'Y' ? 'Y' : v === 'C' ? 'C' : '–' }
// Web 落地视图（无 Web 页面的码标注小程序侧）
const MINI = new Set(['handover:create', 'handover:submit', 'handover:inspect', 'task:accept', 'task:assign_partner', 'checklist:fill',
  'record:submit', 'record:sign_tech', 'record:sign', 'record:refuse', 'order:create', 'order:grab', 'order:arrive', 'order:process',
  'order:submit', 'order:confirm', 'client:report', 'client:sign'])
function webView(code) {
  if (MINI.has(code)) return ''
  const p = code.split(':')[0]
  const map = { handover: '/handovers', project: '/projects', contract: '/contracts', elevator: '/elevators', plan: '/plans', record: '/records', order: '/dispatch', user: '/users', invite: '/grants', stats: '/stats', audit: '/audit', config: '/settings', filing: '/filings', region: '/regions', template: '/templates', org: '/org' }
  if (map[p]) return map[p]
  if (code === 'role:assign') return '/roles'
  if (code === 'data:export_sensitive') return '/stats'
  if (code === 'client:invite') return '/grants'
  if (code === 'client:user_manage') return '/users'
  if (code === 'client:elevator_view') return '/elevators'
  if (code === 'client:download') return '/records'
  if (code === 'client:evaluate') return '/reviews'
  if (code === 'report:view' || code === 'report:retry') return '/reports/failed'
  if (code === 'platform:worker_view' || code === 'platform:worker_register') return '/register/workers'
  if (code === 'platform:sync') return '/sync'
  return ''
}
function deptOf(r) {
  // 部门推导单一事实源在 labels.roleDept（与后端 RoleCatalog.departmentOf 同口径）；
  // 使用单位是外部客户主体（非内部部门），旧码 UNIT_ADMIN 同归此类，不能落到「平台」
  if (!r) return '平台'
  const d = roleDept(r)
  return d ? deptLabel(d) : '使用单位'
}
function countState(r, s) { return rows.value.filter(x => x.states[r] === s).length }
const allViews = MENU_GROUPS.flatMap(g => g.items).filter(i => i.perm)
function visibleViews(r) { return allViews.filter(i => state(r, i.perm) !== 'DENY') }
function hiddenViews(r) { return allViews.filter(i => state(r, i.perm) === 'DENY') }
function openRole(r) { roleDetail.value = r }
function exportMatrix() {
  const cols = [
    { key: 'code', label: '权限码' },
    ...roles.value.map(r => ({ key: r, label: r, value: row => (row.states && row.states[r]) || '' }))
  ]
  const n = exportCsv('角色权限矩阵', cols, rows.value)
  toast.success('已导出 ' + n + ' 个权限码', 'CSV · 按导出人权限脱敏')
}
function buildFromMirror() {
  return [...canonicalCodes()].map(code => ({
    code,
    states: Object.fromEntries(roles.value.map(r => [r, state(r, code)]))
  }))
}
onMounted(async () => {
  loading.value = true
  try {
    const res = await rolesMatrix()
    rows.value = Array.isArray(res) && res.length ? res : buildFromMirror()
  } catch {
    rows.value = buildFromMirror()
  } finally { loading.value = false }
})
</script>