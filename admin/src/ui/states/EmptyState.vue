<template>
  <div class="state">
    <div class="st-ico">
      <svg class="ic ic-xl" aria-hidden="true"><use href="#i-inbox" /></svg>
    </div>
    <h2>当前筛选无数据</h2>
    <p>{{ hint || '没有符合当前条件的记录。' }}</p>
    <div v-if="chips.length" class="chips mt12">
      <span v-for="c in chips" :key="c" class="chip on">{{ c }}</span>
    </div>
    <div v-if="clearable" class="st-acts">
      <button class="btn btn-ghost" type="button" @click="$emit('clear')">
        <svg class="ic" aria-hidden="true"><use href="#i-refresh" /></svg>清空筛选
      </button>
    </div>
  </div>
</template>
<script setup>
// 空态（原型 emptyState 对齐）：图标 + 已选条件 chips + 清空筛选
defineProps({
  chips: { type: Array, default: () => [] },
  clearable: { type: Boolean, default: false },
  hint: { type: String, default: '' }
})
defineEmits(['clear'])
</script>