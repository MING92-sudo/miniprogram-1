<template>
  <div>
    <ViewHead title="消息模板" sub="微信订阅消息通知模板 · 与「检查项模板」区分（此为通知消息）">
      <template #meta><PageMeta perm="config:manage" :extra="[{ k: '模板数', v: 0, icon: 'message', cls: 'count' }]" /></template>
    </ViewHead>
    <div class="card mt16">
      <div class="card-body">
        <div class="col gap8">
          <b>暂无消息模板</b>
          <span class="dim f12">
            消息模板的存储与下发接口尚未接入后端。当前后端只提供「检查项模板」（
            <span class="mono">/admin/templates</span>）与「订阅消息发送记录」（
            <span class="mono">/admin/notify-records</span>），未提供模板本体 CRUD。
          </span>
          <span class="dim f12">模板接入后，此处按场景（签字提醒 / 工单派单 / 合同临期 / 超时升级 / 计划提醒）展示，变量由服务端发送时渲染。</span>
        </div>
      </div>
    </div>
    <RuleNote class="mt12" icon="info">
      本页不展示任何预置模板——所有内容以服务端返回为准。接入端点后需同步 docs/04 契约与 mock 三处。
    </RuleNote>
  </div>
</template>
<script setup>
// 消息模板：后端无模板 CRUD 端点，页面保持空态，不伪造数据
import ViewHead from '../ui/ViewHead.vue'
import PageMeta from '../ui/PageMeta.vue'
import RuleNote from '../ui/RuleNote.vue'
</script>
