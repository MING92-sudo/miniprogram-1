<template>
  <div>
    <ViewHead title="服务评价" sub="使用单位对闭环工单评价 · 闭环后 7 天内可评价">
      <template #meta><PageMeta perm="stats:view" :extra="[{ k: '评价数', v: 0, icon: 'star', cls: 'count' }]" /></template>
    </ViewHead>
    <div class="kpi-grid mt16">
      <div class="kpi">
        <div class="kpi-k"><svg class="ic" aria-hidden="true"><use href="#i-star" /></svg>平均分</div>
        <div class="kpi-n">—<small>/5</small></div>
        <div class="kpi-x">暂无评价数据</div>
      </div>
      <div class="kpi k-ok">
        <div class="kpi-k"><svg class="ic" aria-hidden="true"><use href="#i-alert" /></svg>低分（≤2）</div>
        <div class="kpi-n">0</div>
        <div class="kpi-x">无需回访</div>
      </div>
      <div class="kpi">
        <div class="kpi-k"><svg class="ic" aria-hidden="true"><use href="#i-inbox" /></svg>待评价</div>
        <div class="kpi-n">0</div>
        <div class="kpi-x">闭环 7 天内</div>
      </div>
      <div class="kpi">
        <div class="kpi-k"><svg class="ic" aria-hidden="true"><use href="#i-history" /></svg>已过期</div>
        <div class="kpi-n">0</div>
        <div class="kpi-x">超期未评</div>
      </div>
    </div>
    <div class="card mt16">
      <div class="card-body">
        <div class="col gap8">
          <b>暂无评价记录</b>
          <span class="dim f12">
            服务评价由使用单位在小程序侧提交（<span class="mono">client:evaluate</span>），管理端目前没有评价查询端点，
            因此本页不展示任何示例数据。
          </span>
          <span class="dim f12">记录单满意度的原始字段为 <span class="mono">maintain_record.satisfaction</span>，可在记录单复核页查看单条结果。</span>
        </div>
      </div>
    </div>
    <RuleNote class="mt12" icon="lock">评分 1–5 星 + 意见；超 7 天未评自动「已过期」。低分（≤2）建议回访。数据接入后统计口径与小程序侧一致。</RuleNote>
  </div>
</template>
<script setup>
// 服务评价：后端无评价聚合端点，页面保持空态（记录单 satisfaction 字段可在记录复核页查看）
import ViewHead from '../ui/ViewHead.vue'
import PageMeta from '../ui/PageMeta.vue'
import RuleNote from '../ui/RuleNote.vue'
</script>
