<template>
  <div class="view" style="max-width:760px;margin:0 auto;padding-top:60px">
    <ForbiddenState unknown current-role="" name="未知页面" />
  </div>
</template>
<script setup>
// 404 页（原型 forbidState unknown 分支对齐）
import ForbiddenState from '../ui/states/ForbiddenState.vue'
</script>