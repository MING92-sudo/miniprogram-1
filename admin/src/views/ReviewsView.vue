<template>
  <div>
    <ViewHead title="服务评价" sub="使用单位对闭环工单评价 · 闭环后 7 天内可评价">
      <template #meta><PageMeta perm="stats:view" :extra="[{ k: '评价数', v: rows.length, icon: 'star', cls: 'count' }]" /></template>
    </ViewHead>
    <div class="notice-bar mt16">
      <svg class="ic" aria-hidden="true"><use href="#i-info" /></svg>
      <span><b>演示数据（原型基准集）。</b>评价在小程序侧提交（client:evaluate），后台侧只读查看与统计。</span>
    </div>
    <div class="kpi-grid mt16">
      <div class="kpi">
        <div class="kpi-k"><svg class="ic" aria-hidden="true"><use href="#i-star" /></svg>平均分</div>
        <div class="kpi-n">{{ avg }}<small>/5</small></div>
        <div class="kpi-x">已评价 {{ rated.length }} 条</div>
      </div>
      <div class="kpi k-sig">
        <div class="kpi-k"><svg class="ic" aria-hidden="true"><use href="#i-alert" /></svg>低分（≤2）</div>
        <div class="kpi-n">{{ low }}</div>
        <div class="kpi-x">建议回访</div>
      </div>
      <div class="kpi">
        <div class="kpi-k"><svg class="ic" aria-hidden="true"><use href="#i-inbox" /></svg>待评价</div>
        <div class="kpi-n">{{ rows.filter(r => r.status === '待评价').length }}</div>
        <div class="kpi-x">闭环 7 天内</div>
      </div>
      <div class="kpi">
        <div class="kpi-k"><svg class="ic" aria-hidden="true"><use href="#i-history" /></svg>已过期</div>
        <div class="kpi-n">{{ rows.filter(r => r.status === '已过期').length }}</div>
        <div class="kpi-x">超期未评</div>
      </div>
    </div>
    <div class="card mt12">
      <div class="card-head"><h3><svg class="ic" aria-hidden="true"><use href="#i-chart" /></svg>评分分布（1–5）</h3></div>
      <div class="card-body">
        <div v-for="n in [5, 4, 3, 2, 1]" :key="n" class="dist-row">
          <span class="dim f11" style="width:34px">{{ n }} 星</span>
          <div class="dist-bar"><i :style="{ width: distPct(n) + '%' }" /></div>
          <span class="num f11" style="width:18px;text-align:right">{{ distCount(n) }}</span>
        </div>
      </div>
    </div>
    <div class="mt16">
      <DataTable :columns="cols" :rows="rows" :search-keys="['orderNo', 'reviewer', 'comment']"
        search-placeholder="搜索工单号 / 评价人 / 意见" empty-hint="当前范围内暂无评价。"
        :filters="[{ key: 'status', label: '状态', options: M.REVIEW_STATES }, { key: 'stars', label: '评分', options: ['5', '4', '3', '2', '1'] }]"
        clickable @row-click="openDetail">
        <template #cell-orderNo="{ value }"><span class="mono">{{ value }}</span></template>
        <template #cell-type="{ value }"><span :class="['tag', value === '急修' ? 'tag-signal' : 'tag-ghost']">{{ value }}</span></template>
        <template #cell-elev="{ row }"><span class="mono f12">{{ row.deviceCode }}</span><div class="cell-2">{{ projName(row.projectId) }}</div></template>
        <template #cell-reviewer="{ value }"><b>{{ value }}</b></template>
        <template #cell-stars="{ value }">
          <span v-if="value == null" class="dim">—</span>
          <span v-else class="stars" :aria-label="'评分 ' + value + ' 分'">
            <svg v-for="i in 5" :key="i" :class="['ic', 'star', { on: i <= value }]" aria-hidden="true"><use href="#i-star" /></svg>
          </span>
        </template>
        <template #cell-comment="{ value }"><span v-if="value" class="f12">{{ value }}</span><span v-else class="dim">—</span></template>
        <template #cell-closedAt="{ value }"><span class="mono f12">{{ value }}</span></template>
        <template #cell-status="{ value }"><span :class="['tag', 'tag-' + reviewTone(value)]">{{ value }}</span></template>
        <template #row-actions="{ row }">
          <button class="btn btn-ghost btn-xs" type="button" @click="openDetail(row)">
            <svg class="ic" aria-hidden="true"><use href="#i-eye" /></svg><span>详情</span>
          </button>
        </template>
      </DataTable>
    </div>
    <RuleNote class="mt12" icon="lock">评分 1–5 星 + 意见；超 7 天未评自动「已过期」。低分（≤2）建议回访。</RuleNote>
    <Drawer :open="!!detail" :title="detail ? '评价详情 ' + detail.id : ''" :sub="detail ? detail.orderNo + ' · ' + projName(detail.projectId) : ''"
      :meta="{ perm: 'stats:view' }" note="只读" @close="detail = null">
      <template v-if="detail">
        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-star" /></svg>评价概览</div>
          <div class="kv-grid">
            <div class="kv"><span class="k">工单号</span><span class="v mono">{{ detail.orderNo }}</span></div>
            <div class="kv"><span class="k">类型</span><span class="v">{{ detail.type }}</span></div>
            <div class="kv"><span class="k">设备码</span><span class="v mono">{{ detail.deviceCode }}</span></div>
            <div class="kv"><span class="k">项目</span><span class="v">{{ projName(detail.projectId) }}</span></div>
            <div class="kv"><span class="k">评价人</span><span class="v">{{ detail.reviewer }}</span></div>
            <div class="kv"><span class="k">评分</span><span class="v">{{ detail.stars == null ? '—' : detail.stars + ' 星' }}</span></div>
            <div class="kv"><span class="k">状态</span><span class="v">{{ detail.status }}</span></div>
            <div class="kv"><span class="k">闭环时间</span><span class="v mono">{{ detail.closedAt }}</span></div>
            <div class="kv"><span class="k">评价时间</span><span class="v mono">{{ detail.ratedAt || '—' }}</span></div>
          </div>
        </div>
        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-message" /></svg>意见</div>
          <div class="plate">{{ detail.comment || '未填写意见' }}</div>
        </div>
      </template>
      <template #footer><button class="btn btn-ghost" type="button" @click="detail = null">关闭</button></template>
    </Drawer>
  </div>
