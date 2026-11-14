<template>
  <div class="ui-scope nf-page">
    <p class="nf-code num">404</p>
    <p class="nf-title">页面不存在</p>
    <router-link to="/dashboard" class="nf-back">返回工作台</router-link>
  </div>
</template>
<script setup>
// M2 404 页（spec §5.4）
</script>
<style scoped>
.nf-page { min-height: 100vh; display: flex; flex-direction: column; align-items: center; justify-content: center; }
.nf-code { font-size: 48px; font-weight: 700; color: var(--dim); }
.nf-title { font-size: 16px; margin: 8px 0 20px; }
.nf-back { color: var(--amber); font-size: 13px; }
</style>