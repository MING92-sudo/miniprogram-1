<template>
  <div class="state err-state" role="alert">
    <div class="st-ico">
      <svg class="ic ic-xl" aria-hidden="true"><use href="#i-alert" /></svg>
    </div>
    <h2>接口调用失败</h2>
    <p class="mono">{{ reason || '未知错误' }}</p>
    <p v-if="sim">这是「模拟异常」开关触发的失败态演示。生产环境此处展示重试与降级。</p>
    <div class="st-acts">
      <button class="btn btn-primary" type="button" @click="$emit('retry')">
        <svg class="ic" aria-hidden="true"><use href="#i-refresh" /></svg>重试
      </button>
      <button v-if="sim" class="btn btn-ghost" type="button" @click="$emit('sim-off')">
        <svg class="ic" aria-hidden="true"><use href="#i-x" /></svg>关闭模拟
      </button>
    </div>
  </div>
</template>
<script setup>
// 失败态（原型 errorState 对齐）：signal 描边卡 + 可读原因 + 重试 / 关闭模拟
defineProps({ reason: { type: String, default: '' }, sim: Boolean })
defineEmits(['retry', 'sim-off'])
</script>