</template>
<script setup>
// 二期：服务评价（原型 Views.reviews 对齐；演示数据＝原型基准集，真实接口后续接入）
import { ref, computed } from 'vue'
import { M } from '../ui/prototype-mock'
import { usePerspectiveStore } from '../ui/stores/perspective'
import DataTable from '../ui/DataTable.vue'
import Drawer from '../ui/Drawer.vue'
import ViewHead from '../ui/ViewHead.vue'
import PageMeta from '../ui/PageMeta.vue'
import RuleNote from '../ui/RuleNote.vue'
const perspective = usePerspectiveStore()
const detail = ref(null)
const rows = computed(() => {
  const pids = M.scopeProjects(perspective.activeRole)
  return M.REVIEWS.filter(r => pids.includes(r.projectId))
})
const rated = computed(() => rows.value.filter(r => r.status === '已评价'))
const avg = computed(() => rated.value.length ? (rated.value.reduce((s, r) => s + r.stars, 0) / rated.value.length).toFixed(1) : '—')
const low = computed(() => rated.value.filter(r => r.stars <= 2).length)
const cols = [
  { key: 'orderNo', label: '工单号', sortable: true },
  { key: 'type', label: '类型' },
  { key: 'elev', label: '设备 / 项目' },
  { key: 'reviewer', label: '评价人' },
  { key: 'stars', label: '评分', sortable: true },
  { key: 'comment', label: '意见' },
  { key: 'closedAt', label: '闭环时间', sortable: true },
  { key: 'status', label: '状态', sortable: true }
]
function projName(pid) { const p = M.projectById(pid); return p ? p.name : pid }
function reviewTone(s) { return { 待评价: 'amber', 已评价: 'ok', 已过期: 'dim' }[s] || 'ghost' }
function distCount(n) { return rated.value.filter(r => r.stars === n).length }
function distPct(n) { return rated.value.length ? Math.round(distCount(n) / rated.value.length * 100) : 0 }
function openDetail(r) { detail.value = r }
</script>