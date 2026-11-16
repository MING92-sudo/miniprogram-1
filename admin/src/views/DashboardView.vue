<template>
  <div>
    <ViewHead title="概览工作台" :sub="'在管项目 ' + k('projects') + ' / 电梯 ' + k('elevators') + ' · ' + scopeLabel">
      <template #actions>
        <button v-if="isDev" class="btn btn-ghost" type="button" @click="$router.push('/dev/spec')">
          <svg class="ic" aria-hidden="true"><use href="#i-palette" /></svg>设计规范与自检
        </button>
      </template>
      <template #meta><PageMeta /></template>
    </ViewHead>
    <div class="mt16">
      <div class="kpi-grid">
        <div v-for="it in kpis" :key="it.key" :class="['kpi', it.cls, { clickable: !!it.view }]" @click="it.view && $router.push(it.view)">
          <div class="kpi-k"><svg class="ic" aria-hidden="true"><use :href="'#i-' + it.icon" /></svg>{{ it.label }}</div>
          <div class="kpi-n">{{ it.value }}<small v-if="it.unit">{{ it.unit }}</small></div>
          <div class="kpi-x" v-html="it.sub" />
        </div>
      </div>
    </div>
    <div class="col gap8 mt16">
      <AlertStrip v-if="k('contractExpiring')" tone="as-sig" icon="file-sign" title="合同临期"
        :text="k('contractExpiring') + ' 份合同 ≤90 天到期，需安排续签'" to="/contracts" />
      <AlertStrip v-if="k('slaOverdue')" tone="as-sig" icon="bolt" title="SLA 超时"
        :text="k('slaOverdue') + ' 个工单超出时限仍未闭环'" to="/dispatch" />
      <AlertStrip v-if="k('handoverReview')" icon="exchange" title="待审核移交"
        :text="k('handoverReview') + ' 单等待安装部审核'" to="/handovers" />
      <AlertStrip v-if="k('recordReview')" icon="clipboard" title="待复核"
        :text="k('recordReview') + ' 单记录待维保主管复核'" to="/records" />
      <AlertStrip v-if="amendPending" tone="as-info" icon="history" title="修订待双确认"
        :text="amendPending + ' 单归档修订等待平台确认'" to="/records" />
      <AlertStrip v-if="!hasAlert" tone="as-ok" icon="check" title="运行正常" text="当前范围内无紧急预警" />
    </div>
    <div class="card card-pad0 mt16">
      <div class="card-head">
        <h3><svg class="ic" aria-hidden="true"><use href="#i-inbox" /></svg>我的待办</h3>
        <span class="ch-tag tag tag-dim">{{ todos.length }} 项</span>
      </div>
      <div v-if="todos.length" class="tbl-wrap">
        <table class="tbl">
          <thead><tr><th>事项</th><th>对象</th><th>所需权限</th><th class="acts">操作</th></tr></thead>
          <tbody>
            <tr v-for="t in todos" :key="t.id">
              <td>{{ t.label }}</td>
              <td class="mono">{{ t.obj }}</td>
              <td><code class="mono f11 c-amber">{{ t.perm }}</code></td>
              <td class="acts">
                <PermButton :code="t.perm" label="处理" variant="ghost" size="xs" icon="arrow-right" @click="$router.push(t.link)" />
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="card-body dim">当前角色范围内暂无待办。</div>
    </div>
    <RuleNote class="mt16">KPI 全部由 mock 基准集计算，分项之和与总数闭合（spec §9 不变量 1/6/8）。切换右上角角色可观察导航、数据范围、脱敏与按钮权限的联动变化。</RuleNote>
  </div>
</template>
<script setup>
// 概览工作台（原型 Views.dashboard 逐字对齐）：KPI 7 项 + 预警条 + 权限门控待办 + 元信息条
import { computed } from 'vue'
import { provider } from '../ui/providers/provider'
import { M } from '../ui/prototype-mock'
import { usePerspectiveStore } from '../ui/stores/perspective'
import ViewHead from '../ui/ViewHead.vue'
import PageMeta from '../ui/PageMeta.vue'
import AlertStrip from '../ui/AlertStrip.vue'
import RuleNote from '../ui/RuleNote.vue'
import PermButton from '../ui/PermButton.vue'
const perspective = usePerspectiveStore()
const isDev = import.meta.env.DEV
const kpis = computed(() => provider.getKPIs(perspective.activeRole))
const todos = computed(() => provider.getTodos(perspective.activeRole))
const scopeLabel = computed(() => (M.switchByRole(perspective.activeRole) || {}).scopeLabel || '全量')
const amendPending = computed(() => M.RECORDS.filter(r => r.amendPending).length)
function k(key) { return (kpis.value.find(x => x.key === key) || {}).value || 0 }
const hasAlert = computed(() => k('contractExpiring') || k('slaOverdue') || k('handoverReview') || k('recordReview') || amendPending.value)
</script>